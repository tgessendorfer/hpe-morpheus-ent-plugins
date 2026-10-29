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
 *
 * Derived from the Apache 2.0 licensed HPE morpheus-copilot-plugin.
 */
package com.morpheusdata.anthropic.sync

import com.morpheusdata.anthropic.AnthropicApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.llm.LlmIntegration
import groovy.util.logging.Slf4j

import java.time.Instant

/**
 * Populates the token/request usage fields on LlmIntegration from the Anthropic
 * rate limit response headers, so the Morpheus UI can display remaining budget.
 *
 * Anthropic reports separate buckets for requests, input tokens and output
 * tokens. Morpheus only models one token bucket, so the input token bucket is
 * used - it is the one that MCP-heavy agent traffic actually exhausts.
 *
 * The probe is a billed request of its own and runs only against api.anthropic.com.
 * Through any other host it is skipped and the usage fields are emptied, since
 * Morpheus keeps no time stamp with them and a value that is never renewed would
 * read as current.
 */
@Slf4j
class LlmUsageSync {

	protected final LlmIntegration llmIntegration
	protected final MorpheusContext morpheusContext

	static final String REQUESTS_LIMIT = 'anthropic-ratelimit-requests-limit'
	static final String REQUESTS_REMAINING = 'anthropic-ratelimit-requests-remaining'
	static final String REQUESTS_RESET = 'anthropic-ratelimit-requests-reset'
	static final String INPUT_TOKENS_LIMIT = 'anthropic-ratelimit-input-tokens-limit'
	static final String INPUT_TOKENS_REMAINING = 'anthropic-ratelimit-input-tokens-remaining'
	static final String INPUT_TOKENS_RESET = 'anthropic-ratelimit-input-tokens-reset'
	static final String TOKENS_LIMIT = 'anthropic-ratelimit-tokens-limit'
	static final String TOKENS_REMAINING = 'anthropic-ratelimit-tokens-remaining'
	static final String TOKENS_RESET = 'anthropic-ratelimit-tokens-reset'

	// Each probe is a request of its own; three are enough to get past a stale catalog.
	static final int MAX_PROBE_MODELS = 3

	// The only host the probe is sent to.
	static final String ANTHROPIC_API_HOST = 'api.anthropic.com'

	// Everything the probe fills in, and everything emptied when it does not run.
	static final List<String> USAGE_FIELDS = [
		'tokenUsageLimit', 'tokenUsageRemaining', 'tokenUsageUsed', 'tokenUsagePeriod', 'tokenUsageResetAt',
		'requestUsageLimit', 'requestUsageRemaining', 'requestUsageUsed', 'requestUsagePeriod', 'requestUsageResetAt'
	]

	static final List<String> RATE_LIMIT_HEADERS = [
		REQUESTS_LIMIT, REQUESTS_REMAINING, REQUESTS_RESET,
		INPUT_TOKENS_LIMIT, INPUT_TOKENS_REMAINING, INPUT_TOKENS_RESET,
		TOKENS_LIMIT, TOKENS_REMAINING, TOKENS_RESET
	]

	LlmUsageSync(LlmIntegration llmIntegration, MorpheusContext morpheusContext) {
		this.llmIntegration = llmIntegration
		this.morpheusContext = morpheusContext
	}

	void execute(AnthropicApiService apiService, String baseUrl, String apiKey, String apiVersion, String model, Map opts = [:]) {
		execute(apiService, baseUrl, apiKey, apiVersion, model ? [model] : [], opts)
	}

