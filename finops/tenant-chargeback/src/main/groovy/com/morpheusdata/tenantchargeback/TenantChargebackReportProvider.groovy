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
package com.morpheusdata.tenantchargeback

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.sql.Connection

import static com.morpheusdata.tenantchargeback.ChargebackCalculator.*

/**
 * Chargeback per tenant and group for one month, read from the Morpheus invoices
 * (internal table account_invoice). Counted are instance invoices and server invoices without an
 * instance (no instance_id and no container row linking the server to an instance); the summary
 * invoices per account, group, cloud and user are left out, they would count
 * twice. cost = purchase cost, price = list price including the price-set markup, margin =
 * price - cost. An optional additional markup in percent is applied to the list price.
 *
 * process() stores plain, machine-readable values only (they are what the CSV export shows);
 * renderTemplate() formats them and resolves the texts in the language of the user's Morpheus
 * setting (see {@link #viewerLocale}).
 *
 * masterOnly: the report SQL sees all tenants. process() also checks that the user who ran the
 * report belongs to the master tenant, so a report started another way fails instead of showing
 * every tenant's amounts.
 */
@Slf4j
class TenantChargebackReportProvider extends AbstractReportProvider {

	static final String PROVIDER_CODE = 'tenant-chargeback-report'

	static final String FIELD_MONTH = 'chargebackMonth'
	static final String FIELD_MARKUP = 'markupPercent'
	static final String FIELD_PROVIDER = 'includeProvider'

	/**
	 * Instance invoices plus server invoices without an instance, grouped by tenant, group id and
	 * raw currency. The group id keeps two groups with the same name apart; the name is for display.
	 * Server invoices can carry a group id with an empty name; the name is then looked up in the
	 * group table (compute_site) by that id, so they count towards their group. Lines without a
	 * group id, or whose group no longer exists, keep an empty name; aggregate() merges those into
	 * the one line for servers without a group. is_active is the tenant's account.active (0 = the
	 * tenant is deactivated); it only tags the rows, every invoice is counted whatever its value.
	 */
	static final String INVOICE_SQL = '''
		SELECT a.id AS tenant_id, a.name AS tenant, CAST(a.master_account AS UNSIGNED) AS is_master,
		       CAST(a.active AS UNSIGNED) AS is_active,
		       i.site_id AS grp_id, MAX(COALESCE(NULLIF(TRIM(i.site_name), ''), cs.name)) AS grp,
		       NULLIF(TRIM(i.currency), '') AS currency,
		       COUNT(*) AS resources,
		       SUM(COALESCE(i.total_cost, 0)) AS cost, SUM(COALESCE(i.total_price, 0)) AS price
		FROM account_invoice i JOIN account a ON a.id = i.account_id
		LEFT JOIN compute_site cs ON cs.id = i.site_id
		WHERE i.period_interval = 'month' AND i.period = ?
		  AND (i.ref_type = 'Instance' OR (i.ref_type = 'ComputeServer' AND i.instance_id IS NULL
		    AND NOT EXISTS (SELECT 1 FROM container ct WHERE ct.server_id = i.ref_id AND ct.instance_id IS NOT NULL)))
		GROUP BY a.id, a.name, a.master_account, a.active, i.site_id, NULLIF(TRIM(i.currency), '')
		ORDER BY a.master_account, a.name, grp, grp_id, currency'''

	/** Currency of the master tenant, the second step of the currency rule. */
	static final String MASTER_CURRENCY_SQL = '''
		SELECT NULLIF(TRIM(currency), '') AS currency FROM account
		WHERE master_account = 1 ORDER BY id LIMIT 1'''

	/**
	 * Language setting of a Morpheus user (e.g. 'en-US'). Plugin API 1.4.2 has no locale on the
	 * User model, and the web request carries the browser's language, not this setting.
	 */
	static final String USER_LOCALE_SQL = 'SELECT locale FROM user WHERE id = ?'

	/** Whether a user belongs to the master tenant (is_master 1); the report runs for those only. */
	static final String USER_MASTER_SQL = '''
		SELECT CAST(a.master_account AS UNSIGNED) AS is_master
		FROM user u JOIN account a ON a.id = u.account_id WHERE u.id = ?'''

	/** Whether an account is the master tenant; checked for a report result without a creator. */
	static final String ACCOUNT_MASTER_SQL = 'SELECT CAST(master_account AS UNSIGNED) AS is_master FROM account WHERE id = ?'

