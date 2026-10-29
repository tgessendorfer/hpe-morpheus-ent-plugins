package com.morpheusdata.budgetburn

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.core.web.MorpheusWebRequestService
import com.morpheusdata.model.User
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

import java.sql.Connection

class BudgetBurnAnalyticsProviderSpec extends Specification {

	// The current month as loadData() takes it from the clock.
	static final Calendar NOW = Calendar.instance
	static final int YEAR = NOW.get(Calendar.YEAR), MONTH = NOW.get(Calendar.MONTH) + 1
	static final String PER = BudgetBurnMath.periodKey(YEAR, MONTH)
	static final String JANUARY = "${YEAR}01".toString()

	def "the budget select returns owner_master for the spend rule in scopeCondition"() {
		expect:
		BudgetBurnAnalyticsProvider.BUDGET_SELECT.contains('AS owner_master')
		BudgetBurnAnalyticsProvider.BUDGET_SELECT.contains('a.currency AS owner_currency')
	}

	def "the budget select reads the enabled flag, NULL as enabled, and filters nothing on it"() {
		given:
		String q = BudgetBurnAnalyticsProvider.BUDGET_SELECT

		expect:
		q.contains('COALESCE(CAST(b.enabled AS UNSIGNED), 1) AS budget_enabled')
		q.substring(q.indexOf(' WHERE ')) == " WHERE b.period = 'year' AND b.period_value = ?"
	}

	def "server invoices count only for servers that belong to no instance"() {
		given:
		String f = BudgetBurnAnalyticsProvider.INVOICE_FILTER

		expect: 'an instance VM has an instance invoice and a server invoice without instance_id; the container row links them'
		f.contains("i.ref_type = 'Instance'")
		f.contains("i.ref_type = 'ComputeServer' AND i.instance_id IS NULL")
		f.contains('NOT EXISTS (SELECT 1 FROM container ct WHERE ct.server_id = i.ref_id AND ct.instance_id IS NOT NULL)')
	}

	def "a failing connection release does not replace the page result"() {
		given:
		Sql sql = Mock(Sql)
		Map t = provider(sql)
		sql.firstRow({ it.startsWith('SELECT a.id') }, [1L]) >> row(id: 1L, name: 'Provider', is_master: 1)
		sql.rows({ it.startsWith('SELECT b.*') }, _ as List) >> []

		when:
		def response = t.provider.loadData(new User(id: 1L), [:])

		then:
		1 * t.report.releaseDatabaseConnection(t.connection) >> { throw new IllegalStateException('pool closed') }
		noExceptionThrown()
		response.success
		response.data.master
		response.data.count == 0
	}

	def "as master every budget sums its own scope's invoices per currency, with the parameters in query order"() {
		given:
		Sql sql = Mock(Sql)
		Map t = provider(sql)
		Map text = BudgetBurnAnalyticsProvider.texts(Locale.ENGLISH)

		when:
		Map data = t.provider.loadData(new User(id: 1L), [:]).data

		then: 'a master tenant user, master currency USD'
		1 * sql.firstRow(BudgetBurnAnalyticsProvider.USER_LOCALE_SELECT, [1L]) >> row(locale: 'en-US')
		1 * sql.firstRow({ it.startsWith('SELECT a.id') }, [1L]) >> row(id: 1L, name: 'Provider', is_master: 1)
		1 * sql.firstRow({ it.startsWith('SELECT currency FROM account') }) >> row(currency: 'USD')

		and: 'all budgets of the year, master budgets first'
		1 * sql.rows(BudgetBurnAnalyticsProvider.BUDGET_SELECT + ' ORDER BY a.master_account DESC, b.name', [YEAR.toString()]) >> [tenantBudget(), cloudBudget()]
		1 * sql.rows({ it.startsWith('SELECT interval_index') }, [21L]) >> months(1000G)
		1 * sql.rows({ it.startsWith('SELECT interval_index') }, [22L]) >> months(500G)

		and: "the master's budget on a subtenant: the tenant, then the period; an invoice without currency counts in the budget currency"
		1 * sql.rows({ current(it) && it.contains(' AND i.account_id = ? AND i.period = ?') }, [2L, PER]) >>
			[row(currency: 'USD', running: 10G, forecast: 31G), row(currency: null, running: 1G, forecast: 2G)]
		1 * sql.rows({ yearToDate(it) && it.contains(' AND i.account_id = ? AND i.period >= ? AND i.period < ?') }, [2L, JANUARY, PER]) >>
			[row(currency: 'USD', total: 100G)]

		and: "the subtenant's cloud budget: owner restriction first, then the cloud, then the period"
		1 * sql.rows({ current(it) && it.contains(' AND i.account_id = ? AND i.zone_id = ? AND i.period = ?') }, [2L, 5L, PER]) >>
			[row(currency: 'EUR', running: 5G, forecast: 15.5G), row(currency: 'USD', running: 1.11G, forecast: 2.22G)]
		1 * sql.rows({ yearToDate(it) && it.contains(' AND i.account_id = ? AND i.zone_id = ? AND i.period >= ? AND i.period < ?') }, [2L, 5L, JANUARY, PER]) >>
			[row(currency: 'EUR', total: 40G)]
		0 * sql.rows(_, _)

		and: 'the USD budget compares USD spend with its budget'
		data.master
		data.count == 2
		data.anyMismatch
		data.items[0].currency == 'USD'
		data.items[0].scopeText == 'Tenant'
		data.items[0].target == 'Tenant A'
		data.items[0].budgetText == '1,000.00'
		data.items[0].runningText == '11.00'
		data.items[0].forecastText == '33.00'
		data.items[0].usedPct == '1.1 %'
		data.items[0].forecastPct == '3.3 %'
		!data.items[0].mismatch
		data.items[0].statusText == text.status.ok
		data.items[0].ytdText == '133.00'
		data.items[0].ytdBudgetText == BudgetBurnMath.money(1000G * MONTH, Locale.ENGLISH)

		and: 'the EUR budget keeps the USD spend apart and is flagged'
		data.items[1].currency == 'EUR'
		data.items[1].runningText == '5.00'
		data.items[1].forecastText == '15.50'
		data.items[1].mismatch
		data.items[1].foreignRunning == '1.11 USD'
		data.items[1].foreignForecast == '2.22 USD'
		data.items[1].statusText == text.status.mismatch
		data.items[1].ytdText == '55.50'
		data.items[1].ytdMismatch
		data.items[1].foreignYtd == '2.22 USD'
	}

