package com.morpheusdata.instanceshowback

import spock.lang.Specification
import spock.lang.Unroll

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ShowbackCalculatorSpec extends Specification {

	static Calendar cal(int y, int m, int d) {
		Calendar c = Calendar.getInstance(TimeZone.getTimeZone('UTC'), Locale.ROOT)
		c.clear()
		c.set(y, m - 1, d, 12, 0, 0)
		c
	}

	@Unroll
	def "money(#value, #locale) = #text"() {
		expect:
		ShowbackCalculator.money(value, locale) == text

		where:
		value          | locale         || text
		1234.565G      | Locale.ENGLISH || '1,234.57'
		1234.565G      | Locale.GERMAN  || '1.234,57'
		'1234567.004'  | Locale.GERMANY || '1.234.567,00'
		0.005G         | Locale.ENGLISH || '0.01'
		-2.345G        | Locale.ENGLISH || '-2.35'
		null           | Locale.ENGLISH || '0.00'
		'n/a'          | Locale.ENGLISH || '0.00'
		1000G          | null           || '1,000.00'
	}

	def "round2 rounds half up to cents"() {
		expect:
		ShowbackCalculator.round2('2.675') == 2.68G
		ShowbackCalculator.round2(2.674G) == 2.67G
		ShowbackCalculator.round2(null) == 0.00G
	}

	@Unroll
	def "currency rule: row #row, master #master -> #expected"() {
		expect:
		ShowbackCalculator.resolveCurrency(row, master) == expected

		where:
		row    | master | expected
		'EUR'  | 'CHF'  | 'EUR'
		'eur ' | 'CHF'  | 'EUR'
		null   | 'CHF'  | 'CHF'
		''     | 'chf'  | 'CHF'
		'EURO' | 'GBP'  | 'GBP'
		null   | null   | 'USD'
		''     | ''     | 'USD'
	}

	def "lastPeriods walks back across the year boundary"() {
		expect:
		ShowbackCalculator.lastPeriods(4, cal(2026, 2, 28)) == ['202602', '202601', '202512', '202511']
	}

	def "lastPeriods does not skip February when today is the 31st"() {
		expect:
		ShowbackCalculator.lastPeriods(3, cal(2026, 3, 31)) == ['202603', '202602', '202601']
	}

	def "monthText formats yyyyMM"() {
		expect:
		ShowbackCalculator.monthText('202610') == '2026-10'
		ShowbackCalculator.monthText(null) == '-'
	}

	def "rows without currency fall back and merge with rows in that currency, never across currencies"() {
		given:
		List<Map> rows = [
			[period: '202610', currency: 'EUR', price: 10.10G, running: 5G, compute: 3G, storage: 1G, license: 1G, plan: 'Small', updated: '2026-10-01 03:00:00'],
			[period: '202610', currency: null, price: 1.01G, running: 0.5G, compute: 0.3G, storage: 0.1G, license: 0.1G, plan: null, updated: '2026-10-01 04:00:00'],
			[period: '202610', currency: 'USD', price: 7G, running: 2G, compute: 2G, storage: 0G, license: 0G],
			[period: '202609', currency: 'EUR', price: 20G, running: 20G, compute: 18G, storage: 2G, license: 0G]
		]

		when:
		List<Map> g = ShowbackCalculator.groupByPeriodAndCurrency(rows, 'eur')

		then:
		g*.period == ['202610', '202610', '202609']
		g*.currency == ['EUR', 'USD', 'EUR']
		g[0].price == 11.11G
		g[0].running == 5.5G
		g[0].plan == 'Small'
		g[0].updated == '2026-10-01 04:00:00'
		g[1].price == 7G
	}

	def "bars scale per currency"() {
		expect:
		ShowbackCalculator.barPercent(50G, 200G) == 25
		ShowbackCalculator.barPercent(1G, 3G) == 33
		ShowbackCalculator.barPercent(2G, 3G) == 67
		ShowbackCalculator.barPercent(5G, 0G) == 0
		ShowbackCalculator.barPercent(-1G, 10G) == 0
		ShowbackCalculator.barPercent(12G, 10G) == 100
		ShowbackCalculator.maxByCurrency([[currency: 'EUR', price: 5G], [currency: 'USD', price: 900G], [currency: 'EUR', price: 8G]]) ==
			[EUR: 8G, USD: 900G]
	}

	def "buildModel keeps currencies apart and formats in the viewer's locale"() {
		given:
		List<String> periods = ShowbackCalculator.lastPeriods(4, cal(2026, 10, 15))
		List<Map> rows = [
			[period: '202610', currency: 'EUR', price: 1500G, running: 725.5G, compute: 600G, storage: 100G, license: 25.5G, plan: 'Medium', updated: '2026-10-15 02:00:00.0'],
			[period: '202610', currency: 'USD', price: 10G, running: 5G, compute: 5G, storage: 0G, license: 0G],
			[period: '202608', currency: 'EUR', price: 3000G, running: 3000G, compute: 2900G, storage: 100G, license: 0G]
		]

		when:
		Map m = ShowbackCalculator.buildModel(rows, periods, null, Locale.GERMAN, cal(2026, 10, 15))

		then:
		m.hasData
		m.hasCurrent
		m.multiCurrency
		m.currencies == 'EUR, USD'
		m.plan == 'Medium'
		m.day == 15
		m.days == 31
		m.updated == '2026-10-15 02:00'
		m.current*.currency == ['EUR', 'USD']
		m.current[0].runningText == '725,50'
		m.current[0].forecastText == '1.500,00'
		m.history*.month == ['2026-10', '2026-09', '2026-08', '2026-07']
		m.history*.has == [true, false, true, false]
		m.history[0].current
		!m.history[1].current
		// EUR bars relative to the EUR maximum (3000), USD bar relative to USD only
		m.history[0].amounts.find { it.currency == 'EUR' }.bar == 50
		m.history[0].amounts.find { it.currency == 'USD' }.bar == 100
		m.history[2].amounts[0].bar == 100
	}

	def "buildModel without invoices"() {
		when:
		Map m = ShowbackCalculator.buildModel([], ['202610', '202609'], null, null, cal(2026, 10, 1))

		then:
		!m.hasData
		!m.hasCurrent
		!m.multiCurrency
		m.plan == '-'
		m.updated == '-'
		m.history*.has == [false, false]
	}

	def "a row without currency uses the master currency, else USD"() {
		expect:
		ShowbackCalculator.buildModel([[period: '202610', price: 1G]], ['202610'], 'CHF', Locale.ENGLISH, cal(2026, 10, 1)).currencies == 'CHF'
		ShowbackCalculator.buildModel([[period: '202610', price: 1G]], ['202610'], null, Locale.ENGLISH, cal(2026, 10, 1)).currencies == 'USD'
	}

	/** Runs the closure with a non-UTC JVM default time zone and restores the old one. */
	static <T> T inTimeZone(String zone, Closure<T> body) {
		TimeZone saved = TimeZone.getDefault()
		try {
			TimeZone.setDefault(TimeZone.getTimeZone(zone))
			return body.call()
		} finally {
			TimeZone.setDefault(saved)
		}
	}

	static Map row(def updated) {
		[period: '202610', currency: 'EUR', price: 1G, running: 1G, updated: updated]
	}

	def "the footer time of a Timestamp is UTC, not the JVM time zone"() {
		given:
		Timestamp ts = Timestamp.from(Instant.parse('2026-10-15T02:00:00Z'))

		when:
		Map m = inTimeZone('Europe/Berlin') {
			ShowbackCalculator.buildModel([row(ts)], ['202610'], null, Locale.ENGLISH, cal(2026, 10, 15))
		}

		then:
		m.updated == '2026-10-15 02:00'
	}

	def "a Timestamp just before midnight UTC keeps its UTC date in a zone east of UTC"() {
		expect:
		inTimeZone('Asia/Tokyo') {
			ShowbackCalculator.utcText(Timestamp.from(Instant.parse('2026-10-31T23:30:00Z')))
		} == '2026-10-31 23:30'
	}

	def "a LocalDateTime is the stored UTC wall clock and is not shifted by the JVM time zone"() {
		when:
		Map m = inTimeZone('America/New_York') {
			ShowbackCalculator.buildModel([row(LocalDateTime.of(2026, 10, 15, 2, 0, 59))], ['202610'], null, Locale.ENGLISH, cal(2026, 10, 15))
		}

		then:
		m.updated == '2026-10-15 02:00'
	}

	@Unroll
	def "utcText(#value) = #text"() {
		expect:
		inTimeZone('Europe/Berlin') { ShowbackCalculator.utcText(value) } == text

		where:
		value                                                              || text
		null                                                               || '-'
		Instant.parse('2026-01-01T00:05:00Z')                              || '2026-01-01 00:05'
		OffsetDateTime.of(2026, 1, 1, 1, 5, 0, 0, ZoneOffset.ofHours(1))   || '2026-01-01 00:05'
		new Date(Instant.parse('2026-07-01T10:00:00Z').toEpochMilli())     || '2026-07-01 10:00'
		'2026-10-15 02:00:00.0'                                            || '2026-10-15 02:00'
		'2026-10-15T02:00'                                                 || '2026-10-15 02:00'
		'not a date'                                                       || '-'
	}

	/*
	 * In the cases below the text order and the time order disagree: the Timestamp is the
	 * later cost run (03:00 UTC), but its text '2026-10-15 05:00:00.0' (Berlin) sorts before
	 * the LocalDateTime's '2026-10-15T02:00' because ' ' < 'T'.
	 */

	def "the latest cost run wins across currencies, compared as instants, not as text"() {
		given:
		Timestamp late = Timestamp.from(Instant.parse('2026-10-15T03:00:00Z'))
		LocalDateTime early = LocalDateTime.of(2026, 10, 15, 2, 0)

		when:
		Map m = inTimeZone('Europe/Berlin') {
			ShowbackCalculator.buildModel([row(early), [period: '202610', currency: 'USD', price: 1G, updated: late]],
				['202610'], null, Locale.ENGLISH, cal(2026, 10, 15))
		}

		then:
		m.multiCurrency
		m.updated == '2026-10-15 03:00'
	}

	@Unroll
	def "merging rows of one month and currency keeps the latest cost run (#order)"() {
		given:
		Timestamp late = Timestamp.from(Instant.parse('2026-10-15T03:00:00Z'))
		LocalDateTime early = LocalDateTime.of(2026, 10, 15, 2, 0)
		List<Map> rows = (order == 'later first' ? [late, early] : [early, late]).collect { row(it) }

		when:
		List<Map> grouped = inTimeZone('Europe/Berlin') {
			ShowbackCalculator.groupByPeriodAndCurrency(rows, null)
		}

		then:
		grouped.size() == 1
		grouped[0].price == 2G
		grouped[0].updated.is(late)
		ShowbackCalculator.utcText(grouped[0].updated) == '2026-10-15 03:00'

		where:
		order << ['later first', 'earlier first']
	}

	def "every message the template uses exists in the English bundle, and none goes through the i18n helper"() {
		given:
		String hbs = ShowbackCalculatorSpec.classLoader.getResourceAsStream('renderer/hbs/instanceShowback.hbs').text
		Properties en = new Properties()
		ShowbackCalculatorSpec.classLoader.getResourceAsStream('i18n/messages.properties').withCloseable { en.load(it) }
		Set<String> used = (hbs =~ /\{\{(?:\.\.\/)?t\.([A-Za-z]+)\}\}/).collect { 'instance-showback-tab.' + it[1] } as Set

		expect:
		!used.isEmpty()
		// The helper takes the browser language, not the user's Morpheus setting.
		!hbs.contains('{{i18n')
		en.stringPropertyNames().containsAll(used)
	}
}
