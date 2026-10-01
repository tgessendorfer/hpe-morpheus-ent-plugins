package com.morpheusdata.socketreport

import com.morpheusdata.model.OptionType
import spock.lang.Specification

/**
 * Provider identity, option codes and that every text the provider or the template uses
 * exists in both message bundles.
 */
class SocketUsageReportProviderSpec extends Specification {

	SocketUsageReportProvider provider = new SocketUsageReportProvider(null, null)

	def "provider code and scope follow the plan"() {
		expect:
		provider.code == 'socket-usage-report'
		provider.masterOnly
		!provider.ownerOnly
		provider.category == 'inventory'
	}

	def "option codes start with the provider code and are hyphen-separated"() {
		expect:
		provider.optionTypes*.code == ['socket-usage-report-vms-per-socket', 'socket-usage-report-default-host-sockets']
		provider.optionTypes*.fieldName == ['vmsPerSocket', 'defaultHostSockets']
		provider.optionTypes*.defaultValue == ['15', '2']
		provider.optionTypes.every { !it.code.contains('.') }
	}

	def "validateOptions accepts empty and positive values and rejects others"() {
		expect:
		provider.validateOptions([:]).success
		provider.validateOptions([config: [vmsPerSocket: '10', defaultHostSockets: '4']]).success
		!provider.validateOptions([config: [vmsPerSocket: '0']]).success
		!provider.validateOptions([defaultHostSockets: 'two']).success
	}

	def "the viewer locale falls back to English without a request"() {
		expect:
		provider.viewerLocale() == Locale.ENGLISH
	}

	def "every option label, help text and template key exists in each bundle"() {
		given:
		List<String> keys = provider.optionTypes.collectMany { OptionType o -> [o.fieldCode, o.helpTextI18nCode] }
		String hbs = getClass().getResourceAsStream('/renderer/hbs/socketUsageReport.hbs').text
		keys += (hbs =~ /\{\{i18n '([^']+)'\}\}/).collect { it[1] }
		keys += ['socket-usage-report.error.positiveNumber']

		expect:
		keys.size() > 10
		['messages', 'messages_de'].every { String name ->
			Properties p = new Properties()
			getClass().getResourceAsStream("/i18n/${name}.properties").withStream { p.load(it) }
			List<String> missing = keys.findAll { !p.getProperty(it)?.trim() }
			assert missing.isEmpty(): "${name} misses ${missing}"
			true
		}
	}

	def "every bundle key starts with the provider code"() {
		given:
		Properties p = new Properties()
		getClass().getResourceAsStream('/i18n/messages.properties').withStream { p.load(it) }

		expect:
		p.stringPropertyNames().every { it.startsWith('socket-usage-report') }
	}

	def "the English option texts in code match the English bundle"() {
		given:
		Properties p = new Properties()
		getClass().getResourceAsStream('/i18n/messages.properties').withStream { p.load(it) }

		expect:
		provider.optionTypes.every { OptionType o ->
			p.getProperty(o.fieldCode) == o.fieldLabel && p.getProperty(o.helpTextI18nCode) == o.helpText
		}
	}
}
