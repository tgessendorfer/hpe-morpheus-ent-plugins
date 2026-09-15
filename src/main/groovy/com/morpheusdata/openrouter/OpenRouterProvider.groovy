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
package com.morpheusdata.openrouter

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.LlmProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.llm.LlmChatMessage
import com.morpheusdata.model.llm.LlmChatRequest
import com.morpheusdata.model.llm.LlmChatResponse
import com.morpheusdata.model.llm.LlmIntegration
import com.morpheusdata.model.llm.LlmModel
import com.morpheusdata.model.llm.LlmTokenUsage
import com.morpheusdata.openrouter.sync.LlmModelsSync
import com.morpheusdata.response.LlmStreamingResponseHandler
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * LlmProvider implementation for OpenRouter's OpenAI-compatible API.
 *
 * Morpheus speaks the OpenAI tool-calling convention internally (tool_calls /
 * tool_call_id in LlmChatMessage metadata), which is what OpenRouter accepts, so
 * messages and tools pass through. What the provider adds: a catalog filtered to
 * models an agent can use, footers kept out of replayed history, the cost of each
 * question, and reasoning kept out of the answer.
 */
@Slf4j
class OpenRouterProvider implements LlmProvider {

	Plugin plugin
	MorpheusContext morpheusContext
	OpenRouterApiService apiService

	static final String PROVIDER_CODE = 'openrouter'
	static final String PROVIDER_NAME = 'OpenRouter'
	static final String DEFAULT_API_URL = 'https://openrouter.ai/api/v1'
	static final String API_KEY_PREFIX = 'sk-or-'
	// OpenRouter's 403 on eu.openrouter.ai or us.openrouter.ai for an account without in-region routing.
	static final String REGIONAL_ROUTING_DENIED = 'Regional routing not enabled'
	static final String REGIONAL_PROBE_MODEL = 'openrouter/regional-routing-check'
	static final String REGIONAL_ROUTING_HINT = 'In-region routing is not enabled for this OpenRouter account: ' +
		'eu.openrouter.ai and us.openrouter.ai need the Business or Enterprise plan, which an organization admin can ' +
		'choose under Settings > Preferences > Account Type on openrouter.ai. Without it, use https://openrouter.ai/api/v1.'
	static final String REASONING_EFFORT_DEFAULT = 'default'
	static final List<String> REASONING_EFFORTS = ['low', 'medium', 'high']
	// A model that goes away within this many days says so in its name.
	static final long EXPIRY_NOTICE_DAYS = 365L
	// One or more italic usage lines at the very end of an answer.
	static final String USAGE_FOOTER_PATTERN = '(?:\\s*\\*(?:Tokens|Cost|Kosten|Koszt): [^*\\n]*\\*)+\\s*$'
	// Enough to pick the footer's language, not a language detector: answers in each
	// language are full of its words and next to never contain the others'. Letters
	// only one of the languages uses count extra.
	static final Map<String, Set<String>> LANGUAGE_MARKERS = [
		en: ['the', 'and', 'is', 'are', 'of', 'to', 'with', 'not', 'no', 'for', 'there', 'this', 'that', 'it', 'be', 'on', 'as', 'by', 'has', 'have'] as Set,
		de: ['der', 'die', 'das', 'den', 'dem', 'und', 'ist', 'sind', 'nicht', 'keine', 'mit', 'auf', 'ein', 'eine', 'gibt', 'es', 'auch', 'wird', 'oder', 'bei', 'zu', 'von', 'im', 'sich', 'wie'] as Set,
		pl: ['jest', 'są', 'się', 'nie', 'oraz', 'dla', 'czy', 'które', 'który', 'która', 'będzie', 'żadnych', 'działa', 'działają', 'wszystkie', 'w', 'i', 'na'] as Set
	]
	static final Map<String, String> LANGUAGE_LETTERS = [de: '[äß]', pl: '[ąęłńśźż]']
	// One question is many billed requests once the agent calls tools, and only the
	// last of them carries the answer the footer goes on - so cost is kept per question.
	protected static final ConcurrentHashMap<String, Map> QUESTION_COSTS = new ConcurrentHashMap<>()
	static final long QUESTION_COST_TTL_MS = 60L * 60L * 1000L
	static final String REPLACEMENT_CHARACTER_NOTE = 'Some earlier turns of this conversation came back from the chat ' +
		'application with characters replaced by U+FFFD. Treat each such character as unknown and never copy it; ' +
		'write every character of your answer correctly, including umlauts and other accented letters.'

	OpenRouterProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
		this.apiService = new OpenRouterApiService()
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
		// Plugin assets are served from a stable URL, so a changed icon needs a new file
		// name, or browsers keep showing the previous one after an upgrade.
		return new Icon(path: 'router-mark.svg', darkPath: 'router-mark-white.svg')
	}

	@Override
	String getDescription() {
		// Kept under 255 characters, the size of Morpheus' description columns.
		return 'Models from every vendor OpenRouter serves, through its OpenAI-compatible API, with MCP tool use, ' +
			'streaming and a per-question cost footer. Only models that support tool calling are listed.'
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
			name: 'Service URL',
			fieldName: 'serviceUrl',
			fieldLabel: 'API Endpoint',
			fieldContext: 'domain',
			inputType: OptionType.InputType.TEXT,
			displayOrder: 0,
			required: true,
			defaultValue: DEFAULT_API_URL,
			helpText: 'OpenRouter\'s OpenAI-compatible API, https://openrouter.ai/api/v1. For in-region routing, a Business plan feature, use https://eu.openrouter.ai/api/v1 or https://us.openrouter.ai/api/v1; only models available in that region are then listed. The plugin appends /chat/completions, /models and /key, and adds /api/v1 or /v1 when the URL stops short of it.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.credential",
			name: 'Credentials',
			inputType: OptionType.InputType.CREDENTIAL,
			fieldName: 'type',
			fieldLabel: 'Credentials',
			fieldContext: 'credential',
			required: true,
			displayOrder: 1,
			defaultValue: 'local',
			optionSource: 'credentials',
			config: '{"credentialTypes":["api-key"]}'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.servicePassword",
			name: 'API Key',
			inputType: OptionType.InputType.PASSWORD,
			fieldName: 'servicePassword',
			fieldLabel: 'API Key',
			fieldContext: 'domain',
			displayOrder: 2,
			required: true,
			localCredential: true
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.useNetworkProxy",
			name: 'Use Network Proxy',
			fieldName: 'useNetworkProxy',
			fieldLabel: 'Route Outbound Calls Through a Proxy',
			fieldContext: 'config',
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 3,
			required: false,
			helpText: 'Off means a direct connection. This checkbox exists because the integration form gives no way to clear a chosen proxy: Morpheus 9.0.1 renders the dropdown below with no empty entry, so unticking this is how you go back to connecting directly.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.networkProxy",
			name: 'Network Proxy',
			fieldName: 'networkProxy',
			fieldLabel: 'Network Proxy',
			fieldContext: 'config',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'networkProxies',
			displayOrder: 4,
			required: false,
			// Both of these are the documented way to offer an empty entry, and
			// Morpheus 9.0.1 honours neither in the integration form. Kept because they
			// are correct, and a release that honours them makes the checkbox redundant.
			noBlank: false,
			noSelection: 'No Proxy',
			helpText: 'The proxy to use, from Infrastructure > Networks > Proxies. Only applied when the box above is ticked. The appliance makes the call, so this is the proxy that needs to reach openrouter.ai - each integration picks its own, independently of the proxy any cloud uses.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.maxOutputTokens",
			name: 'Max Output Tokens',
			fieldName: 'maxOutputTokens',
			fieldLabel: 'Default Max Output Tokens',
			fieldContext: 'config',
			inputType: OptionType.InputType.NUMBER,
			displayOrder: 5,
			required: false,
			helpText: 'Sent as max_tokens when the caller does not supply a limit. Leave empty to let each model use its own maximum. Reasoning tokens count against this limit, so a low value can cut off the answers of reasoning models.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.reasoningEffort",
			name: 'Reasoning Effort',
			fieldName: 'reasoningEffort',
			fieldLabel: 'Reasoning Effort',
			fieldContext: 'config',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'openRouterReasoningEfforts',
			displayOrder: 6,
			required: false,
			defaultValue: REASONING_EFFORT_DEFAULT,
			helpText: 'Model default sends nothing and leaves reasoning to each model. Low, Medium or High is sent as reasoning.effort on every request. More effort is slower and uses more output tokens. Reasoning is never shown in the chat.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.includeAnthropicModels",
			name: 'List Anthropic Models',
			fieldName: 'includeAnthropicModels',
			fieldLabel: 'List Anthropic Models',
			fieldContext: 'config',
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 7,
			required: false,
			helpText: 'Off by default. Claude models are better served by the Anthropic Claude plugin, which caches the MCP tool catalog through OpenRouter\'s Anthropic-compatible API. This integration sets no cache breakpoints, so the catalog is billed as fresh input on every tool round.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.includeFreeModels",
			name: 'List Free Variants',
			fieldName: 'includeFreeModels',
			fieldLabel: 'List Free Variants',
			fieldContext: 'config',
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 8,
			required: false,
			helpText: 'Off by default. The :free variants cost nothing, but they have their own rate limits and availability, and one agent question with tool rounds is many requests.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.modelAllowList",
			name: 'Model Allow List',
			fieldName: 'modelAllowList',
			fieldLabel: 'Only List These Models',
			fieldContext: 'config',
			inputType: OptionType.InputType.TEXT,
			displayOrder: 9,
			required: false,
			helpText: 'Optional comma-separated model ids, with * as a wildcard, for example: openai/gpt-5*, google/gemini-3*, mistralai/*. Only matching models are listed, which keeps the model tab and the agent form short. Leave empty to list every model that supports tool calling. The two checkboxes above still apply. A model that drops out of the list is removed when the list is refreshed, which saving does, unless an agent still uses it.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.usageFooter",
			name: 'Cost Footer',
			fieldName: 'usageFooter',
			fieldLabel: 'Append Cost to Answers',
			fieldContext: 'config',
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 10,
			required: false,
			helpText: 'Adds an italic line to the end of each final answer with what the whole question cost, summed over all of its requests as OpenRouter reports them. Morpheus shows no usage anywhere in the chat. Tool-call turns are left untouched, and the line is removed before an answer is replayed as history.'
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
				return validationError('Account integration is required for the OpenRouter integration')
			}
			String apiKey = resolveApiKey(accountIntegration)
			if (!apiKey) {
				return validationError('An OpenRouter API key is required')
			}
			String baseUrl = resolveBaseUrl(accountIntegration)
			Map clientOpts = buildClientOpts(accountIntegration)

			// The model list answers any key or none, so the key is checked on its own.
			Map keyResult = apiService.getKey(baseUrl, apiKey, clientOpts) ?: [success: false, msg: 'no response']
			if (!keyResult.success) {
				String message = "OpenRouter did not accept the API key at ${baseUrl}${OpenRouterApiService.KEY_PATH}: ${keyResult.msg}"
				if (!apiKey.trim().startsWith(API_KEY_PREFIX)) {
					// OpenRouter answers a token in any other format with "Missing Authentication
					// header", although the header was sent. A format check is not made a hard
					// rule, in case OpenRouter changes its key format.
					message = "${message.endsWith('.') ? message : message + '.'} OpenRouter keys start with ${API_KEY_PREFIX}; check that the whole key was pasted."
				}
				return validationError(message)
			}
			Map modelsResult = apiService.listModels(baseUrl, apiKey, clientOpts) ?: [success: false, msg: 'no response']
			if (!modelsResult.success) {
				return validationError("Could not load the model list from ${baseUrl}${OpenRouterApiService.MODELS_PATH}: ${modelsResult.msg}")
			}
			// Without /api, openrouter.ai answers /v1/models with its website and status 200.
			if (!(modelsResult.data instanceof Map && (modelsResult.data as Map).data instanceof List)) {
				return validationError("${baseUrl}${OpenRouterApiService.MODELS_PATH} returned no model list. " +
					"Check the API Endpoint: OpenRouter's OpenAI-compatible API is ${DEFAULT_API_URL}.")
			}
			if (isRegionalDomain(baseUrl)) {
				// A regional domain answers /key and /models for any account and refuses only
				// chat without the plan. The plan is checked before the model: a request for a
				// model that cannot exist comes back 403 without the plan (verified) and is never
				// run, so this check costs nothing.
				Map probe = apiService.createChatCompletion(baseUrl, apiKey, [
					model     : REGIONAL_PROBE_MODEL,
					max_tokens: 1,
					messages  : [[role: 'user', content: '.']]
				], clientOpts) ?: [:]
				if (probe.success != true && probe.msg?.toString()?.contains(REGIONAL_ROUTING_DENIED)) {
					return validationError(explainError("OpenRouter refused chat requests at ${baseUrl}: ${probe.msg}"))
				}
			}
			return ServiceResponse.success(llmIntegration)
		} catch (Exception e) {
			log.error("Error verifying OpenRouter integration: ${e.message}", e)
			return errorResponse("Verification failed: ${e.message}")
		}
	}

	/** Logged as well as returned, so the reason is in the appliance log whatever the form shows. */
	protected ServiceResponse validationError(String message) {
		log.warn("OpenRouter integration validation failed: ${message}")
		return errorResponse(message)
	}

	/** ServiceResponse.error(String) leaves msg empty, so the message is set there as well. */
	protected static ServiceResponse errorResponse(String message) {
		ServiceResponse response = ServiceResponse.error(message)
		response.msg = message
		return response
	}

	@Override
	void refresh(LlmIntegration llmIntegration) {
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		if (!accountIntegration) {
			log.warn('Cannot refresh OpenRouter integration - account integration is missing')
			return
		}
		try {
			String apiKey = resolveApiKey(accountIntegration)
			if (!apiKey) {
				log.warn('Cannot refresh OpenRouter integration - no API key configured')
				return
			}
			// Refresh runs on a schedule, long after anyone was watching the form. It
			// needs the proxy as much as the chat does, or the catalog quietly goes stale.
			new LlmModelsSync(morpheusContext, llmIntegration, PROVIDER_CODE, apiService)
				.execute(resolveBaseUrl(accountIntegration), apiKey, buildClientOpts(accountIntegration)) { Map apiResponse ->
					buildModelsFromApiResponse(llmIntegration, apiResponse)
				}
		} catch (Exception e) {
			log.error("Error refreshing OpenRouter integration: ${e.message}", e)
		}
	}

	@Override
	ServiceResponse<LlmChatResponse> generateResponse(LlmIntegration llmIntegration, LlmChatRequest request, Map opts) {
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		if (!accountIntegration) {
			return errorResponse('Account integration is required for the OpenRouter integration')
		}
		String apiKey = resolveApiKey(accountIntegration)
		String baseUrl = resolveBaseUrl(accountIntegration)
		Map requestBody = buildChatRequestBody(request, accountIntegration, false)
		Map requestOpts = buildClientOpts(accountIntegration, opts)

		int maxAttempts = 3
		Map result = null
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			if (attempt > 1) {
				log.warn("OpenRouter API retry ${attempt - 1}/${maxAttempts - 1} after connection failure: ${result?.msg}")
				Thread.sleep(1500L * (attempt - 1))
			}
			try {
				result = apiService.createChatCompletion(baseUrl, apiKey, requestBody, requestOpts)
			} catch (Exception e) {
				result = [success: false, msg: e.message]
			}
			if (result?.success || !isRetryableError(result?.msg?.toString())) {
				break
			}
		}

		if (result?.success && result.data instanceof Map) {
			Map data = result.data as Map
			if (data.error instanceof Map) {
				String message = explainError(OpenRouterApiService.describeError(data.error as Map))
				log.warn("OpenRouter chat completion failed: ${message}")
				return errorResponse("Chat completion failed: ${message}")
			}
			LlmChatResponse response = trackQuestionCost(requestBody, parseChatResponse(data))
			return ServiceResponse.success(appendUsageFooter(response, accountIntegration))
		}
		// Morpheus shows every chat failure as "The AI model is no longer available",
		// so the log is the only place the real reason appears.
		String message = explainError(result?.msg?.toString()) ?: 'Chat completion failed'
		log.warn("OpenRouter chat completion failed: ${message}")
		return errorResponse(message)
	}

	/**
	 * Adds what OpenRouter's own message leaves out. Its 403 for a regional domain points at
	 * enterprise sales, while the self-serve Business plan is enough.
	 */
	/** eu.openrouter.ai or us.openrouter.ai, OpenRouter's in-region domains. */
	protected static boolean isRegionalDomain(String baseUrl) {
		return baseUrl ==~ /(?i)https?:\/\/(eu|us)\.openrouter\.ai(\/.*)?/
	}

	protected static String explainError(String message) {
		if (message?.contains(REGIONAL_ROUTING_DENIED)) {
			return "${message} ${REGIONAL_ROUTING_HINT}".toString()
		}
		return message
	}

	@Override
	void streamResponse(LlmIntegration llmIntegration, LlmChatRequest request, LlmStreamingResponseHandler handler, Map opts) {
		try {
			AccountIntegration accountIntegration = llmIntegration?.accountIntegration
			if (!accountIntegration) {
				handler?.onError(new IllegalArgumentException('Account integration is required for the OpenRouter integration'))
				return
			}
			String apiKey = resolveApiKey(accountIntegration)
			String baseUrl = resolveBaseUrl(accountIntegration)
			Map requestBody = buildChatRequestBody(request, accountIntegration, true)

			Map result = apiService.streamChatCompletion(baseUrl, apiKey, requestBody, { String chunk ->
				handler?.onPartialResponse(chunk)
			}, buildClientOpts(accountIntegration, opts))

			if (result?.success && result.data instanceof Map) {
				LlmChatResponse response = trackQuestionCost(requestBody, parseChatResponse(result.data as Map))
				handler?.onCompleteResponse(appendUsageFooter(response, accountIntegration))
			} else {
				String message = explainError(result?.msg?.toString()) ?: 'Streaming chat completion failed'
				log.warn("OpenRouter streaming chat failed: ${message}")
				handler?.onError(new RuntimeException(message))
			}
		} catch (Exception e) {
			log.error("Error during OpenRouter streaming chat: ${e.message}", e)
			handler?.onError(e)
		}
	}

	// ------------------------------------------------------------------
	// Request: Morpheus -> /chat/completions
	// ------------------------------------------------------------------

	/**
	 * Build a /chat/completions request body from an LlmChatRequest.
	 *
	 * Both sides speak the OpenAI format, so this is mostly a copy. Sampling
	 * parameters are forwarded unchanged: Morpheus sends a temperature on every
	 * request, and OpenRouter drops it for models that do not support it.
	 */
	protected Map buildChatRequestBody(LlmChatRequest request, AccountIntegration accountIntegration, Boolean stream) {
		List<Map> messages = []
		request.messages?.each { LlmChatMessage msg ->
			String role = msg.role?.toLowerCase() ?: 'user'
			Map metadata = msg.metadata ?: [:]
			String content = msg.content?.toString() ?: ''

			if (role == 'tool' || metadata.tool_call_id) {
				messages << [role: 'tool', tool_call_id: metadata.tool_call_id?.toString(), content: content]
				return
			}
			if (role == 'assistant') {
				// A final answer comes back as history on the next question. Left in, its
				// usage footer teaches the model to write one itself, with invented numbers.
				Map assistant = [role: 'assistant', content: stripUsageFooter(content)]
				List<Map> toolCalls = normalizeToolCalls(metadata.tool_calls)
				if (toolCalls) {
					assistant.tool_calls = toolCalls
				}
				messages << assistant
				return
			}
			messages << [role: role in ['system', 'developer'] ? 'system' : 'user', content: content]
		}

		Map requestBody = [:]
		if (request.model) {
			requestBody.model = request.model
		}
		requestBody.messages = messages

		Integer maxTokens = request.maxOutputTokens ?: resolveDefaultMaxOutputTokens(accountIntegration)
		if (maxTokens) {
			requestBody.max_tokens = maxTokens
		}
		if (request.temperature != null) {
			requestBody.temperature = request.temperature
		}
		if (request.topP != null) {
			requestBody.top_p = request.topP
		}
		if (request.stopSequences) {
			requestBody.stop = request.stopSequences
		}

		// OpenRouter wants the tools on every request of a tool loop, not only the first.
		List<Map> tools = normalizeTools(request.options?.tools)
		if (tools) {
			requestBody.tools = tools
			if (request.options?.tool_choice != null) {
				requestBody.tool_choice = request.options.tool_choice
			}
		}

		String effort = resolveReasoningEffort(accountIntegration)
		if (effort) {
			requestBody.reasoning = [effort: effort]
		}
		if (stream != null) {
			requestBody.stream = stream
		}

		int replaced = countReplacementChars(messages)
		if (replaced) {
			log.warn("Replayed conversation contains ${replaced} U+FFFD replacement characters (${describeReplacementContext(messages)})")
			// Left behind by requests sent before bodies went out as ASCII: OpenRouter turned
			// each non-ASCII byte into U+FFFD, the model repeated it, and Morpheus stored the
			// answer that way. A model reading "L�uft" in its own answer keeps writing it.
			insertSystemNote(messages, REPLACEMENT_CHARACTER_NOTE)
		}
		return requestBody
	}

	/**
	 * Tool definitions in OpenAI shape. Morpheus already sends that shape; a bare
	 * {name, description, parameters} definition is wrapped rather than dropped.
	 */
	protected static List<Map> normalizeTools(def tools) {
		if (!(tools instanceof List)) {
			return []
		}
		List<Map> normalized = []
		(tools as List).each { tool ->
			if (!(tool instanceof Map)) {
				return
			}
			Map toolMap = tool as Map
			if (toolMap.function instanceof Map) {
				normalized << toolMap
				return
			}
			if (!toolMap.name) {
				return
			}
			Map function = [name: toolMap.name.toString()]
			if (toolMap.description) {
				function.description = toolMap.description.toString()
			}
			function.parameters = toolMap.parameters instanceof Map ? toolMap.parameters : [type: 'object', properties: [:]]
			normalized << [type: 'function', function: function]
		}
		return normalized
	}

	/**
	 * Tool calls in OpenAI shape with the arguments as a JSON string - for the
	 * calls Morpheus replays as well as the ones a model returns.
	 */
	protected static List<Map> normalizeToolCalls(def toolCalls) {
		if (!(toolCalls instanceof List)) {
			return []
		}
		List<Map> normalized = []
		(toolCalls as List).each { entry ->
			if (!(entry instanceof Map)) {
				return
			}
			Map call = entry as Map
			Map function = call.function instanceof Map ? call.function as Map : [:]
			String name = function.name?.toString()
			if (!name) {
				return
			}
			def arguments = function.arguments
			String json = (arguments instanceof Map || arguments instanceof List) ? JsonOutput.toJson(arguments) : arguments?.toString()?.trim()
			normalized << [
				id      : call.id?.toString() ?: "call_${UUID.randomUUID().toString().replace('-', '')}".toString(),
				type    : 'function',
				function: [name: name, arguments: json ?: '{}']
			]
		}
		return normalized
	}

	/** Adds a system message after the leading system messages, so their prefix stays unchanged. */
	protected static void insertSystemNote(List<Map> messages, String note) {
		int position = 0
		while (position < messages.size() && messages[position].role == 'system') {
			position++
		}
		messages.add(position, [role: 'system', content: note])
	}

	// ------------------------------------------------------------------
	// Response: /chat/completions -> Morpheus
	// ------------------------------------------------------------------

	/**
	 * Turn a /chat/completions response (or an accumulated stream) into an
	 * LlmChatResponse.
	 */
	protected LlmChatResponse parseChatResponse(Map data) {
		LlmChatResponse response = new LlmChatResponse()
		if (response.metadata == null) {
			response.metadata = [:]
		}
		response.id = data.id?.toString()
		response.model = data.model?.toString()

		List choices = data.choices instanceof List ? data.choices as List : []
		Map choice = choices && choices[0] instanceof Map ? choices[0] as Map : [:]
		Map message = choice.message instanceof Map ? choice.message as Map : [:]
		response.finishReason = choice.finish_reason?.toString()

		LlmChatMessage chatMessage = new LlmChatMessage()
		chatMessage.role = 'assistant'
		// A tool-call turn comes back with content null.
		String content = message.content?.toString() ?: ''
		if (!content && message.refusal) {
			content = message.refusal.toString()
		}
		chatMessage.content = content
		response.message = chatMessage

		List<Map> toolCalls = normalizeToolCalls(message.tool_calls)
		if (toolCalls) {
			// Morpheus runs the tools only when the turn ends in tool_calls.
			response.finishReason = 'tool_calls'
			// The chat shows no trace of tool use, so without this an answer built from
			// MCP data cannot be told apart from one the model made up.
			log.info("OpenRouter tool calls: ${toolCalls.collect { (it.function as Map).name }.join(', ')}")
			response.metadata.put('tool_calls', toolCalls)
			chatMessage.metadata = [tool_calls: toolCalls]
		}

		// Reasoning stays out of the content: Morpheus shows the content as the answer
		// and replays it as history on every later request.
		if (message.reasoning instanceof CharSequence && message.reasoning.toString()) {
			response.metadata.put('reasoning', message.reasoning.toString())
		}
		if (data.provider) {
			response.metadata.put('provider', data.provider.toString())
		}

		if (data.usage instanceof Map) {
			Map usage = data.usage as Map
			Map promptDetails = usage.prompt_tokens_details instanceof Map ? usage.prompt_tokens_details as Map : [:]
			Map completionDetails = usage.completion_tokens_details instanceof Map ? usage.completion_tokens_details as Map : [:]
			Integer inputTokens = toInteger(usage.prompt_tokens)
			Integer outputTokens = toInteger(usage.completion_tokens)
			Integer totalTokens = toInteger(usage.total_tokens)
			Integer cachedTokens = toInteger(promptDetails.cached_tokens)
			Integer reasoningTokens = toInteger(completionDetails.reasoning_tokens)

			LlmTokenUsage tokenUsage = new LlmTokenUsage()
			tokenUsage.inputTokens = inputTokens
			tokenUsage.outputTokens = outputTokens
			if (totalTokens != null) {
				tokenUsage.totalTokens = totalTokens
			} else if (inputTokens != null || outputTokens != null) {
				tokenUsage.totalTokens = (inputTokens ?: 0) + (outputTokens ?: 0)
			}
			response.tokenUsage = tokenUsage
			if (cachedTokens != null) {
				response.metadata.put('cached_tokens', cachedTokens)
			}
			if (reasoningTokens != null) {
				response.metadata.put('reasoning_tokens', reasoningTokens)
			}
			BigDecimal cost = usage.cost instanceof Number ? new BigDecimal(usage.cost.toString()) : null
			if (cost != null) {
				response.metadata.put('cost', cost)
			}
			// Morpheus shows no usage anywhere. This line is how to see which upstream
			// provider served a request, whether caching worked, and what it cost.
			log.info("OpenRouter usage: model=${response.model} provider=${data.provider ?: 'unknown'} " +
				"input=${inputTokens ?: 0} cached=${cachedTokens ?: 0} output=${outputTokens ?: 0} " +
				"reasoning=${reasoningTokens ?: 0} cost=${cost != null ? cost.toPlainString() : 'n/a'}")
		}
		return response
	}

	// ------------------------------------------------------------------
	// Model catalog
	// ------------------------------------------------------------------

	protected List<LlmModel> buildModelsFromApiResponse(LlmIntegration llmIntegration, Map apiResponse) {
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		boolean includeAnthropic = isIncludeAnthropicModels(accountIntegration)
		boolean includeFree = isIncludeFreeModels(accountIntegration)
		List<Pattern> allowList = parseModelAllowList(accountIntegration?.getConfigProperty('modelAllowList')?.toString())
		LocalDate today = LocalDate.now(ZoneOffset.UTC)
		List<LlmModel> models = []
		def modelData = apiResponse?.data
		if (modelData instanceof List) {
			(modelData as List).each { entry ->
				if (!(entry instanceof Map)) {
					return
				}
				Map entryMap = entry as Map
				if (!isListedModel(entryMap, includeAnthropic, includeFree, today, allowList)) {
					return
				}
				String modelId = entryMap.id.toString().trim()
				Map topProvider = entryMap.top_provider instanceof Map ? entryMap.top_provider as Map : [:]
				LlmModel model = new LlmModel()
				// Kept exactly as listed: that is the spelling the endpoint expects back.
				model.code = modelId
				model.externalId = modelId
				// The vendor prefix OpenRouter puts in the name stays: in a multi-vendor
				// list it is what tells two "Flash" models apart.
				model.name = displayName(entryMap, today)
				model.providerCode = PROVIDER_CODE
				model.modelType = 'chat'
				model.contextWindow = toLong(entryMap.context_length) ?: toLong(topProvider.context_length)
				model.maxOutputTokens = toLong(topProvider.max_completion_tokens)
				model.llmIntegration = llmIntegration
				model.enabled = true
				model.metadata = [
					supportsToolUse  : true,
					supportsStreaming: true,
					supportsReasoning: supportedParameters(entryMap).contains('reasoning'),
					supportsVision   : modalities(entryMap, 'input_modalities').contains('image'),
					apiFormat        : 'openai-chat-completions'
				]
				models << model
			}
		}
		models.sort { LlmModel a, LlmModel b -> (a.name ?: '').compareToIgnoreCase(b.name ?: '') }
		return models
	}

	/**
	 * Which catalog entries become Morpheus models.
	 *
	 *  - Agents work through MCP tools, so a model has to list {@code tools}.
	 *  - Text output only.
	 *  - No {@code openrouter/*} routers, which choose the model per request, and no
	 *    {@code ~vendor/...-latest} aliases, which switch models under a running agent.
	 *  - No variants but {@code :free}, and that one only when ticked. {@code :batch}
	 *    entries are served by the Batch API alone.
	 *  - No {@code anthropic/*} unless ticked: the Anthropic plugin caches the tool catalog.
	 *  - Nothing past its expiration date.
	 *  - Only ids matching the allow list, when the integration has one. It narrows the
	 *    list further and never brings back a model the rules above leave out.
	 */
	protected static boolean isListedModel(Map entry, boolean includeAnthropic, boolean includeFree, LocalDate today, List<Pattern> allowList = []) {
		String id = entry?.id?.toString()?.trim()
		if (!id) {
			return false
		}
		if (!supportedParameters(entry).contains('tools')) {
			return false
		}
		List<String> outputs = modalities(entry, 'output_modalities')
		if (outputs && !outputs.contains('text')) {
			return false
		}
		if (id.startsWith('openrouter/') || id.startsWith('~')) {
			return false
		}
		int colon = id.indexOf(':')
		if (colon >= 0 && !(includeFree && id.substring(colon) == ':free')) {
			return false
		}
		if (!includeAnthropic && id.startsWith('anthropic/')) {
			return false
		}
		LocalDate expires = expirationDate(entry)
		if (expires != null && expires.isBefore(today)) {
			return false
		}
		return !allowList || allowList.any { Pattern pattern -> pattern.matcher(id).matches() }
	}

	/**
	 * The allow list as case-insensitive patterns matching a whole model id, with * as
	 * the only wildcard. Empty when nothing is configured.
	 */
	protected static List<Pattern> parseModelAllowList(String configured) {
		if (!configured?.trim()) {
			return []
		}
		return configured.split(/[,\s]+/).findAll { it }.collect { String glob ->
			Pattern.compile(glob.split(/\*/, -1).collect { String part -> Pattern.quote(part) }.join('.*'), Pattern.CASE_INSENSITIVE)
		}
	}

	/** The listed name, with the date a model goes away when that is less than a year off. */
	protected static String displayName(Map entry, LocalDate today) {
		String name = entry.name?.toString()?.trim() ?: entry.id.toString()
		LocalDate expires = expirationDate(entry)
		// Far-off dates are placeholders: OpenRouter lists some models as expiring in 2098.
		if (expires != null && !expires.isAfter(today.plusDays(EXPIRY_NOTICE_DAYS))) {
			return "${name} (expires ${expires})".toString()
		}
		return name
	}

	protected static LocalDate expirationDate(Map entry) {
		String raw = entry?.expiration_date?.toString()?.trim()
		if (!raw) {
			return null
		}
		try {
			return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw)
		} catch (DateTimeParseException ignored) {
			return null
		}
	}

	protected static List<String> supportedParameters(Map entry) {
		return entry?.supported_parameters instanceof List ? (entry.supported_parameters as List).collect { it?.toString() } : []
	}

	protected static List<String> modalities(Map entry, String key) {
		Map architecture = entry?.architecture instanceof Map ? entry.architecture as Map : [:]
		return architecture.get(key) instanceof List ? (architecture.get(key) as List).collect { it?.toString() } : []
	}

	// ------------------------------------------------------------------
	// Usage footer and question cost
	// ------------------------------------------------------------------

	/**
	 * Appends what the question cost to a final answer.
	 *
	 * Only final answers are touched. A turn that ends in tool_calls goes back to
	 * the model as history on the next request, so a footer there would sit in its
	 * context - and be billed - on every later turn.
	 */
	protected LlmChatResponse appendUsageFooter(LlmChatResponse response, AccountIntegration accountIntegration) {
		if (!isUsageFooterEnabled(accountIntegration) || response?.message?.content == null) {
			return response
		}
		if (response.finishReason == 'tool_calls' || !response.message.content.toString().trim()) {
			return response
		}
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
		if (usage == null || (usage.inputTokens == null && usage.outputTokens == null)) {
			return response
		}
		List<String> parts = []
		if (usage.inputTokens != null) {
			// prompt_tokens includes the cached ones.
			Integer cached = toInteger(response.metadata?.get('cached_tokens'))
			parts << (cached ? "${formatTokenCount(usage.inputTokens)} input (${formatTokenCount(cached)} cached)" : "${formatTokenCount(usage.inputTokens)} input").toString()
		}
		if (usage.outputTokens != null) {
			parts << "${formatTokenCount(usage.outputTokens)} output".toString()
		}
		// ASCII and italics only: the answer is replayed as history, and the Morpheus
		// chat renderer escapes raw HTML rather than rendering it.
		response.message.content = "${response.message.content}\n\n*Tokens: ${parts.join(', ')}*".toString()
		return response
	}

	/**
	 * Removes usage footers from the end of an answer that is being replayed - the
	 * plugin's own, and any imitation the model wrote.
	 */
	protected static String stripUsageFooter(String content) {
		return content?.replaceFirst(USAGE_FOOTER_PATTERN, '')
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

	/** en, de or pl - whichever the answer reads as; English when in doubt. */
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
		return [de: 'Kosten', pl: 'Koszt'][language] ?: 'Cost'
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

	/** Where the first U+FFFD sits: message index, role and a short ASCII-safe excerpt around it. */
	protected static String describeReplacementContext(List messages) {
		for (int index = 0; index < (messages?.size() ?: 0); index++) {
			Map message = messages[index] instanceof Map ? messages[index] as Map : [:]
			String text = message.content instanceof CharSequence ? message.content.toString() : null
			if (text?.contains('�')) {
				int at = text.indexOf('�')
				String excerpt = text.substring(Math.max(0, at - 24), Math.min(text.length(), at + 24))
				String safe = excerpt.collect { String ch -> (ch.charAt(0) as int) in 0x20..0x7E ? ch : String.format('<U+%04X>', ch.charAt(0) as int) }.join()
				return "first in message ${index}, role ${message.role}: ${safe}"
			}
		}
		return 'not located'
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

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	protected String resolveApiKey(AccountIntegration accountIntegration) {
		return accountIntegration.credentialData?.password ?: accountIntegration.serviceToken ?: accountIntegration.servicePassword
	}

	/**
	 * The base URL the API paths are appended to. Tolerates a pasted endpoint URL,
	 * and the Anthropic-compatible base URL https://openrouter.ai/api: OpenRouter's
	 * OpenAI API sits one level deeper, and without it /v1/models returns the website.
	 */
	protected String resolveBaseUrl(AccountIntegration accountIntegration) {
		String url = accountIntegration?.serviceUrl?.trim()
		if (!url) {
			return DEFAULT_API_URL
		}
		url = url.replaceAll('/+$', '').replaceAll('/(chat/completions|models|key)$', '').replaceAll('/+$', '')
		if (url.endsWith('/api')) {
			return url + '/v1'
		}
		// The main domain and the in-region domains eu. and us., entered without a path.
		if (url ==~ /(?i)https?:\/\/((www|eu|us)\.)?openrouter\.ai/) {
			return url + '/api/v1'
		}
		return url
	}

	/** Null when nothing usable is configured, so each model keeps its own maximum. */
	protected Integer resolveDefaultMaxOutputTokens(AccountIntegration accountIntegration) {
		Integer parsed = toInteger(accountIntegration?.getConfigProperty('maxOutputTokens'))
		return (parsed != null && parsed > 0) ? parsed : null
	}

	/** low, medium or high; null for the model's own default. */
	protected String resolveReasoningEffort(AccountIntegration accountIntegration) {
		String configured = accountIntegration?.getConfigProperty('reasoningEffort')?.toString()?.trim()?.toLowerCase()
		if (!configured || configured == REASONING_EFFORT_DEFAULT) {
			return null
		}
		if (configured in REASONING_EFFORTS) {
			return configured
		}
		log.warn("Ignoring unknown reasoning effort '${configured}'; sending none")
		return null
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

	protected boolean isUsageFooterEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('usageFooter'), false)
	}

	protected boolean isNetworkProxyEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('useNetworkProxy'), false)
	}

	protected boolean isIncludeAnthropicModels(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('includeAnthropicModels'), false)
	}

	protected boolean isIncludeFreeModels(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('includeFreeModels'), false)
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

	/** Caller options plus the proxy, for any call that leaves the appliance. */
	protected Map buildClientOpts(AccountIntegration accountIntegration, Map opts = [:]) {
		Map clientOpts = new LinkedHashMap(opts ?: [:])
		NetworkProxy proxy = resolveNetworkProxy(accountIntegration)
		if (proxy) {
			clientOpts.put(OpenRouterApiService.NETWORK_PROXY_KEY, proxy)
		}
		return clientOpts
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

	/**
	 * Connection failures before any answer, worth one more try. Not read timeouts:
	 * a completion that ran into the five-minute limit would only run into it again.
	 */
	protected static boolean isRetryableError(String message) {
		String msg = message?.toLowerCase() ?: ''
		return msg.contains('failed to respond') || msg.contains('connection reset') || msg.contains('broken pipe') ||
			msg.contains('nohttpresponse') || msg.contains('stream closed') || msg.contains('connect timed out')
	}
}