	/** Bundle keys (after the provider code) of the texts the template shows. */
	static final List<String> TEXT_KEYS = [
		'title', 'currentMonth', 'tenants', 'invoiceTotal', 'inclMarkup',
		'section.currency', 'section.tenant', 'section.group',
		'col.tenant', 'col.group', 'col.currency', 'col.resources', 'col.cost', 'col.price',
		'col.margin', 'col.marginPct', 'col.invoice',
		'empty', 'noGroup', 'deactivated', 'footnote'
	]

	Plugin plugin
	MorpheusContext morpheus

	TenantChargebackReportProvider(Plugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override MorpheusContext getMorpheus() { morpheus }
	@Override Plugin getPlugin() { plugin }
	@Override String getCode() { PROVIDER_CODE }
	// Provider name and description are stored when the plugin registers and are not localized.
	@Override String getName() { 'Tenant Chargeback' }
	@Override String getDescription() { 'Cost, list price, margin and invoice amount per tenant and group for one month, totalled per currency' }
	@Override String getCategory() { 'cost' }
	@Override Boolean getOwnerOnly() { false }
	@Override Boolean getMasterOnly() { true }
	@Override Boolean getSupportsAllZoneTypes() { true }

	@Override
	List<OptionType> getOptionTypes() {
		List<OptionType> optionTypes = [
			new OptionType(code: "${PROVIDER_CODE}-month", name: 'Month', fieldName: FIELD_MONTH, fieldContext: 'config',
				fieldLabel: 'Month (YYYY-MM)', inputType: OptionType.InputType.TEXT, displayOrder: 0, required: false,
				placeHolder: 'YYYY-MM',
				helpText: 'For example 2026-09. Leave empty for the current month, which Morpheus projects to the end of the month.'),
			new OptionType(code: "${PROVIDER_CODE}-markup-percent", name: 'Additional Markup', fieldName: FIELD_MARKUP, fieldContext: 'config',
				fieldLabel: 'Additional Markup %', inputType: OptionType.InputType.NUMBER, displayOrder: 1, required: false, defaultValue: '0',
				helpText: 'Applied to the list price to get the invoice amount, for example a managed service fee. 0 invoices the list price.'),
			new OptionType(code: "${PROVIDER_CODE}-provider", name: 'Include Provider', fieldName: FIELD_PROVIDER, fieldContext: 'config',
				fieldLabel: 'Include Master Tenant Resources', inputType: OptionType.InputType.CHECKBOX, displayOrder: 2, required: false, defaultValue: 'off',
				helpText: 'Also list the resources of the master tenant itself. Off shows the subtenants only.')
		]
		// Labels and help texts resolve through src/main/resources/i18n in the viewer's
		// language; the literal texts above stay as the fallback.
		optionTypes.each { OptionType o ->
			o.fieldCode = "${PROVIDER_CODE}.${o.fieldName}.label".toString()
			if(o.helpText) o.helpTextI18nCode = "${PROVIDER_CODE}.${o.fieldName}.help".toString()
		}
		optionTypes
	}

	@Override
	ServiceResponse validateOptions(Map opts) {
		String month = cfg(opts, FIELD_MONTH)
		if(!isValidMonth(month)) {
			String text = msg("${PROVIDER_CODE}.error.month", 'Enter the month as YYYY-MM, with a month from 01 to 12.')
			return ServiceResponse.error(text, [(FIELD_MONTH): text])
		}
		String markup = cfg(opts, FIELD_MARKUP)
		if(markup) {
			try {
				parsePercent(markup)
			} catch(NumberFormatException ignored) {
				String text = msg("${PROVIDER_CODE}.error.markup", 'Enter the additional markup as a number from 0 to 1000 with up to four decimals, for example 5 or 7.5.')
				return ServiceResponse.error(text, [(FIELD_MARKUP): text])
			}
		}
		ServiceResponse.success()
	}

	@Override
	void process(ReportResult reportResult) {
		morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.generating).blockingAwait()
		try {
			Map cfgMap = reportResult.configMap ?: [:]
			String month = cfg(cfgMap, FIELD_MONTH)
			// Checked again here: a report started through the API may skip validateOptions(), and a
			// month such as 2026-1 would give an empty report that looks complete.
			if(!isValidMonth(month)) {
				reject(reportResult, "the month '${month}' is not YYYY-MM with a month from 01 to 12")
				return
			}
			String per = period(month)
			BigDecimal markup = parsePercent(cfg(cfgMap, FIELD_MARKUP))
			boolean includeProvider = isTrue(cfg(cfgMap, FIELD_PROVIDER))
			Long userId = creatorId(reportResult)
			Long accountId = userId == null ? resultAccountId(reportResult) : null
			List<GroovyRowResult> rows = []
			String masterCurrency = null
			boolean master = false
			withDbConnection { Connection c ->
				Sql sql = newSql(c)
				// The invoice query sees all tenants, so it runs for the master tenant only.
				master = runsForMaster(sql, userId, accountId)
				if(!master) return
				rows = sql.rows(INVOICE_SQL, [per])
				masterCurrency = sql.firstRow(MASTER_CURRENCY_SQL)?.currency as String
			}
			if(!master) {
				reject(reportResult, userId != null ? "user ${userId} does not belong to the master tenant" :
					accountId != null ? "account ${accountId} is not the master tenant" : 'the report result names neither its creator nor its account')
				return
			}
			List<Map> raw = rows.collect { GroovyRowResult r ->
				[tenantId: r.tenant_id, tenant: r.tenant, isMaster: (r.is_master as Integer) == 1, active: r.is_active,
				 grpId: r.grp_id, grp: r.grp, currency: r.currency, resources: r.resources, cost: r.cost, price: r.price]
			}
			Map result = aggregate(raw, masterCurrency, markup, includeProvider)
			String markupText = markup.stripTrailingZeros().toPlainString()

			Long order = 0
			List<ReportResultRow> out = []
			result.lines.each { Map l ->
				out << new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: order++, dataMap: [
					tenant: l.tenant, group: l.group ?: '', noGroup: l.group == null, resources: l.resources.toString(),
					currency: l.currency, cost: plain(l.cost), price: plain(l.price), margin: plain(l.margin),
					markupPercent: markupText, invoice: plain(l.invoice), deactivated: l.deactivated])
			}
			result.tenants.each { Map t ->
				out << new ReportResultRow(section: ReportResultRow.SECTION_HEADER, displayOrder: order++, dataMap: [
					tenant: t.tenant, currency: t.currency, resources: t.resources.toString(),
					cost: plain(t.cost), price: plain(t.price), margin: plain(t.margin),
					marginPct: pctText(t.margin, t.price), invoice: plain(t.invoice), deactivated: t.deactivated])
			}
			out << new ReportResultRow(section: ReportResultRow.SECTION_FOOTER, displayOrder: order++, dataMap: [
				kind: 'meta', month: monthLabel(per), current: per == period(null), markupPercent: markupText,
				tenants: tenantCount(result.lines).toString(), currencies: result.totals*.currency.join(', ')])
			result.totals.each { Map t ->
				out << new ReportResultRow(section: ReportResultRow.SECTION_FOOTER, displayOrder: order++, dataMap: [
					kind: 'total', currency: t.currency, resources: t.resources.toString(),
					cost: plain(t.cost), price: plain(t.price), margin: plain(t.margin),
					marginPct: pctText(t.margin, t.price), invoice: plain(t.invoice)])
			}
			out.collate(50).each { morpheus.report.appendResultRows(reportResult, it).blockingGet() }
			morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.ready).blockingAwait()
		} catch(Exception e) {
			// The report reads internal tables (account_invoice, account, compute_site), tested on Morpheus 9.0.2 only.
			log.error("Tenant Chargeback report ${reportResult?.id} failed; it reads internal tables tested on Morpheus 9.0.2 only: ${e.message}")
			log.debug('Tenant Chargeback report failure', e)
			morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
		}
	}

	/** Marks the report failed without reading any invoice, with one warning that says why. */
	protected void reject(ReportResult reportResult, String why) {
		log.warn("Tenant Chargeback report ${reportResult?.id} not run: ${why}")
		morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
	}

	/**
	 * Whether the report may read all tenants: the user who ran it belongs to the master tenant, or,
	 * for a result without a creator, the result's own account is the master tenant. False when
	 * neither is known.
	 */
	static boolean runsForMaster(Sql sql, Long userId, Long accountId) {
		def row = userId != null ? sql.firstRow(USER_MASTER_SQL, [userId]) :
			accountId != null ? sql.firstRow(ACCOUNT_MASTER_SQL, [accountId]) : null
		(row?.is_master as Integer) == 1
	}

	/** Id of the user who ran the report, or null when the result carries none. */
	static Long creatorId(ReportResult reportResult) {
		try {
			return reportResult?.createdBy?.id
		} catch(Throwable ignored) {
			return null
		}
	}

	/** Id of the account the report result belongs to, or null when it carries none. */
	static Long resultAccountId(ReportResult reportResult) {
		try {
			return reportResult?.account?.id
		} catch(Throwable ignored) {
			return null
		}
	}

	/** The Groovy Sql over the read-only report connection; a seam for the tests. */
	protected Sql newSql(Connection c) {
		new Sql(c)
	}

	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		ViewModel<Map> model = new ViewModel<>()
		model.object = pageModel(reportRowsBySection, viewerLocale(reportResult))
		getRenderer().renderTemplate('hbs/tenantChargeback', model)
	}

	/**
	 * The template model: stored rows with their formatted values, and the texts, all in {@code locale}.
	 * A currency whose total cost, list price and invoice amount are all zero (e.g. unpriced servers
	 * in USD) is left out of every section of the page, as long as another currency has an amount.
	 * The stored rows, and so the CSV export, keep it. The tenant count then counts only the
	 * tenants the tables still list. Rows of a deactivated tenant carry the tag text next to the
	 * tenant name (deactivatedText); result rows stored before 1.2.3 have no flag and no tag.
	 */
	static Map pageModel(Map<String, List<ReportResultRow>> reportRowsBySection, Locale locale) {
		Map<String, String> t = texts(locale)
		List<Map> footer = reportRowsBySection.footer?.collect { it.dataMap } ?: []
		Map meta = footer.find { it.kind == 'meta' } ?: [:]
		List<Map> totals = footer.findAll { it.kind == 'total' }
		Set<String> hidden = zeroCurrencies(totals)
		Closure<Boolean> shown = { Map m -> !hidden.contains(m.currency as String) }
		List<Map> tenants = (reportRowsBySection.header?.collect { it.dataMap } ?: []).findAll(shown)
		// The stored rows carry the tenant name, not its id, so two tenants of the same name count once here.
		def tenantCount = hidden ? tenants.collect { it.tenant }.unique().size().toString() : meta.tenants
		[
			rows   : (reportRowsBySection.main?.collect { it.dataMap } ?: []).findAll(shown).collect { withTexts(it, locale) + [noGroupText: t.noGroup, deactivatedText: t.deactivated] },
			tenants: tenants.collect { withTexts(it, locale) + [deactivatedText: t.deactivated] },
			footer : meta + [isCurrent: isTrue(meta.current), markupPercentText: decimalShort(meta.markupPercent ?: '0', locale), tenants: tenantCount],
			totals : totals.findAll(shown).collect { withTexts(it, locale) },
			t      : t
		]
	}

	/** Currencies of {@code totals} without any amount; empty when no currency has one. */
	static Set<String> zeroCurrencies(List<Map> totals) {
		Set<String> zero = totals.findAll { Map m -> ['cost', 'price', 'invoice'].every { isZeroAmount(m[it]) } }
			.collect { it.currency as String } as Set
		zero.size() < totals.size() ? zero : ([] as Set)
	}

	static boolean isZeroAmount(def v) {
		try {
			return v == null || new BigDecimal(v.toString().trim()).signum() == 0
		} catch(NumberFormatException ignored) {
			return false
		}
	}

	/** Adds the locale-formatted texts the template shows next to the plain stored values. */
	static Map withTexts(Map row, Locale locale) {
		Map m = new LinkedHashMap(row)
		['cost', 'price', 'margin', 'invoice'].each { String k ->
			if(row.containsKey(k)) m["${k}Text".toString()] = money(row[k], locale)
		}
		if(row.containsKey('marginPct')) m.marginPctText = decimal1(row.marginPct, locale)
		m.noGroup = isTrue(row.noGroup)
		m.deactivated = isTrue(row.deactivated)
		m
	}

	/**
	 * Texts of the template in the language of {@code locale}, keyed by the bundle key with dots
	 * replaced by underscores (col.cost -> col_cost). The template takes them from the model
	 * instead of the i18n helper, because that helper always uses the browser's language.
	 */
	static Map<String, String> texts(Locale locale) {
		TEXT_KEYS.collectEntries { String k ->
			[(k.replace('.', '_')): text("${PROVIDER_CODE}.${k}".toString(), k, locale)]
		}
	}

	/**
	 * Message from the plugin's own bundles (i18n/messages*.properties) in the bundle language for
	 * {@code locale}, falling back to the English bundle and then to {@code defaultText}. The bundle
	 * files are read directly, without the JVM's default locale, so an English setting gives English
	 * on an appliance with any system locale.
	 */
	static String text(String key, String defaultText, Locale locale) {
		try {
			String lang = messageLocale(locale).language
			String v = (lang != 'en' ? bundle(lang)[key] : null) ?: bundle('en')[key]
			return v ?: defaultText
		} catch(Throwable ignored) {
			return defaultText
		}
	}

	/** Bundle key that only this plugin's bundles carry; tells them from another jar's i18n/messages. */
	static final String BUNDLE_MARKER_KEY = "${PROVIDER_CODE}.title".toString()

	private static final Map<String, Properties> BUNDLES = new java.util.concurrent.ConcurrentHashMap<>()

	/**
	 * The plugin's bundle for a bundle language ('en' = i18n/messages.properties). A class loader
	 * that asks its parent first could find another i18n/messages*.properties of the same name
	 * before the plugin's own, so every copy on the class path is checked and the first one that
	 * carries {@link #BUNDLE_MARKER_KEY} is used. Empty when none does.
	 */
	static Properties bundle(String lang) {
		BUNDLES.computeIfAbsent(lang) { String l ->
			String name = l == 'en' ? 'i18n/messages.properties' : "i18n/messages_${l}.properties".toString()
			ownBundle(Collections.list(TenantChargebackReportProvider.classLoader.getResources(name)))
		}
	}

	/** The first of {@code urls} that carries {@link #BUNDLE_MARKER_KEY}, or an empty bundle. */
	static Properties ownBundle(List<URL> urls) {
		for(URL u : urls) {
			Properties p = new Properties()
			// The bundles are ASCII with escaped umlauts, which Properties.load reads correctly.
			u.openStream().withCloseable { InputStream is -> p.load(is) }
			if(p.containsKey(BUNDLE_MARKER_KEY)) return p
		}
		new Properties()
	}

	/**
	 * Locale the report page is shown in: the Morpheus language setting of the user who ran the
	 * report, else the browser's language, else English. renderTemplate() gets no viewing user and
	 * plugin API 1.4.2 has no current user, so the report result's creator stands in for the viewer.
	 * A failed lookup logs one debug line and falls back; it never breaks the page.
	 */
	Locale viewerLocale(ReportResult reportResult) {
		Long userId = creatorId(reportResult)
		if(userId == null) {
			log.debug("Tenant Chargeback: report result ${reportResult?.id} has no creator, using the browser language")
		}
		resolveLocale(userId != null ? userLocaleSetting(userId) : null, browserLocale())
	}

	/** The user's language setting (user.locale), or null when it is empty or cannot be read. */
	String userLocaleSetting(Long userId) {
		try {
			return queryFirstRow(USER_LOCALE_SQL, [userId])?.get('locale')?.toString()
		} catch(Throwable e) {
			log.debug("Tenant Chargeback: language setting of user ${userId} not read, using the browser language: ${e.message}")
			return null
		}
	}

	/** One row of a parameterised query over the read-only report connection. */
	protected Map queryFirstRow(String query, List params) {
		(Map) withDbConnection { Connection c -> newSql(c).firstRow(query, params) }
	}

	/** Locale of the web request (the browser's Accept-Language), or null outside a request. */
	Locale browserLocale() {
		try {
			return morpheus?.webRequest?.locale
		} catch(Throwable ignored) {
			return null
		}
	}

	/** The browser's locale; English outside a web request or when Morpheus does not provide one. */
	Locale requestLocale() {
		browserLocale() ?: Locale.ENGLISH
	}

	/**
	 * Message from the plugin bundles in the browser's language, or the English default. Used for
	 * the option validation, which gets no user to read a language setting from.
	 */
	String msg(String key, String defaultText) {
		text(key, defaultText, requestLocale())
	}
}
