package com.morpheusdata.tenantchargeback

import spock.lang.Specification
import spock.lang.Unroll

import static com.morpheusdata.tenantchargeback.ChargebackCalculator.*

class ChargebackCalculatorSpec extends Specification {

	// ------------------------------------------------------------------
	// Options and month
	// ------------------------------------------------------------------

	def "cfg reads top level, then config, then report, trimmed"() {
		expect:
		cfg([chargebackMonth: ' 2026-09 '], 'chargebackMonth') == '2026-09'
		cfg([config: [chargebackMonth: '2026-08']], 'chargebackMonth') == '2026-08'
		cfg([report: [chargebackMonth: '2026-07']], 'chargebackMonth') == '2026-07'
		cfg([chargebackMonth: '2026-01', config: [chargebackMonth: '2026-02']], 'chargebackMonth') == '2026-01'
		cfg([:], 'chargebackMonth') == null
		cfg(null, 'chargebackMonth') == null
		cfg([config: 'not a map'], 'chargebackMonth') == null
	}

	def "opt never calls get() for a missing key"() {
		given: 'a map whose get() throws, like org.grails.web.json.JSONObject'
		Map strict = new LinkedHashMap([a: 1]) {
			@Override Object get(Object key) {
				if(!containsKey(key)) throw new IllegalStateException("no ${key}")
				super.get(key)
			}
		}

		expect:
		opt(strict, 'a') == 1
		opt(strict, 'b') == null
	}

	@Unroll
	def "month '#month' is #valid"() {
		expect:
		isValidMonth(month) == (valid == 'valid')

		where:
		month      | valid
		null       | 'valid'
		''         | 'valid'
		'2026-09'  | 'valid'
		'2026-01'  | 'valid'
		'2026-12'  | 'valid'
		'202609'   | 'valid'
		'2026-00'  | 'invalid'
		'2026-13'  | 'invalid'
		'202613'   | 'invalid'
		'2026-9'   | 'invalid'
		'26-09'    | 'invalid'
		'2026/09'  | 'invalid'
		'2026-09x' | 'invalid'
		'abcd-ef'  | 'invalid'
	}

	def "period turns the month input into the invoice period key"() {
		given:
		Date now = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm').parse('2026-10-01 12:00')

		expect:
		period('2026-09', now) == '202609'
		period('202608', now) == '202608'
		period(null, now) == '202610'
		period('', now) == '202610'
		monthLabel('202609') == '2026-09'
	}

	@Unroll
	def "isTrue('#v') == #expected"() {
		expect:
		isTrue(v) == expected

		where:
		v       | expected
		'on'    | true
		'true'  | true
		true    | true
		'1'     | true
		1       | true
		'off'   | false
		'false' | false
		false   | false
		null    | false
		''      | false
		'ja'    | false
	}

	def "markup percent accepts a decimal comma and rejects text"() {
		expect:
		parsePercent('7,5') == 7.5G
		parsePercent('7.5') == 7.5G
		parsePercent(' 10 ') == 10G
		parsePercent('') == 0G
		parsePercent(null) == 0G

		when:
		parsePercent('ten')

		then:
		thrown(NumberFormatException)
	}

	@Unroll
	def "markup '#v' is accepted as #expected"() {
		expect:
		parsePercent(v) == expected

		where:
		v           | expected
		'0'         | 0G
		'1000'      | 1000G
		'1000.0000' | 1000G
		'7.1234'    | 7.1234G
		'7,12340'   | 7.1234G
		'.5'        | 0.5G
		'5.'        | 5G
	}

	@Unroll
	def "markup '#v' is rejected"() {
		when:
		parsePercent(v)

		then:
		thrown(NumberFormatException)

		where:
		v << ['-5', '-0.5', '+5', '1E3', '1e2', '1e-10', '5e', '1000.0001', '1001', '99999999999', '7.12345', '0.00001', '1.2.3', '7 5', '.']
	}

	// ------------------------------------------------------------------
	// Rounding and percentages
	// ------------------------------------------------------------------

	@Unroll
	def "r2(#v) == #expected (half up to cents)"() {
		expect:
		r2(v) == expected

		where:
		v           | expected
		1.005G      | 1.01G
		1.004G      | 1.00G
		2.675G      | 2.68G
		-1.005G     | -1.01G
		0G          | 0.00G
		123456.785G | 123456.79G
	}

