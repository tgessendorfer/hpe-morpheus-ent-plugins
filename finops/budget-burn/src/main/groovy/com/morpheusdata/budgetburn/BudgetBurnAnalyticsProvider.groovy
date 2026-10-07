/*
 * Copyright 2026 Thomas Gessendorfer.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.morpheusdata.budgetburn

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.AbstractAnalyticsProvider
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.sql.Connection

import static com.morpheusdata.budgetburn.BudgetBurnMath.*

/**
 * Budget burn per Morpheus budget for the current month and the current year.
 *
 * The budget amount comes from account_budget_period, the spend from the monthly
 * invoices that match the budget scope (tenant, account, group, cloud, user). Spend is
 * the price the tenant pays: to date = running_price, forecast = total_price.
 * Master tenant users see every budget, sub-tenant users only the budgets of their tenant.
 *
 * Currency: a budget is in the currency of its owner tenant, else the master tenant's,
 * else USD. Spend is summed per invoice currency. Only amounts in the budget currency are
 * compared with the budget; amounts in other currencies are shown separately, never added
 * up, and the row is flagged as currency mismatch (no burn rate, no percentage).
 *
 * Language: texts and number formats follow the viewer's own language setting in Morpheus
 * (user.locale), the same setting that picks the language of the Morpheus UI. Without a usable
 * setting the web request's locale (browser language) counts, without a request English.
 *
 * Reads internal tables (account, user, account_budget, account_budget_period,
 * account_invoice, container); tested on Morpheus 9.0.2 only.
 */
@Slf4j
class BudgetBurnAnalyticsProvider extends AbstractAnalyticsProvider {

	static final String PROVIDER_CODE = 'budget-burn-analytics'

	// Only yearly budgets; the budget currency is the owner's (as GET /api/budgets/{id} shows it).
	// owner_master feeds the spend rule in scopeCondition (owner's invoices only, plus subtenants' for the master).
	static final String BUDGET_SELECT = "SELECT b.*, a.name AS owner, a.currency AS owner_currency, CAST(a.master_account AS UNSIGNED) AS owner_master FROM account_budget b JOIN account a ON a.id = b.account_id WHERE b.period = 'year' AND b.period_value = ?"

	// Instance invoices plus invoices of servers that belong to no instance. The server invoice of an
	// instance's VM carries no instance_id either; the link is the container table.
	static final String INVOICE_FILTER = "i.period_interval = 'month' AND (i.ref_type = 'Instance' OR (i.ref_type = 'ComputeServer' AND i.instance_id IS NULL" +
		" AND NOT EXISTS (SELECT 1 FROM container ct WHERE ct.server_id = i.ref_id AND ct.instance_id IS NOT NULL)))"

	Plugin plugin
	MorpheusContext morpheus

