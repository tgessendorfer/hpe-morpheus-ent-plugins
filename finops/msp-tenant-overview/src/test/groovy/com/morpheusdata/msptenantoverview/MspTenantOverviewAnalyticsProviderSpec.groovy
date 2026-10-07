package com.morpheusdata.msptenantoverview

import com.github.jknack.handlebars.Handlebars
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.core.web.MorpheusWebRequestService
import com.morpheusdata.model.User
import groovy.sql.Sql
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

import java.sql.Connection

class MspTenantOverviewAnalyticsProviderSpec extends Specification {

	def "the provider is a master-tenant-only cost page with the planned code"() {
		given:
		def p = new MspTenantOverviewAnalyticsProvider(new MspTenantOverviewPlugin(), null)

		expect:
		p.code == 'msp-tenant-overview-analytics'
		p.masterTenantOnly
		!p.subTenantOnly
		p.category == 'cost'
		p.name == 'MSP Tenant Overview'
	}

	def "without a web request and without a setting the locale is English and messages are English"() {
		given:
		def p = new MspTenantOverviewAnalyticsProvider(new MspTenantOverviewPlugin(), null)

		expect:
		p.viewerLocale() == Locale.ENGLISH
		p.viewerLocale(null) == Locale.ENGLISH
		p.message('error.masterOnly', 'fallback', Locale.ENGLISH) == 'Only the provider (master) tenant can open this page.'
	}

	def "the user's Morpheus setting wins over the browser language"() {
		given: 'a German browser'
		def p = provider(Locale.GERMANY)

		expect:
		p.viewerLocale(setting) == expected
		p.labels(p.viewerLocale(setting)).revenue == revenue

		where:
		setting || expected            | revenue
		'en-US' || Locale.US           | 'Revenue'
		'en_US' || Locale.US           | 'Revenue'
		'en'    || Locale.ENGLISH      | 'Revenue'
		'de-DE' || Locale.GERMANY      | 'Umsatz'
		'de'    || Locale.GERMAN       | 'Umsatz'
	}

	def "a German setting gives German with an English browser"() {
		given:
		def p = provider(Locale.US)

		expect:
		p.viewerLocale('de-DE') == Locale.GERMANY
		p.labels(p.viewerLocale('de-DE')).col_currency == 'W\u00e4hrung'
	}

	def "an empty or unusable setting falls back to the browser language"() {
		given:
		def p = provider(Locale.GERMANY)

		expect:
		p.viewerLocale(setting) == Locale.GERMANY

		where:
		setting << [null, '', '   ', 'garbage', '!!', '12-34', 'xx-YY']
	}

	def "without a request an unusable setting gives English"() {
		given:
		def p = new MspTenantOverviewAnalyticsProvider(new MspTenantOverviewPlugin(), null)

		expect:
		p.viewerLocale('garbage') == Locale.ENGLISH
		p.viewerLocale('de') == Locale.GERMAN
	}

	def "messages exist in English and German only; other languages get English"() {
		given:
		def p = provider(Locale.GERMANY)

		expect:
		MspTenantOverviewAnalyticsProvider.messageLocale(Locale.FRANCE) == Locale.ENGLISH
		MspTenantOverviewAnalyticsProvider.messageLocale(new Locale('de', 'AT')) == Locale.GERMAN
		p.labels(Locale.FRANCE).revenue == 'Revenue'
		p.labels(Locale.FRANCE).keySet() == p.labels(Locale.GERMAN).keySet()
	}

	def "the setting is read with a parameterised query and the user id as its parameter"() {
		given:
		def p = provider(Locale.GERMANY)
		Sql sql = Mock()

		when:
		def setting = p.userLocaleSetting(sql, 42L)

		then:
		1 * sql.firstRow('SELECT locale FROM user WHERE id = ?', [42L]) >> [locale: 'en-US']
		0 * sql._
		setting == 'en-US'
		!MspTenantOverviewAnalyticsProvider.USER_LOCALE_SQL.contains('42')
	}

	def "a failing setting lookup is quiet and falls back to the browser"() {
		given:
		def p = provider(Locale.GERMANY)
		Sql sql = Mock() { firstRow(*_) >> { throw new java.sql.SQLException("Unknown column 'locale'") } }

		expect:
		p.userLocaleSetting(sql, 42L) == null
		p.viewerLocale(p.userLocaleSetting(sql, 42L)) == Locale.GERMANY
		p.userLocaleSetting(null, 42L) == null
		p.userLocaleSetting(sql, null) == null
	}

