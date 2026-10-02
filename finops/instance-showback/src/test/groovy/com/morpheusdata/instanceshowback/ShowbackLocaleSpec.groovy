package com.morpheusdata.instanceshowback

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.web.MorpheusWebRequestService
import com.morpheusdata.model.Instance
import com.morpheusdata.model.User
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import spock.lang.Specification
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Unroll

import java.sql.Connection

/**
 * Content language: the viewing user's Morpheus setting wins over the browser language,
 * the browser language over English, and the template is rendered in the resolved locale.
 */
class ShowbackLocaleSpec extends Specification {

	static final Locale BROWSER_DE = Locale.forLanguageTag('de-DE')
	static final Locale BROWSER_EN = Locale.forLanguageTag('en-US')

	static Calendar cal(int y, int m, int d) {
		Calendar c = Calendar.getInstance(TimeZone.getTimeZone('UTC'), Locale.ROOT)
		c.clear()
		c.set(y, m - 1, d, 12, 0, 0)
		c
	}

	@Unroll
	def "setting #setting with browser #browser -> #expected"() {
		expect:
		ShowbackLocale.resolve(setting, browser) == expected

		where:
		setting   | browser    || expected
		'en-US'   | BROWSER_DE || Locale.forLanguageTag('en-US')
		'en_US'   | BROWSER_DE || Locale.forLanguageTag('en-US')
		'en'      | BROWSER_DE || Locale.ENGLISH
		'de'      | BROWSER_EN || Locale.GERMAN
		'de-DE'   | BROWSER_EN || BROWSER_DE
		' de_DE ' | BROWSER_EN || BROWSER_DE
		null      | BROWSER_DE || BROWSER_DE
		''        | BROWSER_DE || BROWSER_DE
		'   '     | BROWSER_DE || BROWSER_DE
		'garbage' | BROWSER_DE || BROWSER_DE
		'!!'      | BROWSER_DE || BROWSER_DE
		'xx-YY'   | BROWSER_DE || BROWSER_DE
		null      | null       || Locale.ENGLISH
		'garbage' | null       || Locale.ENGLISH
		'de'      | null       || Locale.GERMAN
	}

	def "an en-US setting gives English messages even when the browser sends German"() {
		when:
		Locale l = ShowbackLocale.resolve('en-US', BROWSER_DE)

		then:
		ShowbackLocale.messages(l).monthToDate == 'Month to date'
		ShowbackLocale.text('loadError', 'fallback', l) == 'Cost data could not be loaded. The appliance log has the details.'
	}

	def "a de setting gives German messages even when the browser sends English"() {
		when:
		Locale l = ShowbackLocale.resolve('de', BROWSER_EN)

		then:
		ShowbackLocale.messages(l).monthToDate == 'Bisher in diesem Monat'
		ShowbackLocale.messages(l).currency == 'Währung'
	}

	@Unroll
	def "messages exist only in English and German: #tag -> #expected"() {
		expect:
		ShowbackLocale.messages(Locale.forLanguageTag(tag)).history == expected

		where:
		tag     || expected
		'fr-FR' || 'History'
		'es'    || 'History'
		'de-AT' || 'Verlauf'
		'en-GB' || 'History'
	}

	def "both languages have every message, keyed without the provider prefix"() {
		given:
		Map<String, String> en = ShowbackLocale.messages(Locale.ENGLISH)
		Map<String, String> de = ShowbackLocale.messages(Locale.GERMAN)

		expect:
		en.size() == 22
		en.keySet() == de.keySet()
		en.keySet().every { !it.contains('.') }
		ShowbackLocale.text('noSuchKey', 'default text', Locale.GERMAN) == 'default text'
	}

	def "the locale setting is read with a parameterised query"() {
		expect:
		ShowbackLocale.USER_LOCALE_SQL == 'SELECT locale FROM user WHERE id = ?'
	}

	def "forUser reads the setting of the given user by parameter and prefers it over the browser"() {
		given:
		Sql sql = Mock()

		when:
		Locale l = ShowbackLocale.forUser(sql, 42L, BROWSER_DE)

		then:
		1 * sql.firstRow('SELECT locale FROM user WHERE id = ?', [42L]) >> new GroovyRowResult([locale: 'en-US'])
		0 * sql._
		l == BROWSER_EN
	}

	def "forUser falls back to the browser when the setting is empty"() {
		given:
		Sql sql = Mock()

		when:
		Locale l = ShowbackLocale.forUser(sql, 42L, BROWSER_DE)

		then:
		1 * sql.firstRow(_ as String, [42L]) >> new GroovyRowResult([locale: null])
		l == BROWSER_DE
	}

	def "forUser falls back quietly when the user row is missing or the query fails"() {
		given:
		Sql sql = Mock()

		when:
		Locale missing = ShowbackLocale.forUser(sql, 42L, BROWSER_DE)
		Locale failed = ShowbackLocale.forUser(sql, 43L, BROWSER_DE)
		Locale none = ShowbackLocale.forUser(sql, 44L, null)

		then:
		1 * sql.firstRow(_ as String, [42L]) >> null
		1 * sql.firstRow(_ as String, [43L]) >> { throw new java.sql.SQLException('table user is gone') }
		1 * sql.firstRow(_ as String, [44L]) >> { throw new java.sql.SQLException('table user is gone') }
		noExceptionThrown()
		missing == BROWSER_DE
		failed == BROWSER_DE
		none == Locale.ENGLISH
	}

	def "without a user there is no query and the browser language counts"() {
		given:
		Sql sql = Mock()

		when:
		Locale l = ShowbackLocale.forUser(sql, null, BROWSER_DE)

		then:
		0 * sql._
		l == BROWSER_DE
	}

