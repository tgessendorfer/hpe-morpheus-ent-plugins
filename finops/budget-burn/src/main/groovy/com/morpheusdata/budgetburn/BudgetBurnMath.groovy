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
package com.morpheusdata.budgetburn

import java.math.RoundingMode

/**
 * Pure calculation and formatting helpers of the Budget Burn page. No database or
 * Morpheus context here, so every rule is covered by unit tests.
 */
class BudgetBurnMath {

	/** Last resort of the currency rule: line currency, then master tenant currency, then this. */
	static final String DEFAULT_CURRENCY = 'USD'

	/** Forecast share of the monthly budget (percent) from which a budget is flagged as warning. */
	static final BigDecimal WARNING_PCT = 80G

	/** Forecast share of the monthly budget (percent) above which a budget is over budget. */
	static final BigDecimal OVER_PCT = 100G

	static final String STATUS_OK = 'ok'
	static final String STATUS_WARNING = 'warning'
	static final String STATUS_OVER = 'over'
	static final String STATUS_MISMATCH = 'mismatch'

	// Mid tones that stay readable on a light and on a dark page. The Morpheus theme variables are not
	// used: on 9.0.2 they can switch to dark while the page around the plugin stays light.
	static final Map<String, String> STATUS_COLORS = [
		(STATUS_OK): '#27AE60', (STATUS_WARNING): '#E67E22', (STATUS_OVER): '#C0392B', (STATUS_MISMATCH): '#7F8C8D'
	].asImmutable()

	static final List<String> SCOPES = ['tenant', 'account', 'group', 'cloud', 'user'].asImmutable()

	static BigDecimal num(def v) {
		v == null ? 0.00G : new BigDecimal(v.toString())
	}

	/** The first non-blank ISO code from the candidates, upper-cased; otherwise USD. */
	static String resolveCurrency(String... candidates) {
		String hit = candidates?.find { it != null && it.trim() }
		hit ? hit.trim().toUpperCase(Locale.ROOT) : DEFAULT_CURRENCY
	}

	/** Amount with two decimals and grouping, in the viewer's locale. */
	static String money(BigDecimal v, Locale locale) {
		String.format(locale ?: Locale.ENGLISH, '%,.2f', (v ?: 0.00G).setScale(2, RoundingMode.HALF_UP))
	}

	/** Percentage value with one decimal, in the viewer's locale. */
	static String pctText(BigDecimal v, Locale locale) {
		String.format(locale ?: Locale.ENGLISH, '%.1f', (v ?: 0.00G).setScale(1, RoundingMode.HALF_UP))
	}

	/** a as percent of b, one decimal, half up; 0 when b is not positive. */
	static BigDecimal pct(BigDecimal a, BigDecimal b) {
		(b != null && b > 0.00G) ? ((a ?: 0.00G) * 100G / b).setScale(1, RoundingMode.HALF_UP) : 0.00G
	}

	/** Average spend per elapsed day of the month, two decimals, half up. */
	static BigDecimal burnRate(BigDecimal running, int dayOfMonth) {
		(running ?: 0.00G).divide(new BigDecimal(Math.max(dayOfMonth, 1)), 2, RoundingMode.HALF_UP)
	}

	/**
	 * a as percent of b for display, e.g. "81.3 %"; "-" when b is not positive, since a
	 * share of a budget of 0 has no meaningful number.
	 */
	static String pctLabel(BigDecimal a, BigDecimal b, Locale locale) {
		hasBudget(b) ? "${pctText(pct(a, b), locale)} %".toString() : '-'
	}

	/** Bar width in percent of the cell, capped at 100 and never negative. */
	static int barWidth(BigDecimal forecastPct) {
		Math.max(0, Math.min((forecastPct ?: 0.00G).intValue(), 100))
	}

	/** Bar width of a forecast against a budget: full when there is spend but no budget. */
	static int barWidth(BigDecimal forecast, BigDecimal budget) {
		hasBudget(budget) ? barWidth(pct(forecast, budget)) : (positive(forecast) ? 100 : 0)
	}

	/**
	 * Status of a budget for the current month. A currency mismatch wins, since no
	 * comparison is possible; otherwise forecast &gt; 100 % is over, &gt;= 80 % is warning.
	 */
	static String status(BigDecimal forecastPct, boolean mismatch) {
		if(mismatch) return STATUS_MISMATCH
		BigDecimal p = forecastPct ?: 0.00G
		if(p > OVER_PCT) return STATUS_OVER
		if(p >= WARNING_PCT) return STATUS_WARNING
		STATUS_OK
	}

	/**
	 * Status of a forecast against a monthly budget. Without a positive budget any
	 * forecast spend is over budget, and no spend is on track.
	 */
	static String status(BigDecimal forecast, BigDecimal budget, boolean mismatch) {
		if(mismatch) return STATUS_MISMATCH
		if(!hasBudget(budget)) return positive(forecast) ? STATUS_OVER : STATUS_OK
		status(pct(forecast, budget), false)
	}

	static boolean hasBudget(BigDecimal budget) {
		positive(budget)
	}

	private static boolean positive(BigDecimal v) {
		v != null && v > 0.00G
	}

	static String statusColor(String status) {
		STATUS_COLORS[status] ?: STATUS_COLORS[STATUS_MISMATCH]
	}

	/** i18n key of a budget scope; unknown scopes map to the generic "other" key. */
	static String scopeKey(String scope) {
		"${BudgetBurnAnalyticsProvider.PROVIDER_CODE}.scope.${scope in SCOPES ? scope : 'other'}".toString()
	}

	static String statusKey(String status) {
		"${BudgetBurnAnalyticsProvider.PROVIDER_CODE}.status.${status}".toString()
	}

