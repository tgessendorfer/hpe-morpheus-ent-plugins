package com.morpheusdata.socketreport

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

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

	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@Unroll
	def "validateOptions rejects '#raw' with the #key message"() {
		when:
		def response = provider.validateOptions([config: [vmsPerSocket: raw]])

		then:
		!response.success
		response.errors.vmsPerSocket == message

		where:
		raw            | key              | message
		'1e2000000000' | 'outOfRange'     | 'Enter a number up to 1,000,000 with at most 3 decimal places, without exponent notation, or leave the field empty for the default.'
		'1000001'      | 'outOfRange'     | 'Enter a number up to 1,000,000 with at most 3 decimal places, without exponent notation, or leave the field empty for the default.'
		'1.2345'       | 'outOfRange'     | 'Enter a number up to 1,000,000 with at most 3 decimal places, without exponent notation, or leave the field empty for the default.'
		'-5'           | 'positiveNumber' | 'Enter a number greater than 0, or leave the field empty for the default.'
	}

	def "validateOptions accepts the bounds themselves"() {
		expect:
		provider.validateOptions([config: [vmsPerSocket: '1000000', defaultHostSockets: '0.001']]).success
	}

	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	def "process falls back to the defaults for stored options outside the bounds and finishes"() {
		given:
		List<ReportResultRow> appended = []
		List<ReportResult.Status> statuses = []
		MorpheusReportService reportService = Mock(MorpheusReportService) {
			updateReportResultStatus(_, _) >> { ReportResult r, ReportResult.Status s -> statuses << s; Completable.complete() }
			appendResultRows(_, _) >> { ReportResult r, Collection<ReportResultRow> rows -> appended.addAll(rows); Single.just(true) }
		}
		MorpheusContext context = Mock(MorpheusContext) { getReport() >> reportService }
		SocketUsageReportProvider noDb = new SocketUsageReportProvider(null, context) {
			@Override
			Object withDbConnection(AbstractReportProvider.WithDbConnectionFunction function) { null }
		}
		Map config = [config: [vmsPerSocket: '1e2000000000', defaultHostSockets: '1E+2000000000']]
		ReportResult result = Stub(ReportResult) { getConfigMap() >> config }

		when:
		noDb.process(result)

		then:
		statuses == [ReportResult.Status.generating, ReportResult.Status.ready]
		Map footer = appended.find { it.section == ReportResultRow.SECTION_FOOTER }.dataMap
		footer.vmsPerSocket == '15'
		footer.defaultHostSockets == '2'
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
		keys += ['socket-usage-report.error.positiveNumber', 'socket-usage-report.error.outOfRange']

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
