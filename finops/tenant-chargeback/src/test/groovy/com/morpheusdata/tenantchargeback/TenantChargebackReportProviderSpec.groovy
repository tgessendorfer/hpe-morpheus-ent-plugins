package com.morpheusdata.tenantchargeback

import com.morpheusdata.model.OptionType
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
	def "every i18n key in the template exists in #bundle"() {
		given:
		Properties p = load("i18n/${bundle}.properties")
		String hbs = getClass().classLoader.getResourceAsStream('renderer/hbs/tenantChargeback.hbs').getText('UTF-8')
		List<String> keys = (hbs =~ /\{\{i18n '([^']+)'\}\}/).collect { it[1] }.unique()

		expect:
		keys.size() > 10
		keys.findAll { !p.getProperty(it)?.trim() } == []

		where:
		bundle << ['messages', 'messages_de']
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
		TenantChargebackReportProvider.INVOICE_SQL.contains('i.site_id AS grp_id')
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

	private static Properties load(String path) {
		Properties p = new Properties()
		InputStream is = TenantChargebackReportProviderSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		p
	}
}
