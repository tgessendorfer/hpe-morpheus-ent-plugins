package com.morpheusdata.budgetburn

import spock.lang.Specification
import spock.lang.Unroll

import static com.morpheusdata.budgetburn.BudgetBurnMath.*

class BudgetBurnMathSpec extends Specification {

	static List<Map> periods(Map<Integer, Object> costs) {
		costs.collect { k, v -> [interval_index: k, cost: v] }
	}

	@Unroll
	def "monthlyBudget for interval #interval in month #m is #expected"() {
		expect:
		monthlyBudget(interval, p, m) == expected

		where:
		interval  | p                                     | m  || expected
		'month'   | periods([1: 100, 2: 200, 12: 50.5])   | 2  || 200G
		'month'   | periods([1: 100])                     | 3  || 0G
		'quarter' | periods([1: 300, 2: 600, 4: 100])     | 1  || 100.00G
		'quarter' | periods([1: 300, 2: 600, 4: 100])     | 6  || 200.00G
		'quarter' | periods([1: 300, 2: 600, 4: 100])     | 12 || 33.33G
		'quarter' | periods([1: 300])                     | 7  || 0.00G
		'year'    | periods([1: 1000])                    | 5  || 83.33G
		'year'    | periods([1: '1200.00'])               | 12 || 100.00G
		'year'    | periods([:])                          | 1  || 0.00G
		null      | periods([4: 42])                      | 4  || 42G
	}

	def "quarterly and yearly shares round half up to two decimals"() {
		expect:
		monthlyBudget('quarter', periods([1: 0.05]), 1) == 0.02G
		monthlyBudget('year', periods([1: 0.06]), 1) == 0.01G
		monthlyBudget('year', periods([1: 100]), 1).scale() == 2
	}

	def "budgetToDate sums the monthly shares of months 1..m"() {
		expect:
		budgetToDate('year', periods([1: 1200]), 3) == 300.00G
		budgetToDate('month', periods([1: 10, 2: 20, 3: 30, 4: 40]), 3) == 60G
		budgetToDate('quarter', periods([1: 300, 2: 600]), 4) == 500.00G
	}

	def "byCurrency keeps currencies apart and never adds them up"() {
		given:
		List<Map> rows = [
			[currency: 'EUR', running: 10.5, forecast: 30],
			[currency: 'USD', running: 1.11, forecast: 2],
			[currency: 'EUR', running: '0.5', forecast: null],
		]

		when:
		Map m = byCurrency(rows, 'EUR')

		then:
		m.keySet() as List == ['EUR', 'USD']
		m.EUR.running == 11.0G
		m.EUR.forecast == 30G
		m.EUR.total == 0G
		m.USD.running == 1.11G
		m.USD.forecast == 2G
	}

	def "byCurrency puts rows without a currency into the fallback"() {
		expect:
		byCurrency([[currency: null, total: 5], [currency: '', total: 7], [currency: 'eur', total: 1]], 'EUR') ==
			[EUR: [running: 0G, forecast: 0G, total: 13G]]
	}

	def "foreignText lists only non-zero amounts outside the budget currency"() {
		given:
		Map m = [EUR: [running: 5G], USD: [running: 1.11G], GBP: [running: 0G], CHF: [running: 1234.5G]]

		expect:
		foreignText(m, 'EUR', 'running', Locale.ENGLISH) == '1.11 USD + 1,234.50 CHF'
		foreignText(m, 'EUR', 'running', Locale.GERMANY) == '1,11 USD + 1.234,50 CHF'
		foreignText([EUR: [running: 5G]], 'EUR', 'running', Locale.ENGLISH) == ''
	}

	def "ytdForeign adds previous totals and the current forecast per foreign currency"() {
		given:
		Map previous = [EUR: [total: 100G], USD: [total: 10G]]
		Map current = [EUR: [forecast: 50G], USD: [forecast: 2.5G], GBP: [forecast: 0G]]

		when:
		Map out = ytdForeign(previous, current, 'EUR')

		then:
		out == [USD: 12.5G, GBP: 0G]
		joinForeign(out, 'EUR', Locale.ENGLISH) == '12.50 USD'
	}

	@Unroll
	def "resolveCurrency #candidates -> #expected"() {
		expect:
		resolveCurrency(candidates as String[]) == expected

		where:
		candidates           || expected
		['EUR', 'USD']       || 'EUR'
		[null, 'chf']        || 'CHF'
		['', '  ', 'GBP']    || 'GBP'
		[null, null]         || 'USD'
		[]                   || 'USD'
	}

