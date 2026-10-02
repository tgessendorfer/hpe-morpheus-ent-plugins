package com.morpheusdata.costapproval

import com.morpheusdata.costapproval.CostApprovalLogic.Outcome
import com.morpheusdata.model.RequestReference
import spock.lang.Specification
import spock.lang.Unroll

class CostApprovalLogicSpec extends Specification {

	private static RequestReference ref(def price, String currency) {
		new RequestReference(pricePerMonth: price as BigDecimal, currency: currency)
	}

	// ------------------------------------------------------------------
	// Option lookup
	// ------------------------------------------------------------------

	@Unroll
	def "configValue reads #shape"() {
		expect:
		CostApprovalLogic.configValue(cfg, 'costThreshold') == expected

		where:
		shape                              | cfg                                                       | expected
		'nested cm.plugin (form post)'     | [cm: [plugin: [costThreshold: '50']]]                     | '50'
		'flat dotted key'                  | ['cm.plugin.costThreshold': '60']                         | '60'
		'plain field name (API)'           | [costThreshold: '70']                                     | '70'
		'nested inside config'             | [config: [cm: [plugin: [costThreshold: '80']]]]           | '80'
		'nested before plain'              | [cm: [plugin: [costThreshold: '1']], costThreshold: '2']  | '1'
		'empty nested falls through'       | [cm: [plugin: [costThreshold: '']], costThreshold: '3']   | '3'
		'missing'                          | [other: 'x']                                              | null
		'null map'                         | null                                                      | null
	}

	@Unroll
	def "threshold precedence: policy #p, opts #o, integration #i -> #expected"() {
		expect:
		CostApprovalLogic.threshold(p, o, i) == expected

		where:
		p                                     | o                    | i                                          | expected
		[cm: [plugin: [costThreshold: '10']]] | [costThreshold: 20]  | [cm: [plugin: [costThreshold: '30']]]      | 10G
		[:]                                   | [costThreshold: 20]  | [cm: [plugin: [costThreshold: '30']]]      | 20G
		null                                  | null                 | [cm: [plugin: [costThreshold: '30.5']]]    | 30.5G
		null                                  | null                 | null                                       | 100G
		[costThreshold: '0']                  | null                 | [costThreshold: '30']                      | 0G
		[costThreshold: 'abc']                | null                 | [costThreshold: '-5']                      | 100G
	}

	def "threshold currency precedence and normalisation"() {
		expect:
		CostApprovalLogic.configuredThresholdCurrency([thresholdCurrency: ' chf '], null, [cm: [plugin: [thresholdCurrency: 'EUR']]]) == 'CHF'
		CostApprovalLogic.configuredThresholdCurrency(null, null, [cm: [plugin: [thresholdCurrency: 'eur']]]) == 'EUR'
		CostApprovalLogic.configuredThresholdCurrency([thresholdCurrency: 'euro'], null, null) == null
		CostApprovalLogic.configuredThresholdCurrency(null, null, null) == null
	}

	def "toAmount accepts non-negative plain numbers only"() {
		expect:
		CostApprovalLogic.toAmount('12.50') == 12.50G
		CostApprovalLogic.toAmount(7) == 7G
		CostApprovalLogic.toAmount('0') == 0G
		CostApprovalLogic.toAmount('1,000') == null
		CostApprovalLogic.toAmount('-1') == null
		CostApprovalLogic.toAmount(' ') == null
	}

	@Unroll
	def "toAmount reads a single decimal comma: '#text' -> #expected"() {
		expect:
		CostApprovalLogic.toAmount(text) == expected

		where:
		text        | expected
		'50,00'     | 50.00G
		' 50,5 '    | 50.5G
		'0,99'      | 0.99G
		'1.000,50'  | null
		'1,000'     | null
		'1,000.50'  | null
		'50,'       | null
		',50'       | null
		'5,0,0'     | null
		'-50,00'    | null
	}

	def "a threshold with a decimal comma is used, not the default"() {
		expect:
		CostApprovalLogic.threshold(null, null, [cm: [plugin: [costThreshold: '50,00']]], {}) == 50.00G
	}