	/**
	 * Monthly share of a budget for month m (1-12) from its periods
	 * (interval_index, cost): monthly as is, quarterly / 3, yearly / 12.
	 */
	static BigDecimal monthlyBudget(String interval, List periods, int m) {
		Map byIdx = periods.collectEntries { [(it.interval_index as Integer): num(it.cost)] }
		switch(interval) {
			case 'quarter': return ((byIdx[((m - 1).intdiv(3)) + 1] ?: 0.00G) as BigDecimal).divide(3G, 2, RoundingMode.HALF_UP)
			case 'year': return ((byIdx[1] ?: 0.00G) as BigDecimal).divide(12G, 2, RoundingMode.HALF_UP)
			default: return (byIdx[m] ?: 0.00G) as BigDecimal
		}
	}

	/**
	 * Budget for months 1..month. Quarterly and yearly shares are summed unrounded and
	 * rounded once, so a full year (or quarter) gives exactly its budget.
	 */
	static BigDecimal budgetToDate(String interval, List periods, int month) {
		Map byIdx = periods.collectEntries { [(it.interval_index as Integer): num(it.cost)] }
		switch(interval) {
			case 'quarter':
				BigDecimal thirds = (1..month).inject(0.00G) { BigDecimal acc, m -> acc + ((byIdx[((m as int) - 1).intdiv(3) + 1] ?: 0.00G) as BigDecimal) } as BigDecimal
				return thirds.divide(3G, 2, RoundingMode.HALF_UP)
			case 'year':
				return (((byIdx[1] ?: 0.00G) as BigDecimal) * month).divide(12G, 2, RoundingMode.HALF_UP)
			default:
				return (1..month).inject(0.00G) { BigDecimal acc, m -> acc + monthlyBudget(interval, periods, m as int) } as BigDecimal
		}
	}

	/**
	 * SQL condition on account_invoice (alias i) for the scope of a budget; adds its parameters.
	 * Spend rule: a budget counts only the invoices of its owner (b.account_id), plus those of
	 * the subtenants when the owner is the master tenant (b.owner_master). Clouds are shared
	 * across tenants, and a tenant's group or user can carry invoices of another account, so a
	 * subtenant's budget always gets the owner restriction on top of its scope.
	 */
	static String scopeCondition(Map b, List params) {
		List scopeParams = []
		String cond
		switch(b.ref_scope) {
			case 'tenant': scopeParams << b.ref_id; cond = 'i.account_id = ?'; break
			case 'group': scopeParams << b.ref_id; cond = 'i.site_id = ?'; break
			case 'cloud': scopeParams << b.ref_id; cond = 'i.zone_id = ?'; break
			case 'user': scopeParams << b.ref_id; cond = 'i.user_id = ?'; break
			default: scopeParams << b.account_id; cond = 'i.account_id = ?'
		}
		boolean ownerOnly = cond == 'i.account_id = ?' && sameId(scopeParams[0], b.account_id)
		if(isMaster(b.owner_master) || ownerOnly) {
			params.addAll(scopeParams)
			return cond
		}
		params << b.account_id
		params.addAll(scopeParams)
		return "i.account_id = ? AND ${cond}".toString()
	}

	/** True for a master-tenant flag as the database returns it (1, true, "1"); null is false. */
	static boolean isMaster(def flag) {
		if(flag instanceof Boolean) return flag
		if(flag instanceof Number) return (flag as Number).intValue() == 1
		return flag != null && flag.toString().trim() in ['1', 'true']
	}

	private static boolean sameId(def a, def b) {
		a != null && b != null && a.toString() == b.toString()
	}

	/**
	 * Sums per currency: [currency: [running, forecast, total]]. A row without a currency
	 * goes to the fallback. Amounts in different currencies are never added up.
	 */
	static Map<String, Map<String, BigDecimal>> byCurrency(List<Map> rows, String fallback) {
		Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>()
		rows.each { r ->
			String cur = resolveCurrency(r.currency as String, fallback)
			Map<String, BigDecimal> m = out.computeIfAbsent(cur) { [running: 0.00G, forecast: 0.00G, total: 0.00G] }
			['running', 'forecast', 'total'].each { k -> if(r.containsKey(k)) m[k] = m[k] + num(r[k]) }
		}
		out
	}

	/** Non-zero amounts in currencies other than the budget currency, e.g. "1.11 USD + 2.00 GBP". */
	static String foreignText(Map<String, Map<String, BigDecimal>> m, String budgetCur, String key, Locale locale) {
		joinForeign(m.collectEntries { k, v -> [(k): v[key] ?: 0.00G] } as Map<String, BigDecimal>, budgetCur, locale)
	}

	/**
	 * Foreign amounts of the year to date: previous months (total) plus the current
	 * month (forecast), per currency other than the budget currency.
	 */
	static Map<String, BigDecimal> ytdForeign(Map<String, Map<String, BigDecimal>> previous,
			Map<String, Map<String, BigDecimal>> current, String budgetCur) {
		Map<String, BigDecimal> out = new LinkedHashMap<>()
		previous.each { k, v -> if(k != budgetCur) out[k] = (out[k] ?: 0.00G) + (v.total ?: 0.00G) }
		current.each { k, v -> if(k != budgetCur) out[k] = (out[k] ?: 0.00G) + (v.forecast ?: 0.00G) }
		out
	}

	static String joinForeign(Map<String, BigDecimal> amounts, String budgetCur, Locale locale) {
		amounts.findAll { k, v -> k != budgetCur && v != null && v.compareTo(0.00G) != 0 }
			.collect { k, v -> "${money(v, locale)} ${k}".toString() }.join(' + ')
	}
}
