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
package com.morpheusdata.msptenantoverview

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Pure calculation and formatting helpers of the MSP tenant overview. No Morpheus or
 * database access, so every rule here is covered by a unit test.
 *
 * Money rules:
 *  - amounts are summed per currency, never across currencies
 *  - each tenant's amount per currency and month is rounded to cents once, after summing
 *    its invoice rows; margins and totals are computed from these rounded values
 *  - a row without a currency falls back to the master account's currency, then to USD
 */
class MspTenantOverviewCalc {

	static final String DEFAULT_CURRENCY = 'USD'
	static final BigDecimal BYTES_PER_GB = 1073741824G
	static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern('yyyyMM')
	static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern('yyyy-MM')

	static BigDecimal num(def v) {
		v == null ? BigDecimal.ZERO : new BigDecimal(v.toString())
	}

	static BigDecimal r2(def v) {
		num(v).setScale(2, RoundingMode.HALF_UP)
	}

	/** Currency of an invoice row: the row's own, else the master account's, else USD. */
	static String currency(def rowCurrency, def masterCurrency) {
		String row = rowCurrency?.toString()?.trim()
		if(row) return row.toUpperCase()
		fallbackCurrency(masterCurrency)
	}

	static String fallbackCurrency(def masterCurrency) {
		String master = masterCurrency?.toString()?.trim()
		master ? master.toUpperCase() : DEFAULT_CURRENCY
	}

	/** The current and the previous month as account_invoice periods (yyyyMM) and display texts (yyyy-MM). */
	static Map<String, String> months(YearMonth now) {
		YearMonth last = now.minusMonths(1)
		[cur: now.format(PERIOD), last: last.format(PERIOD), curShown: now.format(SHOWN), lastShown: last.format(SHOWN)]
	}

	static Map<String, BigDecimal> zero() {
		[rev: BigDecimal.ZERO, cost: BigDecimal.ZERO, lrev: BigDecimal.ZERO, lcost: BigDecimal.ZERO]
	}

	/**
	 * Groups one tenant's invoice rows (keys cur, period, rev, cost) by resolved currency.
	 * Rows of the current period count as rev/cost, all others as lrev/lcost (the previous month).
	 * The raw sums are rounded to cents per currency and month. A currency whose four rounded
	 * amounts are all zero is left out (e.g. zero-priced server invoices in USD). A tenant without
	 * any non-zero amount gets one zero entry in the fallback currency, so it is still listed.
	 * Currencies are sorted by code.
	 */
	static Map<String, Map<String, BigDecimal>> groupByCurrency(List<Map> rows, String curPeriod, def masterCurrency) {
		Map<String, Map<String, BigDecimal>> raw = new TreeMap<String, Map<String, BigDecimal>>()
		rows.each { Map m ->
			String ccy = currency(m.cur, masterCurrency)
			Map<String, BigDecimal> x = raw.computeIfAbsent(ccy) { zero() }
			if((m.period as String) == curPeriod) {
				x.rev += num(m.rev); x.cost += num(m.cost)
			} else {
				x.lrev += num(m.rev); x.lcost += num(m.cost)
			}
		}
		Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<String, Map<String, BigDecimal>>()
		raw.each { String ccy, Map<String, BigDecimal> x ->
			Map<String, BigDecimal> r = [rev: r2(x.rev), cost: r2(x.cost), lrev: r2(x.lrev), lcost: r2(x.lcost)]
			if(r.values().any { it.signum() != 0 }) out[ccy] = r
		}
		if(out.isEmpty()) out[fallbackCurrency(masterCurrency)] = [rev: r2(0), cost: r2(0), lrev: r2(0), lcost: r2(0)]
		out
	}

	/** Adds one tenant's per-currency amounts into the running totals per currency. */
	static void addTo(Map<String, Map<String, BigDecimal>> totals, Map<String, Map<String, BigDecimal>> byCurrency) {
		byCurrency.each { String ccy, Map<String, BigDecimal> x ->
			Map<String, BigDecimal> t = totals.computeIfAbsent(ccy) { zero() }
			t.rev += x.rev; t.cost += x.cost; t.lrev += x.lrev; t.lcost += x.lcost
		}
	}

	static String money(def v, Locale locale) {
		DecimalFormat f = new DecimalFormat('#,##0.00', DecimalFormatSymbols.getInstance(locale))
		f.roundingMode = RoundingMode.HALF_UP
		f.format(r2(v))
	}

	/** a as a percentage of b with one decimal, or '-' when b is not positive. */
	static String pct(def a, def b, Locale locale) {
		if(b == null || num(b) <= 0) return '-'
		DecimalFormat f = new DecimalFormat('0.0', DecimalFormatSymbols.getInstance(locale))
		f.roundingMode = RoundingMode.HALF_UP
		f.format((num(a) * 100G).divide(num(b), 1, RoundingMode.HALF_UP))
	}

	/**
	 * pct with its unit for running text ('40.0 %'), or '' when there is no percentage,
	 * so the template can leave it out instead of showing '- %'.
	 */
	static String pctLabel(def a, def b, Locale locale) {
		String p = pct(a, b, locale)
		p == '-' ? '' : "${p} %".toString()
	}

	/** instance.max_memory is in bytes; shown as GB (GiB) with one decimal. */
	static String memoryGb(def bytes, Locale locale) {
		DecimalFormat f = new DecimalFormat('#,##0.0', DecimalFormatSymbols.getInstance(locale))
		f.roundingMode = RoundingMode.HALF_UP
		f.format(num(bytes).divide(BYTES_PER_GB, 1, RoundingMode.HALF_UP))
	}

	/**
	 * Display texts for one currency entry; margins come from the rounded amounts.
	 * marginPct/lmarginPct are table cells ('-' without revenue); marginPctLabel/lmarginPctLabel
	 * carry the unit for running text and are empty without revenue.
	 */
	static Map<String, String> texts(Map<String, BigDecimal> x, Locale locale) {
		BigDecimal margin = x.rev - x.cost
		BigDecimal lmargin = x.lrev - x.lcost
		[revText    : money(x.rev, locale), costText: money(x.cost, locale),
		 marginText : money(margin, locale), marginPct: pct(margin, x.rev, locale),
		 lrevText   : money(x.lrev, locale), lcostText: money(x.lcost, locale),
		 lmarginText: money(lmargin, locale), lmarginPct: pct(lmargin, x.lrev, locale),
		 marginPctLabel: pctLabel(margin, x.rev, locale), lmarginPctLabel: pctLabel(lmargin, x.lrev, locale)]
	}
}
