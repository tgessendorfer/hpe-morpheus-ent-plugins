package com.morpheusdata.tenantchargeback

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.model.Account
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

import java.sql.Connection

class TenantChargebackReportProviderSpec extends Specification {

	TenantChargebackReportProvider provider = new TenantChargebackReportProvider(new TenantChargebackPlugin(), null)

	def "provider identity follows the migration plan, section 4"() {
		expect:
		provider.code == 'tenant-chargeback-report'
		provider.masterOnly
		!provider.ownerOnly
		provider.category == 'cost'
		provider.name == 'Tenant Chargeback'
	}

	def "option type codes start with the provider code, separated by hyphens"() {
		expect:
		provider.optionTypes*.code == [
			'tenant-chargeback-report-month',
			'tenant-chargeback-report-markup-percent',
			'tenant-chargeback-report-provider'
		]
		provider.optionTypes*.fieldName == ['chargebackMonth', 'markupPercent', 'includeProvider']
	}

	@Unroll
	def "every form label and help text has an entry in #bundle"() {
		given:
		Properties p = load("i18n/${bundle}.properties")
		List<String> keys = provider.optionTypes.collectMany { OptionType o ->
			[o.fieldCode] + (o.helpText ? [o.helpTextI18nCode] : [])
		}

		expect:
		keys.every { it?.startsWith('tenant-chargeback-report.') }
		keys.findAll { !p.getProperty(it)?.trim() } == []

		where:
		bundle << ['messages', 'messages_de']
	}

	@Unroll
	def "every text key of the template exists in #bundle"() {
		given:
		Properties p = load("i18n/${bundle}.properties")

		expect:
		TenantChargebackReportProvider.TEXT_KEYS.size() > 10
		TenantChargebackReportProvider.TEXT_KEYS.findAll { !p.getProperty("tenant-chargeback-report.${it}".toString())?.trim() } == []

		where:
		bundle << ['messages', 'messages_de']
	}

	def "the template takes its texts from the model, never from the browser-language i18n helper"() {
		given:
		String hbs = getClass().classLoader.getResourceAsStream('renderer/hbs/tenantChargeback.hbs').getText('UTF-8')
		List<String> used = (hbs =~ /\{\{(?:\.\.\/)?t\.([A-Za-z_]+)\}\}/).collect { it[1] }.unique()
		Set<String> known = TenantChargebackReportProvider.texts(Locale.ENGLISH).keySet()

		expect:
		!hbs.contains('{{i18n')
		used.size() > 10
		used.findAll { !known.contains(it) } == []
		hbs.contains('{{noGroupText}}')
	}

	def "the template shows no literal German or English text outside i18n calls"() {
		given:
		String hbs = getClass().classLoader.getResourceAsStream('renderer/hbs/tenantChargeback.hbs').getText('UTF-8')
		String text = hbs.replaceAll(/(?s)<style>.*?<\/style>/, ' ').replaceAll(/\{\{[^}]*\}\}/, ' ').replaceAll(/<[^>]*>/, ' ').replaceAll(/[\s()%]+/, ' ').trim()

