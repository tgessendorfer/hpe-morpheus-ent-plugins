package com.morpheusdata.budgetburn

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.core.web.MorpheusWebRequestService
import com.morpheusdata.model.User
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

import java.sql.Connection

/**
 * Texts and number formats follow the viewer's language setting in Morpheus (user.locale),
 * then the browser language of the web request, then English.
 */
class BudgetBurnLocaleSpec extends Specification {

	static final Locale BROWSER_DE = Locale.forLanguageTag('de-DE')

	@Unroll
	def "language setting '#setting' parses to #expected"() {
		expect:
		BudgetBurnAnalyticsProvider.parseLocaleSetting(setting) == (expected ? Locale.forLanguageTag(expected) : null)

		where:
		setting      | expected
		'en-US'      | 'en-US'
		'en_US'      | 'en-US'
		'de'         | 'de'
		' de_DE '    | 'de-DE'
		null         | null
		''           | null
		'   '        | null
		'garbage'    | null
		'!!'         | null
		'zz-ZZ'      | null
	}

	@Unroll
	def "setting '#setting' with a German browser gives #language texts"() {
		given:
		Sql sql = Mock(Sql)
		BudgetBurnAnalyticsProvider provider = new BudgetBurnAnalyticsProvider(null, context(BROWSER_DE))

		when:
		Locale locale = provider.viewerLocale(sql, 7L)

		then:
		1 * sql.firstRow(BudgetBurnAnalyticsProvider.USER_LOCALE_SELECT, [7L]) >> new GroovyRowResult([locale: setting])
		locale == Locale.forLanguageTag(expected)
		BudgetBurnAnalyticsProvider.bundleLanguage(locale) == language

		where:
		setting   | expected | language
		'en-US'   | 'en-US'  | 'en'
		'de'      | 'de'     | 'de'
		'de_AT'   | 'de-AT'  | 'de'
		null      | 'de-DE'  | 'de'
		''        | 'de-DE'  | 'de'
		'garbage' | 'de-DE'  | 'de'
	}

	def "a user without a row falls back to the browser language"() {
		given:
		Sql sql = Mock(Sql)
		BudgetBurnAnalyticsProvider provider = new BudgetBurnAnalyticsProvider(null, context(BROWSER_DE))

		when:
		Locale locale = provider.viewerLocale(sql, 7L)

		then:
		1 * sql.firstRow(BudgetBurnAnalyticsProvider.USER_LOCALE_SELECT, [7L]) >> null
		locale == BROWSER_DE
	}

	def "a failed lookup falls back to the browser language and throws nothing"() {
		given:
		Sql sql = Mock(Sql)
		BudgetBurnAnalyticsProvider provider = new BudgetBurnAnalyticsProvider(null, context(BROWSER_DE))

		when:
		Locale locale = provider.viewerLocale(sql, 7L)

		then:
		1 * sql.firstRow(BudgetBurnAnalyticsProvider.USER_LOCALE_SELECT, [7L]) >> { throw new IllegalStateException("Unknown column 'locale'") }
		noExceptionThrown()
		locale == BROWSER_DE
	}

	def "without a web request and without a setting the locale is English"() {
		given:
		MorpheusContext morpheus = Mock(MorpheusContext) {
			getWebRequest() >> { throw new IllegalStateException('No thread-bound request found') }
		}
		BudgetBurnAnalyticsProvider provider = new BudgetBurnAnalyticsProvider(null, morpheus)

		expect:
		provider.viewerLocale(null, null) == Locale.ENGLISH
		new BudgetBurnAnalyticsProvider(null, Mock(MorpheusContext)).viewerLocale(null, 7L) == Locale.ENGLISH
	}

	def "the language query is parameterised and selects only the setting"() {
		expect:
		BudgetBurnAnalyticsProvider.USER_LOCALE_SELECT == 'SELECT locale FROM user WHERE id = ?'
	}

	def "texts come from the bundle of the language, other languages get English"() {
		expect:
		BudgetBurnAnalyticsProvider.texts(Locale.forLanguageTag('en-US')).heading == 'Budget burn'
		BudgetBurnAnalyticsProvider.texts(Locale.forLanguageTag('de-CH')).heading == 'Budgetverbrauch'
		BudgetBurnAnalyticsProvider.texts(Locale.FRENCH).heading == 'Budget burn'
		BudgetBurnAnalyticsProvider.texts(Locale.GERMAN).col.monthlyBudget == load('i18n/messages_de.properties').getProperty('budget-burn-analytics.col.monthlyBudget')
		BudgetBurnAnalyticsProvider.texts(Locale.GERMAN).status.over == 'Über Budget'
		BudgetBurnAnalyticsProvider.text('budget-burn-analytics.scope.group', 'x', Locale.GERMAN) == load('i18n/messages_de.properties').getProperty('budget-burn-analytics.scope.group')
		BudgetBurnAnalyticsProvider.text('no.such.key', 'fallback', Locale.GERMAN) == 'fallback'
	}

