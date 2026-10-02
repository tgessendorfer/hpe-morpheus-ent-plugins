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
package com.morpheusdata.costapproval

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.math.RoundingMode
import java.text.NumberFormat

/**
 * The pure decision logic of the cost threshold approval: no Morpheus services, no state.
 * Everything here is covered by CostApprovalLogicSpec.
 */
@Slf4j
class CostApprovalLogic {

	/** Threshold when neither the policy, the call options nor the integration set one. */
	static final BigDecimal DEFAULT_THRESHOLD = 100G
	/** Last step of the shared currency rule: row currency, else master tenant currency, else USD. */
	static final String DEFAULT_CURRENCY = 'USD'
	/** Prices and thresholds are compared and shown in whole cents. */
	static final int SCALE = 2

	static final String FIELD_THRESHOLD = 'costThreshold'
	static final String FIELD_CURRENCY = 'thresholdCurrency'

	enum Outcome {
		/** Same currency, monthly price at or below the threshold. */
		APPROVED,
		/** Same currency, monthly price above the threshold. */
		OVER_THRESHOLD,
		/** The request currency is unknown, mixed, or differs from the threshold currency. */
		CURRENCY_MISMATCH,
		/** The request carries no price at all. */
		NO_PRICE
	}

	/** The result of one decision, with everything needed to explain it. */
	static class Decision {
		Outcome outcome
		BigDecimal price
		String priceCurrency
		BigDecimal threshold
		String thresholdCurrency
		List<String> refCurrencies = []

		boolean isApproved() { outcome == Outcome.APPROVED }

		/**
		 * Every outcome other than APPROVED is rejected at once. Morpheus 9.0.2 offers no manual
		 * approve or deny for an item owned by an approval integration, so a request left
		 * "requested" could never be decided.
		 */
		boolean isRejected() { !approved }

		/** Currency shown for the request: its own, else the mixed reference currencies. */
		String getShownPriceCurrency() {
			priceCurrency ?: (refCurrencies ? refCurrencies.join('+') : '?')
		}
	}

	// ------------------------------------------------------------------
	// Option values
	// ------------------------------------------------------------------

	/**
	 * Reads an option value from a config map. Morpheus 9.0.2 reports the integration
	 * fields of an approval provider as {@code cm.plugin.<fieldName>} with field context
	 * {@code config}, so the form posts {@code config.cm.plugin.<fieldName>}. Depending on
	 * how the value was saved (UI or API) it ends up nested ({@code cm -> plugin -> name}),
	 * as a flat dotted key, or under the plain field name. All three are read, nested first,
	 * also inside a wrapping {@code config} map.
	 */
	static Object configValue(Map cfg, String fieldName) {
		if (cfg == null) {
			return null
		}
		Object value = lookup(cfg, fieldName)
		if (value == null && cfg.get('config') instanceof Map) {
			value = lookup((Map) cfg.get('config'), fieldName)
		}
		return value
	}

	private static Object lookup(Map cfg, String fieldName) {
		Object cm = cfg.get('cm')
		Object plugin = cm instanceof Map ? ((Map) cm).get('plugin') : null
		List candidates = [
			plugin instanceof Map ? ((Map) plugin).get(fieldName) : null,
			cfg.get("cm.plugin.${fieldName}".toString()),
			cfg.get(fieldName)
		]
		return candidates.find { !blank(it) }
	}

