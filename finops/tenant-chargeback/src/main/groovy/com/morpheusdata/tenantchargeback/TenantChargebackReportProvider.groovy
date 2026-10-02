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
 * instance; the summary invoices per account, group, cloud and user are left out, they would count
 * twice. cost = purchase cost, price = list price including the price-set markup, margin =
 * price - cost. An optional additional markup in percent is applied to the list price.
 *
 * process() stores plain, machine-readable values only (they are what the CSV export shows);
 * renderTemplate() formats them in the viewer's locale.
 *
 * masterOnly: the report SQL sees all tenants.
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
	 */
	static final String INVOICE_SQL = '''
		SELECT a.id AS tenant_id, a.name AS tenant, CAST(a.master_account AS UNSIGNED) AS is_master,
		       i.site_id AS grp_id, MAX(i.site_name) AS grp, NULLIF(TRIM(i.currency), '') AS currency,
		       COUNT(*) AS resources,
		       SUM(COALESCE(i.total_cost, 0)) AS cost, SUM(COALESCE(i.total_price, 0)) AS price
		FROM account_invoice i JOIN account a ON a.id = i.account_id
		WHERE i.period_interval = 'month' AND i.period = ?
		  AND (i.ref_type = 'Instance' OR (i.ref_type = 'ComputeServer' AND i.instance_id IS NULL))
		GROUP BY a.id, a.name, a.master_account, i.site_id, NULLIF(TRIM(i.currency), '')
		ORDER BY a.master_account, a.name, grp, grp_id, currency'''

	/** Currency of the master tenant, the second step of the currency rule. */
	static final String MASTER_CURRENCY_SQL = '''
		SELECT NULLIF(TRIM(currency), '') AS currency FROM account
		WHERE master_account = 1 ORDER BY id LIMIT 1'''

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
			String per = period(cfg(cfgMap, FIELD_MONTH))
			BigDecimal markup = parsePercent(cfg(cfgMap, FIELD_MARKUP))
			boolean includeProvider = isTrue(cfg(cfgMap, FIELD_PROVIDER))
			List<GroovyRowResult> rows = []
			String masterCurrency = null
			withDbConnection { Connection c ->
				Sql sql = new Sql(c)
				rows = sql.rows(INVOICE_SQL, [per])
				masterCurrency = sql.firstRow(MASTER_CURRENCY_SQL)?.currency as String
			}
			List<Map> raw = rows.collect { GroovyRowResult r ->
				[tenantId: r.tenant_id, tenant: r.tenant, isMaster: (r.is_master as Integer) == 1, grpId: r.grp_id, grp: r.grp,
				 currency: r.currency, resources: r.resources, cost: r.cost, price: r.price]
			}
			Map result = aggregate(raw, masterCurrency, markup, includeProvider)
			String markupText = markup.stripTrailingZeros().toPlainString()

			Long order = 0
			List<ReportResultRow> out = []
			result.lines.each { Map l ->
				out << new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: order++, dataMap: [
					tenant: l.tenant, group: l.group ?: '', noGroup: l.group == null, resources: l.resources.toString(),
					currency: l.currency, cost: plain(l.cost), price: plain(l.price), margin: plain(l.margin),
					markupPercent: markupText, invoice: plain(l.invoice)])
			}
			result.tenants.each { Map t ->
				out << new ReportResultRow(section: ReportResultRow.SECTION_HEADER, displayOrder: order++, dataMap: [
					tenant: t.tenant, currency: t.currency, resources: t.resources.toString(),
					cost: plain(t.cost), price: plain(t.price), margin: plain(t.margin),
					marginPct: pctText(t.margin, t.price), invoice: plain(t.invoice)])
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
			// The report reads internal tables (account_invoice, account), tested on Morpheus 9.0.2 only.
			log.error("Tenant Chargeback report ${reportResult?.id} failed; it reads internal tables tested on Morpheus 9.0.2 only: ${e.message}")
			log.debug('Tenant Chargeback report failure', e)
			morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
		}
	}

	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		Locale locale = requestLocale()
		List<Map> footer = reportRowsBySection.footer?.collect { it.dataMap } ?: []
		Map meta = footer.find { it.kind == 'meta' } ?: [:]
		ViewModel<Map> model = new ViewModel<>()
		model.object = [
			rows   : (reportRowsBySection.main?.collect { it.dataMap } ?: []).collect { withTexts(it, locale) },
			tenants: (reportRowsBySection.header?.collect { it.dataMap } ?: []).collect { withTexts(it, locale) },
			footer : meta + [isCurrent: isTrue(meta.current), markupPercentText: decimalShort(meta.markupPercent ?: '0', locale)],
			totals : footer.findAll { it.kind == 'total' }.collect { withTexts(it, locale) }
		]
		getRenderer().renderTemplate('hbs/tenantChargeback', model)
	}

	/** Adds the locale-formatted texts the template shows next to the plain stored values. */
	static Map withTexts(Map row, Locale locale) {
		Map m = new LinkedHashMap(row)
		['cost', 'price', 'margin', 'invoice'].each { String k ->
			if(row.containsKey(k)) m["${k}Text".toString()] = money(row[k], locale)
		}
		if(row.containsKey('marginPct')) m.marginPctText = decimal1(row.marginPct, locale)
		m.noGroup = isTrue(row.noGroup)
		m
	}

	/** The viewer's locale; English outside a web request or when Morpheus does not provide one. */
	Locale requestLocale() {
		try {
			return morpheus?.webRequest?.locale ?: Locale.ENGLISH
		} catch(Throwable ignored) {
			return Locale.ENGLISH
		}
	}

	/** Message from the plugin bundles in the viewer's language, or the English default. */
	String msg(String key, String defaultText) {
		try {
			def web = morpheus?.webRequest
			return web ? (web.getMessage(key, null, defaultText, requestLocale()) ?: defaultText) : defaultText
		} catch(Throwable ignored) {
			return defaultText
		}
	}
}
