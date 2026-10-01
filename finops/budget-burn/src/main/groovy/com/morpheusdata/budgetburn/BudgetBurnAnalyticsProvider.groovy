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
 * Reads internal tables (account, user, account_budget, account_budget_period,
 * account_invoice); tested on Morpheus 9.0.2 only.
 */
@Slf4j
class BudgetBurnAnalyticsProvider extends AbstractAnalyticsProvider {

	static final String PROVIDER_CODE = 'budget-burn-analytics'

	static final String INVOICE_FILTER = "i.period_interval = 'month' AND (i.ref_type = 'Instance' OR (i.ref_type = 'ComputeServer' AND i.instance_id IS NULL))"

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

	/** The viewer's locale for number formats; English when there is no web request. */
	Locale viewerLocale() {
		try {
			return morpheus?.webRequest?.locale ?: Locale.ENGLISH
		} catch(Exception ignored) {
			return Locale.ENGLISH
		}
	}

	/** A message from the plugin bundles in the viewer's language, else the English default. */
	String message(String key, String defaultText, Locale locale) {
		try {
			return morpheus?.webRequest?.getMessage(key, null, defaultText, locale) ?: defaultText
		} catch(Exception ignored) {
			return defaultText
		}
	}

	@Override
	ServiceResponse<Map<String, Object>> loadData(User user, Map<String, Object> opts) {
		Locale locale = viewerLocale()
		Connection c = null
		try {
			c = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
			Sql sql = new Sql(c)
			def acc = sql.firstRow('SELECT a.id, a.name, CAST(a.master_account AS UNSIGNED) AS is_master FROM user u JOIN account a ON a.id = u.account_id WHERE u.id = ?', [user.id])
			boolean master = (acc?.is_master as Integer) == 1
			def masterAcc = sql.firstRow('SELECT currency FROM account WHERE master_account = 1 ORDER BY id LIMIT 1')
			String masterCurrency = resolveCurrency(masterAcc?.currency as String)
			// Current month in the JVM time zone of the appliance.
			Calendar now = Calendar.instance
			int year = now.get(Calendar.YEAR), month = now.get(Calendar.MONTH) + 1
			int day = now.get(Calendar.DAY_OF_MONTH), days = now.getActualMaximum(Calendar.DAY_OF_MONTH)
			String per = now.time.format('yyyyMM')
			// Only yearly budgets; the budget currency is the owner's (as GET /api/budgets/{id} shows it).
			// owner_master feeds the spend rule in scopeCondition (owner's invoices only, plus subtenants' for the master).
			String budgetSelect = "SELECT b.*, a.name AS owner, a.currency AS owner_currency, CAST(a.master_account AS UNSIGNED) AS owner_master FROM account_budget b JOIN account a ON a.id = b.account_id WHERE b.period = 'year' AND b.period_value = ?"
			List<GroovyRowResult> budgets = master ?
				sql.rows("${budgetSelect} ORDER BY a.master_account DESC, b.name".toString(), [year.toString()]) :
				sql.rows("${budgetSelect} AND b.account_id = ? ORDER BY b.name".toString(), [year.toString(), acc?.id])
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
				BigDecimal fPct = pct(forecast, budgetMonth)
				String st = status(fPct, monthMismatch)
				[name: b.name, owner: b.owner, scopeKey: scopeKey(b.ref_scope as String), target: b.ref_name ?: b.owner, currency: bc,
				 budgetText: money(budgetMonth, locale), runningText: money(running, locale), forecastText: money(forecast, locale),
				 burnText: money(burnRate(running, day), locale),
				 usedPct: pctText(pct(running, budgetMonth), locale), forecastPct: pctText(fPct, locale), bar: barWidth(fPct),
				 mismatch: monthMismatch, foreignRunning: foreignRunning, foreignForecast: foreignForecast,
				 statusKey: statusKey(st), color: statusColor(st),
				 ytdMismatch: ytdMismatch, foreignYtd: foreignYtd,
				 ytdBudgetText: money(budgetYtd, locale), ytdText: money(ytdSpend, locale), ytdPct: pctText(pct(ytdSpend, budgetYtd), locale)]
			}
			ServiceResponse.success([items: items, tenant: acc?.name, master: master, day: day, days: days, anyMismatch: anyMismatch,
				month: "${year}-${month.toString().padLeft(2, '0')}".toString(), count: items.size()])
		} catch(Exception e) {
			log.error("Budget Burn: loading budget data failed: ${e.message}", e)
			ServiceResponse.error(message("${PROVIDER_CODE}.error.load".toString(),
				'Budget data could not be loaded. See the appliance log for details.', locale))
		} finally {
			if(c) morpheus.report.releaseDatabaseConnection(c).blockingAwait()
		}
	}

	@Override
	HTMLResponse renderTemplate(User user, Map<String, Object> data, Map<String, Object> opts) {
		ViewModel<Map> model = new ViewModel<>()
		model.object = data
		getRenderer().renderTemplate('hbs/budgetBurn', model)
	}
}