	/**
	 * Probes with the first model, moving on to the next only when the API says the
	 * model does not exist or has been retired - a catalog can still list one. Any
	 * other failure is not about the model, so it is not retried. A base URL whose
	 * host is not api.anthropic.com gets no probe at all.
	 */
	void execute(AnthropicApiService apiService, String baseUrl, String apiKey, String apiVersion, List<String> models, Map opts = [:]) {
		if (!llmIntegration || !apiService) {
			return
		}
		if (!isAnthropicApiHost(baseUrl)) {
			skipProbe(baseUrl)
			return
		}
		List<String> candidates = (models ?: [null]).take(MAX_PROBE_MODELS)
		Map usageResult = null
		for (int i = 0; i < candidates.size(); i++) {
			usageResult = apiService.fetchUsageHeaders(baseUrl, apiKey, apiVersion, candidates[i], opts) ?: [success: false, msg: 'No usage response from the Anthropic API']
			if (usageResult.success == true || !isModelUnavailable(usageResult) || i == candidates.size() - 1) {
				break
			}
			log.info("Anthropic usage probe model ${candidates[i]} is not available (${usageResult.msg}); trying ${candidates[i + 1]}")
		}
		if (usageResult.success != true) {
			log.warn("Unable to refresh Anthropic usage metrics: ${usageResult.msg ?: 'unknown error'}")
		}
		Map usageHeaders = extractRateLimitHeaders(usageResult?.headers instanceof Map ? usageResult.headers as Map : [:])
		if (!usageHeaders) {
			log.debug('No Anthropic rate limit headers present in the usage probe response')
			return
		}
		if (applyHeadersUsageMetrics(usageHeaders)) {
			saveIntegration()
		}
	}

	/**
	 * True when the base URL's host is api.anthropic.com, in any letter case, with any
	 * port or path. Only that host is known to answer the probe with the
	 * anthropic-ratelimit-* headers: OpenRouter sends none, and what another gateway
	 * passes on would describe the gateway's own Anthropic account, not this integration.
	 */
	static boolean isAnthropicApiHost(String baseUrl) {
		return urlHost(baseUrl) == ANTHROPIC_API_HOST
	}

