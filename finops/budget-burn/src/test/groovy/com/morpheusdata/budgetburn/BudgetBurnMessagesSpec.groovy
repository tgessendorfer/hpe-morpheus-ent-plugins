package com.morpheusdata.budgetburn

import spock.lang.Specification

/**
 * Every message key the template and the provider can ask for exists in the English bundle.
 */
class BudgetBurnMessagesSpec extends Specification {

	Properties en = load('i18n/messages.properties')

	def "every literal i18n key in the template exists"() {
		given:
		String hbs = BudgetBurnMessagesSpec.classLoader.getResourceAsStream('renderer/hbs/budgetBurn.hbs').text
		List<String> keys = (hbs =~ /\{\{i18n '([^']+)'\}\}/).collect { it[1] as String }

		expect:
		keys.size() > 10
		keys.findAll { !en.containsKey(it) } == []
	}

	def "every scope and status key the provider computes exists"() {
		given:
		List<String> keys = (BudgetBurnMath.SCOPES + ['other', null]).collect { BudgetBurnMath.scopeKey(it) } +
			BudgetBurnMath.STATUS_COLORS.keySet().collect { BudgetBurnMath.statusKey(it) } +
			["${BudgetBurnAnalyticsProvider.PROVIDER_CODE}.error.load".toString()]

		expect:
		keys.findAll { !en.containsKey(it) } == []
	}

	def "all keys start with the provider code"() {
		expect:
		en.stringPropertyNames().every { it.startsWith(BudgetBurnAnalyticsProvider.PROVIDER_CODE + '.') }
	}

	private static Properties load(String path) {
		Properties p = new Properties()
		BudgetBurnMessagesSpec.classLoader.getResourceAsStream(path).withCloseable { p.load(it) }
		p
	}
}
