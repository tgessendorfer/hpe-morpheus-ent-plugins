package com.morpheusdata.finopsinsights

import spock.lang.Specification

import java.time.YearMonth

class FinopsInsightsCalcSpec extends Specification {

	static Map row(Map m) {
		[account: 1, accountName: 'A', zone: 1, zoneName: 'Z', period: '202610', cur: 'EUR', rev: 0, cost: 0] + m
	}

	def "six trend months end with the current month, oldest first"() {
		when:
		def months = FinopsInsightsCalc.trendMonths(YearMonth.of(2026, 10))

		then:
		FinopsInsightsCalc.periods(months) == ['202605', '202606', '202607', '202608', '202609', '202610']
	}

	def "the main currency is the one with the most money; the fallback applies without rows"() {
		expect:
		FinopsInsightsCalc.mainCurrency([row(cur: 'USD', rev: 5), row(cur: 'EUR', rev: 100), row(cur: 'eur', cost: 3)], 'USD') == 'EUR'
		FinopsInsightsCalc.mainCurrency([], 'eur') == 'EUR'
		FinopsInsightsCalc.mainCurrency([row(cur: null, rev: 1)], null) == 'USD'
		FinopsInsightsCalc.inCurrency([row(cur: 'USD'), row(cur: ' eur ')], 'EUR', 'USD').size() == 1
	}

	def "per period sums are zero-filled, rounded to cents and carry the margin"() {
		when:
		def t = FinopsInsightsCalc.perPeriod([row(period: '202610', rev: 10.004, cost: 4.001), row(period: '202610', rev: 5, cost: 1),
			row(period: '202609', rev: 2, cost: 3)], ['202608', '202609', '202610'])

		then:
		t*.period == ['202608', '202609', '202610']
		t*.rev == [0.00G, 2.00G, 15.00G]
		t*.cost == [0.00G, 3.00G, 5.00G]
		t*.margin == [0.00G, -1.00G, 10.00G]
	}

	def "topBy keeps the top N by revenue, folds the rest into other, and computes share and bar"() {
		given:
		def rows = (1..7).collect { int i -> row(zone: i, zoneName: "cloud-${i}", rev: i * 10) } +
			[row(zone: 99, zoneName: 'empty', rev: 0, cost: 0), row(zone: 3, zoneName: 'cloud-3', period: '202609', rev: 1000)]

		when:
		def top = FinopsInsightsCalc.topBy(rows, '202610', 'zone', 'zoneName', 5, 'other')

		then:
		top*.name == ['cloud-7', 'cloud-6', 'cloud-5', 'cloud-4', 'cloud-3', 'other']
		top*.rev == [70.00G, 60.00G, 50.00G, 40.00G, 30.00G, 30.00G]
		top[-1].other == true
		top[0].bar == 100
		top[-1].bar == 43
		top[0].share == 25.0G
		top.sum { it.share } == 100.0G
	}

	def "percentages and deltas are null without a positive base"() {
		expect:
		FinopsInsightsCalc.pctValue(1, 0) == null
		FinopsInsightsCalc.pctValue(25, 200) == 12.5G
		FinopsInsightsCalc.deltaPct(110, 100) == 10.0G
		FinopsInsightsCalc.deltaPct(90, 100) == -10.0G
		FinopsInsightsCalc.deltaPct(5, null) == null
		FinopsInsightsCalc.deltaText(-10.0G, Locale.ENGLISH) == '-10.0 %'
		FinopsInsightsCalc.deltaText(10.0G, Locale.GERMAN) == '+10,0 %'
		FinopsInsightsCalc.deltaText(null, Locale.ENGLISH) == '-'
		FinopsInsightsCalc.pctText(12.5G, Locale.GERMAN) == '12,5 %'
	}