	def "plain is machine readable: two decimals, dot, no grouping"() {
		expect:
		plain(1234567.891G) == '1234567.89'
		plain(0G) == '0.00'
		plain(5G) == '5.00'
	}

	@Unroll
	def "pctText(#part, #whole) == '#expected'"() {
		expect:
		pctText(part, whole) == expected

		where:
		part  | whole | expected
		25G   | 100G  | '25.0'
		1G    | 3G    | '33.3'
		2G    | 3G    | '66.7'
		0G    | 100G  | '0.0'
		10G   | 0G    | '0.0'
		10G   | -5G   | '0.0'
		-5G   | 100G  | '-5.0'
	}

	def "markupFactor adds the percentage to one"() {
		expect:
		markupFactor(0G) == 1G
		markupFactor(null) == 1G
		markupFactor(12.5G) == 1.125G
		markupFactor(7G) == 1.07G
	}

	// ------------------------------------------------------------------
	// Currency rule
	// ------------------------------------------------------------------

	@Unroll
	def "resolveCurrency(#line, #master) == #expected"() {
		expect:
		resolveCurrency(line, master) == expected

		where:
		line   | master | expected
		'EUR'  | 'CHF'  | 'EUR'
		'eur'  | null   | 'EUR'
		null   | 'CHF'  | 'CHF'
		''     | 'chf'  | 'CHF'
		'  '   | 'GBP'  | 'GBP'
		null   | null   | 'USD'
		''     | ''     | 'USD'
	}

	// ------------------------------------------------------------------
	// Locale-aware formatting
	// ------------------------------------------------------------------

	def "money follows the viewer's locale with two decimals"() {
		expect:
		money('1234567.891', Locale.ENGLISH) == '1,234,567.89'
		money('1234567.891', Locale.GERMAN) == '1.234.567,89'
		money('0', Locale.GERMAN) == '0,00'
		money('-1.005', Locale.ENGLISH) == '-1.01'
		money(null, Locale.ENGLISH) == '0.00'
		money('12.5', null) == '12.50'
	}

	def "percent texts follow the viewer's locale"() {
		expect:
		decimal1('33.3', Locale.ENGLISH) == '33.3'
		decimal1('33.3', Locale.GERMAN) == '33,3'
		decimal1('1234.5', Locale.GERMAN) == '1.234,5'
		decimalShort('7.5', Locale.GERMAN) == '7,5'
		decimalShort('10', Locale.ENGLISH) == '10'
		decimalShort('0', Locale.ENGLISH) == '0'
	}

	def "the markup is shown with the four decimals it is calculated with"() {
		expect:
		decimalShort('7.1234', Locale.ENGLISH) == '7.1234'
		decimalShort('7.1234', Locale.GERMAN) == '7,1234'
		decimalShort('0.0005', Locale.ENGLISH) == '0.0005'
		decimalShort('1000', Locale.GERMAN) == '1.000'
		markupFactor(7.1234G) == 1.071234G
	}

	// ------------------------------------------------------------------
	// Aggregation
	// ------------------------------------------------------------------

	private static Map row(Map m) {
		[tenantId: 2, tenant: 'Tenant A', isMaster: false, grp: 'Group 1', currency: 'EUR', resources: 1, cost: '0', price: '0'] + m
	}

	def "amounts in different currencies are never added"() {
		given:
		List<Map> raw = [
			row(currency: 'EUR', cost: '10', price: '15', resources: 2),
			row(currency: 'USD', cost: '20', price: '30', resources: 3),
			row(tenantId: 3, tenant: 'Tenant B', currency: 'EUR', cost: '1', price: '2', resources: 1)
		]

		when:
		Map r = aggregate(raw, 'EUR', 0G, false)

		then:
		r.totals*.currency == ['EUR', 'USD']
		r.totals.find { it.currency == 'EUR' }.with { cost == 11G && price == 17G && margin == 6G && resources == 3 }
		r.totals.find { it.currency == 'USD' }.with { cost == 20G && price == 30G && margin == 10G && resources == 3 }
		r.tenants.size() == 3
		r.tenants.findAll { it.tenant == 'Tenant A' }*.currency == ['EUR', 'USD']
		tenantCount(r.lines) == 2
	}