	def "show() hands the viewing user's id to the next render on the same thread, once"() {
		given:
		InstanceShowbackTabProvider p = new InstanceShowbackTabProvider(null, null)
		User u = new User()
		u.id = 7L

		when:
		boolean visible = p.show(null, u, null)

		then:
		visible
		p.takeViewerId() == 7L
		p.takeViewerId() == null

		when: 'a later show() without a user replaces the id'
		p.show(null, u, null)
		p.show(null, null, null)

		then:
		p.takeViewerId() == null
	}

	@Unroll
	def "the tab renders in the resolved locale: setting #setting, browser #browser"() {
		given:
		Locale l = ShowbackLocale.resolve(setting, browser)
		List<Map> rows = [[period: '202610', currency: 'EUR', price: 1234.565G, running: 617.2G, compute: 500G, storage: 100G, license: 17.2G, plan: 'Small']]
		Map model = ShowbackCalculator.buildModel(rows, ['202610', '202609'], null, l, cal(2026, 10, 15))
		model.instanceName = 'web-01'
		ViewModel<Map> view = new ViewModel<>()
		view.object = model

		when:
		String html = new HandlebarsRenderer('renderer', ShowbackLocaleSpec.classLoader).renderTemplate('hbs/instanceShowback', view).html

		then:
		html.contains(heading)
		html.contains(amount)
		!html.contains(other)

		where:
		setting | browser    || heading                           | amount     | other
		'en-US' | BROWSER_DE || 'Month to date (day 15/31)'       | '1,234.57' | 'Bisher'
		'de'    | BROWSER_EN || 'Bisher in diesem Monat (Tag 15/31)' | '1.234,57' | 'Month to date'
		null    | BROWSER_DE || 'Bisher in diesem Monat (Tag 15/31)' | '1.234,57' | 'Month to date'
		null    | null       || 'Month to date (day 15/31)'       | '1,234.57' | 'Bisher'
	}

	/**
	 * A tab provider whose report connection, web request and database are stand-ins: the
	 * browser sends browserLocale, the user table answers setting for user 7, and the
	 * current month has one EUR invoice of 1234.565.
	 */
	Map providerWith(String setting, Locale browserLocale) {
		Connection c = Stub()
		MorpheusReportService report = Mock()
		MorpheusWebRequestService web = Stub() { getLocale() >> browserLocale }
		MorpheusContext ctx = Stub() {
			getReport() >> report
			getWebRequest() >> web
		}
		Plugin plugin = Stub() { getClassLoader() >> ShowbackLocaleSpec.classLoader }
		Sql sql = Mock()
		InstanceShowbackTabProvider p = new InstanceShowbackTabProvider(plugin, ctx) {
			@Override protected Sql openSql(Connection conn) { conn.is(c) ? sql : null }
		}
		String current = ShowbackCalculator.lastPeriods(1, Calendar.instance)[0]
		Map row = [period: current, currency: 'EUR', price: 1234.565G, running: 617.2G, compute: 500G, storage: 100G, license: 17.2G, plan: 'Small']
		[provider: p, report: report, sql: sql, connection: c, row: row, setting: setting]
	}

	@Unroll
	def "renderTemplate() reads the setting of the user from show() and renders in it: setting #setting, browser #browser"() {
		given:
		Map t = providerWith(setting, browser)
		InstanceShowbackTabProvider p = t.provider
		Instance instance = new Instance()
		instance.id = 11L
		instance.name = 'web-01'
		User u = new User()
		u.id = 7L

		when:
		p.show(instance, u, null)
		String html = p.renderTemplate(instance).html

		then:
		1 * t.report.getReadOnlyDatabaseConnection() >> Single.just(t.connection)
		1 * t.sql.firstRow(ShowbackLocale.USER_LOCALE_SQL, [7L]) >> new GroovyRowResult([locale: setting])
		1 * t.sql.rows({ it.contains('ref_id = ?') }, { it[0] == 11L }) >> [new GroovyRowResult(t.row)]
		1 * t.sql.firstRow({ it.contains('master_account') }) >> null
		1 * t.report.releaseDatabaseConnection(t.connection) >> Completable.complete()
		html.contains(heading)
		html.contains(amount)
		!html.contains(other)

		where:
		setting | browser    || heading                  | amount     | other
		'en-US' | BROWSER_DE || 'Month to date'          | '1,234.57' | 'Bisher'
		'de'    | BROWSER_EN || 'Bisher in diesem Monat' | '1.234,57' | 'Month to date'
		''      | BROWSER_DE || 'Bisher in diesem Monat' | '1.234,57' | 'Month to date'
	}

	def "renderTemplate() without a preceding show() makes no user query and uses the browser language"() {
		given:
		Map t = providerWith('en-US', BROWSER_DE)
		InstanceShowbackTabProvider p = t.provider
		Instance instance = new Instance()
		instance.id = 11L

		when:
		String html = p.renderTemplate(instance).html

		then:
		1 * t.report.getReadOnlyDatabaseConnection() >> Single.just(t.connection)
		0 * t.sql.firstRow(ShowbackLocale.USER_LOCALE_SQL, _)
		1 * t.sql.rows(_ as String, _ as List) >> [new GroovyRowResult(t.row)]
		1 * t.sql.firstRow({ it.contains('master_account') }) >> null
		1 * t.report.releaseDatabaseConnection(t.connection) >> Completable.complete()
		html.contains('Bisher in diesem Monat')
		html.contains('1.234,57')
	}
}