	def "money and pctText format in the viewer's locale with half-up rounding"() {
		expect:
		money(1234.565G, Locale.ENGLISH) == '1,234.57'
		money(1234.565G, Locale.GERMANY) == '1.234,57'
		money(0.004G, Locale.ENGLISH) == '0.00'
		money(null, null) == '0.00'
		pctText(81.25G, Locale.ENGLISH) == '81.3'
		pctText(81.25G, Locale.GERMANY) == '81,3'
	}

	@Unroll
	def "pct(#a, #b) = #expected"() {
		expect:
		pct(a, b) == expected

		where:
		a      | b     || expected
		50G    | 200G  || 25.0G
		1G     | 3G    || 33.3G
		2G     | 3G    || 66.7G
		10G    | 0G    || 0G
		10G    | -5G   || 0G
		null   | 100G  || 0.0G
	}

	@Unroll
	def "burnRate(#running, day #day) = #expected"() {
		expect:
		burnRate(running, day) == expected

		where:
		running  | day || expected
		300G     | 10  || 30.00G
		100G     | 3   || 33.33G
		0.05G    | 2   || 0.03G
		100G     | 0   || 100.00G
		null     | 5   || 0.00G
	}

	@Unroll
	def "status for forecast #p % and mismatch #mismatch is #expected"() {
		expect:
		status(p, mismatch) == expected
		statusColor(expected) == STATUS_COLORS[expected]

		where:
		p        | mismatch || expected
		0G       | false    || STATUS_OK
		79.9G    | false    || STATUS_OK
		80.0G    | false    || STATUS_WARNING
		100.0G   | false    || STATUS_WARNING
		100.1G   | false    || STATUS_OVER
		250G     | true     || STATUS_MISMATCH
		null     | false    || STATUS_OK
	}

	def "barWidth is capped to 0..100"() {
		expect:
		barWidth(-3G) == 0
		barWidth(55.9G) == 55
		barWidth(140G) == 100
	}

	def "scopeCondition of a master-owned budget filters by the scope only (master sees subtenants' spend)"() {
		given:
		List params = []

		expect:
		scopeCondition([ref_scope: scope, ref_id: 7, account_id: 1, owner_master: master], params) == sql
		params == [param]

		where:
		scope     | master                 || sql                 | param
		'tenant'  | 1                      || 'i.account_id = ?'  | 7
		'group'   | 1                      || 'i.site_id = ?'     | 7
		'cloud'   | 1                      || 'i.zone_id = ?'     | 7
		'user'    | true                   || 'i.user_id = ?'     | 7
		'account' | BigInteger.ONE         || 'i.account_id = ?'  | 1
		null      | 1L                     || 'i.account_id = ?'  | 1
	}

	def "scopeCondition of a subtenant budget counts only the owner's invoices"() {
		given:
		List params = []

		expect:
		scopeCondition([ref_scope: scope, ref_id: ref, account_id: 3, owner_master: master], params) == sql
		params == expected

		where:
		scope     | ref  | master || sql                                    | expected
		'cloud'   | 7    | 0      || 'i.account_id = ? AND i.zone_id = ?'   | [3, 7]
		'group'   | 7    | 0      || 'i.account_id = ? AND i.site_id = ?'   | [3, 7]
		'user'    | 7    | 0      || 'i.account_id = ? AND i.user_id = ?'   | [3, 7]
		'tenant'  | 7    | 0      || 'i.account_id = ? AND i.account_id = ?' | [3, 7]
		'tenant'  | 3L   | 0      || 'i.account_id = ?'                     | [3L]
		'account' | null | 0      || 'i.account_id = ?'                     | [3]
		null      | null | 0      || 'i.account_id = ?'                     | [3]
		'cloud'   | 7    | null   || 'i.account_id = ? AND i.zone_id = ?'   | [3, 7]
		'cloud'   | 7    | false  || 'i.account_id = ? AND i.zone_id = ?'   | [3, 7]
	}

	def "isMaster reads the master flag in the forms the database returns"() {
		expect:
		isMaster(flag) == result

		where:
		flag           || result
		1              || true
		1L             || true
		BigInteger.ONE || true
		true           || true
		'1'            || true
		0              || false
		BigInteger.ZERO|| false
		false          || false
		null           || false
		'0'            || false
	}

	def "scope and status keys use the provider code"() {
		expect:
		scopeKey('group') == 'budget-burn-analytics.scope.group'
		scopeKey('weird') == 'budget-burn-analytics.scope.other'
		scopeKey(null) == 'budget-burn-analytics.scope.other'
		statusKey(STATUS_OVER) == 'budget-burn-analytics.status.over'
	}
}
