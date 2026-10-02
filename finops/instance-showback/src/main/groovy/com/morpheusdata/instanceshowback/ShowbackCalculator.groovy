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
package com.morpheusdata.instanceshowback

import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure logic of the Costs tab: currency resolution, grouping per month and currency,
 * rounding, number formatting and the view model. No Morpheus or database access, so
 * everything here is unit tested.
 *
 * Amounts in different currencies are never added up or compared: every sum and every
 * bar scale is per currency.
 */
class ShowbackCalculator {

	/** Last resort when neither the invoice row nor the master tenant names a currency. */
	static final String DEFAULT_CURRENCY = 'USD'

	/** Number format when no locale is given. */
	static final Locale DEFAULT_LOCALE = Locale.ENGLISH

	static final List<String> AMOUNT_FIELDS = ['price', 'running', 'compute', 'storage', 'license']

	/** Footer time of the last cost run; the template appends "UTC". */
	static final DateTimeFormatter UTC_MINUTE = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm', Locale.ROOT)

	/** null, empty and non-numeric values count as zero. */
	static BigDecimal num(def v) {
		if (v == null) return 0G
		if (v instanceof BigDecimal) return (BigDecimal) v
		try {
			return new BigDecimal(v.toString().trim())
		} catch (NumberFormatException ignored) {
			return 0G
		}
	}

	/** Rounds to cents, half up. */
	static BigDecimal round2(def v) {
		num(v).setScale(2, RoundingMode.HALF_UP)
	}

	static Locale localeOrDefault(Locale locale) {
		locale ?: DEFAULT_LOCALE
	}

	/** Two decimals with grouping in the viewer's locale: 1,234.56 (en) or 1.234,56 (de). */
	static String money(def v, Locale locale) {
		String.format(localeOrDefault(locale), '%,.2f', round2(v))
	}

	/** An ISO 4217 style code (three letters, upper-cased) or null. */
	static String iso(def code) {
		String c = code?.toString()?.trim()?.toUpperCase(Locale.ROOT)
		(c ==~ /[A-Z]{3}/) ? c : null
	}

	/** The shared FinOps currency rule: row currency, else master tenant currency, else USD. */
	static String resolveCurrency(def rowCurrency, def masterCurrency) {
		iso(rowCurrency) ?: iso(masterCurrency) ?: DEFAULT_CURRENCY
	}

	/** The billing periods (yyyyMM) of the current month and the n-1 months before it, newest first. */
	static List<String> lastPeriods(int n, Calendar now) {
		(0..<n).collect { int i ->
			Calendar x = (Calendar) now.clone()
			x.set(Calendar.DAY_OF_MONTH, 1)
			x.add(Calendar.MONTH, -i)
			String.format(Locale.ROOT, '%04d%02d', x.get(Calendar.YEAR), x.get(Calendar.MONTH) + 1)
		}
	}

	/**
	 * A last_cost_date value as a date-time in UTC, or null.
	 *
	 * The JDBC driver may hand the DATETIME column over as java.sql.Timestamp or as
	 * java.time.LocalDateTime, depending on driver and version:
	 * - Timestamp (any java.util.Date), Instant, OffsetDateTime, ZonedDateTime carry an
	 *   instant and are converted to UTC. Timestamp.toString() would use the JVM time zone.
	 * - LocalDateTime is the column's wall clock without a zone. Morpheus writes these
	 *   columns in UTC (its JVM runs with user.timezone=UTC), so it is taken as UTC as is.
	 * - Text in the form yyyy-MM-dd HH:mm[...] is taken as a UTC wall clock as well.
	 * Anything else gives null.
	 */
	static LocalDateTime utcDateTime(def v) {
		if (v == null) return null
		if (v instanceof LocalDateTime) return (LocalDateTime) v
		if (v instanceof Date) return LocalDateTime.ofInstant(((Date) v).toInstant(), ZoneOffset.UTC)
		if (v instanceof Instant) return LocalDateTime.ofInstant((Instant) v, ZoneOffset.UTC)
		if (v instanceof OffsetDateTime) return ((OffsetDateTime) v).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
		if (v instanceof ZonedDateTime) return ((ZonedDateTime) v).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
		String t = v.toString().trim().replace('T', ' ')
		if (t.length() < 16) return null
		try {
			return LocalDateTime.parse(t.take(16), UTC_MINUTE)
		} catch (DateTimeParseException ignored) {
			return null
		}
	}

	/** yyyy-MM-dd HH:mm in UTC, or '-' when the value is missing or not a date-time. */
	static String utcText(def v) {
		LocalDateTime t = utcDateTime(v)
		t != null ? t.format(UTC_MINUTE) : '-'
	}

