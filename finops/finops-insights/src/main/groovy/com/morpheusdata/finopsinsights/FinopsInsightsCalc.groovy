/*
 * Copyright 2026 Thomas Gessendorfer.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.morpheusdata.finopsinsights

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Pure calculation and formatting helpers of the FinOps Insights widgets. No Morpheus or database
 * access, so every rule is covered by a unit test.
 *
 * Input rows are maps with keys account (id), accountName, zone (id), zoneName, period (yyyyMM),
 * cur (currency), rev, cost (already summed per row by the SQL GROUP BY). Amounts are summed per
 * currency and rounded to cents once, after summing.
 */
class FinopsInsightsCalc {

	static final String DEFAULT_CURRENCY = 'USD'
	static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern('yyyyMM')
	static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern('yyyy-MM')
	static final DateTimeFormatter MONTH_SHORT = DateTimeFormatter.ofPattern('MMM')
	static final int TREND_MONTHS = 6
	static final int TOP_ROWS = 5

	// SVG geometry of the trend chart (viewBox units).
	static final int CHART_W = 520
	static final int CHART_H = 190
	static final int CHART_PAD_LEFT = 44
	static final int CHART_PAD_BOTTOM = 26
	static final int CHART_PAD_TOP = 14

	static BigDecimal num(def v) {
		v == null ? BigDecimal.ZERO : new BigDecimal(v.toString())
	}

	static BigDecimal r2(def v) {
		num(v).setScale(2, RoundingMode.HALF_UP)
	}

	/** Currency of an invoice row: the row's own, else the account's, else USD. */
	static String currency(def rowCurrency, def fallback) {
		String row = rowCurrency?.toString()?.trim()
		if(row) return row.toUpperCase()
		String f = fallback?.toString()?.trim()
		f ? f.toUpperCase() : DEFAULT_CURRENCY
	}

	/** The last TREND_MONTHS periods ending with the given month, oldest first. */
	static List<YearMonth> trendMonths(YearMonth now) {
		(TREND_MONTHS - 1..0).collect { int back -> now.minusMonths(back) }
	}

	static List<String> periods(List<YearMonth> months) {
		months.collect { it.format(PERIOD) }
	}

	/**
	 * Picks the currency to show: the one with the highest revenue over all rows, the fallback when
	 * there are no rows. Other currencies are counted so the widget can say they are left out.
	 */
	static String mainCurrency(List<Map> rows, def fallback) {
		Map<String, BigDecimal> byCur = [:]
		rows.each { Map r ->
			String c = currency(r.cur, fallback)
			byCur[c] = (byCur[c] ?: BigDecimal.ZERO) + num(r.rev).abs() + num(r.cost).abs()
		}
		if(byCur.isEmpty()) return currency(null, fallback)
		byCur.max { it.value }.key
	}

	static List<Map> inCurrency(List<Map> rows, String ccy, def fallback) {
		rows.findAll { Map r -> currency(r.cur, fallback) == ccy }
	}

	/** Revenue and cost per period for the given periods, zero-filled, in period order. */
	static List<Map> perPeriod(List<Map> rows, List<String> periods) {
		Map<String, Map> acc = [:]
		periods.each { String p -> acc[p] = [period: p, rev: BigDecimal.ZERO, cost: BigDecimal.ZERO] }
		rows.each { Map r ->
			Map x = acc[r.period as String]
			if(x != null) { x.rev += num(r.rev); x.cost += num(r.cost) }
		}
		periods.collect { String p -> Map x = acc[p]; [period: p, rev: r2(x.rev), cost: r2(x.cost), margin: r2(x.rev - x.cost)] }
	}

	/**
	 * Sums one period by a key (zone or account), sorted by revenue descending, top N plus an
	 * "other" bucket. Each entry: name, rev, cost, share (0-100 of the total), bar (0-100 of the
	 * largest entry, for the bar width). Entries whose revenue and cost are both zero are left out.
	 */
	static List<Map> topBy(List<Map> rows, String period, String keyField, String nameField, int top, String otherLabel) {
		Map<Object, Map> acc = [:]
		rows.findAll { (it.period as String) == period }.each { Map r ->
			Object k = r[keyField]
			Map x = acc.computeIfAbsent(k) { [name: (r[nameField] ?: '-').toString(), rev: BigDecimal.ZERO, cost: BigDecimal.ZERO] }
			x.rev += num(r.rev); x.cost += num(r.cost)
		}
		List<Map> all = acc.values().findAll { it.rev.signum() != 0 || it.cost.signum() != 0 }
			.sort { a, b -> b.rev <=> a.rev ?: b.cost <=> a.cost ?: a.name <=> b.name }
		List<Map> out = all.take(top).collect { Map x -> [name: x.name, rev: r2(x.rev), cost: r2(x.cost)] }
		if(all.size() > top) {
			List<Map> rest = all.drop(top)
			out << [name: otherLabel, rev: r2(rest.sum { it.rev } ?: 0), cost: r2(rest.sum { it.cost } ?: 0), other: true]
		}
		BigDecimal total = out.sum { it.rev } ?: BigDecimal.ZERO
		BigDecimal largest = out.collect { it.rev }.max() ?: BigDecimal.ZERO
		out.each { Map x ->
			x.share = pctValue(x.rev, total)
			x.bar = largest.signum() > 0 ? (num(x.rev) * 100G).divide(largest, 0, RoundingMode.HALF_UP).intValue() : 0
		}
		out
	}

	/** a as a percentage of b, one decimal; null when b is not positive. */
	static BigDecimal pctValue(def a, def b) {
		if(b == null || num(b) <= 0) return null
		(num(a) * 100G).divide(num(b), 1, RoundingMode.HALF_UP)
	}