	/** The host of a URL, lower case and without a trailing dot; null when it has none. */
	static String urlHost(String url) {
		String raw = url?.trim()
		if (!raw) {
			return null
		}
		try {
			String host = new URI(raw.contains('://') ? raw : "https://${raw}".toString()).host
			return host ? host.toLowerCase(Locale.ROOT).replaceFirst('\\.$', '') : null
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * No probe through a gateway: it would be billed and bring back no Anthropic limits
	 * for this integration. Values an earlier probe stored, before the endpoint changed,
	 * are emptied rather than left on show as if they were current.
	 */
	protected void skipProbe(String baseUrl) {
		String reason = "Anthropic usage probe skipped for integration ${integrationLabel()}: " +
			"${urlHost(baseUrl) ?: 'the endpoint'} is not ${ANTHROPIC_API_HOST}, the only host whose anthropic-ratelimit-* headers describe this integration"
		if (clearUsageMetrics()) {
			logInfo("${reason}; the usage values of an earlier probe were cleared")
		} else {
			// Repeats on every refresh of a gateway integration, so it stays at DEBUG.
			logDebug(reason)
		}
	}

	/** Empties every usage field and saves the integration when one held a value. */
	boolean clearUsageMetrics() {
		if (!llmIntegration) {
			return false
		}
		boolean saveRequired = false
		USAGE_FIELDS.each { String field ->
			saveRequired = assignIfChanged(field, null) || saveRequired
		}
		if (saveRequired) {
			saveIntegration()
		}
		return saveRequired
	}

	/**
	 * The data service hands back an RxJava Single; blocking on it makes sure the save
	 * has run before the refresh moves on, as the model sync does with its saves.
	 */
	protected void saveIntegration() {
		def integrationService = morpheusContext?.llm?.integration
		if (!integrationService) {
			return
		}
		try {
			integrationService.save(llmIntegration)?.blockingGet()
		} catch (Exception e) {
			logWarn("Could not save the Anthropic usage values of integration ${integrationLabel()}: ${e.message}")
		}
	}

	// The skip line and a failed save go through these, so a test can check their
	// level and text.
	protected void logInfo(String line) {
		log.info(line)
	}

	protected void logDebug(String line) {
		log.debug(line)
	}

	protected void logWarn(String line) {
		log.warn(line)
	}

	protected String integrationLabel() {
		return (llmIntegration?.accountIntegration?.id ?: llmIntegration?.id)?.toString() ?: 'without id'
	}

	protected static boolean isModelUnavailable(Map usageResult) {
		String text = "${usageResult?.statusCode ?: ''} ${usageResult?.errorType ?: ''} ${usageResult?.msg ?: ''}".toString().toLowerCase()
		return text.contains('404') || text.contains('not_found') || text.contains('end of its life') || text.contains('deprecated')
	}

	boolean applyHeadersUsageMetrics(Map headers) {
		if (!llmIntegration || !headers) {
			return false
		}
		boolean saveRequired = false

		Long tokenLimit = parseLongValue(headers[INPUT_TOKENS_LIMIT] ?: headers[TOKENS_LIMIT])
		Long tokenRemaining = parseLongValue(headers[INPUT_TOKENS_REMAINING] ?: headers[TOKENS_REMAINING])
		String tokenReset = parseResetAt(headers[INPUT_TOKENS_RESET] ?: headers[TOKENS_RESET])
		if (tokenLimit != null || tokenRemaining != null) {
			saveRequired = assignIfChanged('tokenUsageLimit', tokenLimit) || saveRequired
			saveRequired = assignIfChanged('tokenUsageRemaining', tokenRemaining) || saveRequired
			if (tokenLimit != null && tokenRemaining != null) {
				saveRequired = assignIfChanged('tokenUsageUsed', Math.max(tokenLimit - tokenRemaining, 0L)) || saveRequired
			}
			saveRequired = assignIfChanged('tokenUsagePeriod', 'minute') || saveRequired
			saveRequired = assignIfChanged('tokenUsageResetAt', tokenReset) || saveRequired
		}

		Long requestLimit = parseLongValue(headers[REQUESTS_LIMIT])
		Long requestRemaining = parseLongValue(headers[REQUESTS_REMAINING])
		String requestReset = parseResetAt(headers[REQUESTS_RESET])
		if (requestLimit != null || requestRemaining != null) {
			saveRequired = assignIfChanged('requestUsageLimit', requestLimit) || saveRequired
			saveRequired = assignIfChanged('requestUsageRemaining', requestRemaining) || saveRequired
			if (requestLimit != null && requestRemaining != null) {
				saveRequired = assignIfChanged('requestUsageUsed', Math.max(requestLimit - requestRemaining, 0L)) || saveRequired
			}
			saveRequired = assignIfChanged('requestUsagePeriod', 'minute') || saveRequired
			saveRequired = assignIfChanged('requestUsageResetAt', requestReset) || saveRequired
		}

		return saveRequired
	}

	protected Map<String, String> extractRateLimitHeaders(Map responseHeaders) {
		Map<String, String> headers = [:]
		if (!(responseHeaders instanceof Map) || responseHeaders.isEmpty()) {
			return headers
		}
		RATE_LIMIT_HEADERS.each { String headerName ->
			Map.Entry matchedHeader = responseHeaders.entrySet().find { entry ->
				entry?.key?.toString()?.equalsIgnoreCase(headerName) && entry?.value != null && entry.value.toString() != ''
			}
			if (matchedHeader) {
				headers[headerName] = matchedHeader.value.toString()
			}
		}
		return headers
	}

	protected Long parseLongValue(def value) {
		if (value == null || value == '') {
			return null
		}
		if (value instanceof Number) {
			return ((Number) value).longValue()
		}
		try {
			return value.toString().trim().toLong()
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * Anthropic sends RFC3339 timestamps (2026-09-08T12:34:56Z). Epoch values are
	 * still tolerated in case a gateway rewrites the header.
	 */
	protected String parseResetAt(def value) {
		if (value == null || value.toString().trim().isEmpty()) {
			return null
		}
		String raw = value.toString().trim()
		Long epochValue = parseLongValue(raw)
		if (epochValue == null) {
			try {
				return Instant.parse(raw).toString()
			} catch (Exception ignored) {
				return raw
			}
		}
		try {
			return epochValue > 100000000000L ? Instant.ofEpochMilli(epochValue).toString() : Instant.ofEpochSecond(epochValue).toString()
		} catch (Exception ignored) {
			return raw
		}
	}

	protected boolean assignIfChanged(String propertyName, Object value) {
		if (llmIntegration?."${propertyName}" != value) {
			llmIntegration?."${propertyName}" = value
			return true
		}
		return false
	}
}
