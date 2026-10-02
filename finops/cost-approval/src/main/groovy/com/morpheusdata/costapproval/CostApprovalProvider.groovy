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

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.ApprovalProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.MorpheusModel
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Policy
import com.morpheusdata.model.Request
import com.morpheusdata.model.RequestReference
import com.morpheusdata.response.RequestResponse
import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Cost threshold approval. Morpheus calls createApprovalRequest when an approval policy
 * uses an integration of this type. A request whose monthly price is at or below the
 * threshold, in the same currency, is approved in the response; anything else (above the
 * threshold, another or mixed currency, no price) is rejected at once, with the reason in
 * the request name. Morpheus 9.0.2 lets nobody approve or deny an item owned by an approval
 * integration by hand, so a "requested" answer would leave the request stuck for good.
 * Prices in different currencies are never added.
 *
 * Threshold: policy option, else call option, else integration option, else 100.
 * Threshold currency: the same order, else the currency of the request.
 * Request currency: Request.currency, else the one currency of all references, else the
 * currency of the integration's tenant, else of the master tenant, else USD.
 *
 * monitorApproval reports every decision (approved and rejected) once more, unchanged, since
 * Morpheus 9.0.2 takes the status over only in the monitor run. Its signature in plugin API 1.4.2 receives the
 * integration only, never the request, so the decision cannot be recomputed there. The
 * pending reports are therefore kept in memory per integration id and are lost on a
 * restart; the approval in the createApprovalRequest response is not affected.
 *
 * Language of the request name and message: the Morpheus language setting of the user who
 * asked (see {@link UserLocale}), else the browser language of the web request, else English.
 * When the requesting user cannot be found, the texts stay English as before 1.2.0.
 */
@Slf4j
class CostApprovalProvider implements ApprovalProvider {

	static final String PROVIDER_CODE = 'cost-threshold-approval'
	static final String REQUEST_ID_PREFIX = 'ca-'

	Plugin plugin
	MorpheusContext morpheus

	/** integration id -> (external request id -> decided request), reported by the next monitorApproval of that integration. */
	private final Map<Long, Map<String, Request>> pending = new ConcurrentHashMap<>()