	def "an unparsable threshold falls through with one warning naming the level and the value"() {
		given:
		List<String> warnings = []

		when:
		BigDecimal t = CostApprovalLogic.threshold([costThreshold: '1.000,50'], [costThreshold: 'abc'],
			[cm: [plugin: [costThreshold: '30']]], { String m -> warnings << m })

		then:
		t == 30G
		warnings == ["Cost threshold approval: policy threshold '1.000,50' is not a non-negative amount, ignored",
			"Cost threshold approval: call options threshold 'abc' is not a non-negative amount, ignored"]
	}

	def "an unparsable integration threshold warns and the default applies; empty values stay silent"() {
		given:
		List<String> warnings = []

		when:
		BigDecimal t = CostApprovalLogic.threshold([costThreshold: ''], null, [costThreshold: '-5'], { String m -> warnings << m })

		then:
		t == 100G
		warnings == ["Cost threshold approval: integration threshold '-5' is not a non-negative amount, ignored"]
	}

	def "a valid threshold logs nothing"() {
		given:
		List<String> warnings = []

		expect:
		CostApprovalLogic.threshold([costThreshold: '10'], [costThreshold: 'abc'], null, { String m -> warnings << m }) == 10G
		warnings.isEmpty()
	}

	// ------------------------------------------------------------------
	// Currency grouping
	// ------------------------------------------------------------------

	@Unroll
	def "request currency: own #own, refs #refCurs -> #expected"() {
		given:
		List<RequestReference> refs = refCurs.collect { ref(1, it) }

		expect:
		CostApprovalLogic.requestCurrency(own, refs, { 'GBP' }) == expected

		where:
		own   | refCurs              | expected
		'eur' | ['USD']              | 'EUR'
		null  | ['USD', 'usd']       | 'USD'
		null  | ['USD', 'EUR']       | null
		null  | [null, null]         | 'GBP'
		null  | []                   | 'GBP'
		null  | ['USD', null]        | 'USD'
	}

	def "the fallback is only asked when nothing else names a currency"() {
		given:
		int calls = 0

		when:
		CostApprovalLogic.requestCurrency('EUR', [], { calls++; 'USD' })

		then:
		calls == 0
	}

	def "request price prefers the request's own price"() {
		expect:
		CostApprovalLogic.requestPrice(42G, [ref(1, 'EUR')], 'EUR') == 42G
	}

	def "request price sums references of the request currency"() {
		expect:
		CostApprovalLogic.requestPrice(null, [ref(10.10, 'EUR'), ref(5.05, null), ref(null, 'EUR')], 'EUR') == 15.15G
	}

	def "prices in different currencies are never added"() {
		expect:
		CostApprovalLogic.requestPrice(null, [ref(10, 'EUR'), ref(5, 'USD')], 'EUR') == null
	}

	def "no price anywhere gives null, not zero"() {
		expect:
		CostApprovalLogic.requestPrice(null, [ref(null, 'EUR')], 'EUR') == null
		CostApprovalLogic.requestPrice(null, [], 'EUR') == null
		CostApprovalLogic.requestPrice(null, [ref(5, 'EUR')], null) == null
	}

	// ------------------------------------------------------------------
	// Rounding and decisions
	// ------------------------------------------------------------------

	@Unroll
	def "price #price EUR against threshold #limit EUR -> #outcome"() {
		expect:
		CostApprovalLogic.decide(price as BigDecimal, 'EUR', ['EUR'], limit as BigDecimal, 'EUR').outcome == outcome

		where:
		price    | limit | outcome
		49.99    | 50    | Outcome.APPROVED
		50       | 50    | Outcome.APPROVED
		50.004   | 50    | Outcome.APPROVED
		50.005   | 50    | Outcome.OVER_THRESHOLD
		50.01    | 50    | Outcome.OVER_THRESHOLD
		0        | 0     | Outcome.APPROVED
		0.01     | 0     | Outcome.OVER_THRESHOLD
	}

	@Unroll
	def "currency #reqCur against threshold currency #limitCur -> #outcome"() {
		expect:
		CostApprovalLogic.decide(1G, reqCur, [], 100G, limitCur).outcome == outcome

		where:
		reqCur | limitCur | outcome
		'EUR'  | 'EUR'    | Outcome.APPROVED
		'EUR'  | 'USD'    | Outcome.CURRENCY_MISMATCH
		null   | 'EUR'    | Outcome.CURRENCY_MISMATCH
		'EUR'  | null     | Outcome.CURRENCY_MISMATCH
	}

