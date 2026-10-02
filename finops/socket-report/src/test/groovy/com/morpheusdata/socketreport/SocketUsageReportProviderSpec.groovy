package com.morpheusdata.socketreport

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.core.web.MorpheusWebRequestService
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.User
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import java.sql.Connection
import java.sql.ParameterMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
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

	/** Provider with a browser locale, a stored user setting (or a failing lookup) and the real template. */
	private SocketUsageReportProvider renderingProvider(Locale browser, Object setting, List<Long> lookups = []) {
		MorpheusWebRequestService web = Stub(MorpheusWebRequestService) { getLocale() >> browser }
		MorpheusContext context = Stub(MorpheusContext) { getWebRequest() >> (browser ? web : null) }
		Renderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
		return new SocketUsageReportProvider(null, context) {
			@Override
			Object readUserSetting(Long userId) {
				lookups << userId
				if (setting instanceof Exception) {
					throw (Exception) setting
				}
				return setting
			}

			@Override
			Renderer<?> getRenderer() { renderer }
		}
	}

	private static ReportResult resultOf(Long userId) {
		ReportResult result = new ReportResult()
		if (userId != null) {
			result.createdBy = new User(id: userId)
		}
		return result
	}

	private static Map<String, List<ReportResultRow>> rowsOf() {
		Map footer = SocketMath.summarize([[cloud: 'Cloud A', cloud_type: 'Proxmox', category: 'proxmox', tenant: 'Tenant A',
			is_master: 1, hosts: 2, host_sockets_known: 1234, hosts_default: 0, vms_without_host: 3, vms_on_hosts: 0]],
			SocketMath.DEFAULT_VMS_PER_SOCKET, SocketMath.DEFAULT_HOST_SOCKETS).footer as Map
		return [footer: [new ReportResultRow(section: ReportResultRow.SECTION_FOOTER, dataMap: footer)]]
	}

	@Unroll
	def "content locale for setting '#setting' and browser #browser is #expected"() {
		given:
		List<Long> lookups = []
		SocketUsageReportProvider p = renderingProvider(browser, setting, lookups)

		expect:
		p.contentLocale(resultOf(5L)) == expected
		lookups == [5L]

		where:
		setting                                  | browser        | expected
		'en-US'                                  | Locale.GERMANY | Locale.forLanguageTag('en-US')
		'de'                                     | Locale.US      | Locale.GERMAN
		null                                     | Locale.GERMANY | Locale.GERMANY
		' '                                      | Locale.GERMANY | Locale.GERMANY
		'not a locale!'                          | Locale.GERMANY | Locale.GERMANY
		new IllegalStateException('no db')       | Locale.GERMANY | Locale.GERMANY
		null                                     | null           | Locale.ENGLISH
	}

	def "without a user on the result the browser language applies and nothing is looked up"() {
		given:
		List<Long> lookups = []
		SocketUsageReportProvider p = renderingProvider(Locale.GERMANY, 'en-US', lookups)

		expect:
		p.contentLocale(resultOf(null)) == Locale.GERMANY
		p.contentLocale(null) == Locale.GERMANY
		lookups.isEmpty()
	}

	def "a failing lookup without a request still renders, in English"() {
		given:
		SocketUsageReportProvider p = new SocketUsageReportProvider(null, null) {
			@Override
			Renderer<?> getRenderer() { new HandlebarsRenderer('renderer', getClass().classLoader) }
		}

		when:
		String html = p.renderTemplate(resultOf(5L), rowsOf()).html

		then:
		html.contains('Sockets in total')
		html.contains('1,234.200')
	}

	def "an en-US setting renders English texts and numbers although the browser sends German"() {
		when:
		String html = renderingProvider(Locale.GERMANY, 'en-US').renderTemplate(resultOf(5L), rowsOf()).html

		then:
		html.contains('Sockets in total')
		html.contains('Per cloud and tenant')
		html.contains('1,234.200')
		!html.contains('Sockets gesamt')
		!html.contains('1.234,200')
	}

	def "a German setting renders German texts and numbers although the browser sends English"() {
		when:
		String html = renderingProvider(Locale.US, 'de-DE').renderTemplate(resultOf(5L), rowsOf()).html

		then:
		html.contains('Sockets gesamt')
		html.contains('1.234,200')
		!html.contains('Sockets in total')
	}

	/** Provider whose setting comes through the real withDbConnection over a stubbed report service. */
	private SocketUsageReportProvider databaseProvider(Locale browser, MorpheusReportService reportService) {
		MorpheusWebRequestService web = Stub(MorpheusWebRequestService) { getLocale() >> browser }
		MorpheusContext context = Stub(MorpheusContext) {
			getWebRequest() >> web
			getReport() >> reportService
		}
		Renderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
		return new SocketUsageReportProvider(null, context) {
			@Override
			Renderer<?> getRenderer() { renderer }
		}
	}

	def "the setting is read over the read-only report connection, bound as a parameter, and the connection is released"() {
		given:
		List<String> prepared = []
		Map<Integer, Object> bound = [:]
		ResultSetMetaData meta = Stub(ResultSetMetaData) {
			getColumnCount() >> 1
			getColumnLabel(1) >> 'locale'
			getColumnName(1) >> 'locale'
		}
		ResultSet rs = Stub(ResultSet) {
			next() >>> [true, false]
			getMetaData() >> meta
			getObject(1) >> 'en-US'
		}
		ParameterMetaData params = Stub(ParameterMetaData) { getParameterCount() >> 1 }
		PreparedStatement statement = Stub(PreparedStatement) {
			getParameterMetaData() >> params
			setObject(_, _) >> { int index, Object value -> bound[index] = value }
			executeQuery() >> rs
			execute() >> true
			getResultSet() >> rs
		}
		Connection conn = Stub(Connection) {
			prepareStatement(*_) >> { args -> prepared << (args[0] as String); statement }
		}
		MorpheusReportService reportService = Mock(MorpheusReportService)
		SocketUsageReportProvider p = databaseProvider(Locale.GERMANY, reportService)

		when:
		String html = p.renderTemplate(resultOf(5L), rowsOf()).html

		then:
		1 * reportService.getReadOnlyDatabaseConnection() >> Single.just(conn)
		1 * reportService.releaseDatabaseConnection(conn) >> Completable.complete()
		prepared == [ContentLocale.USER_LOCALE_SQL]
		bound == [1: 5L]
		html.contains('Sockets in total')
		html.contains('1,234.200')
		!html.contains('Sockets gesamt')
	}

	def "a failing query falls back to the browser language and still releases the connection"() {
		given:
		Connection conn = Stub(Connection) {
			prepareStatement(*_) >> { throw new SQLException('no such column') }
		}
		MorpheusReportService reportService = Mock(MorpheusReportService)
		SocketUsageReportProvider p = databaseProvider(Locale.GERMANY, reportService)

		when:
		String html = p.renderTemplate(resultOf(5L), rowsOf()).html

		then:
		1 * reportService.getReadOnlyDatabaseConnection() >> Single.just(conn)
		1 * reportService.releaseDatabaseConnection(conn) >> Completable.complete()
		html.contains('Sockets gesamt')
		html.contains('1.234,200')
	}

	def "the template uses no i18n helper, so no text follows the request locale"() {
		expect:
		!getClass().getResourceAsStream('/renderer/hbs/socketUsageReport.hbs').text.contains('{{i18n')
	}

	def "every option label, help text and template key exists in each bundle"() {
		given:
		List<String> keys = provider.optionTypes.collectMany { OptionType o -> [o.fieldCode, o.helpTextI18nCode] }
		String hbs = getClass().getResourceAsStream('/renderer/hbs/socketUsageReport.hbs').text
		keys += (hbs =~ /\{\{text\.([^}]+)\}\}/).collect { 'socket-usage-report.' + it[1] }
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
