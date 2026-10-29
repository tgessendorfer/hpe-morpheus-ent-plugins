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
		render(resp.data).contains('>Revenue</th>')
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

	def "a tenant without invoices is listed, but adds no zero total in the fallback currency"() {
		given: 'a master tenant without a currency, Tenant A with revenue in EUR, Tenant B without invoices'
		def env = tenantsEnv(null, [
			[id: 2L, name: 'Tenant A', users: 1, sites: 1, instances: 1, cores: 2, memory: 2147483648L, servers: 1],
			[id: 3L, name: 'Tenant B', users: 0, sites: 0, instances: 0, cores: 0, memory: 0L, servers: 0]],
			[(2L): [[cur: 'EUR', period: currentPeriod(), rev: 28.58G, cost: 23.22G]]])

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])
		String html = render(resp.data)

		then: 'Tenant B keeps its zero row in USD, the totals and cards show EUR only'
		resp.success
		resp.data.items*.name == ['Tenant A', 'Tenant B']
		resp.data.items*.currency == ['EUR', 'USD']
		resp.data.totals*.currency == ['EUR']
		html.contains('<strong>Total EUR</strong>')
		!html.contains('<strong>Total USD</strong>')
		!html.contains('(forecast, USD)')
	}

	def "with no amount in any currency the zero total stays"() {
		given:
		def env = tenantsEnv(null, [[id: 3L, name: 'Tenant B', users: 0, sites: 0, instances: 0, cores: 0, memory: 0L, servers: 0]], [:])

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		resp.success
		resp.data.totals*.currency == ['USD']
		resp.data.totals[0].revText == '0.00'
	}

	def "a failing connection release does not replace the page result"() {
		given:
		def env = loadEnv(Locale.US, 'en-US')

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		1 * env.report.releaseDatabaseConnection(_) >> { throw new IllegalStateException('pool closed') }
		noExceptionThrown()
		resp.success
		resp.data.totals[0].revText == '1,234.56'
	}

	def "deactivated sub-tenants are read with the page's invoice filter, in one grouped query"() {
		given:
		String q = MspTenantOverviewAnalyticsProvider.DEACTIVATED_SQL

		expect: 'one grouped query over the invoices of deactivated sub-tenants, the same filter as the page'
		q.contains(MspTenantOverviewAnalyticsProvider.INVOICE_FILTER)
		q.contains('AND period IN (?, ?)')
		q.contains('account_id IN (SELECT a.id FROM account a WHERE a.master_account = 0 AND a.active = 0)')
		q.endsWith('GROUP BY account_id, currency, period')
		!q.contains('?, ?, ?')

		and: 'asked first: whether any deactivated sub-tenant exists at all'
		MspTenantOverviewAnalyticsProvider.DEACTIVATED_EXISTS_SQL ==
			'SELECT 1 AS found FROM account a WHERE a.master_account = 0 AND a.active = 0 LIMIT 1'
	}

	def "without any deactivated sub-tenant the invoice query for them is not run"() {
		given:
		def env = loadEnv(Locale.US, 'en-US')

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		1 * env.sql.firstRow(MspTenantOverviewAnalyticsProvider.DEACTIVATED_EXISTS_SQL) >> null
		0 * env.sql.rows(MspTenantOverviewAnalyticsProvider.DEACTIVATED_SQL, _)
		resp.success
		resp.data.totals[0].revText == '1,234.56'
		resp.data.deactivated == []
	}

	def "deactivated sub-tenants with invoices get one line under the totals; the totals stay the same"() {
		given: 'Tenant A active with EUR revenue; two deactivated tenants with EUR invoices, one also in USD'
		List<Map> deactivated = [
			[id: 8L, cur: 'EUR', period: currentPeriod(), rev: 100.00G, cost: 70.00G],
			[id: 8L, cur: 'USD', period: currentPeriod(), rev: 10.00G, cost: 9.00G],
			[id: 9L, cur: 'EUR', period: previousPeriod(), rev: 23.45G, cost: 20.00G]]
		def plainEnv = loadEnv(Locale.US, 'en-US')
		def deactEnv = loadEnv(Locale.US, 'en-US', 1, [[cur: 'EUR', period: currentPeriod(), rev: 1234.56G, cost: 1000.00G]], deactivated)

		when:
		def plain = plainEnv.p.loadData(new User(id: 7L), [:])
		def resp = deactEnv.p.loadData(new User(id: 7L), [:])
		String html = render(resp.data)

		then: 'the query gets the two months the page shows; the tenant rows still come from the active sub-tenants only'
		1 * deactEnv.sql.rows(MspTenantOverviewAnalyticsProvider.DEACTIVATED_SQL, [currentPeriod(), previousPeriod()]) >> deactivated
		1 * deactEnv.sql.rows({ String q -> q.contains('WHERE a.master_account = 0 AND a.active = 1') }) >>
			[[id: 2L, name: 'Tenant A', users: 1, sites: 1, instances: 1, cores: 2, memory: 2147483648L, servers: 1]]

		and: 'tenants, cards and totals are those of the page without deactivated tenants'
		resp.success
		resp.data.items*.name == ['Tenant A']
		resp.data.count == 1
		resp.data.totals == plain.data.totals
		plain.data.deactivated == []

		and: 'one row per currency, the label on the first'
		resp.data.deactivated*.currency == ['EUR', 'USD']
		resp.data.deactivated*.first == [true, false]
		resp.data.deactivated[0].label == 'Not shown: 2 deactivated tenants'
		resp.data.deactivated[0].revText == '100.00'
		resp.data.deactivated[0].costText == '70.00'
		resp.data.deactivated[0].lrevText == '23.45'
		resp.data.deactivated[1].revText == '10.00'

		and: 'rendered under the totals, with the same columns'
		html.indexOf('<strong>Total EUR</strong>') < html.indexOf('Not shown: 2 deactivated tenants')
		html.contains('<tr class="finops-deactivated"><td colspan="7">Not shown: 2 deactivated tenants</td><td>EUR</td><td class="num">100.00</td><td class="num">70.00</td>')
		html.contains('<tr class="finops-deactivated"><td colspan="7"></td><td>USD</td><td class="num">10.00</td>')
		!render(plain.data).contains('<tr class="finops-deactivated">')
	}

	def "one deactivated tenant reads in the singular, in the viewer's language"() {
		given:
		List<Map> rows = [[id: 8L, cur: 'EUR', period: currentPeriod(), rev: 100.00G, cost: 70.00G]]

		expect:
		loadEnv(Locale.US, 'en-US', 1, [[cur: 'EUR', period: currentPeriod(), rev: 1G, cost: 1G]], rows).p
			.loadData(new User(id: 7L), [:]).data.deactivated[0].label == 'Not shown: 1 deactivated tenant'
		loadEnv(Locale.US, 'de-DE', 1, [[cur: 'EUR', period: currentPeriod(), rev: 1G, cost: 1G]], rows).p
			.loadData(new User(id: 7L), [:]).data.deactivated[0].label == 'Nicht gezeigt: 1 deaktivierter Tenant'
		provider(Locale.US).message('deactivated.other', '', Locale.GERMAN) == 'Nicht gezeigt: {0} deaktivierte Tenants'
	}

	def "deactivated tenants without any amount give no line"() {
		given:
		def env = loadEnv(Locale.US, 'en-US', 1, [[cur: 'EUR', period: currentPeriod(), rev: 1G, cost: 1G]],
			[[id: 8L, cur: 'EUR', period: currentPeriod(), rev: 0G, cost: 0G]])

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		resp.success
		resp.data.deactivated == []
		!render(resp.data).contains('<tr class="finops-deactivated">')
	}

	def "a failing query for deactivated tenants leaves the page as it is, without the line"() {
		given:
		def env = loadEnv(Locale.US, 'en-US')

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		1 * env.sql.firstRow(MspTenantOverviewAnalyticsProvider.DEACTIVATED_EXISTS_SQL) >> [found: 1]
		1 * env.sql.rows(MspTenantOverviewAnalyticsProvider.DEACTIVATED_SQL, _) >> { throw new java.sql.SQLException("Unknown column 'active'") }
		resp.success
		resp.data.totals[0].revText == '1,234.56'
		resp.data.items*.name == ['Tenant A']
		resp.data.deactivated == []
	}

	def "a failing check for deactivated sub-tenants leaves the page as it is, without the line"() {
		given:
		def env = loadEnv(Locale.US, 'en-US')

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])

		then:
		1 * env.sql.firstRow(MspTenantOverviewAnalyticsProvider.DEACTIVATED_EXISTS_SQL) >> { throw new java.sql.SQLException("Unknown column 'active'") }
		0 * env.sql.rows(MspTenantOverviewAnalyticsProvider.DEACTIVATED_SQL, _)
		resp.success
		resp.data.totals[0].revText == '1,234.56'
		resp.data.deactivated == []
	}

	def "the line is shown even when no active sub-tenant is left"() {
		given:
		def env = tenantsEnv('EUR', [], [:], [[id: 8L, cur: 'EUR', period: currentPeriod(), rev: 12.00G, cost: 10.00G]])

		when:
		def resp = env.p.loadData(new User(id: 7L), [:])
		String html = render(resp.data)

		then:
		resp.success
		resp.data.items == []
		resp.data.totals == []
		html.contains('No sub-tenants.')
		html.contains('<tr class="finops-deactivated"><td colspan="7">Not shown: 1 deactivated tenant</td><td>EUR</td><td class="num">12.00</td>')
	}

	def "a bundle of the same name found first on the class path is skipped for the plugin's own"() {
		given: 'a foreign i18n/messages.properties ahead of the plugin one, as a parent-first class loader would order them'
		File foreign = File.createTempFile('foreign-messages', '.properties')
		foreign.text = 'default.title=Other\n'
		URL own = MspTenantOverviewAnalyticsProvider.classLoader.getResource('i18n/messages.properties')

		when:
		Properties p = MspTenantOverviewAnalyticsProvider.ownBundle([foreign.toURI().toURL(), own])

		then:
		p.getProperty(MspTenantOverviewAnalyticsProvider.BUNDLE_MARKER_KEY) == 'MSP Tenant Overview'
		MspTenantOverviewAnalyticsProvider.ownBundle([foreign.toURI().toURL()]).isEmpty()

		cleanup:
		foreign?.delete()
	}

	def "every label key exists in both bundles and the template uses only provided labels"() {
		given:
		String hbs = resource('renderer/hbs/mspTenantOverview.hbs')
		Set<String> used = (hbs =~ /\{\{(?:\.\.\/)?t\.([A-Za-z_]+)\}\}/).collect { it[1] } as Set
		Set<String> keys = MspTenantOverviewAnalyticsProvider.LABEL_KEYS.collect { "msp-tenant-overview-analytics.${it}".toString() } as Set
		keys += ['msp-tenant-overview-analytics.error.masterOnly', 'msp-tenant-overview-analytics.error.loadFailed',
			'msp-tenant-overview-analytics.deactivated.one', 'msp-tenant-overview-analytics.deactivated.other']
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
		!(hbs =~ /#[0-9A-Fa-f]{3,6}\b/)
		hbs.contains('class="finops-msp-tenant-overview"')
	}

	def "cards and table bring their own layout, so they fit the 857 px analytics pane"() {
		given:
		String hbs = resource('renderer/hbs/mspTenantOverview.hbs')
		String css = hbs.find(/(?s)<style>(.*?)<\/style>/) { all, c -> c }

		expect: 'own class names, not the Morpheus stats classes a whitelabel stylesheet may restyle'
		!hbs.contains('stats-container')
		!hbs.contains('count-stats')
		css.contains('grid-template-columns: repeat(auto-fit, minmax(140px, 1fr))')
		css.contains('overflow-x: auto')
		css.contains('white-space: nowrap')

		and: 'every rule is scoped to the plugin root and sets no text color, so it follows the page in light and dark mode'
		css.readLines()*.trim().findAll { it && !it.startsWith('.finops-msp-tenant-overview ') }.isEmpty()
		!(css =~ /(^|[\s;{])color\s*:/)
		!css.contains('var(--')
	}

	def "the table groups the month columns and leaves out the separate margin % column"() {
		given:
		Map paid = MspTenantOverviewCalc.texts([rev: 100.00G, cost: 60.00G, lrev: 50.00G, lcost: 45.00G], Locale.ENGLISH)
		def p = provider(Locale.US)

		when:
		String html = render([count: 1, instances: 1, cores: 2, month: '2026-10', lastMonth: '2026-09', t: p.labels(Locale.ENGLISH),
			items: [[first: true, name: 'Tenant A', currency: 'EUR', users: 1, sites: 1, instances: 1, cores: 2, memoryGb: '2.0', servers: 1] + paid],
			totals: [[currency: 'EUR'] + paid]])

		then:
		html.contains('<th colspan="3" class="finops-group">2026-10 (forecast)</th><th colspan="2" class="finops-group">2026-09</th>')
		!html.contains('Margin %')
		html.findAll('<td').size() == 13 + 7
		html.contains('<td><strong>Tenant A</strong></td><td class="num">1</td>')
	}

	def "the template shows no '- %' for a tenant without invoices and keeps the percentage otherwise"() {
		given: 'one tenant without invoices, one with revenue in both months, and their totals'
		Map none = MspTenantOverviewCalc.texts(MspTenantOverviewCalc.zero(), Locale.ENGLISH)
		Map paid = MspTenantOverviewCalc.texts([rev: 100.00G, cost: 60.00G, lrev: 50.00G, lcost: 45.00G], Locale.ENGLISH)
		Map data = [count: 2, instances: 0, cores: 0, month: '2026-10', lastMonth: '2026-09', t: [forecast: 'forecast', vcpu: 'vCPU'],
			items: [[first: true, name: 'Empty', currency: 'USD'] + none, [first: true, name: 'Paying', currency: 'EUR'] + paid],
			totals: [[currency: 'EUR'] + paid, [currency: 'USD'] + none]]

		when:
		String html = render(data)

		then:
		!html.contains('- %')
		!html.contains('()')
		html.contains('(USD)')
		html.contains('<td class="num">0.00</td><td class="num">0.00</td></tr>')
		html.contains('(EUR, 40.0 %)')
		html.contains('<td class="num">40.00<span class="finops-sub">40.0 %</span></td>')
		html.contains('<td class="num">5.00<span class="finops-sub">10.0 %</span></td>')
		!html.contains('<td>-</td>')
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
		full.contains('>2026-10 (Prognose)</th>')
		full.contains('>2026-09</th>')
		full.contains('>Marge</th>')
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
			List<Map> invoiceRows = [[cur: 'EUR', period: currentPeriod(), rev: 1234.56G, cost: 1000.00G]], List<Map> deactivatedRows = []) {
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
		sql.firstRow(MspTenantOverviewAnalyticsProvider.DEACTIVATED_EXISTS_SQL) >> (deactivatedRows ? [found: 1] : null)
		// Declared before the generic invoice stub, which would match this query as well.
		sql.rows(MspTenantOverviewAnalyticsProvider.DEACTIVATED_SQL, _) >> deactivatedRows
		sql.rows({ it.contains('FROM account a') }) >> [[id: 2L, name: 'Tenant A', users: 1, sites: 1, instances: 1, cores: 2,
			memory: 2147483648L, servers: 1]]
		sql.rows({ it.contains('account_invoice') }, _) >> invoiceRows
		def p = Spy(MspTenantOverviewAnalyticsProvider, constructorArgs: [new MspTenantOverviewPlugin(), ctx]) {
			newSql(_) >> sql
		}
		[p: p, sql: sql, report: report]
	}

	/** Several tenants, each with its own invoice rows (by tenant id), for an English user. */
	private Map tenantsEnv(String masterCurrency, List<Map> tenants, Map<Long, List<Map>> invoicesByTenant, List<Map> deactivatedRows = []) {
		MorpheusWebRequestService web = Mock() { getLocale() >> Locale.US }
		Connection conn = Mock()
		MorpheusReportService report = Mock() {
			getReadOnlyDatabaseConnection() >> Single.just(conn)
			releaseDatabaseConnection(_) >> Completable.complete()
		}
		MorpheusContext ctx = Mock() { getWebRequest() >> web; getReport() >> report }
		Sql sql = Mock()
		sql.firstRow(MspTenantOverviewAnalyticsProvider.USER_LOCALE_SQL, [7L]) >> [locale: 'en-US']
		sql.firstRow({ it.contains('master_account') }, [7L]) >> [is_master: 1, currency: masterCurrency]
		sql.firstRow(MspTenantOverviewAnalyticsProvider.DEACTIVATED_EXISTS_SQL) >> (deactivatedRows ? [found: 1] : null)
		sql.rows(MspTenantOverviewAnalyticsProvider.DEACTIVATED_SQL, _) >> deactivatedRows
		sql.rows({ it.contains('FROM account a') }) >> tenants
		sql.rows({ it.contains('account_invoice') }, _) >> { String q, List params -> invoicesByTenant[params[0] as Long] ?: [] }
		def p = Spy(MspTenantOverviewAnalyticsProvider, constructorArgs: [new MspTenantOverviewPlugin(), ctx]) {
			newSql(_) >> sql
		}
		[p: p, sql: sql, report: report]
	}

	private static String currentPeriod() {
		MspTenantOverviewCalc.months(java.time.YearMonth.now()).cur
	}

	private static String previousPeriod() {
		MspTenantOverviewCalc.months(java.time.YearMonth.now()).last
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
