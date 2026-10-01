package com.morpheusdata.msptenantoverview

import spock.lang.Specification
import spock.lang.Unroll

import java.time.YearMonth

class MspTenantOverviewCalcSpec extends Specification {

	static final Locale EN = Locale.ENGLISH
	static final Locale DE = Locale.GERMAN

	@Unroll
	def "currency(#row, #master) resolves to #expected"() {
		expect:
		MspTenantOverviewCalc.currency(row, master) == expected

		where:
		row    | master | expected
		'EUR'  | 'CHF'  | 'EUR'
		'eur'  | null   | 'EUR'
		''     | 'CHF'  | 'CHF'
		'  '   | 'chf'  | 'CHF'
		null   | 'GBP'  | 'GBP'
		null   | ''     | 'USD'
		null   | null   | 'USD'
	}

	def "months gives the current and the previous period, across a year boundary"() {
		expect:
		MspTenantOverviewCalc.months(YearMonth.of(2026, 1)) ==
			[cur: '202601', last: '202512', curShown: '2026-01', lastShown: '2025-12']
		MspTenantOverviewCalc.months(YearMonth.of(2026, 10)).last == '202609'
	}

	def "rows are grouped per currency and month, never summed across currencies"() {
		given:
		List<Map> rows = [
			[cur: 'EUR', period: '202610', rev: 100.004, cost: 60.001],
			[cur: 'EUR', period: '202609', rev: 80, cost: 50],
			[cur: 'USD', period: '202610', rev: 10, cost: 4],
		]

		when:
		Map g = MspTenantOverviewCalc.groupByCurrency(rows, '202610', 'EUR')

		then:
		g.keySet() as List == ['EUR', 'USD']
		g.EUR == [rev: 100.00G, cost: 60.00G, lrev: 80.00G, lcost: 50.00G]
		g.USD == [rev: 10.00G, cost: 4.00G, lrev: 0.00G, lcost: 0.00G]
	}

	def "rows without a currency merge into the master currency before rounding"() {
		given: 'NULL and empty currency come back as two SQL groups'
		List<Map> rows = [
			[cur: null, period: '202610', rev: 0.005, cost: 0],
			[cur: '', period: '202610', rev: 0.005, cost: 0],
			[cur: 'CHF', period: '202610', rev: 1, cost: 0],
		]

		when:
		Map g = MspTenantOverviewCalc.groupByCurrency(rows, '202610', 'CHF')

		then: 'one CHF entry, rounded once: 1.01 (rounding each group first would give 1.02)'
		g.keySet() as List == ['CHF']
		g.CHF.rev == 1.01G
	}

	def "a tenant without invoices gets one zero entry in the fallback currency"() {
		expect:
		MspTenantOverviewCalc.groupByCurrency([], '202610', null) == [USD: [rev: 0.00G, cost: 0.00G, lrev: 0.00G, lcost: 0.00G]]
		MspTenantOverviewCalc.groupByCurrency([], '202610', 'EUR').keySet() as List == ['EUR']
	}

	def "totals add rounded tenant amounts per currency"() {
		given:
		Map totals = new TreeMap()

		when:
		MspTenantOverviewCalc.addTo(totals, [EUR: [rev: 1.01G, cost: 0.50G, lrev: 2G, lcost: 1G]])
		MspTenantOverviewCalc.addTo(totals, [EUR: [rev: 1.01G, cost: 0.50G, lrev: 0G, lcost: 0G], USD: [rev: 5G, cost: 1G, lrev: 0G, lcost: 0G]])

		then:
		totals.keySet() as List == ['EUR', 'USD']
		totals.EUR == [rev: 2.02G, cost: 1.00G, lrev: 2G, lcost: 1G]
		totals.USD.rev == 5G
	}

	@Unroll
	def "r2(#v) == #expected (half up)"() {
		expect:
		MspTenantOverviewCalc.r2(v) == expected

		where:
		v        | expected
		1.005G   | 1.01G
		1.004G   | 1.00G
		-1.005G  | -1.01G
		null     | 0.00G
	}

	def "money is locale aware with two decimals"() {
		expect:
		MspTenantOverviewCalc.money(1234567.891G, EN) == '1,234,567.89'
		MspTenantOverviewCalc.money(1234567.891G, DE) == '1.234.567,89'
		MspTenantOverviewCalc.money(-0.005G, EN) == '-0.01'
		MspTenantOverviewCalc.money(0G, DE) == '0,00'
	}

	@Unroll
	def "pct(#a, #b) in #locale == #expected"() {
		expect:
		MspTenantOverviewCalc.pct(a, b, locale) == expected

		where:
		a     | b      | locale | expected
		40G   | 100G   | EN     | '40.0'
		1G    | 3G     | DE     | '33,3'
		2G    | 3G     | EN     | '66.7'
		-5G   | 100G   | EN     | '-5.0'
		5G    | 0G     | EN     | '-'
		5G    | -10G   | EN     | '-'
		5G    | null   | EN     | '-'
	}

	def "memory is shown in GB with one decimal"() {
		expect:
		MspTenantOverviewCalc.memoryGb(8589934592L, EN) == '8.0'
		MspTenantOverviewCalc.memoryGb(1610612736L, DE) == '1,5'
		MspTenantOverviewCalc.memoryGb(null, EN) == '0.0'
		MspTenantOverviewCalc.memoryGb(2199023255552L, EN) == '2,048.0'
	}

	def "texts compute margins from the rounded amounts"() {
		when:
		Map t = MspTenantOverviewCalc.texts([rev: 100.00G, cost: 60.00G, lrev: 0.00G, lcost: 10.00G], EN)

		then:
		t.revText == '100.00'
		t.costText == '60.00'
		t.marginText == '40.00'
		t.marginPct == '40.0'
		t.lrevText == '0.00'
		t.lmarginText == '-10.00'
		t.lmarginPct == '-'
	}
}
