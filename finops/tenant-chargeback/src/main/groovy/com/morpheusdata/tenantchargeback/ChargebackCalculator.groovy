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
package com.morpheusdata.tenantchargeback

import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat

/**
 * Pure logic of the chargeback report: option parsing, month handling, currency resolution,
 * rounding, aggregation and locale-aware formatting. No Morpheus or database access, so all
 * of it is covered by unit tests.
 *
 * Money rules:
 * - Every invoice line is rounded to cents first; all totals are sums of rounded lines, so the
 *   rows shown always add up.
 * - Margin = list price - cost, both already rounded.
 * - Invoice = list price x (1 + additional markup %), rounded to cents per line.
 * - Amounts in different currencies are never added; every total is per currency.
 */
class ChargebackCalculator {

	/** Fallback when neither the invoice line nor the master tenant carries a currency. */
	static final String DEFAULT_CURRENCY = 'USD'

	/** Accepted month input: YYYY-MM (or YYYYMM), month 01 to 12. */
	static final String MONTH_PATTERN = /^\d{4}-?(0[1-9]|1[0-2])$/

	/** Accepted markup input: digits with an optional decimal point or comma (5, 7.5, 7,5, .5); no sign, no exponent. */
	static final String MARKUP_PATTERN = /^(\d+([.,]\d*)?|[.,]\d+)$/

	/** Highest additional markup in percent. */
	static final BigDecimal MAX_MARKUP_PERCENT = 1000G

	/**
	 * Decimals of a percent the markup may carry. {@link #markupFactor} divides by 100 at six
	 * decimals, so four decimals of a percent are calculated exactly and shown in full.
	 */
	static final int MARKUP_DECIMALS = 4

	/**
	 * Safe map lookup. Morpheus passes org.grails.web.json.JSONObject, whose get() throws on a
	 * missing key, so containsKey is checked first.
	 */
	static def opt(def m, String key) {
		(m instanceof Map && m.containsKey(key)) ? m.get(key) : null
	}

	/** Reads a report option from the top level, then from config, then from report. Trimmed, or null. */
	static String cfg(Map opts, String key) {
		def v = opt(opts, key)
		if(v == null) v = opt(opt(opts, 'config'), key)
		if(v == null) v = opt(opt(opts, 'report'), key)
		v == null ? null : v.toString().trim()
	}

	/** True for the values a Morpheus checkbox or a JSON boolean can carry when ticked. */
	static boolean isTrue(def v) {
		v != null && v.toString().trim().toLowerCase() in ['on', 'true', '1', 'yes']
	}

	/** Empty input is valid (current month); otherwise YYYY-MM or YYYYMM with month 01-12. */
	static boolean isValidMonth(String month) {
		!month || (month ==~ MONTH_PATTERN)
	}

	/**
	 * Invoice period key (YYYYMM) for the given month input; empty means the month of {@code now}
	 * in the JVM time zone of the appliance.
	 */
	static String period(String month, Date now = new Date()) {
		if(!month) return new SimpleDateFormat('yyyyMM').format(now)
		month.replace('-', '')
	}

	/** YYYYMM -> YYYY-MM for display. */
	static String monthLabel(String period) {
		"${period.substring(0, 4)}-${period.substring(4)}".toString()
	}

	static BigDecimal num(def v) {
		if(v == null || v.toString().trim().isEmpty()) return BigDecimal.ZERO
		new BigDecimal(v.toString().trim())
	}

	/**
	 * Additional markup input; a decimal comma is accepted (7,5 = 7.5). Empty = 0. Throws
	 * NumberFormatException for anything else: text, a sign, an exponent, more than
	 * {@link #MARKUP_DECIMALS} decimals (trailing zeros do not count) or more than
	 * {@link #MAX_MARKUP_PERCENT}.
	 */
	static BigDecimal parsePercent(String v) {
		if(v == null || v.trim().isEmpty()) return BigDecimal.ZERO
		String s = v.trim()
		if(!(s ==~ MARKUP_PATTERN)) throw new NumberFormatException("Not a markup percentage: ${s}")
		BigDecimal p = new BigDecimal(s.replace(',', '.'))
		if(p > MAX_MARKUP_PERCENT) throw new NumberFormatException("Markup above ${MAX_MARKUP_PERCENT} %: ${s}")
		if(p.stripTrailingZeros().scale() > MARKUP_DECIMALS) throw new NumberFormatException("Markup with more than ${MARKUP_DECIMALS} decimals: ${s}")
		p
	}

	/** Rounded to cents, half up. */
	static BigDecimal r2(BigDecimal v) {
		v.setScale(2, RoundingMode.HALF_UP)
	}

	/** Machine-readable amount for CSV export: two decimals, dot, no grouping. */
	static String plain(BigDecimal v) {
		r2(v).toPlainString()
	}

	/** part / whole in percent with one decimal, '0.0' for an empty or negative whole. */
	static String pctText(BigDecimal part, BigDecimal whole) {
		whole > 0 ? (part * 100G / whole).setScale(1, RoundingMode.HALF_UP).toPlainString() : '0.0'
	}

	/** 1 + percent / 100, for the additional markup on the list price. */
	static BigDecimal markupFactor(BigDecimal percent) {
		BigDecimal.ONE + (percent ?: BigDecimal.ZERO).divide(100G, 6, RoundingMode.HALF_UP)
	}