	def "money and compact formats follow the locale"() {
		expect:
		FinopsInsightsCalc.money(1234.567, Locale.ENGLISH) == '1,234.57'
		FinopsInsightsCalc.money(1234.567, Locale.GERMAN) == '1.234,57'
		FinopsInsightsCalc.compact(0, Locale.ENGLISH) == '0'
		FinopsInsightsCalc.compact(999.4, Locale.ENGLISH) == '999'
		FinopsInsightsCalc.compact(1250, Locale.ENGLISH) == '1.3k'
		FinopsInsightsCalc.compact(12500, Locale.ENGLISH) == '13k'
		FinopsInsightsCalc.compact(1500000, Locale.GERMAN) == '1,5M'
		FinopsInsightsCalc.compact(-2500, Locale.ENGLISH) == '-2.5k'
	}

	def "nice axis maximum is 1, 2, 5 x 10^n"() {
		expect:
		FinopsInsightsCalc.niceMax(v) == expected

		where:
		v       | expected
		null    | 1G
		0G      | 1G
		0.7G    | 1G
		1G      | 1G
		1.2G    | 2G
		3G      | 5G
		7G      | 10G
		888.12G | 1000G
		1200G   | 2000G
		5000G   | 5000G
		5001G   | 10000G
	}

	def "the trend chart puts bars inside the plot and labels every month"() {
		given:
		def months = FinopsInsightsCalc.trendMonths(YearMonth.of(2026, 10))
		def series = FinopsInsightsCalc.perPeriod([row(period: '202610', rev: 1000, cost: 400), row(period: '202607', rev: 500, cost: 100)], FinopsInsightsCalc.periods(months))

		when:
		def chart = FinopsInsightsCalc.trendChart(series, months, true, Locale.ENGLISH)

		then:
		chart.groups.size() == 6
		chart.groups*.label == ['May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct']
		chart.groups[-1].current == true
		chart.groups[0].current == false
		chart.groups[-1].revH == 150.0G             // 1000 of axis max 1000 over a plot height of 150
		chart.groups[-1].costH == 60.0G
		chart.groups[2].revH == 75.0G
		chart.groups[0].revH == 0.0G
		chart.groups.every { it.revX >= FinopsInsightsCalc.CHART_PAD_LEFT && it.costX + it.width <= FinopsInsightsCalc.CHART_W }
		chart.groups.every { it.revY + it.revH == chart.baseY }
		chart.grid*.label == ['0', '250', '500', '750', '1.0k']
		chart.withCost
	}

	def "a negative month total draws no bar instead of a negative height"() {
		given:
		def months = FinopsInsightsCalc.trendMonths(YearMonth.of(2026, 10))
		def series = FinopsInsightsCalc.perPeriod([row(period: '202609', rev: -40, cost: -10), row(period: '202610', rev: 1000, cost: 400)],
			FinopsInsightsCalc.periods(months))

		when:
		def chart = FinopsInsightsCalc.trendChart(series, months, true, Locale.ENGLISH)
		def credit = chart.groups[-2]

		then:
		credit.revH == 0.0G
		credit.costH == 0.0G
		credit.revY == chart.baseY
		credit.costY == chart.baseY
		// the tooltip still tells the real figure
		credit.revText == '-40.00'
		credit.costText == '-10.00'
		chart.groups.every { it.revH >= 0 && it.costH >= 0 }
		chart.groups[-1].revH == 150.0G
		FinopsInsightsCalc.barHeight(-1, 150, 1000G) == 0.0G
		FinopsInsightsCalc.barHeight(null, 150, 1000G) == 0.0G
		FinopsInsightsCalc.barHeight(500, 150, 1000G) == 75.0G
	}

	def "a tenant chart has single bars without cost"() {
		given:
		def months = FinopsInsightsCalc.trendMonths(YearMonth.of(2026, 10))
		def series = FinopsInsightsCalc.perPeriod([row(period: '202610', rev: 30)], FinopsInsightsCalc.periods(months))

		when:
		def chart = FinopsInsightsCalc.trendChart(series, months, false, Locale.GERMAN)

		then:
		!chart.withCost
		chart.groups.every { it.costX == null }
		chart.groups*.label == ['Mai', 'Juni', 'Juli', 'Aug.', 'Sept.', 'Okt.']
		chart.grid*.label == ['0', '13', '25', '38', '50']
	}
}