	def "a failing web request service does not break the page"() {
		given:
		MorpheusWebRequestService web = Mock()
		MorpheusContext ctx = Mock() { getWebRequest() >> web }
		web.getLocale() >> { throw new IllegalStateException('no request') }
		def p = new MspTenantOverviewAnalyticsProvider(new MspTenantOverviewPlugin(), ctx)

		expect:
		p.viewerLocale() == Locale.ENGLISH
		p.message('no.such.key', 'fallback', Locale.ENGLISH) == 'fallback'
	}

	def "server invoices count only for servers that belong to no instance"() {
		given:
		String f = MspTenantOverviewAnalyticsProvider.INVOICE_FILTER

		expect: 'an instance VM has an instance invoice and a server invoice without instance_id; the container row links them'
		f.contains("ref_type = 'Instance'")
		f.contains("ref_type = 'ComputeServer' AND instance_id IS NULL")
		f.contains('NOT EXISTS (SELECT 1 FROM container ct WHERE ct.server_id = account_invoice.ref_id AND ct.instance_id IS NOT NULL)')
	}

	def "loadData leaves out currencies whose amounts are all zero, in the rows and in the totals"() {
		given: 'EUR revenue plus zero-priced server invoices in USD'
		def env = loadEnv(Locale.US, 'en-US', 1, [
			[cur: 'EUR', period: currentPeriod(), rev: 28.58G, cost: 23.22G],
			[cur: 'USD', period: currentPeriod(), rev: 0G, cost: 0G]])

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		resp.success
		resp.data.items*.currency == ['EUR']
		resp.data.totals*.currency == ['EUR']
		resp.data.totals[0].revText == '28.58'
	}

	def "loadData renders numbers and texts in the user's setting, not the browser's"() {
		given: 'a German browser, a user whose Morpheus setting is en-US, one tenant with revenue'
		def env = loadEnv(Locale.GERMANY, 'en-US')

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		resp.success
		resp.data.t.revenue == 'Revenue'
		resp.data.totals[0].revText == '1,234.56'
		resp.data.items[0].memoryGb == '2.0'
		render(resp.data).contains('<th>Revenue ')
		!render(resp.data).contains('Umsatz')
	}

	def "loadData uses German with a German setting even for an English browser"() {
		given:
		def env = loadEnv(Locale.US, 'de-DE')

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		resp.success
		resp.data.t.revenue == 'Umsatz'
		resp.data.totals[0].revText == '1.234,56'
		render(resp.data).contains('Summe EUR')
	}

	def "loadData follows the browser when the setting is empty, and the master check uses it too"() {
		given:
		def env = loadEnv(Locale.GERMANY, '', 0)

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		!resp.success
		resp.errors.error == 'Nur der Provider-Tenant (Master) kann diese Seite \u00f6ffnen.'
	}

	def "every label key exists in both bundles and the template uses only provided labels"() {
		given:
		String hbs = resource('renderer/hbs/mspTenantOverview.hbs')
		Set<String> used = (hbs =~ /\{\{(?:\.\.\/)?t\.([A-Za-z_]+)\}\}/).collect { it[1] } as Set
		Set<String> keys = MspTenantOverviewAnalyticsProvider.LABEL_KEYS.collect { "msp-tenant-overview-analytics.${it}".toString() } as Set
		keys += ['msp-tenant-overview-analytics.error.masterOnly', 'msp-tenant-overview-analytics.error.loadFailed']
		Properties en = bundle('i18n/messages.properties')
		Properties de = bundle('i18n/messages_de.properties')

		expect:
		!hbs.contains('{{i18n')
		used.size() > 10
		(used - MspTenantOverviewAnalyticsProvider.LABEL_KEYS.collect { it.replace('.', '_') }).isEmpty()
		(keys - en.stringPropertyNames()).isEmpty()
		(keys - de.stringPropertyNames()).isEmpty()
	}