	def "lines without a currency take the master currency and merge with lines that carry it"() {
		given:
		List<Map> raw = [
			row(currency: 'CHF', cost: '10', price: '12', resources: 1),
			row(currency: null, cost: '5', price: '6', resources: 2),
			row(currency: '', cost: '1', price: '1', resources: 1)
		]

		when:
		Map r = aggregate(raw, 'chf', 0G, false)

		then:
		r.lines.size() == 1
		r.lines[0].currency == 'CHF'
		r.lines[0].resources == 4
		r.lines[0].cost == 16G
		r.lines[0].price == 19G
		r.totals*.currency == ['CHF']
	}

	def "without line and master currency the currency is USD"() {
		when:
		Map r = aggregate([row(currency: null, cost: '1', price: '2')], null, 0G, false)

		then:
		r.totals*.currency == ['USD']
	}

	def "lines are rounded to cents first, so the totals equal the sum of the shown lines"() {
		given: 'three groups of 0.004 each would total 0.01 unrounded, 0.00 from the shown lines'
		List<Map> raw = (1..3).collect { row(grp: "G${it}".toString(), cost: '0.004', price: '0.006') }

		when:
		Map r = aggregate(raw, 'EUR', 0G, false)

		then:
		r.lines*.cost == [0.00G, 0.00G, 0.00G]
		r.lines*.price == [0.01G, 0.01G, 0.01G]
		r.totals[0].cost == 0.00G
		r.totals[0].price == 0.03G
		r.totals[0].margin == 0.03G
		r.totals[0].price == r.lines*.price.sum()
	}

	def "margin is list price minus cost and the invoice applies the additional markup per line"() {
		given:
		List<Map> raw = [
			row(grp: 'G1', cost: '80.004', price: '99.995'),
			row(grp: 'G2', cost: '10', price: '10.01')
		]

		when:
		Map r = aggregate(raw, 'EUR', 7.5G, false)

		then:
		r.lines[0].cost == 80.00G
		r.lines[0].price == 100.00G
		r.lines[0].margin == 20.00G
		r.lines[0].invoice == 107.50G
		r.lines[1].invoice == 10.76G   // 10.01 x 1.075 = 10.76075
		r.totals[0].invoice == 118.26G
		r.totals[0].invoice == r.lines*.invoice.sum()
	}

	def "the master tenant is left out unless included"() {
		given:
		List<Map> raw = [
			row(tenantId: 1, tenant: 'Provider', isMaster: true, cost: '100', price: '100'),
			row(cost: '1', price: '2')
		]

		expect:
		aggregate(raw, 'EUR', 0G, false).lines*.tenant == ['Tenant A']
		aggregate(raw, 'EUR', 0G, true).lines*.tenant == ['Provider', 'Tenant A']
	}

	def "servers without a group keep a null group"() {
		when:
		Map r = aggregate([row(grp: null, cost: '1', price: '1'), row(grp: '  ', cost: '1', price: '1')], 'EUR', 0G, false)

		then:
		r.lines.size() == 1
		r.lines[0].group == null
		r.lines[0].resources == 2
	}

	def "two groups with the same name in one tenant stay apart by group id"() {
		when:
		Map r = aggregate([
			row(grpId: 11, grp: 'Same', cost: '1', price: '2'),
			row(grpId: 12, grp: 'Same', cost: '3', price: '4'),
			row(grpId: 11, grp: 'Same', cost: '5', price: '6')
		], 'EUR', 0G, false)

		then:
		r.lines.size() == 2
		r.lines*.group == ['Same', 'Same']
		r.lines*.cost == [6G, 3G]
		r.lines*.resources == [2, 1]
		r.tenants.size() == 1
		r.tenants[0].cost == 9G
	}

	def "rows of one group id merge into one line"() {
		when:
		Map r = aggregate([row(grpId: 11, grp: 'Group 1', cost: '1', price: '1'), row(grpId: 11, grp: 'Group 1', cost: '2', price: '2')], 'EUR', 0G, false)

		then:
		r.lines.size() == 1
		r.lines[0].group == 'Group 1'
		r.lines[0].cost == 3G
	}

