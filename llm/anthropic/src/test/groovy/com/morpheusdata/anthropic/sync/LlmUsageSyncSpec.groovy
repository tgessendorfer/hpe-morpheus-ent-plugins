package com.morpheusdata.anthropic.sync

import com.morpheusdata.anthropic.AnthropicApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.integration.MorpheusLlmIntegrationService
import com.morpheusdata.core.integration.MorpheusLlmService
import com.morpheusdata.model.llm.LlmIntegration
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * Covers when the usage probe runs, and what the usage fields hold when it does not.
 */
class LlmUsageSyncSpec extends Specification {

	List<String> probed = []
	List<LlmIntegration> saved = []
	List<String> logged = []

	AnthropicApiService api = new AnthropicApiService() {
		@Override
		Map fetchUsageHeaders(String baseUrl, String apiKey, String apiVersion, String model, Map opts) {
			probed << baseUrl
			return [success: true, headers: [
				'anthropic-ratelimit-requests-limit'          : '50',
				'anthropic-ratelimit-requests-remaining'      : '49',
				'anthropic-ratelimit-input-tokens-limit'      : '30000',
				'anthropic-ratelimit-input-tokens-remaining'  : '29000',
				'anthropic-ratelimit-input-tokens-reset'      : '2026-10-29T12:00:00Z'
			]]
		}
	}

	/** A context whose save only runs when something subscribes to it. */
	private MorpheusContext savingContext() {
		MorpheusContext context = Mock()
		MorpheusLlmService llm = Mock()
		MorpheusLlmIntegrationService integrations = Mock()
		context.getLlm() >> llm
		llm.getIntegration() >> integrations
		integrations.save(_) >> { List args -> Single.fromCallable { saved << (args[0] as LlmIntegration); args[0] } }
		return context
	}

	/** Records the skip line and a failed save, each with its level. */
	private LlmUsageSync recordingSync(LlmIntegration integration, MorpheusContext context) {
		return new LlmUsageSync(integration, context) {
			@Override
			protected void logInfo(String line) {
				logged << "INFO ${line}".toString()
			}

			@Override
			protected void logDebug(String line) {
				logged << "DEBUG ${line}".toString()
			}

			@Override
			protected void logWarn(String line) {
				logged << "WARN ${line}".toString()
			}
		}
	}

	private static String skipLine(String host) {
		return "Anthropic usage probe skipped for integration 3: ${host} is not api.anthropic.com, " +
			'the only host whose anthropic-ratelimit-* headers describe this integration'
	}

	private static LlmIntegration withStaleUsage() {
		return new LlmIntegration(id: 3L,
			tokenUsageLimit: 30000L, tokenUsageRemaining: 12000L, tokenUsageUsed: 18000L, tokenUsagePeriod: 'minute',
			tokenUsageResetAt: '2026-09-01T08:00:00Z',
			requestUsageLimit: 50L, requestUsageRemaining: 40L, requestUsageUsed: 10L, requestUsagePeriod: 'minute',
			requestUsageResetAt: '2026-09-01T08:00:00Z')
	}

	def "only api.anthropic.com counts as the Anthropic API, in any letter case and with any port or path"() {
		expect:
		LlmUsageSync.isAnthropicApiHost(baseUrl) == expected

		where:
		baseUrl                                         || expected
		'https://api.anthropic.com'                     || true
		'https://API.Anthropic.COM'                     || true
		'https://api.anthropic.com:443/v1'              || true
		'http://api.anthropic.com:8443/some/path'       || true
		'api.anthropic.com'                             || true
		'  https://api.anthropic.com.  '                || true
		'https://openrouter.ai/api'                     || false
		'https://llm-gateway.example.com/anthropic'     || false
		'https://api.anthropic.com.example.com'         || false
		'https://example.com/api.anthropic.com'         || false
		'https://api.anthropic.com@example.com'         || false
		'https://anthropic.com'                         || false
		'https://bad host/v1'                           || false
		''                                              || false
		null                                            || false
	}

	def "against api.anthropic.com the probe runs and its values are saved"() {
		given:
		LlmIntegration llm = new LlmIntegration(id: 3L)

		when:
		recordingSync(llm, savingContext()).execute(api, 'https://api.anthropic.com', 'k', null, ['claude-haiku-4-5'], [:])

		then: 'the save runs, not only its Single is created'
		probed == ['https://api.anthropic.com']
		logged.isEmpty()
		llm.requestUsageLimit == 50L
		llm.requestUsageUsed == 1L
		llm.tokenUsageLimit == 30000L
		llm.tokenUsageRemaining == 29000L
		llm.tokenUsageResetAt == '2026-10-29T12:00:00Z'
		saved == [llm]
	}

	def "through a gateway no probe is sent, and values of an earlier probe are emptied and saved"() {
		given: 'usage from the time the integration still pointed at api.anthropic.com'
		LlmIntegration llm = withStaleUsage()

		when:
		recordingSync(llm, savingContext()).execute(api, baseUrl, 'k', null, ['anthropic/claude-haiku-4.5'], [:])

		then: 'one INFO line says why, and that values were cleared'
		probed.isEmpty()
		LlmUsageSync.USAGE_FIELDS.every { llm."${it}" == null }
		saved == [llm]
		logged == ["INFO ${skipLine(host)}; the usage values of an earlier probe were cleared".toString()]

		where:
		baseUrl                                             || host
		'https://openrouter.ai/api'                         || 'openrouter.ai'
		'https://LLM-Gateway.example.com:8443/anthropic'    || 'llm-gateway.example.com'
	}

	def "through a gateway with nothing stored there is nothing to save"() {
		given:
		LlmIntegration llm = new LlmIntegration(id: 3L)

		when:
		recordingSync(llm, savingContext()).execute(api, 'https://openrouter.ai/api', 'k', null, [], [:])

		then: 'the reason is logged at DEBUG only, since it repeats on every refresh'
		probed.isEmpty()
		saved.isEmpty()
		logged == ["DEBUG ${skipLine('openrouter.ai')}".toString()]
	}

	def "a save that fails is logged and does not break the refresh"() {
		given:
		MorpheusContext context = Mock()
		MorpheusLlmService llm = Mock()
		MorpheusLlmIntegrationService integrations = Mock()
		context.getLlm() >> llm
		llm.getIntegration() >> integrations
		integrations.save(_) >> Single.error(new IllegalStateException('database is read-only'))
		LlmIntegration integration = withStaleUsage()

		when:
		boolean cleared = recordingSync(integration, context).clearUsageMetrics()

		then:
		notThrown(Exception)
		cleared
		integration.tokenUsageLimit == null
		logged == ['WARN Could not save the Anthropic usage values of integration 3: database is read-only']
	}
}