	def "every bundle key ends up in the text tree"() {
		given:
		Properties en = load('i18n/messages.properties')
		Map texts = BudgetBurnAnalyticsProvider.texts(Locale.ENGLISH)

		expect:
		en.stringPropertyNames().every { key ->
			def v = texts
			(key - 'budget-burn-analytics.').tokenize('.').each { v = v[it] }
			v == en.getProperty(key)
		}
	}

	@Unroll
	def "page data with setting '#setting' and a German browser is in #language"() {
		given:
		Sql sql = Mock(Sql)
		BudgetBurnAnalyticsProvider provider = provider(sql, BROWSER_DE)
		stubPage(sql)

		when:
		def response = provider.loadData(new User(id: 7L), [:])

		then:
		1 * sql.firstRow(BudgetBurnAnalyticsProvider.USER_LOCALE_SELECT, [7L]) >> new GroovyRowResult([locale: setting])
		response.success
		response.data.language == language
		response.data.text.heading == heading
		response.data.items[0].budgetText == budget
		response.data.items[0].scopeText == scope

		where:
		setting | language | heading           | budget     | scope
		'en-US' | 'en'     | 'Budget burn'     | '1,234.50' | 'Own tenant'
		'de-DE' | 'de'     | 'Budgetverbrauch' | '1.234,50' | load('i18n/messages_de.properties').getProperty('budget-burn-analytics.scope.account')
		null    | 'de'     | 'Budgetverbrauch' | '1.234,50' | load('i18n/messages_de.properties').getProperty('budget-burn-analytics.scope.account')
	}

	def "the error message follows the language setting too"() {
		given:
		Sql sql = Mock(Sql)
		BudgetBurnAnalyticsProvider provider = provider(sql, BROWSER_DE)

		when:
		def response = provider.loadData(new User(id: 7L), [:])

		then:
		1 * sql.firstRow(BudgetBurnAnalyticsProvider.USER_LOCALE_SELECT, [7L]) >> new GroovyRowResult([locale: 'en-US'])
		sql.firstRow(_ as String, _ as List) >> { throw new IllegalStateException('table gone') }
		!response.success
		response.errors.error == 'Budget data could not be loaded. See the appliance log for details.'
	}

	@Unroll
	def "the rendered page is in #language for setting '#setting', whatever the browser sends"() {
		given:
		Sql sql = Mock(Sql)
		BudgetBurnAnalyticsProvider provider = provider(sql, BROWSER_DE)
		sql.firstRow(BudgetBurnAnalyticsProvider.USER_LOCALE_SELECT, [7L]) >> new GroovyRowResult([locale: setting])
		stubPage(sql)
		Map data = provider.loadData(new User(id: 7L), [:]).data
		ViewModel<Map> model = new ViewModel<>()
		model.object = data

		when:
		String html = new HandlebarsRenderer('renderer', BudgetBurnLocaleSpec.classLoader).renderTemplate('hbs/budgetBurn', model).html

		then:
		html.contains("lang=\"${language}\"")
		html.contains(heading)
		html.contains(column)
		html.contains(budget)
		!html.contains(other)

		where:
		setting | language | heading           | column           | budget     | other
		'en-US' | 'en'     | 'Budget burn'     | 'Monthly budget' | '1,234.50' | 'Budgetverbrauch'
		'de'    | 'de'     | 'Budgetverbrauch' | load('i18n/messages_de.properties').getProperty('budget-burn-analytics.col.monthlyBudget') | '1.234,50' | 'Budget burn'
	}

	def "owner and scope sit under the budget name, amounts carry the currency once and stay on one line"() {
		given:
		Map text = BudgetBurnAnalyticsProvider.texts(Locale.ENGLISH)
		Map row = [name: 'Budget Contoso 2026', owner: 'Nordwind Cloud Services Ltd.', scopeText: 'Tenant', target: 'Contoso Ltd.',
			currency: 'EUR', budgetText: '500.00', runningText: '2.75', usedPct: '0.6 %', burnText: '0.39', forecastText: '29.48',
			forecastPct: '5.9 %', bar: 5.9, color: '#27AE60', statusText: text.status.ok,
			ytdText: '35.64', ytdBudgetText: '5,000.00', ytdPct: '0.7 %']
		HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', BudgetBurnLocaleSpec.classLoader)

		when:
		String master = renderer.renderTemplate('hbs/budgetBurn', new ViewModel<Map>(object: [text: text, language: 'en', master: true, items: [row]])).html
		String tenant = renderer.renderTemplate('hbs/budgetBurn', new ViewModel<Map>(object: [text: text, language: 'en', master: false, tenant: 'Contoso Ltd.', items: [row]])).html

		then: 'no owner, scope or currency column; the budget cell carries owner and scope'
		master.findAll(/<th[ >]/).size() == 8
		master.contains('<span class="finops-sub">Owner: Nordwind Cloud Services Ltd. &middot; Tenant: Contoso Ltd.</span>')
		tenant.contains('<span class="finops-sub">Tenant: Contoso Ltd.</span>')
		!tenant.contains('Owner:')

		and: 'the currency once with the monthly budget, percentages under the amounts'
		master.contains('<td class="num">500.00 EUR</td>')
		master.contains('<td class="num">2.75<span class="finops-sub">0.6 %</span></td>')
		master.contains('<td class="num">35.64 / 5,000.00<span class="finops-sub">0.7 %</span></td>')
		master.contains('style="color:#27AE60"')
	}