	def "servers without a group stay one line when the rows carry group ids"() {
		when:
		Map r = aggregate([
			row(grpId: null, grp: null, cost: '1', price: '1'),
			row(grpId: null, grp: '', cost: '1', price: '1'),
			row(grpId: 11, grp: 'Group 1', cost: '1', price: '1')
		], 'EUR', 0G, false)

		then:
		r.lines.size() == 2
		r.lines[0].group == null
		r.lines[0].resources == 2
		r.lines[1].group == 'Group 1'
	}

	def "server invoices with a group id but no group name merge into one line per tenant and currency"() {
		given: 'the shape of the query result on 9.0.2: server invoices carry a group id with an empty group name'
		List<Map> raw = [
			row(tenantId: 2, tenant: 'Tenant A', grpId: 1, grp: null, currency: 'USD', resources: 34, cost: '10', price: '20'),
			row(tenantId: 2, tenant: 'Tenant A', grpId: 7, grp: '', currency: 'USD', resources: 3, cost: '1', price: '2'),
			row(tenantId: 2, tenant: 'Tenant A', grpId: 11, grp: 'Group 1', currency: 'USD', resources: 2, cost: '5', price: '6'),
			row(tenantId: 3, tenant: 'Tenant B', grpId: 1, grp: null, currency: 'USD', resources: 1, cost: '1', price: '1'),
			row(tenantId: 3, tenant: 'Tenant B', grpId: 2, grp: '  ', currency: 'USD', resources: 1, cost: '1', price: '1'),
			row(tenantId: 3, tenant: 'Tenant B', grpId: 7, grp: null, currency: 'USD', resources: 3, cost: '1', price: '1')
		]

		when:
		Map r = aggregate(raw, 'EUR', 0G, false)

		then:
		r.lines.size() == 3
		r.lines*.tenant == ['Tenant A', 'Tenant A', 'Tenant B']
		r.lines*.group == [null, 'Group 1', null]
		r.lines*.resources == [37, 2, 5]
		r.lines*.cost == [11G, 5G, 3G]
		r.lines*.price == [22G, 6G, 3G]
		r.tenants*.resources == [39, 5]
		r.totals.size() == 1
		r.totals[0].resources == 44
		r.totals[0].cost == 19G
	}

	def "nameless rows merge per currency, whether their group id is set or null"() {
		when:
		Map r = aggregate([
			row(grpId: 1, grp: null, currency: 'USD', resources: 1, cost: '1', price: '2'),
			row(grpId: null, grp: null, currency: 'USD', resources: 1, cost: '1', price: '2'),
			row(grpId: 7, grp: '', currency: 'EUR', resources: 1, cost: '3', price: '4'),
			row(grpId: 2, grp: null, currency: null, resources: 1, cost: '5', price: '6'),
			row(grpId: 7, grp: null, currency: 'USD', resources: 1, cost: '1', price: '2')
		], 'EUR', 0G, false)

		then: 'one no-group line per currency; the line without a currency falls back to EUR'
		r.lines.size() == 2
		r.lines*.group == [null, null]
		r.lines*.currency == ['USD', 'EUR']
		r.lines*.resources == [3, 2]
		r.lines*.cost == [3G, 8G]
		r.totals*.currency == ['USD', 'EUR']
		r.totals*.price == [6G, 10G]
	}

	def "named groups stay keyed by id while nameless rows merge"() {
		when:
		Map r = aggregate([
			row(grpId: 11, grp: 'Same', cost: '1', price: '1'),
			row(grpId: 1, grp: null, cost: '1', price: '1'),
			row(grpId: 12, grp: 'Same', cost: '2', price: '2'),
			row(grpId: 2, grp: null, cost: '1', price: '1'),
			row(grpId: null, grp: 'Named without id', cost: '4', price: '4'),
			row(grpId: null, grp: 'Named without id', cost: '4', price: '4')
		], 'EUR', 0G, false)

		then:
		r.lines*.group == ['Same', null, 'Same', 'Named without id']
		r.lines*.cost == [1G, 2G, 2G, 8G]
		r.lines*.resources == [1, 2, 1, 2]
	}