	/** Parses a JSON object string into a map; anything else becomes null. */
	static Map parseJson(String json) {
		if (blank(json)) {
			return null
		}
		try {
			Object parsed = new JsonSlurper().parseText(json)
			return parsed instanceof Map ? (Map) parsed : null
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * A non-negative amount, or null for empty, malformed or negative input. A single decimal
	 * comma with one or two digits after it is read as a decimal point ({@code 50,00} is 50.00);
	 * forms that mix comma and dot or look like a thousands separator ({@code 1.000,50},
	 * {@code 1,000}) stay malformed rather than being guessed.
	 */
	static BigDecimal toAmount(Object value) {
		if (blank(value)) {
			return null
		}
		String text = value.toString().trim()
		if (text ==~ /\d+,\d{1,2}/) {
			text = text.replace(',', '.')
		}
		try {
			BigDecimal amount = new BigDecimal(text)
			return amount.signum() < 0 ? null : amount
		} catch (Exception ignored) {
			return null
		}
	}

	/** An upper-case three-letter ISO 4217 code, or null. */
	static String isoCurrency(Object value) {
		String code = value?.toString()?.trim()?.toUpperCase(Locale.ROOT)
		return code ==~ /[A-Z]{3}/ ? code : null
	}

	/**
	 * First threshold that parses: policy, call options, integration, else the default. A value
	 * that is set but does not parse is skipped as before, with one warning naming the level and
	 * the raw value, so a typo no longer falls back to the next level unnoticed.
	 */
	static BigDecimal threshold(Map policyCfg, Map opts, Map integrationCfg,
	                            Closure warn = { String msg -> log.warn(msg) }) {
		// No Elvis chain: a threshold of 0 is valid and Groovy treats 0 as false.
		Map<String, Map> levels = [policy: policyCfg, 'call options': opts, integration: integrationCfg]
		for (Map.Entry<String, Map> level : levels.entrySet()) {
			Object raw = configValue(level.value, FIELD_THRESHOLD)
			BigDecimal amount = toAmount(raw)
			if (amount != null) {
				return amount
			}
			if (!blank(raw)) {
				warn?.call("Cost threshold approval: ${level.key} threshold '${raw}' is not a non-negative amount, ignored".toString())
			}
		}
		return DEFAULT_THRESHOLD
	}

	/** First configured threshold currency: policy, call options, integration; null when none is set. */
	static String configuredThresholdCurrency(Map policyCfg, Map opts, Map integrationCfg) {
		return isoCurrency(configValue(policyCfg, FIELD_CURRENCY)) ?:
			isoCurrency(configValue(opts, FIELD_CURRENCY)) ?:
			isoCurrency(configValue(integrationCfg, FIELD_CURRENCY))
	}

	// ------------------------------------------------------------------
	// Currency and price of a request
	// ------------------------------------------------------------------

	/**
	 * A property of a request reference, or null. Morpheus 9.0.2 hands the provider its internal
	 * domain objects ({@code com.morpheus.RequestReference}) in {@code Request.refs}, not the
	 * plugin model class, so references are read by property name and never cast.
	 */
	static Object refValue(Object ref, String name) {
		if (ref == null) {
			return null
		}
		try {
			return ref."${name}"
		} catch (MissingPropertyException ignored) {
			return null
		}
	}

	/** The monthly price of one reference as an exact decimal (the domain object may carry a Double), or null. */
	static BigDecimal refPrice(Object ref) {
		Object value = refValue(ref, 'pricePerMonth')
		if (value == null) {
			return null
		}
		try {
			return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString())
		} catch (Exception ignored) {
			return null
		}
	}

	/** The distinct currencies of the references, in order of appearance. */
	static List<String> refCurrencies(List refs) {
		return (refs ?: []).collect { isoCurrency(refValue(it, 'currency')) }.findAll { it }.unique()
	}

	/**
	 * The currency of a request: its own, else the one currency all references share,
	 * else - only when no reference names a currency - the fallback (tenant, master
	 * tenant, USD). Mixed reference currencies give null: such a request is never
	 * approved automatically.
	 */
	static String requestCurrency(String ownCurrency, List refs, Closure<String> fallback) {
		String own = isoCurrency(ownCurrency)
		if (own) {
			return own
		}
		List<String> currencies = refCurrencies(refs)
		if (currencies.size() == 1) {
			return currencies[0]
		}
		return currencies.isEmpty() ? isoCurrency(fallback?.call()) : null
	}

	/**
	 * The monthly price of a request: its own price, else the sum of the reference prices.
	 * Prices in different currencies are never added: a reference in another currency
	 * than {@code currency} makes the sum unknown (null). References without a price
	 * count as 0, but if none has a price the result is null.
	 */
	static BigDecimal requestPrice(BigDecimal ownPrice, List refs, String currency) {
		if (ownPrice != null) {
			return ownPrice
		}
		List list = refs ?: []
		if (currency == null || list.every { refPrice(it) == null }) {
			return null
		}
		if (list.any { String c = isoCurrency(refValue(it, 'currency')); c && c != currency }) {
			return null
		}
		return list.collect { refPrice(it) ?: 0G }.sum(0G) as BigDecimal
	}

	static BigDecimal round(BigDecimal amount) {
		return amount?.setScale(SCALE, RoundingMode.HALF_UP)
	}

	/**
	 * The decision. Both sides are rounded to cents first, so 50.004 passes a threshold
	 * of 50 and 50.005 does not.
	 */
	static Decision decide(BigDecimal price, String priceCurrency, List<String> refCurrencies,
	                       BigDecimal threshold, String thresholdCurrency) {
		Decision d = new Decision(price: round(price), priceCurrency: priceCurrency,
			threshold: round(threshold), thresholdCurrency: thresholdCurrency,
			refCurrencies: refCurrencies ?: [])
		if (priceCurrency == null || thresholdCurrency == null || priceCurrency != thresholdCurrency) {
			d.outcome = Outcome.CURRENCY_MISMATCH
		} else if (d.price == null) {
			d.outcome = Outcome.NO_PRICE
		} else {
			d.outcome = d.price <= d.threshold ? Outcome.APPROVED : Outcome.OVER_THRESHOLD
		}
		return d
	}

	// ------------------------------------------------------------------
	// Text
	// ------------------------------------------------------------------

	/** An amount in the number format of the locale with two decimals and the ISO code, e.g. 1,234.50 USD. */
	static String money(BigDecimal amount, String currency, Locale locale) {
		String code = currency ?: '?'
		if (amount == null) {
			return "? ${code}".toString()
		}
		NumberFormat format = NumberFormat.getNumberInstance(locale ?: Locale.ENGLISH)
		format.minimumFractionDigits = SCALE
		format.maximumFractionDigits = SCALE
		format.roundingMode = RoundingMode.HALF_UP
		return "${format.format(amount)} ${code}".toString()
	}

	/** The external name Morpheus shows for the request, in the given locale. */
	static String requestName(Decision d, Locale locale) {
		String price = money(d.price, d.shownPriceCurrency, locale)
		String limit = money(d.threshold, d.thresholdCurrency, locale)
		return Messages.text("cost-threshold-approval.request.${outcomeKey(d.outcome)}", locale, price, limit)
	}

	/** The short status message of the response, in the given locale. */
	static String responseMessage(Decision d, Locale locale) {
		return Messages.text("cost-threshold-approval.msg.${outcomeKey(d.outcome)}", locale)
	}

	static String outcomeKey(Outcome outcome) {
		switch (outcome) {
			case Outcome.APPROVED: return 'approved'
			case Outcome.OVER_THRESHOLD: return 'overThreshold'
			case Outcome.CURRENCY_MISMATCH: return 'currencyMismatch'
			default: return 'noPrice'
		}
	}

	private static boolean blank(Object value) {
		return value == null || value.toString().trim().isEmpty()
	}
}