	BudgetBurnAnalyticsProvider(Plugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override MorpheusContext getMorpheus() { morpheus }
	@Override Plugin getPlugin() { plugin }
	@Override String getCode() { PROVIDER_CODE }
	// Menu name and description are not localizable in plugin API 1.4.2.
	@Override String getName() { 'Budget Burn' }
	@Override String getCategory() { 'cost' }
	@Override String getDescription() { 'Budget, spend to date, burn rate and forecast per budget' }
	@Override Boolean getMasterTenantOnly() { false }
	@Override Boolean getSubTenantOnly() { false }
	@Override Integer getDisplayOrder() { 50 }

	/** The viewer's language setting in Morpheus (user.locale, e.g. 'en-US'). Parameterised. */
	static final String USER_LOCALE_SELECT = 'SELECT locale FROM user WHERE id = ?'

	/** Languages with a message bundle; every other language gets the English texts. */
	static final List<String> BUNDLE_LANGUAGES = ['en', 'de'].asImmutable()

	private static final Set<String> ISO_LANGUAGES = (Locale.getISOLanguages() as Set<String>).asImmutable()
	private static final Map<String, Map<String, Object>> TEXTS = [:].asSynchronized()

	/** The locale of the web request (the browser's Accept-Language); English without a request. */
	Locale requestLocale() {
		try {
			return morpheus?.webRequest?.locale ?: Locale.ENGLISH
		} catch(Exception ignored) {
			return Locale.ENGLISH
		}
	}

	/**
	 * A Morpheus language setting as a locale: 'en-US', 'de' or 'de_DE'. Null when the setting is
	 * empty or names no ISO 639 language.
	 */
	static Locale parseLocaleSetting(String setting) {
		String tag = setting?.trim()?.replace('_', '-')
		if(!tag) return null
		Locale l = Locale.forLanguageTag(tag)
		return (l.language && ISO_LANGUAGES.contains(l.language)) ? l : null
	}

	/**
	 * The viewer's locale for texts and number formats: the user's own language setting in
	 * Morpheus, which is also the language of the Morpheus UI. Without a usable setting the locale
	 * of the web request, without a request English. A failed lookup never breaks the page.
	 */
	Locale viewerLocale(Sql sql, def userId) {
		if(sql != null && userId != null) {
			try {
				Locale l = parseLocaleSetting(sql.firstRow(USER_LOCALE_SELECT, [userId])?.locale as String)
				if(l) return l
			} catch(Exception e) {
				log.debug("Budget Burn: reading the language setting of user ${userId} failed, using the request locale: ${e.message}")
			}
		}
		return requestLocale()
	}

	/** The language of the bundle the texts come from: German for German, else English. */
	static String bundleLanguage(Locale locale) {
		String lang = locale?.language
		return (lang && BUNDLE_LANGUAGES.contains(lang)) ? lang : 'en'
	}

	/**
	 * The texts of the plugin bundle for a locale, keyed without the provider code and nested by
	 * the dots of the key ('col.budget' -> texts.col.budget). The page gets them as data, because
	 * the i18n helper of the shared renderer always uses the request locale. German keys missing
	 * from the German bundle fall back to English.
	 */
	static Map<String, Object> texts(Locale locale) {
		String lang = bundleLanguage(locale)
		Map<String, Object> cached = TEXTS[lang]
		if(cached != null) return cached
		Properties p = bundle('i18n/messages.properties')
		if(lang != 'en') p.putAll(bundle("i18n/messages_${lang}.properties".toString()))
		String prefix = PROVIDER_CODE + '.'
		Map<String, Object> tree = [:]
		p.stringPropertyNames().sort().findAll { it.startsWith(prefix) }.each { key ->
			List<String> parts = key.substring(prefix.length()).tokenize('.')
			Map node = tree
			parts.init().each { part -> node = (Map) node.computeIfAbsent(part) { [:] } }
			node[parts.last()] = p.getProperty(key)
		}
		Map<String, Object> result = tree.asImmutable()
		// An empty read is not cached, so the next page load tries again.
		if(result) TEXTS[lang] = result
		return result
	}

	/** One text by its key, with or without the provider code ('error.load'), else the default. */
	static String text(String key, String defaultText, Locale locale) {
		try {
			String prefix = PROVIDER_CODE + '.'
			def v = texts(locale)
			(key.startsWith(prefix) ? key.substring(prefix.length()) : key).tokenize('.').each { v = (v instanceof Map) ? v[it] : null }
			return (v instanceof String) ? v : defaultText
		} catch(Exception ignored) {
			return defaultText
		}
	}

	/**
	 * A bundle of this plugin. Other jars on the class path can carry a file of the same name,
	 * so the first one whose keys start with the provider code counts.
	 */
	private static Properties bundle(String path) {
		String prefix = PROVIDER_CODE + '.'
		for(URL url in BudgetBurnAnalyticsProvider.classLoader.getResources(path)) {
			Properties p = new Properties()
			url.openStream().withCloseable { p.load(it) }
			if(p.stringPropertyNames().any { it.startsWith(prefix) }) return p
		}
		return new Properties()
	}

	/** The Groovy Sql over the read-only report connection; a seam for the tests. */
	protected Sql newSql(Connection c) {
		return new Sql(c)
	}

	@Override
	ServiceResponse<Map<String, Object>> loadData(User user, Map<String, Object> opts) {
		Locale locale = requestLocale()
		Connection c = null
		try {
			c = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
			Sql sql = newSql(c)
			locale = viewerLocale(sql, user?.id)
			def acc = sql.firstRow('SELECT a.id, a.name, CAST(a.master_account AS UNSIGNED) AS is_master FROM user u JOIN account a ON a.id = u.account_id WHERE u.id = ?', [user.id])
			boolean master = isMaster(acc?.is_master)
			def masterAcc = sql.firstRow('SELECT currency FROM account WHERE master_account = 1 ORDER BY id LIMIT 1')
			String masterCurrency = resolveCurrency(masterAcc?.currency as String)
			// Current month in the JVM time zone of the appliance.
			Calendar now = Calendar.instance
			int year = now.get(Calendar.YEAR), month = now.get(Calendar.MONTH) + 1
			int day = now.get(Calendar.DAY_OF_MONTH), days = now.getActualMaximum(Calendar.DAY_OF_MONTH)
			String per = String.format('%04d%02d', year, month)
			List<GroovyRowResult> budgets = master ?
				sql.rows("${BUDGET_SELECT} ORDER BY a.master_account DESC, b.name".toString(), [year.toString()]) :
				sql.rows("${BUDGET_SELECT} AND b.account_id = ? ORDER BY b.name".toString(), [year.toString(), acc?.id])
			boolean anyMismatch = false
			List items = budgets.collect { b ->
				String bc = resolveCurrency(b.owner_currency as String, masterCurrency)
				String interval = b.period_interval as String
				List periods = sql.rows('SELECT interval_index, cost FROM account_budget_period WHERE budget_id = ?', [b.id])
				BigDecimal budgetMonth = monthlyBudget(interval, periods, month)
				BigDecimal budgetYtd = budgetToDate(interval, periods, month)
				List params = []
				String cond = scopeCondition(b, params)
				Map<String, Map<String, BigDecimal>> cur = byCurrency(sql.rows("SELECT i.currency, SUM(COALESCE(i.running_price,0)) AS running, SUM(COALESCE(i.total_price,0)) AS forecast FROM account_invoice i WHERE ${INVOICE_FILTER} AND ${cond} AND i.period = ? GROUP BY i.currency".toString(), params + [per]), bc)
				Map<String, Map<String, BigDecimal>> ytd = byCurrency(sql.rows("SELECT i.currency, SUM(COALESCE(i.total_price,0)) AS total FROM account_invoice i WHERE ${INVOICE_FILTER} AND ${cond} AND i.period >= ? AND i.period < ? GROUP BY i.currency".toString(), params + ["${year}01".toString(), per]), bc)
				BigDecimal running = cur[bc]?.running ?: 0.00G, forecast = cur[bc]?.forecast ?: 0.00G
				BigDecimal ytdSpend = (ytd[bc]?.total ?: 0.00G) + forecast
				String foreignRunning = foreignText(cur, bc, 'running', locale), foreignForecast = foreignText(cur, bc, 'forecast', locale)
				String foreignYtd = joinForeign(ytdForeign(ytd, cur, bc), bc, locale)
				boolean monthMismatch = (foreignRunning || foreignForecast) as boolean, ytdMismatch = foreignYtd as boolean
				anyMismatch = anyMismatch || monthMismatch || ytdMismatch
				String st = status(forecast, budgetMonth, monthMismatch)
				[name: b.name, owner: b.owner, scopeText: text(scopeKey(b.ref_scope as String), b.ref_scope as String, locale), target: b.ref_name ?: b.owner, currency: bc,
				 budgetText: money(budgetMonth, locale), runningText: money(running, locale), forecastText: money(forecast, locale),
				 burnText: money(burnRate(running, day), locale),
				 usedPct: pctLabel(running, budgetMonth, locale), forecastPct: pctLabel(forecast, budgetMonth, locale), bar: barWidth(forecast, budgetMonth),
				 mismatch: monthMismatch, foreignRunning: foreignRunning, foreignForecast: foreignForecast,
				 statusText: text(statusKey(st), st, locale), color: statusColor(st),
				 ytdMismatch: ytdMismatch, foreignYtd: foreignYtd,
				 ytdBudgetText: money(budgetYtd, locale), ytdText: money(ytdSpend, locale), ytdPct: pctLabel(ytdSpend, budgetYtd, locale)]
			}
			ServiceResponse.success([text: texts(locale), language: bundleLanguage(locale), items: items, tenant: acc?.name, master: master, day: day, days: days, anyMismatch: anyMismatch,
				month: "${year}-${month.toString().padLeft(2, '0')}".toString(), count: items.size()])
		} catch(Exception e) {
			log.error("Budget Burn: loading budget data failed: ${e.message}", e)
			ServiceResponse.error(text('error.load', 'Budget data could not be loaded. See the appliance log for details.', locale))
		} finally {
			// A failed release must not replace the page result.
			try {
				if(c) morpheus.report.releaseDatabaseConnection(c).blockingAwait()
			} catch(Exception e) {
				log.warn("Budget Burn: releasing the database connection failed: ${e.message}", e)
			}
		}
	}

	@Override
	HTMLResponse renderTemplate(User user, Map<String, Object> data, Map<String, Object> opts) {
		ViewModel<Map> model = new ViewModel<>()
		model.object = data
		getRenderer().renderTemplate('hbs/budgetBurn', model)
	}
}