	/** True when a is a later date-time than b; a value that is not a date-time never wins. */
	static boolean later(def a, def b) {
		LocalDateTime ta = utcDateTime(a), tb = utcDateTime(b)
		ta != null && (tb == null || ta.isAfter(tb))
	}

	/** yyyyMM to yyyy-MM. */
	static String monthText(String period) {
		(period?.length() == 6) ? "${period.substring(0, 4)}-${period.substring(4)}".toString() : (period ?: '-')
	}

	/**
	 * Applies the currency rule to every row and merges rows that end up in the same
	 * period and currency (a row without currency and a row in the fallback currency).
	 * Amounts are summed only inside one currency. Result is sorted by period (newest
	 * first), then currency.
	 */
	static List<Map> groupByPeriodAndCurrency(List<Map> rows, def masterCurrency) {
		Map<String, Map> merged = new LinkedHashMap<>()
		(rows ?: []).each { Map r ->
			String period = r.period?.toString()
			String currency = resolveCurrency(r.currency, masterCurrency)
			String key = "${period}|${currency}".toString()
			Map m = merged[key]
			if (m == null) {
				m = [period: period, currency: currency, plan: null, updated: null]
				AMOUNT_FIELDS.each { m[it] = 0G }
				merged[key] = m
			}
			AMOUNT_FIELDS.each { m[it] = (m[it] as BigDecimal) + num(r[it]) }
			if (r.plan && !m.plan) m.plan = r.plan.toString()
			if (later(r.updated, m.updated)) m.updated = r.updated
		}
		merged.values().sort { Map a, Map b -> (b.period <=> a.period) ?: (a.currency <=> b.currency) }
	}

	/** The highest monthly price per currency, the scale of the history bars. */
	static Map<String, BigDecimal> maxByCurrency(List<Map> grouped) {
		Map<String, BigDecimal> max = [:]
		grouped.each { Map r ->
			BigDecimal p = num(r.price)
			String cu = r.currency as String
			if (!max.containsKey(cu) || p > max[cu]) max[cu] = p
		}
		max
	}

	/** Bar width in percent of the currency's maximum, 0..100, half up. */
	static int barPercent(def value, def max) {
		BigDecimal mx = num(max), v = num(value)
		if (mx <= 0G || v <= 0G) return 0
		int pct = (v * 100G / mx).setScale(0, RoundingMode.HALF_UP).intValue()
		Math.min(100, Math.max(0, pct))
	}

	/**
	 * Builds the template model.
	 *
	 * @param rows     invoice sums per period and currency (keys period, currency, price,
	 *                 running, compute, storage, license, plan, updated); currency may be null
	 * @param periods  from {@link #lastPeriods}, current month first
	 * @param masterCurrency currency of the master tenant, may be null
	 * @param locale   content locale (see {@link ShowbackLocale#resolve}) for number formats
	 *                 and the messages in t, may be null (English)
	 * @param now      clock for the day-of-month counter
	 */
	static Map buildModel(List<Map> rows, List<String> periods, def masterCurrency, Locale locale, Calendar now) {
		List<Map> grouped = groupByPeriodAndCurrency(rows, masterCurrency)
		String currentPeriod = periods ? periods[0] : null
		List<String> currencies = grouped.collect { it.currency as String }.unique().sort()
		List<Map> curRows = grouped.findAll { it.period == currentPeriod }
		List<Map> current = curRows.collect { Map r ->
			[currency    : r.currency,
			 runningText : money(r.running, locale),
			 forecastText: money(r.price, locale),
			 computeText : money(r.compute, locale),
			 storageText : money(r.storage, locale),
			 licenseText : money(r.license, locale)]
		}
		Map<String, BigDecimal> maxBy = maxByCurrency(grouped)
		List<Map> history = periods.collect { String p ->
			List<Map> amounts = grouped.findAll { it.period == p }.collect { Map r ->
				[currency : r.currency,
				 priceText: money(r.price, locale),
				 bar      : barPercent(r.price, maxBy[r.currency as String])]
			}
			[month: monthText(p), amounts: amounts, has: !amounts.isEmpty(), current: p == currentPeriod]
		}
		def updated = curRows.collect { it.updated }.inject(null) { def best, def u -> later(u, best) ? u : best }
		[
			hasData      : !grouped.isEmpty(),
			hasCurrent   : !current.isEmpty(),
			multiCurrency: currencies.size() > 1,
			currencies   : currencies.join(', '),
			plan         : curRows.collect { it.plan }.find { it } ?: '-',
			current      : current,
			day          : now.get(Calendar.DAY_OF_MONTH),
			days         : now.getActualMaximum(Calendar.DAY_OF_MONTH),
			history      : history,
			updated      : utcText(updated),
			t            : ShowbackLocale.messages(locale)
		]
	}
}
