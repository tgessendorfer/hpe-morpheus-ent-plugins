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
package com.morpheusdata.anthropic

import com.morpheusdata.anthropic.sync.LlmModelsSync
import com.morpheusdata.anthropic.sync.LlmUsageSync
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.LlmProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.llm.*
import com.morpheusdata.response.LlmStreamingResponseHandler
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.math.RoundingMode
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * LlmProvider implementation for Anthropic Claude using the native Messages API.
 *
 * Morpheus speaks the OpenAI tool-calling convention internally (tool_calls /
 * tool_call_id in LlmChatMessage metadata). Anthropic instead uses tool_use and
 * tool_result content blocks. This provider translates in both directions so the
 * Morpheus MCP tool loop works unchanged against Claude.
 */
@Slf4j
class AnthropicProvider implements LlmProvider {

	Plugin plugin
	MorpheusContext morpheusContext
	AnthropicApiService apiService

	static final String PROVIDER_CODE = 'anthropic-claude'
	static final String PROVIDER_NAME = 'Anthropic Claude'
	static final String DEFAULT_API_URL = 'https://api.anthropic.com'
	static final String DEFAULT_CHAT_MODEL = 'claude-sonnet-4-6'
	static final Integer DEFAULT_MAX_OUTPUT_TOKENS = 8192
	static final Integer DEFAULT_THINKING_BUDGET_TOKENS = 4096
	static final Integer MIN_THINKING_BUDGET_TOKENS = 1024
	static final Long STANDARD_CONTEXT_WINDOW = 200000L
	static final Long LONG_CONTEXT_WINDOW = 1000000L

	// Server-side tool versions. The dated variants carry dynamic filtering, which
	// runs the search from inside code execution and needs a model that supports
	// programmatic tool calling (Claude 4.6 and newer); older models must be sent
	// the basic variants or the request comes back 400.
	static final String WEB_SEARCH_TOOL_TYPE = 'web_search_20260318'
	static final String WEB_SEARCH_TOOL_TYPE_BASIC = 'web_search_20250305'
	static final String WEB_FETCH_TOOL_TYPE = 'web_fetch_20260318'
	static final String WEB_FETCH_TOOL_TYPE_BASIC = 'web_fetch_20250910'
	static final Integer DEFAULT_WEB_SEARCH_MAX_USES = 5
	static final Integer MAX_LISTED_SOURCES = 8
	static final Integer MAX_SOURCE_LABEL_LENGTH = 90
	// A paused turn is resumed by resending it unchanged; the cap stops a runaway
	// server-tool loop from spending the whole conversation on one answer.
	static final Integer MAX_PAUSE_TURN_CONTINUATIONS = 4
	// One or more italic usage lines at the very end of an answer.
	static final String USAGE_FOOTER_PATTERN = '(?:\\s*\\*(?:Tokens|Cost|Kosten|Koszt|Náklady|Költség): [^*\\n]*\\*)+\\s*$'
	// Enough to pick the footer's language, not a language detector: answers in each
	// language are full of its words and next to never contain the others'. Letters
	// only one of the languages uses count extra.
	static final Map<String, Set<String>> LANGUAGE_MARKERS = [
		en: ['the', 'and', 'is', 'are', 'of', 'to', 'with', 'not', 'no', 'for', 'there', 'this', 'that', 'it', 'be', 'on', 'as', 'by', 'has', 'have'] as Set,
		de: ['der', 'die', 'das', 'den', 'dem', 'und', 'ist', 'sind', 'nicht', 'keine', 'mit', 'auf', 'ein', 'eine', 'gibt', 'es', 'auch', 'wird', 'oder', 'bei', 'zu', 'von', 'im', 'sich', 'wie'] as Set,
		pl: ['jest', 'są', 'się', 'nie', 'oraz', 'dla', 'czy', 'które', 'który', 'która', 'będzie', 'żadnych', 'działa', 'działają', 'wszystkie', 'w', 'i', 'na'] as Set,
		cs: ['je', 'jsou', 'není', 'nejsou', 'nebo', 'pro', 'které', 'který', 'která', 'bude', 'běží', 'žádné', 'také', 'všechny', 'v', 've'] as Set,
		hu: ['a', 'az', 'és', 'nem', 'van', 'vannak', 'egy', 'hogy', 'ez', 'is', 'fut', 'futnak', 'összes', 'szerver', 'szerverek', 'nincs', 'mind'] as Set,
		ro: ['și', 'este', 'sunt', 'nu', 'de', 'la', 'în', 'cu', 'pentru', 'care', 'rulează', 'toate', 'servere', 'serverele', 'nicio', 'niciun'] as Set
	]
	static final Map<String, String> LANGUAGE_LETTERS = [de: '[äß]', pl: '[ąęłńśźż]', cs: '[ěřůťď]', hu: '[őű]', ro: '[ăâîșțşţ]']
	// One question is many billed requests once the agent calls tools, and only the
	// last of them carries the answer the footer goes on - so cost is kept per question.
	protected static final ConcurrentHashMap<String, Map> QUESTION_COSTS = new ConcurrentHashMap<>()
	static final long QUESTION_COST_TTL_MS = 60L * 60L * 1000L
	static final String REPLACEMENT_CHARACTER_NOTE = 'Some earlier turns of this conversation came back from the chat ' +
		'application with characters replaced by U+FFFD. Treat each such character as unknown and never copy it; ' +
		'write every character of your answer correctly, including umlauts and other accented letters.'

	AnthropicProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
		this.apiService = new AnthropicApiService()
	}

	@Override
	String getCode() { return PROVIDER_CODE }

	@Override
	String getName() { return PROVIDER_NAME }

	@Override
	MorpheusContext getMorpheus() { return this.morpheusContext }

	@Override
	Plugin getPlugin() { return this.plugin }

	@Override
	Icon getIcon() {
		// The filename carries the icon revision on purpose: plugin assets are served
		// from a stable URL, so reusing a name leaves browsers showing the previous
		// icon after an upgrade, and a hard reload does not always clear it.
		return new Icon(path: 'anthropic-mark.svg', darkPath: 'anthropic-mark-white.svg')
	}

	@Override
	String getDescription() {
		// Kept under 255 characters, the size of Morpheus' description columns.
		return 'Claude through the native Messages API, with MCP tool use, prompt caching, thinking and web search. ' +
			'Direct or via OpenRouter, which lists only its Claude models; other vendors need a separate OpenRouter integration.'
	}

	@Override
	Boolean getCreatable() { return true }

	@Override
	Boolean getEnabled() { return true }

	@Override
	Boolean getChatSupported() { return true }

	@Override
	Boolean getStreamingChatSupported() { return true }

	@Override
	Boolean getEmbeddingSupported() { return false }

	@Override
	List<OptionType> getOptionTypes() {
		List<OptionType> optionTypes = []

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.serviceUrl",
			name: "Service URL",
			fieldName: "serviceUrl",
			fieldLabel: "API Endpoint",
			fieldContext: "domain",
			inputType: OptionType.InputType.TEXT,
			displayOrder: 0,
			required: true,
			defaultValue: DEFAULT_API_URL,
			helpText: 'https://api.anthropic.com, or an Anthropic-compatible gateway such as https://openrouter.ai/api. Through OpenRouter only the Anthropic Claude models are listed.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.credential",
			name: "Credentials",
			inputType: OptionType.InputType.CREDENTIAL,
			fieldName: "type",
			fieldLabel: "Credentials",
			fieldContext: "credential",
			required: true,
			displayOrder: 1,
			defaultValue: "local",
			optionSource: "credentials",
			config: '{"credentialTypes":["api-key"]}'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.servicePassword",
			name: "API Key",
			inputType: OptionType.InputType.PASSWORD,
			fieldName: "servicePassword",
			fieldLabel: "API Key",
			fieldContext: "domain",
			displayOrder: 2,
			required: true,
			localCredential: true
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.useNetworkProxy",
			name: "Use Network Proxy",
			fieldName: "useNetworkProxy",
			fieldLabel: "Route Outbound Calls Through a Proxy",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 3,
			required: false,
			helpText: 'Off means a direct connection. This checkbox exists because the integration form gives no way to clear a chosen proxy: Morpheus 9.0.1 renders the dropdown below with no empty entry, so unticking this is how you go back to connecting directly.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.networkProxy",
			name: "Network Proxy",
			fieldName: "networkProxy",
			fieldLabel: "Network Proxy",
			fieldContext: "config",
			inputType: OptionType.InputType.SELECT,
			optionSource: "networkProxies",
			displayOrder: 4,
			required: false,
			// Both of these are the documented way to offer an empty entry, and
			// Morpheus 9.0.1 honours neither in the integration form - verified on a
			// live appliance. Kept because they are correct, and a release that
			// honours them would make the checkbox above redundant.
			noBlank: false,
			noSelection: "No Proxy",
			helpText: 'The proxy to use, from Infrastructure > Networks > Proxies. Only applied when the box above is ticked. The appliance makes the call, so this is the proxy that needs to reach api.anthropic.com - each integration picks its own, independently of the proxy any cloud uses.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.apiVersion",
			name: "API Version",
			fieldName: "apiVersion",
			fieldLabel: "Anthropic API Version",
			fieldContext: "config",
			inputType: OptionType.InputType.TEXT,
			displayOrder: 5,
			required: false,
			defaultValue: AnthropicApiService.DEFAULT_API_VERSION,
			helpText: 'Value sent in the anthropic-version header. Leave at the default unless Anthropic tells you otherwise.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.maxOutputTokens",
			name: "Max Output Tokens",
			fieldName: "maxOutputTokens",
			fieldLabel: "Default Max Output Tokens",
			fieldContext: "config",
			inputType: OptionType.InputType.NUMBER,
			displayOrder: 6,
			required: false,
			defaultValue: DEFAULT_MAX_OUTPUT_TOKENS.toString(),
			helpText: 'The Messages API requires max_tokens on every request. Used when the caller does not supply one.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.promptCaching",
			name: "Prompt Caching",
			fieldName: "promptCaching",
			fieldLabel: "Enable Prompt Caching",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 7,
			required: false,
			defaultValue: 'on',
			helpText: 'Marks the system prompt and tool definitions as cacheable. Strongly recommended for MCP-backed Agents, where the same large tool catalog is resent on every turn.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.thinkingEnabled",
			name: "Extended Thinking",
			fieldName: "thinkingEnabled",
			fieldLabel: "Enable Extended Thinking",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 8,
			required: false,
			helpText: 'Lets the model reason before answering. Slower and more expensive; temperature and top_p are ignored while enabled.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.thinkingBudgetTokens",
			name: "Thinking Budget",
			fieldName: "thinkingBudgetTokens",
			fieldLabel: "Thinking Budget (tokens)",
			fieldContext: "config",
			inputType: OptionType.InputType.NUMBER,
			displayOrder: 9,
			required: false,
			defaultValue: DEFAULT_THINKING_BUDGET_TOKENS.toString(),
			helpText: 'Minimum 1024. Must stay below Max Output Tokens - the provider raises max_tokens automatically if needed.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.longContext",
			name: "1M Context Window",
			fieldName: "longContext",
			fieldLabel: "Enable 1M Token Context (beta)",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 10,
			required: false,
			helpText: 'Sends the context-1m beta header. Only supported on Sonnet 4.5 and newer, and priced at a premium above 200k input tokens.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.samplingParams",
			name: "Sampling Parameters",
			fieldName: "samplingParams",
			fieldLabel: "Send temperature and top_p",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 11,
			required: false,
			helpText: 'Off by default. Newer Claude models reject temperature with "400 `temperature` is deprecated for this model", and Morpheus supplies one on every chat request. Only enable this against models that still accept sampling parameters.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.usageFooter",
			name: "Token Usage Footer",
			fieldName: "usageFooter",
			fieldLabel: "Append token usage to answers",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 12,
			required: false,
			helpText: 'Adds an italic line with cached, input and output token counts to the end of each final answer. Morpheus does not display token usage anywhere in the chat, so this is the only way to see the prompt cache working without reading the appliance log. Intermediate tool-call turns are left untouched. Through OpenRouter, which reports what each request cost, the line shows the cost of the whole question instead, summed over all of its requests.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.webSearch",
			name: "Web Search",
			fieldName: "webSearch",
			fieldLabel: "Enable Web Search and Fetch",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 13,
			required: false,
			helpText: 'Adds Anthropic\'s server-side web_search and web_fetch tools. Anthropic runs both on its own infrastructure inside the same API call, so the appliance needs no extra egress and the agent needs no additional MCP server. Web search is billed at $10 per 1,000 searches on top of tokens; web fetch costs only the tokens of the page it reads. Answers gain a Sources list.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.webSearchCodeFiltering",
			name: "Web Search Code Filtering",
			fieldName: "webSearchCodeFiltering",
			fieldLabel: "Filter Search Results with Code Execution",
			fieldContext: "config",
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 14,
			required: false,
			helpText: 'Off by default. On Claude 4.6 and newer, lets Claude filter search results by running code before they reach the context window, which saves tokens on search-heavy research. The catch for MCP-backed agents: once code execution is available the model also uses it to process tool results, so a tool round costs an extra inference round and a fresh sandbox container - measured even on questions that never searched the web. Leave it off for agents that use MCP tools.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.webSearchMaxUses",
			name: "Web Search Max Uses",
			fieldName: "webSearchMaxUses",
			fieldLabel: "Max Web Searches per Request",
			fieldContext: "config",
			inputType: OptionType.InputType.NUMBER,
			displayOrder: 15,
			required: false,
			defaultValue: DEFAULT_WEB_SEARCH_MAX_USES.toString(),
			helpText: 'Hard cap on searches and fetches for a single request, applied to both tools. Simple questions use one to three searches. This is the only ceiling on what a looping agent can spend on search.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.webSearchAllowedDomains",
			name: "Web Search Allowed Domains",
			fieldName: "webSearchAllowedDomains",
			fieldLabel: "Restrict to Domains",
			fieldContext: "config",
			inputType: OptionType.InputType.TEXT,
			displayOrder: 16,
			required: false,
			helpText: 'Optional comma-separated allow list, for example: docs.morpheusdata.com, community.hpe.com, support.hpe.com. Bare hostnames with an optional path and no scheme. Leave empty to search the whole web. Narrowing this is the strongest control against a fetched page trying to talk the agent into something.'
		)

		// Labels and help texts resolve through the plugin's i18n bundles in
		// src/main/resources/i18n, in the viewer's language; the literal texts above
		// stay as the fallback.
		optionTypes.each { OptionType optionType ->
			optionType.fieldCode = "${optionType.code}.label".toString()
			if (optionType.helpText) {
				optionType.helpTextI18nCode = "${optionType.code}.help".toString()
			}
		}
		return optionTypes
	}

	@Override
	ServiceResponse validate(LlmIntegration llmIntegration, Map opts) {
		try {
			AccountIntegration accountIntegration = llmIntegration?.accountIntegration
			if (!accountIntegration) {
				return ServiceResponse.error('Account integration is required for the Anthropic integration')
			}
			String apiKey = resolveApiKey(accountIntegration)
			if (!apiKey) {
				return ServiceResponse.error('An Anthropic API key is required')
			}
			def result = apiService.listModels(resolveBaseUrl(accountIntegration), apiKey,
				resolveApiVersion(accountIntegration), buildClientOpts(accountIntegration))
			if (result.success) {
				return ServiceResponse.success(llmIntegration)
			}
			return ServiceResponse.error(result.msg ?: 'Failed to verify the Anthropic connection')
		} catch (Exception e) {
			log.error("Error verifying Anthropic integration: ${e.message}", e)
			return ServiceResponse.error("Verification failed: ${e.message}")
		}
	}

	@Override
	void refresh(LlmIntegration llmIntegration) {
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		if (!accountIntegration) {
			log.warn('Cannot refresh Anthropic integration - account integration is missing')
			return
		}
		try {
			String apiKey = resolveApiKey(accountIntegration)
			if (!apiKey) {
				log.warn('Cannot refresh Anthropic integration - no API key configured')
				return
			}
			String baseUrl = resolveBaseUrl(accountIntegration)
			String apiVersion = resolveApiVersion(accountIntegration)
			// Refresh runs on a schedule, long after anyone was watching the form.
			// It needs the proxy as much as the chat does, or saving the integration
			// succeeds and the model catalog quietly stops updating.
			Map clientOpts = buildClientOpts(accountIntegration)
			new LlmModelsSync(morpheusContext, llmIntegration, PROVIDER_CODE, apiService)
				.execute(baseUrl, apiKey, apiVersion, clientOpts) { Map apiResponse ->
					buildModelsFromApiResponse(llmIntegration, apiResponse)
				}
			new LlmUsageSync(llmIntegration, morpheusContext)
				.execute(apiService, baseUrl, apiKey, apiVersion, resolveUsageProbeModel(llmIntegration), clientOpts)
		} catch (Exception e) {
			log.error("Error refreshing Anthropic integration: ${e.message}", e)
		}
	}

	@Override
	ServiceResponse<LlmChatResponse> generateResponse(LlmIntegration llmIntegration, LlmChatRequest request, Map opts) {
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		if (!accountIntegration) {
			return ServiceResponse.error('Account integration is required for the Anthropic integration')
		}
		String apiKey = resolveApiKey(accountIntegration)
		String baseUrl = resolveBaseUrl(accountIntegration)
		String apiVersion = resolveApiVersion(accountIntegration)
		Map requestBody = buildMessagesRequestBody(request, accountIntegration, false)

		int maxAttempts = 3
		Exception lastException = null
		Map requestOpts = buildClientOpts(accountIntegration, opts)
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			if (attempt > 1) {
				log.warn("Anthropic API retry ${attempt - 1}/${maxAttempts - 1} after connection failure")
				Thread.sleep(1500L * (attempt - 1))
			}
			try {
				Map result = runToCompletion(requestBody) { Map body ->
					apiService.createMessage(baseUrl, apiKey, body, apiVersion, resolveBetas(accountIntegration), requestOpts) as Map
				}
				if (result.success && result.data) {
					LlmChatResponse response = trackQuestionCost(requestBody, parseMessageResponse(result.data as Map))
					return ServiceResponse.success(appendUsageFooter(appendSourceList(response), accountIntegration))
				}
				return ServiceResponse.error(result.msg ?: 'Chat completion failed')
			} catch (Exception e) {
				lastException = e
				if (isRetryableError(e) && attempt < maxAttempts) {
					log.warn("Anthropic connection error (attempt ${attempt}): ${e.message}")
					continue
				}
				log.error("Error during Anthropic chat completion: ${e.message}", e)
				return ServiceResponse.error("Chat completion failed: ${e.message}")
			}
		}
		return ServiceResponse.error("Chat completion failed after ${maxAttempts} attempts: ${lastException?.message}")
	}

	@Override
	void streamResponse(LlmIntegration llmIntegration, LlmChatRequest request, LlmStreamingResponseHandler handler, Map opts) {
		try {
			AccountIntegration accountIntegration = llmIntegration?.accountIntegration
			if (!accountIntegration) {
				handler?.onError(new IllegalArgumentException('Account integration is required for the Anthropic integration'))
				return
			}
			String apiKey = resolveApiKey(accountIntegration)
			String baseUrl = resolveBaseUrl(accountIntegration)
			String apiVersion = resolveApiVersion(accountIntegration)
			Map requestBody = buildMessagesRequestBody(request, accountIntegration, true)

			Map streamOpts = buildClientOpts(accountIntegration, opts)
			Map result = runToCompletion(requestBody) { Map body ->
				apiService.streamMessage(baseUrl, apiKey, body, apiVersion, resolveBetas(accountIntegration), { String chunk ->
					handler?.onPartialResponse(chunk)
				}, streamOpts)
			}

			if (result?.success && result.data) {
				LlmChatResponse response = trackQuestionCost(requestBody, parseMessageResponse(result.data as Map))
				handler?.onCompleteResponse(appendUsageFooter(appendSourceList(response), accountIntegration))
			} else {
				handler?.onError(new RuntimeException(result?.msg ?: 'Streaming chat completion failed'))
			}
		} catch (Exception e) {
			log.error("Error during Anthropic streaming chat: ${e.message}", e)
			handler?.onError(e)
		}
	}

	// ------------------------------------------------------------------
	// Server-tool turns
	// ------------------------------------------------------------------

	/**
	 * Runs one request through to a finished turn, resuming across pause_turn.
	 *
	 * The server-side tool loop has an iteration limit. When it is reached mid
	 * answer the API returns {@code stop_reason: pause_turn} with a partial turn
	 * instead of a finished one, and the caller is expected to send it straight
	 * back. Without this the agent would show a half-written answer that stops
	 * in the middle of a sentence, which is exactly what a web search that took
	 * more than a handful of round trips would produce.
	 */
	protected Map runToCompletion(Map requestBody, Closure<Map> call) {
		Map result = call(requestBody)
		if (result?.success != true || !(result.data instanceof Map)) {
			return result
		}
		Map data = result.data as Map
		if (data.stop_reason != 'pause_turn') {
			return result
		}

		List<Map> segments = [data]
		List messages = new ArrayList((requestBody.messages ?: []) as List)
		int continuations = 0
		// Server tools are what pause a turn, and web search is the only server tool this
		// plugin declares - so this is logged only for integrations with web search on.
		boolean logPauses = hasWebSearchTools(requestBody)
		while (data.stop_reason == 'pause_turn' && continuations < MAX_PAUSE_TURN_CONTINUATIONS) {
			continuations++
			if (logPauses) {
				log.info("Anthropic pause_turn ${continuations}: resending a turn that paused with ${describeTurn(data)}")
			}
			// Resumed by handing the paused assistant turn back unchanged - no
			// "continue" message. Anthropic sees the trailing server_tool_use block
			// and picks up where it left off. The blocks must go back verbatim:
			// search results carry an encrypted_content field the API decrypts to
			// restore them, and a modified one is a 400.
			messages = messages + [[role: 'assistant', content: data.content]]
			Map continued = new LinkedHashMap(requestBody)
			continued.messages = messages
			Map next
			try {
				next = call(continued)
			} catch (Exception e) {
				// Deliberately not rethrown: the caller's retry would re-run the
				// whole turn, paying for every search in it a second time.
				log.warn("Anthropic pause_turn continuation ${continuations} threw (${e.message}); returning the partial answer")
				break
			}
			if (next?.success != true || !(next.data instanceof Map)) {
				log.warn("Anthropic pause_turn continuation ${continuations} failed (${next?.msg}); returning the partial answer")
				break
			}
			data = next.data as Map
			segments << data
			result = next
		}
		if (logPauses) {
			log.info("Anthropic turn finished after ${continuations} pause_turn continuation(s): ${describeTurn(data)}")
		}
		if (data.stop_reason == 'pause_turn') {
			log.warn("Anthropic turn still paused after ${continuations} continuations; returning what has been generated so far")
		}

		Map merged = new LinkedHashMap(result)
		merged.data = mergeMessageSegments(segments)
		return merged
	}

	protected static boolean hasWebSearchTools(Map requestBody) {
		return (requestBody?.tools instanceof List) && (requestBody.tools as List).any { tool ->
			tool instanceof Map && tool.type?.toString()?.startsWith('web_')
		}
	}

	/** How a turn ended: its stop reason, block sequence, cache read and container, for the log. */
	protected static String describeTurn(Map data) {
		List blocks = data?.content instanceof List ? data.content as List : []
		String shape = blocks.collect { block ->
			Map b = block instanceof Map ? block as Map : [:]
			b.name ? "${b.type}:${b.name}" : "${b.type}"
		}.join(', ')
		Map usage = data?.usage instanceof Map ? data.usage as Map : [:]
		def container = data?.container instanceof Map ? (data.container as Map).id : data?.container
		return "stop_reason=${data?.stop_reason} blocks=[${shape}] read=${usage.cache_read_input_tokens ?: 0} " +
			"output=${usage.output_tokens ?: 0} container=${container ?: 'none'}"
	}

	/**
	 * Fold the segments of a resumed turn back into one message payload, so the
	 * rest of the provider only ever sees a single response.
	 */
	protected Map mergeMessageSegments(List<Map> segments) {
		if (!segments) {
			return [:]
		}
		if (segments.size() == 1) {
			return segments[0]
		}
		List content = []
		Map usage = [:]
		segments.each { Map segment ->
			if (segment.content instanceof List) {
				content.addAll(segment.content as List)
			}
			if (segment.usage instanceof Map) {
				(segment.usage as Map).each { key, value ->
					// Every segment is a billed request of its own, so the counts add up.
					if (value instanceof Integer || value instanceof Long) {
						usage[key] = (toInteger(usage[key]) ?: 0) + ((Number) value).intValue()
					} else if (value instanceof Number) {
						// A decimal such as OpenRouter's cost; truncated to an int it reads as free.
						BigDecimal sofar = usage[key] instanceof Number ? new BigDecimal(usage[key].toString()) : BigDecimal.ZERO
						usage[key] = sofar + new BigDecimal(value.toString())
					} else if (!usage.containsKey(key)) {
						usage[key] = value
					}
				}
			}
		}
		Map merged = new LinkedHashMap(segments[-1])
		merged.content = content
		if (usage) {
			merged.usage = usage
		}
		return merged
	}

	// ------------------------------------------------------------------
	// Request translation: Morpheus (OpenAI-shaped) -> Anthropic Messages
	// ------------------------------------------------------------------

	/**
	 * Build a /v1/messages request body from an LlmChatRequest.
	 *
	 * Handles the three structural differences to the OpenAI schema:
	 *  - system prompts are a top-level field, not a message
	 *  - assistant tool calls become tool_use content blocks
	 *  - tool results become tool_result blocks inside a user message
	 */
	protected Map buildMessagesRequestBody(LlmChatRequest request, AccountIntegration accountIntegration, Boolean stream) {
		List<String> systemParts = []
		List<Map> messages = []

		request.messages?.each { LlmChatMessage msg ->
			String role = msg.role?.toLowerCase() ?: 'user'
			Map metadata = msg.metadata ?: [:]

			if (role == 'system' || role == 'developer') {
				if (msg.content) {
					systemParts << msg.content.toString()
				}
				return
			}

			if (role == 'tool' || metadata.tool_call_id) {
				Map toolResult = [
					type       : 'tool_result',
					tool_use_id: metadata.tool_call_id?.toString(),
					content    : msg.content?.toString() ?: ''
				]
				// Anthropic requires tool_result blocks to sit in a user message and
				// expects every result of one assistant turn in a single message.
				Map previous = messages ? messages[-1] : null
				if (previous && previous.role == 'user' && previous.content instanceof List &&
					(previous.content as List).every { it instanceof Map && it.type == 'tool_result' }) {
					(previous.content as List) << toolResult
				} else {
					messages << [role: 'user', content: [toolResult]]
				}
				return
			}

			if (role == 'assistant' && metadata.tool_calls) {
				List<Map> blocks = []
				if (msg.content) {
					blocks << [type: 'text', text: msg.content.toString()]
				}
				(metadata.tool_calls as List).each { toolCall ->
					Map call = toolCall as Map
					Map function = call.function instanceof Map ? call.function as Map : [:]
					blocks << [
						type : 'tool_use',
						id   : call.id?.toString() ?: UUID.randomUUID().toString(),
						name : function.name?.toString(),
						input: parseToolArguments(function.arguments)
					]
				}
				messages << [role: 'assistant', content: blocks]
				return
			}

			String content = msg.content?.toString() ?: ''
			if (role == 'assistant') {
				// A final answer comes back as history on the next question. Left in, its
				// usage footer teaches the model to write one itself, with invented numbers.
				content = stripUsageFooter(content)
			}
			messages << [role: role == 'assistant' ? 'assistant' : 'user', content: content]
		}

		boolean cachingEnabled = isPromptCachingEnabled(accountIntegration)
		boolean thinkingEnabled = isThinkingEnabled(accountIntegration)

		Integer maxTokens = request.maxOutputTokens ?: resolveDefaultMaxOutputTokens(accountIntegration)
		Map requestBody = [
			model     : request.model ?: DEFAULT_CHAT_MODEL,
			max_tokens: maxTokens,
			messages  : messages
		]

		boolean webSearchEnabled = isWebSearchEnabled(accountIntegration)
		String systemText = systemParts ? systemParts.join('\n\n') : null
		if (systemText || webSearchEnabled) {
			if (!cachingEnabled && !webSearchEnabled) {
				requestBody.system = systemText
			} else {
				List<Map> systemBlocks = []
				if (systemText) {
					// The system prompt is the most stable prefix of an Agent
					// conversation, so it gets the first cache breakpoint.
					Map block = [type: 'text', text: systemText]
					if (cachingEnabled) {
						block.cache_control = [type: 'ephemeral']
					}
					systemBlocks << block
				}
				if (webSearchEnabled) {
					// A model with no idea what day it is cannot judge whether a
					// release dated "August 2026" has already happened - it falls back
					// to somewhere near its training cutoff and calls it the future.
					// Deliberately a second block placed *after* the breakpoint, so
					// the cached prefix stays byte-identical and this costs nothing.
					systemBlocks << [type: 'text', text: currentDateNote()]
				}
				requestBody.system = systemBlocks
			}
		}

		if (thinkingEnabled) {
			Integer budget = resolveThinkingBudget(accountIntegration)
			// max_tokens has to leave room for the answer on top of the thinking budget.
			if (maxTokens <= budget) {
				maxTokens = budget + DEFAULT_MAX_OUTPUT_TOKENS
				requestBody.max_tokens = maxTokens
			}
			requestBody.thinking = [type: 'enabled', budget_tokens: budget]
			// temperature/top_p are rejected while thinking is enabled.
		} else if (isSamplingParamsEnabled(accountIntegration)) {
			// Opt-in only. Morpheus sends a temperature on every chat request, and
			// newer Claude models reject it outright, which surfaces in the UI as the
			// misleading "The AI model is no longer available".
			if (request.temperature != null) {
				requestBody.temperature = request.temperature
			}
			if (request.topP != null) {
				requestBody.top_p = request.topP
			}
		}

		if (request.stopSequences) {
			requestBody.stop_sequences = request.stopSequences
		}

		List<Map> tools = convertTools(request.options?.tools)
		if (tools) {
			if (cachingEnabled) {
				// A cache_control marker on the final tool caches the whole tool block.
				// This is the big win for MCP agents: the tool catalog is identical on
				// every turn but would otherwise be re-billed as fresh input each time.
				Map lastTool = new LinkedHashMap(tools[-1])
				lastTool.cache_control = [type: 'ephemeral']
				tools = tools[0..<tools.size() - 1] + [lastTool]
			}
			Map toolChoice = convertToolChoice(request.options?.tool_choice)
			if (toolChoice) {
				requestBody.tool_choice = toolChoice
			}
		}

		// Server tools go first. The cached prefix runs up to and including the
		// breakpoint on the last MCP tool, so putting them ahead of it keeps them
		// inside the cache rather than re-billing them on every turn.
		List<Map> serverTools = buildServerTools(accountIntegration, requestBody.model?.toString())
		if (serverTools || tools) {
			requestBody.tools = serverTools + tools
		}

		if (stream != null) {
			requestBody.stream = stream
		}
		int replaced = countReplacementChars(requestBody.messages)
		if (replaced) {
			log.warn("Replayed conversation contains ${replaced} U+FFFD replacement characters (${describeReplacementContext(requestBody.messages as List)})")
			// Left behind by requests sent before the body went out as UTF-8: a gateway
			// turned each non-ASCII byte into U+FFFD, the model repeated it, and Morpheus
			// stored the answer that way. The characters cannot be restored, but a model
			// reading "L�uft" in its own earlier answer keeps writing it like that.
			appendSystemNote(requestBody, REPLACEMENT_CHARACTER_NOTE)
		}
		return requestBody
	}

	/**
	 * OpenAI tool definitions -> Anthropic tool definitions.
	 * [{type:function, function:{name, description, parameters}}]
	 *   -> [{name, description, input_schema}]
	 */
	protected List<Map> convertTools(def tools) {
		if (!(tools instanceof List)) {
			return []
		}
		List<Map> converted = []
		tools.each { tool ->
			if (!(tool instanceof Map)) {
				return
			}
			Map toolMap = tool as Map
			// Already in Anthropic shape - pass through untouched.
			if (toolMap.input_schema != null && toolMap.name != null) {
				converted << new LinkedHashMap(toolMap)
				return
			}
			Map function = toolMap.function instanceof Map ? toolMap.function as Map : toolMap
			String name = function.name?.toString()
			if (!name) {
				return
			}
			Map schema = function.parameters instanceof Map ? new LinkedHashMap(function.parameters as Map) : [type: 'object', properties: [:]]
			if (!schema.type) {
				schema.type = 'object'
			}
			Map anthropicTool = [name: name, input_schema: schema]
			if (function.description) {
				anthropicTool.description = function.description.toString()
			}
			converted << anthropicTool
		}
		return converted
	}

	/**
	 * Anthropic's server-side web tools, when the integration opts into them.
	 *
	 * These are not tools Morpheus ever executes: Anthropic runs the search and
	 * the fetch on its own infrastructure inside the same /v1/messages call and
	 * returns the results as extra content blocks, so the MCP tool loop, the
	 * agent's read-only mode and its MCP server list are all untouched. The only
	 * egress involved is the one to api.anthropic.com the plugin already needs.
	 *
	 * web_fetch is deliberately paired with web_search: on its own it can only
	 * read URLs that already appeared in the conversation, which covers "check
	 * this link" but not "find the release notes".
	 */
	protected List<Map> buildServerTools(AccountIntegration accountIntegration, String model) {
		if (!isWebSearchEnabled(accountIntegration)) {
			return []
		}
		// Dynamic filtering runs the search inside code execution, and once that is
		// provisioned the model also uses it to crunch MCP results. Measured on an agent
		// whose question never searched at all: an extra inference round and a fresh
		// container on every tool round, and minutes for an inventory question. So it
		// is opt-in; by default the basic tools are called directly.
		boolean filtering = isWebSearchCodeFilteringEnabled(accountIntegration) && supportsDynamicFiltering(model)
		Integer maxUses = resolveWebSearchMaxUses(accountIntegration)
		List<String> allowedDomains = resolveWebSearchAllowedDomains(accountIntegration)

		Map search = [type: filtering ? WEB_SEARCH_TOOL_TYPE : WEB_SEARCH_TOOL_TYPE_BASIC, name: 'web_search']
		// Citations are always on for search results but are opt-in for fetched
		// pages, and an answer about a release is only worth as much as its source.
		Map fetch = [type: filtering ? WEB_FETCH_TOOL_TYPE : WEB_FETCH_TOOL_TYPE_BASIC, name: 'web_fetch',
					 citations: [enabled: true]]
		if (maxUses != null) {
			search.max_uses = maxUses
			fetch.max_uses = maxUses
		}
		if (allowedDomains) {
			search.allowed_domains = allowedDomains
			fetch.allowed_domains = allowedDomains
		}
		return [search, fetch]
	}

	/**
	 * The one thing a model cannot look up: today's date.
	 *
	 * Day granularity on purpose. It is the finest resolution that answers
	 * "is this current?" and the coarsest that stops the note changing between
	 * turns of one conversation.
	 */
	protected String currentDateNote() {
		LocalDate today = LocalDate.now()
		return "Today's date is ${today.format(DateTimeFormatter.ofPattern('EEEE, d MMMM yyyy', Locale.US))}. " +
			'Use it to judge whether something is current, recent or still in the future - ' +
			'do not assume the present is close to your training cutoff.'
	}

	/**
	 * Dynamic filtering runs the search from inside code execution, which needs a
	 * model that supports programmatic tool calling - Claude 4.6 and newer. Note
	 * that Haiku 4.5 and Sonnet 4.5 are older than 4.6 despite the higher-looking
	 * minor number on the family before them.
	 */
	protected boolean supportsDynamicFiltering(String model) {
		String id = normalizeModelId(model)
		return id.contains('-4-6') || id.contains('-4-7') || id.contains('-4-8') ||
			id.contains('sonnet-5') || id.contains('opus-5') || id.contains('fable-5') || id.contains('mythos-5')
	}

	/**
	 * OpenAI tool_choice -> Anthropic tool_choice.
	 * 'auto'|'none'|'required'|{type:function, function:{name}}
	 */
	protected Map convertToolChoice(def toolChoice) {
		if (toolChoice == null) {
			return null
		}
		if (toolChoice instanceof CharSequence) {
			switch (toolChoice.toString().toLowerCase()) {
				case 'auto': return [type: 'auto']
				case 'required': return [type: 'any']
				case 'none': return [type: 'none']
				default: return [type: 'auto']
			}
		}
		if (toolChoice instanceof Map) {
			Map choice = toolChoice as Map
			if (choice.type == 'auto' || choice.type == 'any' || choice.type == 'none') {
				return new LinkedHashMap(choice)
			}
			Map function = choice.function instanceof Map ? choice.function as Map : [:]
			String name = function.name?.toString() ?: choice.name?.toString()
			if (name) {
				return [type: 'tool', name: name]
			}
		}
		return null
	}

	/**
	 * Tool arguments arrive as a JSON string in the OpenAI convention; Anthropic
	 * expects a real object.
	 */
	protected Map parseToolArguments(def arguments) {
		if (arguments == null) {
			return [:]
		}
		if (arguments instanceof Map) {
			return new LinkedHashMap(arguments as Map)
		}
		String raw = arguments.toString().trim()
		if (!raw) {
			return [:]
		}
		try {
			def parsed = new JsonSlurper().parseText(raw)
			return parsed instanceof Map ? new LinkedHashMap(parsed as Map) : [:]
		} catch (Exception ignored) {
			log.warn("Could not parse tool arguments as JSON: ${raw}")
			return [:]
		}
	}

	// ------------------------------------------------------------------
	// Response translation: Anthropic Messages -> Morpheus (OpenAI-shaped)
	// ------------------------------------------------------------------

	/**
	 * Turn a /v1/messages response (or an accumulated stream) into an
	 * LlmChatResponse, mapping tool_use blocks back onto the OpenAI-shaped
	 * tool_calls metadata Morpheus expects.
	 */
	protected LlmChatResponse parseMessageResponse(Map data) {
		LlmChatResponse response = new LlmChatResponse()
		response.id = data.id?.toString()
		response.model = data.model?.toString()
		response.finishReason = mapStopReason(data.stop_reason?.toString())

		StringBuilder text = new StringBuilder()
		StringBuilder thinking = new StringBuilder()
		List<Map> toolCalls = []
		List<Map> sources = []
		Map<String, String> sourceTitles = [:]
		def content = data.content
		if (content instanceof List) {
			content.each { block ->
				if (!(block instanceof Map)) {
					return
				}
				Map blockMap = block as Map
				if (blockMap.type == 'text' && blockMap.text != null) {
					text.append(blockMap.text.toString())
					// Search citations name their source; fetch citations only carry a
					// document title, so the fetched URLs are picked up below instead.
					collectSources(sources, blockMap.citations)
				} else if (blockMap.type == 'web_fetch_tool_result') {
					Map fetchResult = blockMap.content instanceof Map ? blockMap.content as Map : [:]
					Map document = fetchResult.content instanceof Map ? fetchResult.content as Map : [:]
					addSource(sources, fetchResult.url?.toString(), document.title?.toString())
				} else if (blockMap.type == 'web_search_tool_result') {
					// Search results are not sources in themselves - only what the
					// answer cites is - but they are where the page titles live, and a
					// fetched page often arrives without one.
					if (blockMap.content instanceof List) {
						(blockMap.content as List).each { entry ->
							if (entry instanceof Map) {
								Map result = entry as Map
								String url = result.url?.toString()
								String title = result.title?.toString()
								if (url && title) {
									sourceTitles.putIfAbsent(url, title)
								}
							}
						}
					}
				} else if (blockMap.type == 'thinking' || blockMap.type == 'redacted_thinking') {
					String thinkingText = blockMap.thinking?.toString()
					if (thinkingText) {
						thinking.append(thinking.length() > 0 ? '\n' : '').append(thinkingText)
					}
				} else if (blockMap.type == 'tool_use') {
					toolCalls << [
						id      : blockMap.id?.toString() ?: UUID.randomUUID().toString(),
						type    : 'function',
						function: [
							name     : blockMap.name?.toString(),
							arguments: new JsonBuilder(blockMap.input ?: [:]).toString()
						]
					]
				}
			}
		} else if (content instanceof CharSequence) {
			text.append(content.toString())
		}

		LlmChatMessage message = new LlmChatMessage()
		message.role = data.role?.toString() ?: 'assistant'
		message.content = text.toString()
		response.message = message

		if (thinking.length() > 0) {
			response.metadata.put('thinking', thinking.toString())
		}

		if (toolCalls) {
			// The chat shows no trace of tool use, so without this an answer built from
			// MCP data cannot be told apart from one the model made up.
			log.info("Anthropic tool calls: ${toolCalls.collect { (it.function as Map).name }.join(', ')}")
			response.metadata.put('tool_calls', toolCalls)
			if (message.metadata == null) {
				message.metadata = [:]
			}
			message.metadata.put('tool_calls', toolCalls)
		}

		if (sources) {
			// Search results can arrive after the fetch that used them, so titles are
			// filled in once everything has been seen rather than block by block.
			sources.each { Map source ->
				if (!source.title) {
					source.title = sourceTitles.get(source.url)
				}
			}
			response.metadata.put('sources', sources)
		}

		def usage = data.usage
		if (usage instanceof Map) {
			Map usageMap = usage as Map
			LlmTokenUsage tokenUsage = new LlmTokenUsage()
			Integer inputTokens = toInteger(usageMap.input_tokens)
			Integer outputTokens = toInteger(usageMap.output_tokens)
			tokenUsage.inputTokens = inputTokens
			tokenUsage.outputTokens = outputTokens
			// Cached prefix tokens are billed separately and are not part of
			// input_tokens, so they are added in to keep the total honest.
			Integer cacheRead = toInteger(usageMap.cache_read_input_tokens)
			Integer cacheWrite = toInteger(usageMap.cache_creation_input_tokens)
			if (inputTokens != null || outputTokens != null || cacheRead != null || cacheWrite != null) {
				tokenUsage.totalTokens = (inputTokens ?: 0) + (outputTokens ?: 0) + (cacheRead ?: 0) + (cacheWrite ?: 0)
			}
			response.tokenUsage = tokenUsage
			// OpenRouter reports what the request cost, in USD. Anthropic does not.
			if (usageMap.cost instanceof Number) {
				response.metadata.put('cost', new BigDecimal(usageMap.cost.toString()))
			}
			if (cacheRead != null || cacheWrite != null) {
				response.metadata.put('cache_read_input_tokens', cacheRead ?: 0)
				response.metadata.put('cache_creation_input_tokens', cacheWrite ?: 0)
				// Morpheus does not surface response metadata anywhere in the UI, so
				// without this line prompt caching - the reason this plugin exists -
				// cannot be observed on a running appliance.
				log.info("Anthropic prompt cache: read=${cacheRead ?: 0} created=${cacheWrite ?: 0} " +
					"uncached_input=${inputTokens ?: 0} output=${outputTokens ?: 0}")
			}
		}

		return response
	}

	/**
	 * Anthropic stop_reason -> OpenAI finish_reason, so downstream Morpheus code
	 * (which branches on 'tool_calls') keeps working.
	 */
	protected String mapStopReason(String stopReason) {
		if (!stopReason) {
			return null
		}
		switch (stopReason) {
			case 'end_turn': return 'stop'
			case 'stop_sequence': return 'stop'
			case 'max_tokens': return 'length'
			case 'tool_use': return 'tool_calls'
			case 'pause_turn': return 'stop'
			case 'refusal': return 'content_filter'
			default: return stopReason
		}
	}

	// ------------------------------------------------------------------
	// Model catalog
	// ------------------------------------------------------------------

	protected List<LlmModel> buildModelsFromApiResponse(LlmIntegration llmIntegration, Map apiResponse) {
		boolean longContext = isLongContextEnabled(llmIntegration?.accountIntegration)
		List<LlmModel> models = []
		def modelData = apiResponse?.data
		if (modelData instanceof List) {
			modelData.each { entry ->
				if (!(entry instanceof Map)) {
					return
				}
				Map entryMap = entry as Map
				String modelId = entryMap.id?.toString()
				if (!isClaudeModelId(modelId)) {
					return
				}
				LlmModel model = new LlmModel()
				// Kept exactly as listed: that is the spelling the endpoint expects back.
				model.code = modelId
				model.externalId = modelId
				// Anthropic calls it display_name, OpenRouter name - or display_name as well
				// when it answers in Anthropic's format. OpenRouter also prefixes the vendor,
				// on most models but not all, so that is dropped for one consistent list.
				String listedName = (entryMap.display_name ?: entryMap.name)?.toString()?.replaceFirst('^Anthropic:\\s*', '')
				model.name = listedName ?: formatModelName(modelId)
				model.providerCode = PROVIDER_CODE
				model.modelType = 'chat'
				model.contextWindow = estimateContextWindow(modelId, longContext)
				model.maxOutputTokens = estimateMaxOutputTokens(modelId)
				model.llmIntegration = llmIntegration
				model.enabled = true
				model.metadata = [
					supportsToolUse    : true,
					supportsStreaming  : true,
					supportsVision     : true,
					supportsPromptCache: true,
					apiFormat          : 'anthropic-messages'
				]
				models.add(model)
			}
		}
		models.sort { a, b -> (a.name ?: '').compareTo(b.name ?: '') }
		return models
	}

	protected String formatModelName(String modelId) {
		return modelId.split('-').collect { it.capitalize() }.join(' ')
	}

	/**
	 * Claude chat models, as Anthropic lists them ({@code claude-sonnet-4-6}) or as
	 * a gateway that prefixes the vendor does ({@code anthropic/claude-sonnet-4.6}
	 * on OpenRouter). Suffixed variants such as {@code :batch} are left out, since
	 * each duplicates a model already in the list.
	 */
	protected boolean isClaudeModelId(String modelId) {
		if (!modelId || modelId.contains(':')) {
			return false
		}
		return modelId.startsWith('claude') || modelId.startsWith('anthropic/claude')
	}

	/**
	 * One spelling for the capability checks, so they ignore the vendor prefix, the
	 * dotted version and the [1m] variant suffix a gateway uses. Only for matching -
	 * never sent anywhere.
	 */
	protected static String normalizeModelId(String modelId) {
		return (modelId ?: '').toLowerCase().replaceFirst('^anthropic/', '').replaceFirst('\\[[^\\]]*\\]$', '').replace('.', '-')
	}

	/**
	 * Claude models expose a 200k token context by default. Sonnet 4.5 and newer
	 * can be extended to 1M via the context-1m beta header; that larger window is
	 * only reported when the integration actually enables it.
	 */
	protected Long estimateContextWindow(String modelId, boolean longContext = false) {
		// OpenRouter's Anthropic-format listing marks its 1M-context models this way.
		if (modelId?.toLowerCase()?.endsWith('[1m]')) {
			return LONG_CONTEXT_WINDOW
		}
		if (longContext && supportsLongContext(modelId)) {
			return LONG_CONTEXT_WINDOW
		}
		return STANDARD_CONTEXT_WINDOW
	}

	protected boolean supportsLongContext(String modelId) {
		String id = normalizeModelId(modelId)
		return id.contains('sonnet-4-5') || id.contains('sonnet-4-6') || id.contains('sonnet-5') || id.contains('opus-5')
	}

	protected Long estimateMaxOutputTokens(String modelId) {
		String id = normalizeModelId(modelId)
		if (id.contains('haiku-4') || id.contains('sonnet-4')) {
			return 64000L
		}
		if (id.contains('opus-4') || id.contains('opus-5')) {
			return 32000L
		}
		if (id.contains('3-7-sonnet')) {
			return 64000L
		}
		if (id.contains('haiku')) {
			return 8192L
		}
		return 8192L
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	protected String resolveApiKey(AccountIntegration accountIntegration) {
		return accountIntegration.credentialData?.password ?: accountIntegration.serviceToken ?: accountIntegration.servicePassword
	}

	protected String resolveBaseUrl(AccountIntegration accountIntegration) {
		String url = accountIntegration?.serviceUrl?.trim()
		if (!url) {
			return DEFAULT_API_URL
		}
		// Tolerate someone pasting the full messages URL into the endpoint field.
		return url.replaceAll('/+$', '').replaceAll('/v1/messages$', '').replaceAll('/v1$', '')
	}

	protected String resolveApiVersion(AccountIntegration accountIntegration) {
		def configured = accountIntegration?.getConfigProperty('apiVersion')
		return configured?.toString()?.trim() ?: AnthropicApiService.DEFAULT_API_VERSION
	}

	protected Integer resolveDefaultMaxOutputTokens(AccountIntegration accountIntegration) {
		def configured = accountIntegration?.getConfigProperty('maxOutputTokens')
		Integer parsed = toInteger(configured)
		return (parsed != null && parsed > 0) ? parsed : DEFAULT_MAX_OUTPUT_TOKENS
	}

	/** Checkbox config values arrive as 'on'/'true'/true depending on the caller. */
	protected static boolean toBoolean(def value, boolean defaultValue) {
		if (value == null) {
			return defaultValue
		}
		if (value instanceof Boolean) {
			return (Boolean) value
		}
		String raw = value.toString().trim().toLowerCase()
		if (!raw) {
			return defaultValue
		}
		return raw in ['on', 'true', 'yes', '1']
	}

	protected boolean isPromptCachingEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('promptCaching'), true)
	}

	protected boolean isThinkingEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('thinkingEnabled'), false)
	}

	protected boolean isLongContextEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('longContext'), false)
	}

	/**
	 * Off by default: Morpheus supplies a temperature on every chat request, and
	 * newer Claude models answer that with
	 * "400 `temperature` is deprecated for this model".
	 */
	protected boolean isSamplingParamsEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('samplingParams'), false)
	}

	protected boolean isUsageFooterEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('usageFooter'), false)
	}

	protected boolean isNetworkProxyEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('useNetworkProxy'), false)
	}

	protected boolean isWebSearchEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('webSearch'), false)
	}

	protected boolean isWebSearchCodeFilteringEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('webSearchCodeFiltering'), false)
	}

	/** Null means no cap, which the API accepts - but the default is a cap. */
	protected Integer resolveWebSearchMaxUses(AccountIntegration accountIntegration) {
		def configured = accountIntegration?.getConfigProperty('webSearchMaxUses')
		if (configured == null || !configured.toString().trim()) {
			return DEFAULT_WEB_SEARCH_MAX_USES
		}
		Integer parsed = toInteger(configured)
		return (parsed != null && parsed > 0) ? parsed : null
	}

	/**
	 * Anthropic wants bare hostnames with an optional path, so a pasted
	 * 'https://docs.morpheusdata.com/' is trimmed back to the part it accepts.
	 */
	protected List<String> resolveWebSearchAllowedDomains(AccountIntegration accountIntegration) {
		String configured = accountIntegration?.getConfigProperty('webSearchAllowedDomains')?.toString()
		if (!configured?.trim()) {
			return []
		}
		return configured.split(/[,\s]+/)
			.collect { it.trim().replaceAll('^[a-zA-Z]+://', '').replaceAll('/+$', '') }
			.findAll { it } as List<String>
	}

	/**
	 * Appends an italic token summary to a final answer.
	 *
	 * Only final answers are touched. An agent turn that ends in tool_use is
	 * sent back to Anthropic as conversation history on the next request, so a
	 * footer there would end up in the model's own context - and be billed - on
	 * every subsequent turn.
	 */
	protected LlmChatResponse appendUsageFooter(LlmChatResponse response, AccountIntegration accountIntegration) {
		if (!isUsageFooterEnabled(accountIntegration) || response?.message?.content == null) {
			return response
		}
		if (response.finishReason == 'tool_calls' || !response.message.content.toString().trim()) {
			return response
		}
		// Where the endpoint reports cost, the line shows what the whole question cost
		// instead of the last request's tokens.
		def questionCost = response.metadata?.get('question_cost')
		if (questionCost instanceof BigDecimal) {
			int requests = toInteger(response.metadata.get('question_requests')) ?: 1
			// In the language of the answer it sits under.
			String language = answerLanguage(response.message.content.toString())
			String count = requests > 1 ? " (${requestCount(requests, language)})" : ''
			response.message.content = "${response.message.content}\n\n*${costLabel(language)}: ${formatCost(questionCost)}${count}*".toString()
			return response
		}
		LlmTokenUsage usage = response.tokenUsage
		if (!usage) {
			return response
		}
		List<String> parts = []
		Integer cached = toInteger(response.metadata?.get('cache_read_input_tokens'))
		if (cached) {
			parts << "${formatTokenCount(cached)} cached"
		}
		if (usage.inputTokens != null) {
			parts << "${formatTokenCount(usage.inputTokens)} input"
		}
		if (usage.outputTokens != null) {
			parts << "${formatTokenCount(usage.outputTokens)} output"
		}
		if (!parts) {
			return response
		}
		// ASCII only. The footer becomes part of the conversation history replayed
		// on the next turn, and before request bodies went out as UTF-8 bytes a
		// U+21B3 arrow and a U+00B7 separator there killed the follow-up request with
		// "400 ... str is not valid UTF-8: surrogates not allowed". Plain ASCII
		// survives whatever encoding a Morpheus version applies.
		// Italics only. The Morpheus chat renderer escapes raw HTML rather than
		// stripping it, so a <sub> wrapper for smaller type shows up as literal
		// tags in the answer. Markdown itself has no notion of type size.
		response.message.content = "${response.message.content}\n\n*Tokens: ${parts.join(', ')}*"
		return response
	}

	/**
	 * Removes usage footers from the end of an answer that is being replayed - the
	 * plugin's own, and any imitation the model wrote before they were stripped.
	 */
	protected static String stripUsageFooter(String content) {
		return content?.replaceFirst(USAGE_FOOTER_PATTERN, '')
	}

	/** Adds a text block after whatever system prompt the request has, cached or not. */
	protected static void appendSystemNote(Map requestBody, String note) {
		def system = requestBody.system
		List blocks = system instanceof List ? new ArrayList(system as List) : (system ? [[type: 'text', text: system.toString()]] : [])
		blocks << [type: 'text', text: note]
		requestBody.system = blocks
	}

	/** Where the first U+FFFD sits: message index, role and a short ASCII-safe excerpt around it. */
	protected static String describeReplacementContext(List messages) {
		for (int index = 0; index < (messages?.size() ?: 0); index++) {
			Map message = messages[index] instanceof Map ? messages[index] as Map : [:]
			String text = firstTextContaining(message.content, '�')
			if (text != null) {
				int at = text.indexOf('�')
				String excerpt = text.substring(Math.max(0, at - 24), Math.min(text.length(), at + 24))
				String safe = excerpt.collect { String ch -> (ch.charAt(0) as int) in 0x20..0x7E ? ch : String.format('<U+%04X>', ch.charAt(0) as int) }.join()
				return "first in message ${index}, role ${message.role}: ${safe}"
			}
		}
		return 'not located'
	}

	protected static String firstTextContaining(Object value, String needle) {
		if (value instanceof CharSequence) {
			return value.toString().contains(needle) ? value.toString() : null
		}
		Collection children = value instanceof Map ? (value as Map).values() : value instanceof List ? value as List : []
		for (Object child : children) {
			String found = firstTextContaining(child, needle)
			if (found != null) {
				return found
			}
		}
		return null
	}

	protected static int countReplacementChars(Object value) {
		if (value instanceof CharSequence) {
			return value.toString().count('�')
		}
		if (value instanceof Map) {
			return (value as Map).values().sum(0) { countReplacementChars(it) } as int
		}
		if (value instanceof List) {
			return (value as List).sum(0) { countReplacementChars(it) } as int
		}
		return 0
	}

	/**
	 * Adds this request's cost to the running total of its question, and hands the
	 * total to the response. A final answer closes the question.
	 */
	protected LlmChatResponse trackQuestionCost(Map requestBody, LlmChatResponse response) {
		def cost = response?.metadata?.get('cost')
		if (!(cost instanceof BigDecimal)) {
			return response
		}
		long now = System.currentTimeMillis()
		QUESTION_COSTS.values().removeIf { Map tally -> now - (tally.updated as long) > QUESTION_COST_TTL_MS }
		String key = questionKey(requestBody)
		Map tally = QUESTION_COSTS.compute(key) { String ignored, Map existing ->
			[total   : ((existing?.total ?: BigDecimal.ZERO) as BigDecimal) + (cost as BigDecimal),
			 requests: ((existing?.requests ?: 0) as int) + 1,
			 updated : now]
		}
		response.metadata.put('question_cost', tally.total)
		response.metadata.put('question_requests', tally.requests)
		if (response.finishReason != 'tool_calls') {
			QUESTION_COSTS.remove(key)
		}
		return response
	}

	/**
	 * Identifies the question a request belongs to: the model and the conversation up
	 * to the user's latest message. Every tool round of one question repeats exactly
	 * that prefix and only appends to it. Morpheus passes no conversation id.
	 */
	protected static String questionKey(Map requestBody) {
		List messages = (requestBody?.messages ?: []) as List
		int last = -1
		messages.eachWithIndex { def message, int index ->
			if (message instanceof Map && message.role == 'user' && message.content instanceof CharSequence) {
				last = index
			}
		}
		String prefix = JsonOutput.toJson([model: requestBody?.model, messages: last >= 0 ? messages[0..last] : []])
		return MessageDigest.getInstance('SHA-256').digest(prefix.getBytes('UTF-8')).encodeHex().toString()
	}

	/** en, de, pl, cs, hu or ro - whichever the answer reads as; English when in doubt. */
	protected static String answerLanguage(String text) {
		String lower = (text ?: '').toLowerCase()
		List<String> words = lower.findAll(/\p{L}+/)
		Map<String, Integer> scores = LANGUAGE_MARKERS.collectEntries { String language, Set<String> markers ->
			String letters = LANGUAGE_LETTERS[language]
			[(language): (words.count { it in markers } as int) + (letters && lower =~ letters ? 2 : 0)]
		}
		Map.Entry<String, Integer> best = scores.max { it.value }
		return best.value > scores.en ? best.key : 'en'
	}

	protected static String costLabel(String language) {
		return [de: 'Kosten', pl: 'Koszt', cs: 'Náklady', hu: 'Költség', ro: 'Cost'][language] ?: 'Cost'
	}

	/** "5 requests", with the plural form each language uses for that number. */
	protected static String requestCount(int count, String language) {
		String noun
		if (language == 'de') {
			noun = 'Anfragen'
		} else if (language == 'pl') {
			int ones = count % 10
			int tens = count % 100
			noun = ones >= 2 && ones <= 4 && !(tens >= 12 && tens <= 14) ? 'zapytania' : 'zapytań'
		} else if (language == 'cs') {
			noun = count >= 2 && count <= 4 ? 'požadavky' : 'požadavků'
		} else if (language == 'hu') {
			noun = 'kérés'
		} else if (language == 'ro') {
			noun = count % 100 >= 20 || count % 100 == 0 ? 'de cereri' : 'cereri'
		} else {
			noun = 'requests'
		}
		return "${count} ${noun}".toString()
	}

	protected static String formatCost(BigDecimal cost) {
		if (cost > 0 && cost < 0.0001) {
			return '<$0.0001'
		}
		return '$' + cost.setScale(4, RoundingMode.HALF_UP).toPlainString()
	}

	protected static String formatTokenCount(Integer value) {
		return String.format(Locale.US, '%,d', value ?: 0)
	}

	/** Citations on a text block; only web_search_result_location carries a URL. */
	protected void collectSources(List<Map> sources, def citations) {
		if (!(citations instanceof List)) {
			return
		}
		citations.each { citation ->
			if (citation instanceof Map) {
				Map citationMap = citation as Map
				addSource(sources, citationMap.url?.toString(),
					citationMap.title?.toString() ?: citationMap.document_title?.toString())
			}
		}
	}

	protected void addSource(List<Map> sources, String url, String title) {
		if (!url?.trim() || sources.any { it.url == url }) {
			return
		}
		sources << [url: url.trim(), title: title?.trim()]
	}

	/**
	 * Appends the pages a web-search or web-fetch answer was built from.
	 *
	 * Anthropic asks that citations reach the reader, and an answer about which
	 * release is current is only worth as much as the page it came from. Same
	 * two constraints as the token footer: final answers only, because a
	 * tool-call turn is replayed to the model as history, and ASCII only, so
	 * the replayed history survives whatever encoding a Morpheus version applies.
	 */
	protected LlmChatResponse appendSourceList(LlmChatResponse response) {
		List<Map> sources = response?.metadata?.get('sources') as List<Map>
		if (!sources || response.finishReason == 'tool_calls' || !response.message?.content?.toString()?.trim()) {
			return response
		}
		List<String> lines = []
		sources.take(MAX_LISTED_SOURCES).each { Map source ->
			// The URL is never shortened - a truncated one is a broken link.
			String url = toAscii(source.url?.toString(), 0)
			if (!url) {
				return
			}
			String label = toAscii(source.title?.toString(), MAX_SOURCE_LABEL_LENGTH) ?: hostOf(url) ?: url
			// Plain text, not a markdown link: the Morpheus chat renderer handles
			// emphasis and tables but leaves [label](url) as literal characters, so a
			// link here renders as visible punctuation around a URL. A bare URL is
			// auto-linked where that is supported and copy-pasteable where it is not.
			lines << (label == url ? url : "${label} - ${url}").toString()
		}
		if (!lines) {
			return response
		}
		response.message.content = "${response.message.content}\n\n**Sources**\n\n${lines.join('\n\n')}"
		return response
	}

	/** maxLength 0 means leave the value at whatever length it is. */
	protected static String toAscii(String value, Integer maxLength = 0) {
		if (!value) {
			return null
		}
		String cleaned = value.replaceAll(/[^\x20-\x7E]/, '').trim()
		if (maxLength > 0 && cleaned.length() > maxLength) {
			cleaned = cleaned.substring(0, maxLength - 3) + '...'
		}
		return cleaned ?: null
	}

	protected static String hostOf(String url) {
		try {
			return new URI(url).host
		} catch (Exception ignored) {
			return null
		}
	}

	protected Integer resolveThinkingBudget(AccountIntegration accountIntegration) {
		Integer configured = toInteger(accountIntegration?.getConfigProperty('thinkingBudgetTokens'))
		Integer budget = (configured != null && configured > 0) ? configured : DEFAULT_THINKING_BUDGET_TOKENS
		return Math.max(budget, MIN_THINKING_BUDGET_TOKENS)
	}

	/**
	 * The proxy this integration routes through, or null for a direct connection.
	 *
	 * Resolved per call rather than cached: an administrator who repoints the
	 * proxy, or edits its host, expects the next request to use it. Morpheus
	 * stores the selection as the proxy's id.
	 */
	protected NetworkProxy resolveNetworkProxy(AccountIntegration accountIntegration) {
		// The checkbox is the authority, not the dropdown. Morpheus 9.0.1 offers no
		// empty entry in that dropdown, so a selection can be changed but never
		// removed - without this gate, choosing a proxy once would be permanent.
		if (!isNetworkProxyEnabled(accountIntegration)) {
			return null
		}
		Long proxyId = toLong(accountIntegration?.getConfigProperty('networkProxy'))
		if (!proxyId) {
			return null
		}
		try {
			NetworkProxy proxy = loadNetworkProxy(proxyId)
			if (!proxy) {
				// Deleted out from under the integration. Failing the call would be
				// worse than the direct connection the appliance would have used
				// anyway, but silence would hide a broken configuration.
				log.warn("Configured network proxy ${proxyId} no longer exists; connecting directly")
			}
			return proxy
		} catch (Exception e) {
			log.warn("Could not load network proxy ${proxyId}: ${e.message}")
			return null
		}
	}

	/**
	 * The appliance lookup, kept on its own so tests exercise the real gating
	 * and id handling around it rather than replacing the lot.
	 */
	protected NetworkProxy loadNetworkProxy(Long proxyId) {
		return morpheusContext?.services?.network?.networkProxy?.get(proxyId)
	}

	/**
	 * Caller options plus the proxy, for any call that leaves the appliance.
	 */
	protected Map buildClientOpts(AccountIntegration accountIntegration, Map opts = [:]) {
		Map clientOpts = new LinkedHashMap(opts ?: [:])
		NetworkProxy proxy = resolveNetworkProxy(accountIntegration)
		if (proxy) {
			clientOpts.put(AnthropicApiService.NETWORK_PROXY_KEY, proxy)
		}
		return clientOpts
	}

	/** Beta headers the integration has opted into. */
	protected List<String> resolveBetas(AccountIntegration accountIntegration) {
		List<String> betas = []
		if (isLongContextEnabled(accountIntegration)) {
			betas << AnthropicApiService.LONG_CONTEXT_BETA
		}
		return betas
	}

	/**
	 * Cheapest enabled model for the rate limit probe, so refreshing usage costs
	 * as close to nothing as possible.
	 */
	protected String resolveUsageProbeModel(LlmIntegration llmIntegration) {
		List<LlmModel> models = llmIntegration?.models ?: []
		LlmModel haiku = models.find { it?.enabled != false && it?.code?.toLowerCase()?.contains('haiku') }
		return haiku?.code ?: models.find { it?.enabled != false }?.code ?: 'claude-haiku-4-5'
	}

	protected static Long toLong(def value) {
		if (value == null) {
			return null
		}
		if (value instanceof Number) {
			return ((Number) value).longValue()
		}
		try {
			String raw = value.toString().trim()
			return raw ? Long.parseLong(raw) : null
		} catch (Exception ignored) {
			return null
		}
	}

	protected static Integer toInteger(def value) {
		if (value == null) {
			return null
		}
		if (value instanceof Number) {
			return ((Number) value).intValue()
		}
		try {
			String raw = value.toString().trim()
			return raw ? Integer.parseInt(raw) : null
		} catch (Exception ignored) {
			return null
		}
	}

	protected boolean isRetryableError(Exception e) {
		String msg = e?.message?.toLowerCase() ?: ''
		return msg.contains('failed to respond') || msg.contains('connection') || msg.contains('reset') ||
			msg.contains('broken pipe') || msg.contains('socket') || msg.contains('nohttpresponse') ||
			msg.contains('stream closed') || msg.contains('timeout')
	}
}