	def "a request without price is never approved"() {
		expect:
		CostApprovalLogic.decide(null, 'EUR', [], 100G, 'EUR').outcome == Outcome.NO_PRICE
	}

	def "decision values are rounded half-up to cents"() {
		when:
		def d = CostApprovalLogic.decide(12.345G, 'EUR', [], 99.999G, 'EUR')

		then:
		d.price == 12.35G
		d.threshold == 100.00G
	}

	// ------------------------------------------------------------------
	// Text
	// ------------------------------------------------------------------

	def "money uses the number format of the locale and the ISO code"() {
		expect:
		CostApprovalLogic.money(1234.5G, 'USD', Locale.ENGLISH) == '1,234.50 USD'
		CostApprovalLogic.money(1234.5G, 'EUR', Locale.GERMAN) == '1.234,50 EUR'
		CostApprovalLogic.money(0.005G, 'EUR', Locale.ENGLISH) == '0.01 EUR'
		CostApprovalLogic.money(null, 'EUR', Locale.ENGLISH) == '? EUR'
		CostApprovalLogic.money(1G, null, null) == '1.00 ?'
	}

	def "request names in English and German"() {
		given:
		def ok = CostApprovalLogic.decide(12.5G, 'EUR', [], 1000G, 'EUR')
		def mixed = CostApprovalLogic.decide(null, null, ['EUR', 'USD'], 50G, 'EUR')

		expect:
		CostApprovalLogic.requestName(ok, Locale.ENGLISH) == 'Approved automatically (12.50 EUR <= 1,000.00 EUR per month)'
		CostApprovalLogic.requestName(ok, Locale.GERMAN) == 'Automatisch freigegeben (12,50 EUR <= 1.000,00 EUR pro Monat)'
		CostApprovalLogic.requestName(mixed, Locale.ENGLISH) == 'Currency differs from the cost threshold (requested ? EUR+USD, threshold 50.00 EUR/month). Please contact your provider for approval.'
		CostApprovalLogic.requestName(mixed, Locale.GERMAN) == 'Währung weicht von der Kostenschwelle ab (angefragt ? EUR+USD, Schwelle 50,00 EUR/Monat). Bitte wenden Sie sich für eine Freigabe an Ihren Provider.'
		CostApprovalLogic.responseMessage(ok, Locale.FRENCH) == 'Approved automatically'
	}

	def "rejection texts above the threshold in English and German"() {
		given:
		def over = CostApprovalLogic.decide(32G, 'EUR', ['EUR'], 20G, 'EUR')

		expect:
		over.rejected
		!over.approved
		CostApprovalLogic.requestName(over, Locale.ENGLISH) == 'Above cost threshold of 20.00 EUR/month (requested 32.00 EUR). Please contact your provider for approval.'
		CostApprovalLogic.requestName(over, Locale.GERMAN) == 'Über der Kostenschwelle von 20,00 EUR/Monat (angefragt 32,00 EUR). Bitte wenden Sie sich für eine Freigabe an Ihren Provider.'
		CostApprovalLogic.responseMessage(over, Locale.ENGLISH) == 'Rejected: above cost threshold'
		CostApprovalLogic.responseMessage(over, Locale.GERMAN) == 'Abgelehnt: Kostenschwelle überschritten'
	}

	@Unroll
	def "only APPROVED is not rejected: #outcome"() {
		expect:
		new CostApprovalLogic.Decision(outcome: outcome).rejected == rejected

		where:
		outcome                    | rejected
		Outcome.APPROVED           | false
		Outcome.OVER_THRESHOLD     | true
		Outcome.CURRENCY_MISMATCH  | true
		Outcome.NO_PRICE           | true
	}

	def "every rejection text fits the external name column and has no unresolved placeholder"() {
		given:
		def cases = [CostApprovalLogic.decide(123456.78G, 'EUR', [], 99999.99G, 'EUR'),
			CostApprovalLogic.decide(null, null, ['EUR', 'USD', 'CHF'], 99999.99G, 'EUR'),
			CostApprovalLogic.decide(null, 'EUR', [], 99999.99G, 'EUR')]

		expect:
		[Locale.ENGLISH, Locale.GERMAN].every { Locale l ->
			cases.every { String t = CostApprovalLogic.requestName(it, l); t.length() <= 255 && !t.contains('{') }
		}
	}
}