	def "a subtenant user gets only the own tenant's budgets"() {
		given:
		Sql sql = Mock(Sql)
		Map t = provider(sql)

		when:
		Map data = t.provider.loadData(new User(id: 7L), [:]).data

		then:
		1 * sql.firstRow({ it.startsWith('SELECT a.id') }, [7L]) >> row(id: 2L, name: 'Tenant A', is_master: 0)
		1 * sql.firstRow({ it.startsWith('SELECT currency FROM account') }) >> row(currency: 'USD')
		1 * sql.rows(BudgetBurnAnalyticsProvider.BUDGET_SELECT + ' AND b.account_id = ? ORDER BY b.name', [YEAR.toString(), 2L]) >> [cloudBudget()]
		1 * sql.rows({ it.startsWith('SELECT interval_index') }, [22L]) >> months(500G)
		1 * sql.rows({ current(it) }, [2L, 5L, PER]) >> [row(currency: 'EUR', running: 5G, forecast: 15.5G)]
		1 * sql.rows({ yearToDate(it) }, [2L, 5L, JANUARY, PER]) >> []
		0 * sql.rows(_, _)

		and:
		!data.master
		data.tenant == 'Tenant A'
		data.items*.name == ['Tenant A cloud']
		data.items[0].runningText == '5.00'
		data.items[0].ytdText == '15.50'
		!data.items[0].mismatch
		!data.anyMismatch
	}