	/** Currency rule shared by the FinOps plugins: line currency, else master tenant currency, else USD. */
	static String resolveCurrency(def lineCurrency, def masterCurrency) {
		String line = lineCurrency?.toString()?.trim()
		if(line) return line.toUpperCase()
		String master = masterCurrency?.toString()?.trim()
		if(master) return master.toUpperCase()
		DEFAULT_CURRENCY
	}

	/** Locale-aware amount with exactly two decimals, e.g. 1,234.56 (en) or 1.234,56 (de). */
	static String money(def v, Locale locale) {
		NumberFormat f = NumberFormat.getNumberInstance(locale ?: Locale.ENGLISH)
		f.minimumFractionDigits = 2
		f.maximumFractionDigits = 2
		f.roundingMode = RoundingMode.HALF_UP
		f.format(r2(num(v)))
	}

	/** Locale-aware number with one decimal, for percentages. */
	static String decimal1(def v, Locale locale) {
		NumberFormat f = NumberFormat.getNumberInstance(locale ?: Locale.ENGLISH)
		f.minimumFractionDigits = 1
		f.maximumFractionDigits = 1
		f.roundingMode = RoundingMode.HALF_UP
		f.format(num(v))
	}

	/**
	 * Locale-aware number with up to {@link #MARKUP_DECIMALS} decimals and no trailing zeros, for
	 * the markup option: the same precision the invoice amount is calculated with.
	 */
	static String decimalShort(def v, Locale locale) {
		NumberFormat f = NumberFormat.getNumberInstance(locale ?: Locale.ENGLISH)
		f.minimumFractionDigits = 0
		f.maximumFractionDigits = MARKUP_DECIMALS
		f.roundingMode = RoundingMode.HALF_UP
		f.format(num(v))
	}

	private static Map newSum() {
		[cost: 0G, price: 0G, margin: 0G, invoice: 0G, resources: 0]
	}

	/**
	 * Aggregates raw invoice rows (one per tenant, group and raw currency, as the SQL returns
	 * them) into the three report levels. Each input row needs tenant (name), isMaster, grp (null
	 * for servers without a group), currency (raw, may be empty), resources, cost and price;
	 * tenantId is optional and keeps two tenants with the same name apart, grpId likewise keeps
	 * two groups with the same name apart. Without grpId the group name is the key. A row without
	 * a group name belongs to the one line for servers without a group, whatever its grpId: server
	 * invoices can carry a group id with an empty group name, and each such id would otherwise
	 * show as a line of its own that reads the same.
	 *
	 * Rows whose raw currency is empty are resolved with {@link #resolveCurrency} and merged with
	 * rows that already carry that currency, so a group never shows twice for one currency.
	 *
	 * @return [lines: [...], tenants: [...], totals: [...]], every amount a BigDecimal rounded to
	 *         cents, in the order the rows came in.
	 */
	static Map aggregate(List<Map> rawRows, String masterCurrency, BigDecimal markupPercent, boolean includeProvider) {
		BigDecimal factor = markupFactor(markupPercent)
		Map<List, Map> lines = new LinkedHashMap<List, Map>()
		rawRows.each { Map r ->
			if(!includeProvider && isTrue(r.isMaster)) return
			String cur = resolveCurrency(r.currency, masterCurrency)
			String grp = r.grp?.toString()?.trim() ?: null
			def tenantKey = r.tenantId != null ? r.tenantId.toString() : r.tenant?.toString()
			String groupKey = !grp ? null : (r.grpId != null ? "id:${r.grpId}".toString() : "name:${grp}".toString())
			List key = [tenantKey, groupKey, cur]
			Map line = lines.get(key)
			if(line == null) {
				line = [tenantKey: tenantKey, tenant: r.tenant?.toString(), group: grp, currency: cur, cost: 0G, price: 0G, resources: 0]
				lines.put(key, line)
			}
			line.cost += num(r.cost)
			line.price += num(r.price)
			line.resources += (num(r.resources) as Integer)
		}
		Map<List, Map> perTenant = new LinkedHashMap<List, Map>()
		Map<String, Map> totals = new LinkedHashMap<String, Map>()
		List<Map> out = []
		lines.values().each { Map l ->
			BigDecimal cost = r2(l.cost as BigDecimal), price = r2(l.price as BigDecimal)
			BigDecimal margin = price - cost
			BigDecimal invoice = r2(price * factor)
			out << [tenantKey: l.tenantKey, tenant: l.tenant, group: l.group, currency: l.currency, resources: l.resources,
				cost: cost, price: price, margin: margin, invoice: invoice]
			List tk = [l.tenantKey, l.currency]
			Map t = perTenant.get(tk)
			if(t == null) { t = newSum() + [tenant: l.tenant]; perTenant.put(tk, t) }
			Map c = totals.get(l.currency)
			if(c == null) { c = newSum(); totals.put(l.currency as String, c) }
			[t, c].each { Map s ->
				s.cost += cost; s.price += price; s.margin += margin; s.invoice += invoice; s.resources += l.resources
			}
		}
		[
			lines  : out,
			tenants: perTenant.collect { k, v -> [tenant: v.tenant, currency: k[1]] + v },
			totals : totals.collect { k, v -> [currency: k] + v }
		]
	}

	/** Number of distinct tenants among the aggregated lines. */
	static int tenantCount(List<Map> lines) {
		lines.collect { it.tenantKey }.unique().size()
	}
}