		expect:
		text == ''
	}

	def "the English and German error messages exist"() {
		expect:
		['messages', 'messages_de'].every { String b ->
			Properties p = load("i18n/${b}.properties")
			['tenant-chargeback-report.error.month', 'tenant-chargeback-report.error.markup'].every { p.getProperty(it) }
		}
	}

	@Unroll
	def "validateOptions accepts month '#month' and markup '#markup': #ok"() {
		when:
		ServiceResponse r = provider.validateOptions([config: [chargebackMonth: month, markupPercent: markup]])

		then:
		r.success == ok
		ok || r.errors.containsKey(field)

		where:
		month     | markup | ok    | field
		''        | '0'    | true  | null
		'2026-09' | '7,5'  | true  | null
		'2026-12' | ''     | true  | null
		'2026-00' | '0'    | false | 'chargebackMonth'
		'2026-13' | '0'    | false | 'chargebackMonth'
		'2026-09' | 'ten'  | false | 'markupPercent'
		'2026-09' | '1000' | true  | null
		'2026-09' | '7.1234' | true | null
		'2026-09' | '-5'   | false | 'markupPercent'
		'2026-09' | '1e3'  | false | 'markupPercent'
		'2026-09' | '1E-2' | false | 'markupPercent'
		'2026-09' | '1000.01' | false | 'markupPercent'
		'2026-09' | '7.12345' | false | 'markupPercent'
	}

	def "the markup error names the accepted range in English and German"() {
		given:
		Properties en = load('i18n/messages.properties')
		Properties de = load('i18n/messages_de.properties')

		expect:
		en.getProperty('tenant-chargeback-report.error.markup').contains('0 to 1000')
		de.getProperty('tenant-chargeback-report.error.markup').contains('0 bis 1000')
		provider.validateOptions([config: [markupPercent: '-5']]).errors.markupPercent.contains('0 to 1000')
	}

	def "the invoice query groups by group id, not by group name"() {
		given:
		String groupBy = TenantChargebackReportProvider.INVOICE_SQL.find(/(?s)GROUP BY(.*?)ORDER BY/) { all, g -> g }

		expect:
		groupBy.contains('i.site_id')
		!groupBy.contains('site_name')
		!groupBy.contains('cs.')
		TenantChargebackReportProvider.INVOICE_SQL.contains('i.site_id AS grp_id')
	}

	def "the invoice query counts server invoices only for servers that belong to no instance"() {
		given:
		String q = TenantChargebackReportProvider.INVOICE_SQL.replaceAll(/\s+/, ' ')

		expect: 'an instance VM has an instance invoice and a server invoice without instance_id; the container row links them'
		q.contains("i.ref_type = 'Instance' OR (i.ref_type = 'ComputeServer' AND i.instance_id IS NULL")
		q.contains('NOT EXISTS (SELECT 1 FROM container ct WHERE ct.server_id = i.ref_id AND ct.instance_id IS NOT NULL)')
	}

	def "the invoice query looks a missing group name up in compute_site by group id"() {
		given:
		String q = TenantChargebackReportProvider.INVOICE_SQL.replaceAll(/\s+/, ' ')

		expect: 'a LEFT JOIN, so lines without a group or with a deleted group are kept'
		q.contains('LEFT JOIN compute_site cs ON cs.id = i.site_id')
		q.contains("MAX(COALESCE(NULLIF(TRIM(i.site_name), ''), cs.name)) AS grp")
		!q.contains(' JOIN compute_site cs ON cs.name')
	}

	def "the invoice query keys, counts and sums its rows as queryRows() in ChargebackCalculatorSpec models them"() {
		given:
		String q = TenantChargebackReportProvider.INVOICE_SQL.replaceAll(/\s+/, ' ')
		String groupBy = q.find(/GROUP BY (.*?) ORDER BY/) { all, g -> g }

		expect: 'the model keys a row by tenant id, group id and trimmed currency (empty = none); a.name and a.master_account follow from a.id'
		groupBy == "a.id, a.name, a.master_account, i.site_id, NULLIF(TRIM(i.currency), '')"
		q.contains("NULLIF(TRIM(i.currency), '') AS currency,")

		and: 'the model counts the invoices of a row and sums their cost and price'
		q.contains('COUNT(*) AS resources,')
		q.contains('SUM(COALESCE(i.total_cost, 0)) AS cost, SUM(COALESCE(i.total_price, 0)) AS price FROM ')
	}

	def "the language setting query is parameterised"() {
		expect:
		TenantChargebackReportProvider.USER_LOCALE_SQL == 'SELECT locale FROM user WHERE id = ?'
	}

	def "the viewer locale reads the creator's setting with the user id as a parameter"() {
		given:
		List calls = []
		TenantChargebackReportProvider p = stub(setting: 'en-US', browser: Locale.forLanguageTag('de-DE'), calls: calls)

		when:
		Locale l = p.viewerLocale(result(42L))

		then: 'en-US wins over the German browser'
		l == Locale.forLanguageTag('en-US')
		calls == [[TenantChargebackReportProvider.USER_LOCALE_SQL, [42L]]]
	}

	@Unroll
	def "setting #setting with browser #browser and user #userId gives #expected"() {
		given:
		List calls = []
		TenantChargebackReportProvider p = stub(setting: setting, browser: browser, calls: calls)

		expect:
		p.viewerLocale(result(userId)) == expected
		calls.size() == (userId != null ? 1 : 0)

		where:
		setting   | browser                        | userId | expected
		'en-US'   | Locale.forLanguageTag('de-DE') | 1L     | Locale.forLanguageTag('en-US')
		'de'      | Locale.forLanguageTag('en-US') | 1L     | Locale.GERMAN
		null      | Locale.forLanguageTag('de-DE') | 1L     | Locale.forLanguageTag('de-DE')
		''        | Locale.forLanguageTag('de-DE') | 1L     | Locale.forLanguageTag('de-DE')
		'garbage' | Locale.forLanguageTag('de-DE') | 1L     | Locale.forLanguageTag('de-DE')
		'de'      | Locale.forLanguageTag('en-US') | null   | Locale.forLanguageTag('en-US')
		null      | null                           | 1L     | Locale.ENGLISH
		null      | null                           | null   | Locale.ENGLISH
	}

	def "a failed language lookup falls back to the browser language and does not throw"() {
		given:
		TenantChargebackReportProvider p = new TenantChargebackReportProvider(new TenantChargebackPlugin(), null) {
			@Override protected Map queryFirstRow(String query, List params) { throw new IllegalStateException('no connection') }
			@Override Locale browserLocale() { Locale.forLanguageTag('de-DE') }
		}

		expect:
		p.viewerLocale(result(7L)) == Locale.forLanguageTag('de-DE')
		p.userLocaleSetting(7L) == null
	}

	def "without a Morpheus context the lookup fails quietly and the page is English"() {
		expect:
		provider.viewerLocale(result(7L)) == Locale.ENGLISH
		provider.viewerLocale(null) == Locale.ENGLISH
	}

	def "texts come from the plugin bundles, English for en-US even when the JVM default is German"() {
		given:
		Locale saved = Locale.default
		Locale.default = Locale.GERMAN

		when:
		Map<String, String> en = TenantChargebackReportProvider.texts(Locale.forLanguageTag('en-US'))
		Map<String, String> de = TenantChargebackReportProvider.texts(Locale.forLanguageTag('de-DE'))
		Map<String, String> fr = TenantChargebackReportProvider.texts(Locale.FRENCH)

		then:
		en.col_cost == 'Cost'
		en.noGroup == 'Servers without a group'
		de.col_cost == 'Kosten'
		de.section_currency == 'Summe je W\u00e4hrung'
		fr.col_cost == 'Cost'
		en.keySet() == TenantChargebackReportProvider.TEXT_KEYS.collect { it.replace('.', '_') } as Set

		cleanup:
		Locale.default = saved
	}

	def "a bundle of the same name found first on the class path is skipped for the plugin's own"() {
		given: 'a foreign i18n/messages.properties ahead of the plugin one, as a parent-first class loader would order them'
		File foreign = File.createTempFile('foreign-messages', '.properties')
		foreign.text = 'default.title=Other\n'
		URL own = TenantChargebackReportProvider.classLoader.getResource('i18n/messages.properties')

		when:
		Properties p = TenantChargebackReportProvider.ownBundle([foreign.toURI().toURL(), own])

		then:
		p.getProperty(TenantChargebackReportProvider.BUNDLE_MARKER_KEY) == 'Tenant Chargeback'
		TenantChargebackReportProvider.ownBundle([foreign.toURI().toURL()]).isEmpty()

		cleanup:
		foreign?.delete()
	}

	def "a key missing from the German bundle falls back to English, then to the default text"() {
		expect:
		TenantChargebackReportProvider.text('tenant-chargeback-report.col.cost', 'x', Locale.GERMAN) == 'Kosten'
		TenantChargebackReportProvider.text('no.such.key', 'Fallback', Locale.GERMAN) == 'Fallback'
		TenantChargebackReportProvider.text('no.such.key', 'Fallback', null) == 'Fallback'
	}

	def "a report result without a creator uses the browser language"() {
		given:
		List calls = []
		TenantChargebackReportProvider p = stub(setting: 'en-US', browser: Locale.forLanguageTag('de-DE'), calls: calls)

		expect:
		p.viewerLocale(new ReportResult(createdBy: new User())) == Locale.forLanguageTag('de-DE')
		calls.isEmpty()
	}

	def "the template brings its own cards and right-aligned number columns, colors from the theme"() {
		given:
		String hbs = getClass().classLoader.getResourceAsStream('renderer/hbs/tenantChargeback.hbs').getText('UTF-8')
		String css = hbs.find(/(?s)<style>(.*?)<\/style>/) { all, c -> c }

		expect:
		!hbs.contains('stats-container')
		hbs.findAll('<td class="num">').size() == 6 + 6 + 5
		css.readLines()*.trim().findAll { it && !it.startsWith('.finops-chargeback ') }.isEmpty()
		!(hbs =~ /#[0-9A-Fa-f]{3,6}\b/)
		!(css =~ /(^|[\s;{])color\s*:/)
		!css.contains('var(--')
	}

	def "the page leaves out a currency without any amount, but keeps it in the stored rows"() {
		given: 'zero-priced servers in USD next to priced EUR invoices, as on the lab appliance'
		Map<String, List<ReportResultRow>> rows = [
			main  : [row(tenant: 'Contoso', group: 'DC1', currency: 'EUR', resources: '5', cost: '23.96', price: '29.48', margin: '5.52', invoice: '29.48'),
				row(tenant: 'Contoso', group: 'DC1', currency: 'USD', resources: '3', cost: '0.00', price: '0.00', margin: '0.00', invoice: '0.00'),
				row(tenant: 'Contoso', group: 'AWS', currency: 'EUR', resources: '1', cost: '0.00', price: '0.00', margin: '0.00', invoice: '0.00')],
			header: [row(tenant: 'Contoso', currency: 'USD', resources: '3', cost: '0.00', price: '0.00', margin: '0.00', marginPct: '0.0', invoice: '0.00'),
				row(tenant: 'Contoso', currency: 'EUR', resources: '6', cost: '23.96', price: '29.48', margin: '5.52', marginPct: '18.7', invoice: '29.48')],
			footer: [row(kind: 'meta', month: '2026-10', current: true, markupPercent: '0', tenants: '1', currencies: 'USD, EUR'),
				row(kind: 'total', currency: 'USD', resources: '3', cost: '0.00', price: '0.00', margin: '0.00', marginPct: '0.0', invoice: '0.00'),
				row(kind: 'total', currency: 'EUR', resources: '6', cost: '23.96', price: '29.48', margin: '5.52', marginPct: '18.7', invoice: '29.48')]
		]

		when:
		Map m = TenantChargebackReportProvider.pageModel(rows, Locale.ENGLISH)

		then: 'USD is gone everywhere; a zero EUR group of a priced currency stays'
		m.totals*.currency == ['EUR']
		m.tenants*.currency == ['EUR']
		m.rows*.currency == ['EUR', 'EUR']
		m.rows*.group == ['DC1', 'AWS']
		m.footer.tenants == '1'

		and: 'the stored rows, which the CSV export writes, are untouched'
		rows.footer.size() == 3
		rows.main.size() == 3
	}

	def "the tenant count on the page leaves out a tenant whose only currency is hidden"() {
		given: 'Tenant A priced in EUR, Tenant B with unpriced servers in USD only'
		Map<String, List<ReportResultRow>> rows = [
			main  : [row(tenant: 'Tenant A', group: 'DC1', currency: 'EUR', resources: '5', cost: '23.96', price: '29.48', margin: '5.52', invoice: '29.48'),
				row(tenant: 'Tenant B', group: 'DC2', currency: 'USD', resources: '2', cost: '0.00', price: '0.00', margin: '0.00', invoice: '0.00')],
			header: [row(tenant: 'Tenant A', currency: 'EUR', resources: '5', cost: '23.96', price: '29.48', margin: '5.52', marginPct: '18.7', invoice: '29.48'),
				row(tenant: 'Tenant B', currency: 'USD', resources: '2', cost: '0.00', price: '0.00', margin: '0.00', marginPct: '0.0', invoice: '0.00')],
			footer: [row(kind: 'meta', month: '2026-10', current: true, markupPercent: '0', tenants: '2', currencies: 'EUR, USD'),
				row(kind: 'total', currency: 'EUR', resources: '5', cost: '23.96', price: '29.48', margin: '5.52', marginPct: '18.7', invoice: '29.48'),
				row(kind: 'total', currency: 'USD', resources: '2', cost: '0.00', price: '0.00', margin: '0.00', marginPct: '0.0', invoice: '0.00')]
		]

		when:
		Map m = TenantChargebackReportProvider.pageModel(rows, Locale.ENGLISH)

		then: 'the card counts the tenants the tables list; the stored count stays for the CSV export'
		m.tenants*.tenant == ['Tenant A']
		m.footer.tenants == '1'
		rows.footer[0].dataMap.tenants == '2'

		and: 'with nothing hidden the stored count is shown'
		TenantChargebackReportProvider.pageModel([header: rows.header, footer: [rows.footer[0], rows.footer[1]]], Locale.ENGLISH).footer.tenants == '2'
	}

	def "process() runs the invoice query for a user of the master tenant and stores the query's columns"() {
		given: 'two subtenants and the master tenant; a line without a currency takes the master currency'
		Map env = processEnv()
		ReportResult rr = new ReportResult(createdBy: new User(id: 7L))
		rr.configMap = [chargebackMonth: '2026-09', markupPercent: '10']
		env.sql.firstRow(TenantChargebackReportProvider.USER_MASTER_SQL, [7L]) >> dbRow(is_master: 1G)
		env.sql.firstRow(TenantChargebackReportProvider.MASTER_CURRENCY_SQL) >> dbRow(currency: 'EUR')

		when:
		env.p.process(rr)

		then:
		1 * env.sql.rows(TenantChargebackReportProvider.INVOICE_SQL, ['202609']) >> [
			dbRow(tenant_id: 2L, tenant: 'Tenant A', is_master: 0G, grp_id: 5L, grp: 'Group 5', currency: 'EUR', resources: 3L, cost: 10.004G, price: 12.5G),
			dbRow(tenant_id: 3L, tenant: 'Tenant B', is_master: 0G, grp_id: null, grp: null, currency: null, resources: 1L, cost: 1.0G, price: 2.0G),
			dbRow(tenant_id: 1L, tenant: 'Provider', is_master: 1G, grp_id: 9L, grp: 'Own', currency: 'EUR', resources: 4L, cost: 50.0G, price: 60.0G)]
		env.statuses == [ReportResult.Status.generating, ReportResult.Status.ready]

		and: 'one row per tenant and group, the master tenant left out'
		section(env.appended, ReportResultRow.SECTION_MAIN) == [
			[tenant: 'Tenant A', group: 'Group 5', noGroup: false, resources: '3', currency: 'EUR', cost: '10.00', price: '12.50',
			 margin: '2.50', markupPercent: '10', invoice: '13.75'],
			[tenant: 'Tenant B', group: '', noGroup: true, resources: '1', currency: 'EUR', cost: '1.00', price: '2.00',
			 margin: '1.00', markupPercent: '10', invoice: '2.20']]
		section(env.appended, ReportResultRow.SECTION_HEADER)*.marginPct == ['20.0', '50.0']

		and:
		List<Map> footer = section(env.appended, ReportResultRow.SECTION_FOOTER)
		footer[0].subMap(['kind', 'month', 'markupPercent', 'tenants', 'currencies']) ==
			[kind: 'meta', month: '2026-09', markupPercent: '10', tenants: '2', currencies: 'EUR']
		footer[1] == [kind: 'total', currency: 'EUR', resources: '4', cost: '11.00', price: '14.50', margin: '3.50', marginPct: '24.1', invoice: '15.95']
	}

	def "process() fails without reading any invoice when the creator does not belong to the master tenant"() {
		given:
		Map env = processEnv()
		ReportResult rr = new ReportResult(createdBy: new User(id: 8L))
		rr.configMap = [chargebackMonth: '2026-09']

		when:
		env.p.process(rr)

		then:
		1 * env.sql.firstRow(TenantChargebackReportProvider.USER_MASTER_SQL, [8L]) >> dbRow(is_master: 0G)
		0 * env.sql.rows(*_)
		env.statuses == [ReportResult.Status.generating, ReportResult.Status.failed]
		env.appended.isEmpty()
	}

	@Unroll
	def "a result without a creator runs only when its own account is the master tenant: account #accountId, is_master #isMaster"() {
		given:
		Map env = processEnv()
		ReportResult rr = new ReportResult(account: accountId != null ? new Account(id: accountId) : null)
		rr.configMap = [:]
		env.sql.firstRow(TenantChargebackReportProvider.ACCOUNT_MASTER_SQL, [accountId]) >> dbRow(is_master: isMaster)
		env.sql.rows(TenantChargebackReportProvider.INVOICE_SQL, _) >> []

		when:
		env.p.process(rr)

		then:
		0 * env.sql.firstRow(TenantChargebackReportProvider.USER_MASTER_SQL, _)
		env.statuses.last() == expected

		where:
		accountId | isMaster || expected
		1L        | 1G       || ReportResult.Status.ready
		2L        | 0G       || ReportResult.Status.failed
		null      | null     || ReportResult.Status.failed
	}

	@Unroll
	def "process() fails for month '#month' before it takes a database connection"() {
		given:
		Map env = processEnv()
		ReportResult rr = new ReportResult(createdBy: new User(id: 7L))
		rr.configMap = [chargebackMonth: month]

		when:
		env.p.process(rr)

		then:
		0 * env.report.getReadOnlyDatabaseConnection()
		env.statuses == [ReportResult.Status.generating, ReportResult.Status.failed]

		where:
		month << ['2026-1', '2026-13', 'September']
	}

	def "zeroCurrencies hides nothing when every currency is zero, and treats unreadable amounts as amounts"() {
		expect:
		TenantChargebackReportProvider.zeroCurrencies([[currency: 'USD', cost: '0', price: '0.00', invoice: '0.00']]) == [] as Set
		TenantChargebackReportProvider.zeroCurrencies([[currency: 'USD', cost: '0', price: '0', invoice: '0'],
			[currency: 'EUR', cost: '1', price: '2', invoice: '2']]) == ['USD'] as Set
		TenantChargebackReportProvider.zeroCurrencies([[currency: 'USD', cost: '0', price: 'n/a', invoice: '0'],
			[currency: 'EUR', cost: '1', price: '2', invoice: '2']]) == [] as Set
		TenantChargebackReportProvider.zeroCurrencies([]) == [] as Set
	}

	def "the page model formats numbers and texts in the resolved locale"() {
		given:
		Map<String, List<ReportResultRow>> rows = [
			main  : [new ReportResultRow(dataMap: [tenant: 'Tenant A', group: '', noGroup: true, resources: '2', currency: 'EUR',
				cost: '1234.50', price: '2000.00', margin: '765.50', markupPercent: '7.5', invoice: '2150.00'])],
			header: [new ReportResultRow(dataMap: [tenant: 'Tenant A', currency: 'EUR', resources: '2', cost: '1234.50',
				price: '2000.00', margin: '765.50', marginPct: '38.3', invoice: '2150.00'])],
			footer: [new ReportResultRow(dataMap: [kind: 'meta', month: '2026-09', current: false, markupPercent: '7.5', tenants: '1', currencies: 'EUR']),
				new ReportResultRow(dataMap: [kind: 'total', currency: 'EUR', resources: '2', cost: '1234.50', price: '2000.00',
					margin: '765.50', marginPct: '38.3', invoice: '2150.00'])]
		]
		TenantChargebackReportProvider en = stub(setting: 'en-US', browser: Locale.forLanguageTag('de-DE'), calls: [])
		TenantChargebackReportProvider de = stub(setting: 'de-DE', browser: Locale.US, calls: [])

		when:
		Map e = TenantChargebackReportProvider.pageModel(rows, en.viewerLocale(result(1L)))
		Map d = TenantChargebackReportProvider.pageModel(rows, de.viewerLocale(result(1L)))

		then:
		e.rows[0].costText == '1,234.50'
		e.rows[0].noGroupText == 'Servers without a group'
		e.tenants[0].marginPctText == '38.3'
		e.totals[0].invoiceText == '2,150.00'
		e.footer.markupPercentText == '7.5'
		e.t.col_invoice == 'Invoice Amount'
		d.rows[0].costText == '1.234,50'
		d.rows[0].noGroupText == 'Server ohne Gruppe'
		d.footer.markupPercentText == '7,5'
		d.t.col_invoice == 'Rechnungsbetrag'
		d.rows[0].cost == '1234.50'
	}

	def "without a web request messages fall back to English and the locale to ENGLISH"() {
		expect:
		provider.requestLocale() == Locale.ENGLISH
		provider.msg('any.key', 'Fallback') == 'Fallback'
	}

	def "withTexts formats the stored plain values in the viewer's locale"() {
		given:
		Map row = [tenant: 'T', cost: '1234.50', price: '2000.00', margin: '765.50', invoice: '2150.00', marginPct: '38.3', noGroup: false]

		when:
		Map de = TenantChargebackReportProvider.withTexts(row, Locale.GERMAN)
		Map en = TenantChargebackReportProvider.withTexts(row, Locale.ENGLISH)

		then:
		de.costText == '1.234,50'
		de.marginPctText == '38,3'
		en.invoiceText == '2,150.00'
		en.marginPctText == '38.3'
		!en.noGroup
		en.cost == '1234.50'
	}

	/** A provider whose language setting and browser locale are given; records the queries it runs. */
	private static TenantChargebackReportProvider stub(Map m) {
		new TenantChargebackReportProvider(new TenantChargebackPlugin(), null) {
			@Override protected Map queryFirstRow(String query, List params) {
				m.calls << [query, params]
				[locale: m.setting]
			}
			@Override Locale browserLocale() { m.browser as Locale }
		}
	}

	/**
	 * A provider whose report service records the statuses and appended rows, and whose report
	 * connection yields the mocked Sql {@code sql}; the queries are stubbed per feature.
	 */
	private Map processEnv() {
		List<ReportResult.Status> statuses = []
		List<ReportResultRow> appended = []
		Connection conn = Mock()
		MorpheusReportService report = Mock() {
			getReadOnlyDatabaseConnection() >> Single.just(conn)
			releaseDatabaseConnection(_) >> Completable.complete()
			updateReportResultStatus(_, _) >> { ReportResult r, ReportResult.Status s -> statuses << s; Completable.complete() }
			appendResultRows(_, _) >> { ReportResult r, Collection<ReportResultRow> rows -> appended.addAll(rows); Single.just(true) }
		}
		MorpheusContext ctx = Mock() { getReport() >> report }
		Sql sql = Mock()
		TenantChargebackReportProvider p = Spy(TenantChargebackReportProvider, constructorArgs: [new TenantChargebackPlugin(), ctx]) {
			newSql(_) >> sql
		}
		[p: p, sql: sql, report: report, statuses: statuses, appended: appended]
	}

	private static GroovyRowResult dbRow(Map m) {
		new GroovyRowResult(new LinkedHashMap(m))
	}

	private static List<Map> section(List<ReportResultRow> rows, String section) {
		rows.findAll { it.section == section }*.dataMap
	}

	private static ReportResult result(Long userId) {
		new ReportResult(createdBy: userId != null ? new User(id: userId) : null)
	}

	private static Properties load(String path) {
		Properties p = new Properties()
		InputStream is = TenantChargebackReportProviderSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		p
	}

	private static ReportResultRow row(Map data) {
		new ReportResultRow(dataMap: data)
	}
}