	def "a disabled budget stays listed with its amounts, sorts last, shows Disabled with the status it would have, and raises no flag"() {
		given:
		Sql sql = Mock(Sql)
		Map t = provider(sql)
		Map text = BudgetBurnAnalyticsProvider.texts(Locale.ENGLISH)
		sql.firstRow({ it.startsWith('SELECT a.id') }, [1L]) >> row(id: 1L, name: 'Provider', is_master: 1)
		sql.firstRow({ it.startsWith('SELECT currency FROM account') }) >> row(currency: 'USD')

		and: 'the database order puts a disabled budget first'
		sql.rows({ it.startsWith('SELECT b.*') }, [YEAR.toString()]) >> [archiveBudget(), tenantBudget(), oldCloudBudget()]
		sql.rows({ it.startsWith('SELECT interval_index') }, [23L]) >> months(10G)
		sql.rows({ it.startsWith('SELECT interval_index') }, [21L]) >> months(1000G)
		sql.rows({ it.startsWith('SELECT interval_index') }, [24L]) >> months(500G)

		and: 'the disabled budget on tenant 3 is five times over its budget'
		sql.rows({ current(it) }, [3L, PER]) >> [row(currency: 'USD', running: 20G, forecast: 50G)]
		sql.rows({ current(it) }, [2L, PER]) >> [row(currency: 'USD', running: 10G, forecast: 31G)]

		and: 'the disabled EUR cloud budget of tenant 4 also has USD spend'
		sql.rows({ current(it) }, [4L, 6L, PER]) >> [row(currency: 'EUR', running: 5G, forecast: 15.5G), row(currency: 'USD', running: 1.11G, forecast: 2.22G)]
		sql.rows(_ as String, _ as List) >> []

		when:
		Map data = t.provider.loadData(new User(id: 1L), [:]).data

		then: 'enabled budgets first, in the database order; every budget is counted'
		data.items*.name == ['Tenant A', 'Archive', 'Old cloud']
		data.count == 3
		!data.items[0].disabled
		data.items[0].statusText == text.status.ok
		data.items[0].statusDetail == null

		and: 'the over-budget one is Disabled in its own color, with its amounts and the status it would have'
		data.items[1].disabled
		data.items[1].statusText == text.status.disabled
		data.items[1].statusDetail == 'would be Over budget'
		data.items[1].color == BudgetBurnMath.STATUS_COLORS[BudgetBurnMath.STATUS_DISABLED]
		data.items[1].color != BudgetBurnMath.STATUS_COLORS[BudgetBurnMath.STATUS_OVER]
		data.items[1].budgetText == '10.00'
		data.items[1].runningText == '20.00'
		data.items[1].forecastText == '50.00'
		data.items[1].forecastPct == '500.0 %'

		and: 'the one with foreign spend keeps the foreign amounts, but is no currency mismatch and sets no page flag'
		data.items[2].disabled
		data.items[2].mismatch
		data.items[2].foreignForecast == '2.22 USD'
		data.items[2].statusText == text.status.disabled
		data.items[2].statusDetail == 'would be Currency mismatch'
		data.items[2].color != BudgetBurnMath.STATUS_COLORS[BudgetBurnMath.STATUS_MISMATCH]
		!data.anyMismatch
	}

	/** The month's spend query of loadData(). */
	static boolean current(String q) {
		q.contains('AS forecast FROM account_invoice i WHERE ' + BudgetBurnAnalyticsProvider.INVOICE_FILTER)
	}

	/** The query of the earlier months of the year. */
	static boolean yearToDate(String q) {
		q.contains('AS total FROM account_invoice i WHERE ' + BudgetBurnAnalyticsProvider.INVOICE_FILTER)
	}

	static GroovyRowResult row(Map m) {
		new GroovyRowResult(m)
	}

	static List<GroovyRowResult> months(BigDecimal cost) {
		(1..12).collect { row(interval_index: it, cost: cost) }
	}

	/** A master tenant budget on its subtenant 2, in USD. */
	static GroovyRowResult tenantBudget() {
		row(id: 21L, name: 'Tenant A', owner: 'Provider', owner_currency: 'USD', owner_master: 1, account_id: 1L,
			period_interval: 'month', ref_scope: 'tenant', ref_id: 2L, ref_name: 'Tenant A', budget_enabled: 1)
	}

	/** Subtenant 2's own budget on the shared cloud 5, in EUR. */
	static GroovyRowResult cloudBudget() {
		row(id: 22L, name: 'Tenant A cloud', owner: 'Tenant A', owner_currency: 'EUR', owner_master: 0, account_id: 2L,
			period_interval: 'month', ref_scope: 'cloud', ref_id: 5L, ref_name: 'Cloud 5', budget_enabled: BigInteger.ONE)
	}

	/** A disabled master tenant budget on subtenant 3, in USD (CAST AS UNSIGNED comes back as BigInteger). */
	static GroovyRowResult archiveBudget() {
		row(id: 23L, name: 'Archive', owner: 'Provider', owner_currency: 'USD', owner_master: 1, account_id: 1L,
			period_interval: 'month', ref_scope: 'tenant', ref_id: 3L, ref_name: 'Tenant B', budget_enabled: BigInteger.ZERO)
	}

	/** Subtenant 4's disabled budget on cloud 6, in EUR. */
	static GroovyRowResult oldCloudBudget() {
		row(id: 24L, name: 'Old cloud', owner: 'Tenant C', owner_currency: 'EUR', owner_master: 0, account_id: 4L,
			period_interval: 'month', ref_scope: 'cloud', ref_id: 6L, ref_name: 'Cloud 6', budget_enabled: 0)
	}

	/** The provider over the given Sql, with an English web request. */
	private Map provider(Sql sql) {
		Connection connection = Mock(Connection)
		MorpheusReportService report = Mock(MorpheusReportService) {
			getReadOnlyDatabaseConnection() >> Single.just(connection)
			releaseDatabaseConnection(connection) >> Completable.complete()
		}
		MorpheusWebRequestService web = Mock(MorpheusWebRequestService) {
			getLocale() >> Locale.ENGLISH
		}
		MorpheusContext morpheus = Mock(MorpheusContext) {
			getReport() >> report
			getWebRequest() >> web
		}
		[provider: new BudgetBurnLocaleSpec.SqlProvider(morpheus, sql), report: report, connection: connection]
	}
}