	def "two tenants with the same name stay apart by id"() {
		when:
		Map r = aggregate([row(tenantId: 5, tenant: 'Same'), row(tenantId: 6, tenant: 'Same')], 'EUR', 0G, false)

		then:
		r.lines.size() == 2
		r.tenants.size() == 2
		tenantCount(r.lines) == 2
	}

	def "an empty month gives empty lists"() {
		when:
		Map r = aggregate([], 'EUR', 0G, false)

		then:
		r.lines.isEmpty()
		r.tenants.isEmpty()
		r.totals.isEmpty()
		tenantCount(r.lines) == 0
	}

	// ------------------------------------------------------------------
	// Group names looked up by group id (INVOICE_SQL joins compute_site)
	// ------------------------------------------------------------------

	/**
	 * What INVOICE_SQL returns for the given invoice lines and group table: one row per tenant,
	 * group id and currency, the group name being MAX(COALESCE(NULLIF(TRIM(site_name), ''), cs.name))
	 * with cs from LEFT JOIN compute_site. With {@code join = false} it is the 1.1.2 query, MAX(site_name).
	 */
	private static List<Map> queryRows(List<Map> invoices, Map<Long, String> sites, boolean join = true) {
		Map<List, Map> out = new LinkedHashMap<List, Map>()
		invoices.each { Map i ->
			String cur = i.currency?.toString()?.trim() ?: null
			List key = [i.tenantId, i.siteId, cur]
			String own = i.siteName?.toString()?.trim() ?: null
			String name = join ? (own ?: (i.siteId != null ? sites[i.siteId as Long] : null)) : i.siteName
			Map r = out.get(key)
			if(r == null) {
				r = [tenantId: i.tenantId, tenant: "Tenant ${i.tenantId}".toString(), isMaster: false, grpId: i.siteId, grp: null,
					 currency: cur, resources: 0, cost: 0G, price: 0G]
				out.put(key, r)
			}
			if(name != null && (r.grp == null || name > r.grp)) r.grp = name
			r.resources += 1
			r.cost += new BigDecimal(i.cost.toString())
			r.price += new BigDecimal(i.price.toString())
		}
		out.values() as List<Map>
	}

	private static Map inv(Map m) {
		[tenantId: 2, siteId: null, siteName: null, currency: 'USD', cost: '1', price: '2'] + m
	}

	private static final Map<Long, String> SITES = [1L: 'Group 1', 2L: 'Group 2', 7L: 'Group 7', 8L: 'Group 1']

	def "a nameless server line with an existing group id joins that group's named lines"() {
		when:
		Map r = aggregate(queryRows([
			inv(siteId: 1, siteName: 'Group 1', cost: '10', price: '20'),
			inv(siteId: 1, siteName: '', cost: '1', price: '2'),
			inv(siteId: 2, siteName: null, cost: '3', price: '4')
		], SITES), 'EUR', 0G, false)

		then: 'group 1 is one row with both lines, group 2 a row of its own; no line without a group'
		r.lines*.group == ['Group 1', 'Group 2']
		r.lines*.resources == [2, 1]
		r.lines*.cost == [11G, 3G]
		!r.lines.any { it.group == null }
	}

	def "a nameless line whose group id is not in compute_site is a server without a group"() {
		when:
		Map r = aggregate(queryRows([
			inv(siteId: 99, siteName: '', cost: '1', price: '2'),
			inv(siteId: 98, siteName: null, cost: '1', price: '2'),
			inv(siteId: 7, siteName: null, cost: '5', price: '6')
		], SITES), 'EUR', 0G, false)

		then: 'the two missing ids are one no-group line, id 7 shows under its name'
		r.lines*.group == [null, 'Group 7']
		r.lines*.resources == [2, 1]
	}

	def "a nameless line without a group id is a server without a group"() {
		when:
		Map r = aggregate(queryRows([
			inv(siteId: null, siteName: null, cost: '1', price: '2'),
			inv(siteId: null, siteName: '  ', cost: '1', price: '2')
		], SITES), 'EUR', 0G, false)

		then:
		r.lines.size() == 1
		r.lines[0].group == null
		r.lines[0].resources == 2
	}

