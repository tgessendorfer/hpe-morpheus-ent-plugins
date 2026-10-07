package com.morpheusdata.tenantchargeback

import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

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
		String text = hbs.replaceAll(/\{\{[^}]*\}\}/, ' ').replaceAll(/<[^>]*>/, ' ').replaceAll(/[\s()%]+/, ' ').trim()

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
}
