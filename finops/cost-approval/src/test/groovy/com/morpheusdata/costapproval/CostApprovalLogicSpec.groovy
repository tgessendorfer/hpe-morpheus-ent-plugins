package com.morpheusdata.costapproval

import com.morpheusdata.costapproval.CostApprovalLogic.Outcome
import com.morpheusdata.model.RequestReference
import spock.lang.Specification
import spock.lang.Timeout
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
	def "threshold precedence: policy #p, opts #o, integration #i -> #expected from #level"() {
		when:
		def s = CostApprovalLogic.thresholdSetting(p, o, i)

		then:
		s.value == expected
		s.level == level
		!s.misconfigured

		where:
		p                                     | o                    | i                                          | expected | level
		[cm: [plugin: [costThreshold: '10']]] | [costThreshold: 20]  | [cm: [plugin: [costThreshold: '30']]]      | 10G      | 'policy'
		[:]                                   | [costThreshold: 20]  | [cm: [plugin: [costThreshold: '30']]]      | 20G      | 'callOptions'
		null                                  | null                 | [cm: [plugin: [costThreshold: '30.5']]]    | 30.5G    | 'integration'
		null                                  | null                 | null                                       | null     | null
		[costThreshold: '0']                  | null                 | [costThreshold: '30']                      | 0G       | 'policy'
		[costThreshold: '10']                 | [costThreshold: 'x'] | [costThreshold: '-5']                      | 10G      | 'policy'
	}

	@Unroll
	def "the first threshold that is set but does not parse is misconfigured, looser levels are not used: #p, #o, #i"() {
		when:
		def s = CostApprovalLogic.thresholdSetting(p, o, i)

		then:
		s.misconfigured
		s.value == null
		s.level == level
		s.invalid == invalid

		where:
		p                                             | o                      | i                     | level         | invalid
		[costThreshold: 'abc']                        | null                   | [costThreshold: '-5'] | 'policy'      | 'abc'
		[cm: [plugin: [costThreshold: ' 1.000,50 ']]] | null                   | [costThreshold: '30'] | 'policy'      | '1.000,50'
		[costThreshold: '']                           | [costThreshold: 'abc'] | [costThreshold: '30'] | 'callOptions' | 'abc'
		[:]                                           | null                   | [costThreshold: '-5'] | 'integration' | '-5'
	}

	def "threshold currency precedence and normalisation"() {
		expect:
		CostApprovalLogic.currencySetting([thresholdCurrency: ' chf '], null, [cm: [plugin: [thresholdCurrency: 'EUR']]]).value == 'CHF'
		CostApprovalLogic.currencySetting(null, null, [cm: [plugin: [thresholdCurrency: 'eur']]]).value == 'EUR'
		CostApprovalLogic.currencySetting([thresholdCurrency: 'usd'], null, [thresholdCurrency: 'euro']).value == 'USD'
		CostApprovalLogic.currencySetting(null, null, null).value == null
		!CostApprovalLogic.currencySetting(null, null, null).misconfigured
	}

	def "the first currency that is set but is no ISO code is misconfigured, looser levels are not used"() {
		when:
		def policy = CostApprovalLogic.currencySetting([thresholdCurrency: 'euro'], null, [thresholdCurrency: 'EUR'])
		def integration = CostApprovalLogic.currencySetting([thresholdCurrency: ' '], null, [cm: [plugin: [thresholdCurrency: 'E1R']]])

		then:
		policy.misconfigured
		policy.value == null
		policy.level == 'policy'
		policy.invalid == 'euro'
		integration.misconfigured
		integration.level == 'integration'
		integration.invalid == 'E1R'
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

	@Unroll
	@Timeout(10)
	def "toAmount refuses an amount that is too large, too precise or too long: '#shown'"() {
		expect:
		CostApprovalLogic.toAmount(text) == null

		where:
		text << ['1e999999999', '1E+2147483647', '1e-999999999', '1e300', '1000000000000', '0.' + '1' * 40, '9' * 40]
		shown = text.length() > 20 ? text.take(20) + '...' : text
	}

	def "toAmount keeps the largest plain amount and exponent forms within the bounds"() {
		expect:
		CostApprovalLogic.toAmount('999999999999.99') == 999999999999.99G
		CostApprovalLogic.toAmount(1.0E7d) == 10000000G
		CostApprovalLogic.toAmount('5e-3') == 0.005G
	}

	/** The decision for a request of 10 EUR under the given threshold settings, collecting the warnings. */
	private static CostApprovalLogic.Decision decide10Eur(Map p, Map o, Map i, List<String> warnings) {
		CostApprovalLogic.decideConfigured(10G, 'EUR', ['EUR'], CostApprovalLogic.thresholdSetting(p, o, i),
			CostApprovalLogic.currencySetting(p, o, i), { String m -> warnings << m })
	}

	@Timeout(10)
	def "an exponent threshold is misconfigured at once with a short warning, and no looser level is read"() {
		given:
		List<String> warnings = []

		when:
		def d = decide10Eur([costThreshold: '1e999999999'], [costThreshold: '9' * 100], [costThreshold: '30'], warnings)

		then:
		d.outcome == Outcome.THRESHOLD_MISCONFIGURED
		d.rejected
		d.misconfiguredLevel == 'policy'
		d.misconfiguredField == 'threshold'
		d.threshold == null
		warnings == ["Cost threshold approval: policy threshold '1e999999999' is not a non-negative amount of at most 12 integer digits, request rejected (threshold misconfigured)"]
	}

	@Timeout(10)
	def "the warning cuts a long raw value after 40 characters"() {
		given:
		List<String> warnings = []

		when:
		decide10Eur([:], [costThreshold: '9' * 100], [costThreshold: '30'], warnings)

		then:
		warnings == ["Cost threshold approval: call options threshold '${'9' * 40}...' is not a non-negative amount of at most 12 integer digits, request rejected (threshold misconfigured)".toString()]
	}

	def "a threshold with a decimal comma is used, not the default"() {
		expect:
		CostApprovalLogic.thresholdSetting(null, null, [cm: [plugin: [costThreshold: '50,00']]]).value == 50.00G
	}

	def "an unparsable threshold rejects the request with one warning naming the level and the value, although a looser level would approve"() {
		given:
		List<String> warnings = []

		when:
		def d = decide10Eur([costThreshold: '1.000,50'], [costThreshold: 'abc'], [cm: [plugin: [costThreshold: '30']]], warnings)

		then:
		d.outcome == Outcome.THRESHOLD_MISCONFIGURED
		d.misconfiguredLevel == 'policy'
		warnings == ["Cost threshold approval: policy threshold '1.000,50' is not a non-negative amount of at most 12 integer digits, request rejected (threshold misconfigured)"]
	}

	def "an unparsable integration threshold rejects the request instead of the default; empty values stay silent"() {
		given:
		List<String> warnings = []

		when:
		def d = decide10Eur([costThreshold: ''], null, [costThreshold: '-5'], warnings)

		then:
		d.outcome == Outcome.THRESHOLD_MISCONFIGURED
		d.misconfiguredLevel == 'integration'
		warnings == ["Cost threshold approval: integration threshold '-5' is not a non-negative amount of at most 12 integer digits, request rejected (threshold misconfigured)"]
	}

	def "a valid threshold logs nothing and the levels below it are not read"() {
		given:
		List<String> warnings = []

		when:
		def d = decide10Eur([costThreshold: '10', thresholdCurrency: 'eur'], [costThreshold: 'abc', thresholdCurrency: 'euro'],
			[costThreshold: '-5', thresholdCurrency: 'E1R'], warnings)

		then:
		d.outcome == Outcome.APPROVED
		d.threshold == 10.00G
		d.thresholdCurrency == 'EUR'
		warnings.isEmpty()
	}

	def "an invalid threshold currency rejects the request with one warning, although the integration currency would match"() {
		given:
		List<String> warnings = []

		when:
		def d = decide10Eur([costThreshold: '50', thresholdCurrency: 'euro'], null, [thresholdCurrency: 'EUR'], warnings)

		then:
		d.outcome == Outcome.THRESHOLD_MISCONFIGURED
		d.rejected
		d.misconfiguredLevel == 'policy'
		d.misconfiguredField == 'currency'
		d.threshold == 50.00G
		d.thresholdCurrency == null
		warnings == ["Cost threshold approval: policy threshold currency 'euro' is not a three-letter ISO 4217 code, request rejected (threshold misconfigured)"]
	}

	def "a misconfigured threshold and currency both warn; the decision names the threshold"() {
		given:
		List<String> warnings = []

		when:
		def d = decide10Eur(null, [thresholdCurrency: 'EU'], [costThreshold: 'ten'], warnings)

		then:
		d.outcome == Outcome.THRESHOLD_MISCONFIGURED
		d.misconfiguredLevel == 'integration'
		d.misconfiguredField == 'threshold'
		warnings == ["Cost threshold approval: integration threshold 'ten' is not a non-negative amount of at most 12 integer digits, request rejected (threshold misconfigured)",
			"Cost threshold approval: call options threshold currency 'EU' is not a three-letter ISO 4217 code, request rejected (threshold misconfigured)"]
	}

	@Unroll
	def "without a misconfigured value the threshold (else 100) and its currency (else the request's) decide: #p, #i -> #outcome"() {
		given:
		List<String> warnings = []

		when:
		def d = decide10Eur(p, null, i, warnings)

		then:
		d.outcome == outcome
		d.threshold == threshold
		d.thresholdCurrency == currency
		warnings.isEmpty()

		where:
		p                          | i                                               | outcome                   | threshold | currency
		null                       | null                                            | Outcome.APPROVED          | 100.00G   | 'EUR'
		[costThreshold: '0']       | [costThreshold: '30']                           | Outcome.OVER_THRESHOLD    | 0.00G     | 'EUR'
		null                       | [costThreshold: '30', thresholdCurrency: 'usd'] | Outcome.CURRENCY_MISMATCH | 30.00G    | 'USD'
		[thresholdCurrency: 'EUR'] | [costThreshold: '9.99']                         | Outcome.OVER_THRESHOLD    | 9.99G     | 'EUR'
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
		CostApprovalLogic.requestPrice(null, [ref(10.10, 'EUR'), ref(5.05, null), ref(0, 'EUR')], 'EUR') == 15.15G
	}

	def "one reference without a price makes the request price unknown (no partial sums)"() {
		expect:
		CostApprovalLogic.requestPrice(null, [ref(10.10, 'EUR'), ref(null, 'EUR')], 'EUR') == null
		CostApprovalLogic.requestPrice(null, [ref(null, 'EUR'), ref(5, 'EUR')], 'EUR') == null
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
		outcome                         | rejected
		Outcome.APPROVED                | false
		Outcome.OVER_THRESHOLD          | true
		Outcome.CURRENCY_MISMATCH       | true
		Outcome.NO_PRICE                | true
		Outcome.THRESHOLD_MISCONFIGURED | true
	}

	@Unroll
	def "misconfigured texts name the field and the level: #field of #level"() {
		given:
		def d = new CostApprovalLogic.Decision(outcome: Outcome.THRESHOLD_MISCONFIGURED, price: 10G, priceCurrency: 'EUR',
			misconfiguredField: field, misconfiguredLevel: level)

		expect:
		CostApprovalLogic.requestName(d, Locale.ENGLISH) == english
		CostApprovalLogic.requestName(d, Locale.GERMAN) == german
		CostApprovalLogic.responseMessage(d, Locale.ENGLISH) == 'Rejected: cost threshold misconfigured'
		CostApprovalLogic.responseMessage(d, Locale.GERMAN) == 'Abgelehnt: Kostenschwelle falsch konfiguriert'

		where:
		field       | level         | english | german
		'threshold' | 'policy'      | 'Cost threshold misconfigured: the field "Monthly Cost Threshold" of the policy has an invalid value. An administrator must correct it. Please contact your provider for approval.' | 'Kostenschwelle falsch konfiguriert: Das Feld „Kostenschwelle pro Monat“ der Policy hat einen ungültigen Wert. Ein Administrator muss ihn korrigieren. Bitte wenden Sie sich für eine Freigabe an Ihren Provider.'
		'currency'  | 'integration' | 'Cost threshold misconfigured: the field "Threshold Currency" of the integration has an invalid value. An administrator must correct it. Please contact your provider for approval.' | 'Kostenschwelle falsch konfiguriert: Das Feld „Währung der Kostenschwelle“ der Integration hat einen ungültigen Wert. Ein Administrator muss ihn korrigieren. Bitte wenden Sie sich für eine Freigabe an Ihren Provider.'
		'threshold' | 'callOptions' | 'Cost threshold misconfigured: the field "Monthly Cost Threshold" of the approval call options has an invalid value. An administrator must correct it. Please contact your provider for approval.' | 'Kostenschwelle falsch konfiguriert: Das Feld „Kostenschwelle pro Monat“ der Optionen des Freigabeaufrufs hat einen ungültigen Wert. Ein Administrator muss ihn korrigieren. Bitte wenden Sie sich für eine Freigabe an Ihren Provider.'
	}

	def "every rejection text fits the external name column and has no unresolved placeholder"() {
		given:
		def cases = [CostApprovalLogic.decide(123456.78G, 'EUR', [], 99999.99G, 'EUR'),
			CostApprovalLogic.decide(null, null, ['EUR', 'USD', 'CHF'], 99999.99G, 'EUR'),
			CostApprovalLogic.decide(null, 'EUR', [], 99999.99G, 'EUR')] +
			CostApprovalLogic.LEVELS.keySet().collectMany { String level ->
				['threshold', 'currency'].collect { String field ->
					new CostApprovalLogic.Decision(outcome: Outcome.THRESHOLD_MISCONFIGURED, price: 123456.78G, priceCurrency: 'EUR',
						misconfiguredLevel: level, misconfiguredField: field)
				}
			}

		expect:
		[Locale.ENGLISH, Locale.GERMAN].every { Locale l ->
			cases.every { String t = CostApprovalLogic.requestName(it, l); t.length() <= 255 && !t.contains('{') }
		}
	}
}