	def "two groups with the same name stay apart by id after the lookup"() {
		when:
		Map r = aggregate(queryRows([
			inv(siteId: 1, siteName: null, cost: '1', price: '2'),
			inv(siteId: 8, siteName: null, cost: '3', price: '4')
		], SITES), 'EUR', 0G, false)

		then:
		r.lines*.group == ['Group 1', 'Group 1']
		r.lines*.cost == [1G, 3G]
	}

	def "the group lookup moves rows but leaves the tenant and currency totals unchanged"() {
		given: 'the data shape seen on 9.0.2: nameless server lines with group ids, named instance lines'
		List<Map> invoices = [
			inv(tenantId: 2, siteId: 1, siteName: '', cost: '10.004', price: '12.5'),
			inv(tenantId: 2, siteId: 2, siteName: '', cost: '3', price: '4.006'),
			inv(tenantId: 2, siteId: 1, siteName: 'Group 1', cost: '7', price: '9'),
			inv(tenantId: 3, siteId: 7, siteName: null, currency: 'EUR', cost: '1', price: '1.5'),
			inv(tenantId: 3, siteId: 99, siteName: null, currency: 'EUR', cost: '2', price: '3'),
			inv(tenantId: 3, siteId: null, siteName: null, currency: '', cost: '4', price: '6')
		]

		when:
		Map before = aggregate(queryRows(invoices, SITES, false), 'EUR', 10G, false)
		Map after = aggregate(queryRows(invoices, SITES), 'EUR', 10G, false)

		then: '1.1.2 put every nameless id into the no-group row; now only id 99 and the line without an id'
		before.lines.findAll { it.group == null }*.resources == [1, 3]
		after.lines.findAll { it.group == null }*.resources == [2]
		after.lines.collect { [it.tenantKey, it.group, it.resources] } == [
			['2', 'Group 1', 2], ['2', 'Group 2', 1], ['3', 'Group 7', 1], ['3', null, 2]]

		and: 'every tenant and currency adds up to the same figures'
		after.tenants == before.tenants
		after.totals == before.totals
	}

	// ------------------------------------------------------------------
	// Locale: the user's Morpheus setting before the browser's language
	// ------------------------------------------------------------------

	@Unroll
	def "language setting '#setting' parses to #expected"() {
		expect:
		parseLocaleSetting(setting) == expected

		where:
		setting      | expected
		'en-US'      | Locale.forLanguageTag('en-US')
		'en_US'      | Locale.forLanguageTag('en-US')
		' de '       | Locale.GERMAN
		'de-DE'      | Locale.forLanguageTag('de-DE')
		null         | null
		''           | null
		'   '        | null
		'garbage'    | null
		'!!'         | null
		'xx-YY'      | null
		42           | null
	}

	@Unroll
	def "setting '#setting' with browser #browser resolves to #expected"() {
		expect:
		resolveLocale(setting, browser) == expected

		where:
		setting   | browser                        | expected
		'en-US'   | Locale.forLanguageTag('de-DE') | Locale.forLanguageTag('en-US')
		'de'      | Locale.forLanguageTag('en-US') | Locale.GERMAN
		'de_DE'   | null                           | Locale.forLanguageTag('de-DE')
		null      | Locale.forLanguageTag('de-DE') | Locale.forLanguageTag('de-DE')
		''        | Locale.forLanguageTag('de-DE') | Locale.forLanguageTag('de-DE')
		'garbage' | Locale.forLanguageTag('de-DE') | Locale.forLanguageTag('de-DE')
		null      | null                           | Locale.ENGLISH
		'garbage' | null                           | Locale.ENGLISH
	}

	@Unroll
	def "messages for #locale come from the #expected bundle"() {
		expect:
		messageLocale(locale) == expected

		where:
		locale                         | expected
		Locale.forLanguageTag('en-US') | Locale.ENGLISH
		Locale.forLanguageTag('de-AT') | Locale.GERMAN
		Locale.GERMAN                  | Locale.GERMAN
		Locale.FRENCH                  | Locale.ENGLISH
		null                           | Locale.ENGLISH
	}

	def "an en-US setting formats numbers in English even when the browser sends German"() {
		given:
		Locale l = resolveLocale('en-US', Locale.forLanguageTag('de-DE'))

		expect:
		money('1234.5', l) == '1,234.50'
		money('1234.5', resolveLocale('de', Locale.US)) == '1.234,50'
	}
}