	def "the template keeps its CSS scoped and sets no text color, so it follows the page in light and dark mode"() {
		given:
		String hbs = BudgetBurnLocaleSpec.classLoader.getResourceAsStream('renderer/hbs/budgetBurn.hbs').text
		String css = hbs.find(/(?s)<style>(.*?)<\/style>/) { all, c -> c }

		expect:
		css.readLines()*.trim().findAll { it && !it.startsWith('.finops-budget-burn ') }.isEmpty()
		!(hbs =~ /#[0-9A-Fa-f]{3,6}\b/)
		css.contains('overflow-x: auto')
		!(css =~ /(^|[\s;{])color\s*:/)
		!css.contains('var(--')
		!css.contains('#e6e6e6')
	}

	def "texts inside a row, in the currency mismatch branches and for an empty list come from the page texts"() {
		given:
		Map text = BudgetBurnAnalyticsProvider.texts(Locale.GERMAN)
		Map row = [name: 'Ops', scopeText: 'x', target: 'Tenant A', currency: 'EUR', mismatch: true, ytdMismatch: true,
			foreignRunning: '1,00 USD', foreignForecast: '2,00 USD', foreignYtd: '3,00 USD', statusText: text.status.mismatch]
		HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', BudgetBurnLocaleSpec.classLoader)

		when:
		String rows = renderer.renderTemplate('hbs/budgetBurn', new ViewModel<Map>(object: [text: text, language: 'de', items: [row], anyMismatch: true])).html
		String empty = renderer.renderTemplate('hbs/budgetBurn', new ViewModel<Map>(object: [text: text, language: 'de', items: []])).html

		then:
		rows.contains("${text.noComparison} (EUR)")
		rows.contains("+ 3,00 USD, ${text.status.mismatch}")
		rows.contains(text.mismatchNote as String)
		empty.contains(text.empty as String)
	}

	private MorpheusContext context(Locale browser) {
		MorpheusWebRequestService web = Mock(MorpheusWebRequestService) {
			getLocale() >> browser
		}
		return Mock(MorpheusContext) {
			getWebRequest() >> web
		}
	}

	private BudgetBurnAnalyticsProvider provider(Sql sql, Locale browser) {
		Connection connection = Mock(Connection)
		MorpheusReportService report = Mock(MorpheusReportService) {
			getReadOnlyDatabaseConnection() >> Single.just(connection)
			releaseDatabaseConnection(connection) >> Completable.complete()
		}
		MorpheusWebRequestService web = Mock(MorpheusWebRequestService) {
			getLocale() >> browser
		}
		MorpheusContext morpheus = Mock(MorpheusContext) {
			getReport() >> report
			getWebRequest() >> web
		}
		return new SqlProvider(morpheus, sql)
	}

	/** One monthly budget of 1234.50 per month for the viewer's own tenant, no invoices. */
	private void stubPage(Sql sql) {
		sql.firstRow({ it.startsWith('SELECT a.id') }, [7L]) >> new GroovyRowResult([id: 2L, name: 'Tenant A', is_master: 0])
		sql.firstRow({ it.startsWith('SELECT currency') }) >> new GroovyRowResult([currency: 'EUR'])
		sql.rows({ it.startsWith('SELECT b.*') }, _ as List) >> [new GroovyRowResult([id: 11L, name: 'Ops', owner: 'Tenant A', owner_currency: 'EUR',
			owner_master: 0, account_id: 2L, period_interval: 'month', ref_scope: 'account', ref_id: null, ref_name: null])]
		sql.rows({ it.startsWith('SELECT interval_index') }, _ as List) >> (1..12).collect { new GroovyRowResult([interval_index: it, cost: 1234.50G]) }
		sql.rows(_ as String, _ as List) >> []
	}

	private static Properties load(String path) {
		Properties p = new Properties()
		BudgetBurnLocaleSpec.classLoader.getResourceAsStream(path).withCloseable { p.load(it) }
		p
	}

	/** The provider with a given Sql in place of the report connection's. */
	static class SqlProvider extends BudgetBurnAnalyticsProvider {
		Sql testSql

		SqlProvider(MorpheusContext morpheus, Sql sql) {
			super(null, morpheus)
			this.testSql = sql
		}

		@Override
		protected Sql newSql(Connection c) {
			return testSql
		}
	}
}