	CostApprovalProvider(Plugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override
	MorpheusContext getMorpheus() { morpheus }

	@Override
	Plugin getPlugin() { plugin }

	@Override
	String getCode() { PROVIDER_CODE }

	@Override
	String getName() { 'Cost Threshold Approval' }

	@Override
	List<OptionType> integrationOptionTypes() {
		return i18n([
			[code: "${PROVIDER_CODE}-threshold".toString(), name: 'Cost Threshold',
				fieldName: CostApprovalLogic.FIELD_THRESHOLD, fieldContext: 'config',
				fieldLabel: 'Monthly Cost Threshold', inputType: OptionType.InputType.NUMBER,
				displayOrder: 0, required: false, defaultValue: CostApprovalLogic.DEFAULT_THRESHOLD.toPlainString(),
				helpText: 'Requests with a monthly price up to this amount are approved automatically. Currency: the field below, else the currency of the request.',
				i18nKey: 'threshold'],
			[code: "${PROVIDER_CODE}-currency".toString(), name: 'Threshold Currency',
				fieldName: CostApprovalLogic.FIELD_CURRENCY, fieldContext: 'config',
				fieldLabel: 'Threshold Currency', inputType: OptionType.InputType.TEXT,
				displayOrder: 1, required: false,
				helpText: 'ISO 4217 code such as EUR, USD or CHF. Empty means the currency of the request. Requests in another currency are never approved automatically.',
				i18nKey: 'currency']
		])
	}

	@Override
	List<OptionType> policyOptionTypes() {
		return i18n([
			[code: "${PROVIDER_CODE}-policy-threshold".toString(), name: 'Cost Threshold',
				fieldName: CostApprovalLogic.FIELD_THRESHOLD, fieldContext: 'config',
				fieldLabel: 'Monthly Cost Threshold', inputType: OptionType.InputType.NUMBER,
				displayOrder: 0, required: false,
				helpText: 'Overrides the threshold of the integration for this policy.',
				i18nKey: 'policyThreshold'],
			[code: "${PROVIDER_CODE}-policy-currency".toString(), name: 'Threshold Currency',
				fieldName: CostApprovalLogic.FIELD_CURRENCY, fieldContext: 'config',
				fieldLabel: 'Threshold Currency', inputType: OptionType.InputType.TEXT,
				displayOrder: 1, required: false,
				helpText: 'ISO 4217 code. Empty means the currency of the integration, else of the request.',
				i18nKey: 'policyCurrency']
		])
	}

	/**
	 * Labels and help texts resolve through the plugin's i18n bundles in the viewer's
	 * language; the literal texts stay as fallback. Keys: cost-threshold-approval.<name>.label/.help
	 */
	private static List<OptionType> i18n(List<Map> specs) {
		return specs.collect { Map spec ->
			String key = spec.remove('i18nKey')
			OptionType optionType = new OptionType(spec)
			optionType.fieldCode = "${PROVIDER_CODE}.${key}.label".toString()
			optionType.helpTextI18nCode = "${PROVIDER_CODE}.${key}.help".toString()
			optionType
		}
	}

	@Override
	RequestResponse createApprovalRequest(List instances, Request request, AccountIntegration integration, Policy policy, Map opts) {
		Map policyCfg = config(policy)
		Map integrationCfg = integrationConfig(integration)
		List refs = request?.refs ?: []

		String reqCur = CostApprovalLogic.requestCurrency(request?.currency, refs, { fallbackCurrency(integration) })
		BigDecimal price = CostApprovalLogic.requestPrice(request?.pricePerMonth, refs, reqCur)
		BigDecimal limit = CostApprovalLogic.threshold(policyCfg, opts, integrationCfg)
		String limitCur = CostApprovalLogic.configuredThresholdCurrency(policyCfg, opts, integrationCfg) ?: reqCur
		CostApprovalLogic.Decision decision = CostApprovalLogic.decide(price, reqCur,
			CostApprovalLogic.refCurrencies(refs), limit, limitCur)

		Locale locale = requesterLocale(request)
		String reqId = "${REQUEST_ID_PREFIX}${UUID.randomUUID().toString().take(8)}".toString()
		String name = CostApprovalLogic.requestName(decision, locale)
		RequestReference.ApprovalStatus status = decision.approved ?
			RequestReference.ApprovalStatus.approved : RequestReference.ApprovalStatus.rejected

		// Morpheus 9.0.2 passes its internal domain objects (com.morpheus.RequestReference) in
		// request.refs: never type or cast them to the model class, read them by property name.
		List<RequestReference> outRefs = []
		for (int i = 0; i < refs.size(); i++) {
			Object r = refs[i]
			Object refId = CostApprovalLogic.refValue(r, 'refId')
			outRefs << new RequestReference(refId: refId?.toString(),
				refType: CostApprovalLogic.refValue(r, 'refType')?.toString(),
				name: CostApprovalLogic.refValue(r, 'name')?.toString(),
				pricePerMonth: CostApprovalLogic.refPrice(r),
				currency: CostApprovalLogic.refValue(r, 'currency')?.toString(),
				externalId: "${reqId}-${i}".toString(), externalName: name, status: status)
		}
		// The monitor run reports exactly the decision of this response, never a new one. The put
		// runs inside compute, under the same lock as the remove in monitorApproval, so it can
		// never land in a map that a monitor run has already taken away.
		Request report = new Request(externalId: reqId, externalName: name, refs: outRefs)
		pending.compute(integrationKey(integration)) { Long key, Map<String, Request> reports ->
			Map<String, Request> target = reports ?: newReportMap()
			target.put(reqId, report)
			target
		}
		log.info("Cost threshold approval ${reqId}: ${CostApprovalLogic.money(decision.price, decision.shownPriceCurrency, Locale.ENGLISH)} per month, " +
			"threshold ${CostApprovalLogic.money(decision.threshold, decision.thresholdCurrency, Locale.ENGLISH)} -> ${decision.outcome} (${status})")
		return new RequestResponse(success: true, externalRequestId: reqId, externalRequestName: name,
			references: outRefs, msg: CostApprovalLogic.responseMessage(decision, locale))
	}

	/**
	 * Takes the pending reports of the integration in one atomic remove. Every later decision
	 * goes into a new map through compute, so the removed map is complete and no longer changes.
	 */
	@Override
	List<Request> monitorApproval(AccountIntegration integration) {
		Map<String, Request> mine = pending.remove(integrationKey(integration))
		return mine ? new ArrayList<Request>(mine.values()) : []
	}

	/** The map that collects the pending reports of one integration; a seam for tests. */
	protected Map<String, Request> newReportMap() {
		return new ConcurrentHashMap<String, Request>()
	}

	/** Pending reports of one integration; visible for tests. */
	protected Map<String, Request> pendingFor(AccountIntegration integration) {
		return pending.get(integrationKey(integration)) ?: [:]
	}

	private static Long integrationKey(AccountIntegration integration) {
		return integration?.id ?: 0L
	}

	private static Map config(MorpheusModel model) {
		try {
			return model?.configMap ?: CostApprovalLogic.parseJson(model?.config)
		} catch (Exception ignored) {
			return null
		}
	}

	private static Map integrationConfig(AccountIntegration integration) {
		Map cfg = config(integration)
		return cfg ?: CostApprovalLogic.parseJson(integration?.serviceConfig)
	}

	/**
	 * The locale for the texts written into the request: the requesting user's own setting, else
	 * the browser locale, else English. Without a known requesting user the texts stay in
	 * {@link Messages#DEFAULT_LOCALE}: the approval call need not run in that user's web request.
	 */
	protected Locale requesterLocale(Object request) {
		Long userId = UserLocale.requestingUserId(request)
		if (userId == null) {
			return Messages.DEFAULT_LOCALE
		}
		return UserLocale.resolve(userLocaleSetting(userId), { browserLocale() })
	}

	/** The locale of the current web request (the browser language); throws when there is none. */
	protected Locale browserLocale() {
		return morpheus?.webRequest?.locale
	}

	/**
	 * The user's Morpheus language setting from the internal table user, read through the
	 * read-only report connection; null when unset or not readable. A failure logs one line and
	 * never stops the decision.
	 */
	protected String userLocaleSetting(Long userId) {
		def conn = null
		try {
			conn = morpheus?.report?.getReadOnlyDatabaseConnection()?.blockingGet()
			return conn ? UserLocale.setting(new Sql(conn), userId) : null
		} catch (Exception e) {
			log.debug("Cost threshold approval: language setting of user ${userId} not readable: ${e.message}")
			return null
		} finally {
			if (conn) {
				try {
					morpheus.report.releaseDatabaseConnection(conn).blockingAwait()
				} catch (Exception ignored) {
					// nothing more to do
				}
			}
		}
	}

	/**
	 * Currency of the integration's tenant, else of the master tenant, else USD. Reads the
	 * internal table account through the read-only report connection when the model does
	 * not carry the currency.
	 */
	protected String fallbackCurrency(AccountIntegration integration) {
		String code = CostApprovalLogic.isoCurrency(integration?.account?.currency)
		if (code) {
			return code
		}
		def conn = null
		try {
			conn = morpheus?.report?.getReadOnlyDatabaseConnection()?.blockingGet()
			if (conn) {
				Sql sql = new Sql(conn)
				Long accountId = integration?.account?.id
				def row = accountId ? sql.firstRow('SELECT currency FROM account WHERE id = ?', [accountId]) : null
				code = CostApprovalLogic.isoCurrency(row?.currency)
				if (!code) {
					row = sql.firstRow('SELECT currency FROM account WHERE master_account = 1 ORDER BY id LIMIT 1')
					code = CostApprovalLogic.isoCurrency(row?.currency)
				}
			}
		} catch (Exception e) {
			log.warn("Cost threshold approval: tenant currency not readable, using ${CostApprovalLogic.DEFAULT_CURRENCY}: ${e.message}")
		} finally {
			if (conn) {
				try {
					morpheus.report.releaseDatabaseConnection(conn).blockingAwait()
				} catch (Exception ignored) {
					// nothing more to do
				}
			}
		}
		return code ?: CostApprovalLogic.DEFAULT_CURRENCY
	}
}