	def "the template carries no hard-coded colors or vendor-specific CSS classes"() {
		given:
		String hbs = resource('renderer/hbs/mspTenantOverview.hbs')

		expect:
		!(hbs =~ /#[0-9A-Fa-f]{6}\b/)
		hbs.contains('class="finops-msp-tenant-overview"')
	}

	def "the template shows no '- %' for a tenant without invoices and keeps the percentage otherwise"() {
		given: 'one tenant without invoices, one with revenue in both months, and their totals'
		Map none = MspTenantOverviewCalc.texts(MspTenantOverviewCalc.zero(), Locale.ENGLISH)
		Map paid = MspTenantOverviewCalc.texts([rev: 100.00G, cost: 60.00G, lrev: 50.00G, lcost: 45.00G], Locale.ENGLISH)
		Map data = [count: 2, instances: 0, cores: 0, month: '2026-10', lastMonth: '2026-09', t: [:],
			items: [[first: true, name: 'Empty', currency: 'USD'] + none, [first: true, name: 'Paying', currency: 'EUR'] + paid],
			totals: [[currency: 'EUR'] + paid, [currency: 'USD'] + none]]

		when:
		String html = render(data)

		then:
		!html.contains('- %')
		!html.contains('()')
		html.contains('(USD)')
		html.contains('<td>0.00</td></tr>')
		html.contains('(EUR, 40.0 %)')
		html.contains('<td>5.00 (10.0 %)</td>')
		html.contains('<td>-</td>')
	}

	def "labels inside loops, the empty-list branch and the footer come from the provided texts"() {
		given:
		def p = provider(Locale.US)
		Map paid = MspTenantOverviewCalc.texts([rev: 100.00G, cost: 60.00G, lrev: 50.00G, lcost: 45.00G], Locale.GERMAN)

		when:
		String full = render([count: 1, instances: 0, cores: 0, month: '2026-10', lastMonth: '2026-09', t: p.labels(Locale.GERMAN),
			items: [[first: true, name: 'Tenant A', currency: 'EUR'] + paid], totals: [[currency: 'EUR'] + paid]])
		String empty = render([count: 0, instances: 0, cores: 0, month: '2026-10', lastMonth: '2026-09', t: p.labels(Locale.GERMAN),
			items: [], totals: []])

		then:
		full.contains('Umsatz 2026-10 (Prognose, EUR)')
		full.contains('Marge 2026-09')
		full.contains('<strong>Summe EUR</strong>')
		full.contains('MSP-Tenant-\u00dcbersicht 2026-10')
		empty.contains('Keine Sub-Tenants.')
	}

	private MspTenantOverviewAnalyticsProvider provider(Locale browser) {
		MorpheusWebRequestService web = Mock() { getLocale() >> browser }
		MorpheusContext ctx = Mock() { getWebRequest() >> web }
		new MspTenantOverviewAnalyticsProvider(new MspTenantOverviewPlugin(), ctx)
	}

	private Map loadEnv(Locale browser, String setting, int isMaster = 1,
			List<Map> invoiceRows = [[cur: 'EUR', period: currentPeriod(), rev: 1234.56G, cost: 1000.00G]]) {
		MorpheusWebRequestService web = Mock() { getLocale() >> browser }
		Connection conn = Mock()
		MorpheusReportService report = Mock() {
			getReadOnlyDatabaseConnection() >> Single.just(conn)
			releaseDatabaseConnection(_) >> Completable.complete()
		}
		MorpheusContext ctx = Mock() { getWebRequest() >> web; getReport() >> report }
		Sql sql = Mock()
		sql.firstRow(MspTenantOverviewAnalyticsProvider.USER_LOCALE_SQL, [7L]) >> [locale: setting]
		sql.firstRow({ it.contains('master_account') }, [7L]) >> [is_master: isMaster, currency: 'EUR']
		sql.rows({ it.contains('FROM account a') }) >> [[id: 2L, name: 'Tenant A', users: 1, sites: 1, instances: 1, cores: 2,
			memory: 2147483648L, servers: 1]]
		sql.rows({ it.contains('account_invoice') }, _) >> invoiceRows
		def p = Spy(MspTenantOverviewAnalyticsProvider, constructorArgs: [new MspTenantOverviewPlugin(), ctx]) {
			newSql(_) >> sql
		}
		[p: p, sql: sql]
	}

	private static String currentPeriod() {
		MspTenantOverviewCalc.months(java.time.YearMonth.now()).cur
	}

	private static String render(Map data) {
		new Handlebars().compileInline(resource('renderer/hbs/mspTenantOverview.hbs')).apply(data)
	}

	private static String resource(String path) {
		InputStream is = MspTenantOverviewAnalyticsProviderSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { it.getText('UTF-8') }
	}

	private static Properties bundle(String path) {
		Properties p = new Properties()
		InputStream is = MspTenantOverviewAnalyticsProviderSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		p
	}
}