	/** Change of cur against prev in percent (one decimal); null when prev is not positive. */
	static BigDecimal deltaPct(def cur, def prev) {
		if(prev == null || num(prev) <= 0) return null
		((num(cur) - num(prev)) * 100G).divide(num(prev), 1, RoundingMode.HALF_UP)
	}

	static String money(def v, Locale locale) {
		DecimalFormat f = new DecimalFormat('#,##0.00', DecimalFormatSymbols.getInstance(locale))
		f.roundingMode = RoundingMode.HALF_UP
		f.format(r2(v))
	}

	/** Compact amount for cards and axis labels: 1.2k, 35k, 1.4M; below 1000 with no decimals. */
	static String compact(def v, Locale locale) {
		BigDecimal n = num(v)
		BigDecimal a = n.abs()
		DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(locale)
		String sign = n.signum() < 0 ? '-' : ''
		if(a >= 1000000G) return sign + new DecimalFormat('0.0', sym).format(a.divide(1000000G, 1, RoundingMode.HALF_UP)) + 'M'
		if(a >= 10000G) return sign + new DecimalFormat('0', sym).format(a.divide(1000G, 0, RoundingMode.HALF_UP)) + 'k'
		if(a >= 1000G) return sign + new DecimalFormat('0.0', sym).format(a.divide(1000G, 1, RoundingMode.HALF_UP)) + 'k'
		sign + new DecimalFormat('0', sym).format(a.setScale(0, RoundingMode.HALF_UP))
	}

	static String pctText(BigDecimal pct, Locale locale) {
		if(pct == null) return '-'
		new DecimalFormat('0.0', DecimalFormatSymbols.getInstance(locale)).format(pct) + ' %'
	}

	/** Signed delta text like "+12.5 %" or "-3.0 %"; "-" without a base. */
	static String deltaText(BigDecimal pct, Locale locale) {
		if(pct == null) return '-'
		String s = new DecimalFormat('0.0', DecimalFormatSymbols.getInstance(locale)).format(pct.abs())
		(pct.signum() < 0 ? '-' : '+') + s + ' %'
	}

	/** A "nice" axis maximum (1, 2, 5 x 10^n) at or above the value; 1 when the value is 0. */
	static BigDecimal niceMax(BigDecimal v) {
		if(v == null || v.signum() <= 0 || v <= 1G) return 1G
		int exp = (int) Math.floor(Math.log10(v.doubleValue()))
		BigDecimal base = 10G.pow(exp)
		for(BigDecimal m : [1G, 2G, 5G, 10G]) {
			BigDecimal cand = base * m
			if(cand >= v) return cand
		}
		base * 10G
	}

	/**
	 * Height of one bar, rounded to one decimal. A negative month total (credits larger than the
	 * charges) draws no bar: a negative SVG height is invalid and the browser drops the rect.
	 */
	static BigDecimal barHeight(def value, int plotH, BigDecimal axisMax) {
		BigDecimal h = (num(value) * plotH).divide(axisMax, 1, RoundingMode.HALF_UP)
		h.signum() < 0 ? 0.0G : h
	}

	/**
	 * Bar geometry of the trend chart: one group per period with a revenue bar and, when
	 * withCost is set, a cost bar next to it. Coordinates are rounded to one decimal.
	 */
	static Map trendChart(List<Map> series, List<YearMonth> months, boolean withCost, Locale locale) {
		BigDecimal maxVal = series.collect { [it.rev, withCost ? it.cost : BigDecimal.ZERO] }.flatten().max() ?: BigDecimal.ZERO
		BigDecimal axisMax = niceMax(maxVal)
		int plotH = CHART_H - CHART_PAD_BOTTOM - CHART_PAD_TOP
		int plotW = CHART_W - CHART_PAD_LEFT - 8
		int n = Math.max(series.size(), 1)
		BigDecimal slot = (plotW / n) as BigDecimal
		BigDecimal barW = withCost ? (slot * 0.3G) : (slot * 0.5G)
		BigDecimal gap = withCost ? (slot * 0.06G) : 0G
		List<Map> groups = []
		series.eachWithIndex { Map s, int i ->
			BigDecimal x0 = CHART_PAD_LEFT + slot * i + (slot - (withCost ? barW * 2 + gap : barW)) / 2
			BigDecimal revH = barHeight(s.rev, plotH, axisMax)
			BigDecimal costH = barHeight(s.cost, plotH, axisMax)
			Map g = [label: months[i].format(MONTH_SHORT.withLocale(locale)),
				labelX: (CHART_PAD_LEFT + slot * i + slot / 2).setScale(1, RoundingMode.HALF_UP),
				revX: x0.setScale(1, RoundingMode.HALF_UP), revY: (CHART_PAD_TOP + plotH - revH).setScale(1, RoundingMode.HALF_UP),
				revH: revH, revText: money(s.rev, locale), width: barW.setScale(1, RoundingMode.HALF_UP),
				current: i == series.size() - 1]
			if(withCost) {
				g.costX = (x0 + barW + gap).setScale(1, RoundingMode.HALF_UP)
				g.costY = (CHART_PAD_TOP + plotH - costH).setScale(1, RoundingMode.HALF_UP)
				g.costH = costH
				g.costText = money(s.cost, locale)
			}
			groups << g
		}
		// Four grid lines (25 % steps) with their axis labels.
		List<Map> grid = (0..4).collect { int k ->
			BigDecimal val = axisMax * k / 4
			[y: (CHART_PAD_TOP + plotH - (plotH * k / 4)).setScale(1, RoundingMode.HALF_UP), label: compact(val, locale)]
		}
		[width: CHART_W, height: CHART_H, baseY: CHART_PAD_TOP + plotH, groups: groups, grid: grid, withCost: withCost]
	}
}
