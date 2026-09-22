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
import com.morpheusdata.model.AccountCredential
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
import groovy.json.JsonSlurper
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
	// A rate limit or an overloaded provider is worth waiting for: twice, as long as
	// OpenRouter asks or else 10 and then 20 seconds, never more than 30 seconds at a time.
	static final Set<Integer> WAIT_AND_RETRY_STATUSES = [429, 503] as Set
	static final List<Long> RETRY_WAITS_MS = [10000L, 20000L]
	static final long MAX_RETRY_WAIT_MS = 30000L
	static final int MAX_CONNECTION_RETRIES = 2
	// The heading an error answer starts with, per language. Replayed history drops
	// everything from that heading on, so a model never reads the plugin's error text.
	static final Map<String, String> ERROR_LABELS = [en: 'OpenRouter error', de: 'OpenRouter-Fehler', pl: 'Błąd OpenRouter']
	static final String ERROR_ANSWER_PATTERN = '(?s)(?:^|\\n\\n)\\*\\*(?:OpenRouter error|OpenRouter-Fehler|Błąd OpenRouter)(?: \\d{3})?:\\*\\* .*$'
	// What to do about a failure, by HTTP status, in the languages of the footer.
	static final Map<Integer, Map<String, String>> ERROR_HINTS = [
		400: [en: 'OpenRouter rejected the request. Another model may accept it.',
			  de: 'OpenRouter hat die Anfrage abgelehnt. Ein anderes Modell nimmt sie vielleicht an.',
			  pl: 'OpenRouter odrzucił zapytanie. Inny model może je przyjąć.'],
		401: [en: 'OpenRouter did not accept the API key of this integration. Check the key.',
			  de: 'OpenRouter hat den API-Key dieser Integration nicht angenommen. Bitte den Key prüfen.',
			  pl: 'OpenRouter nie zaakceptował klucza API tej integracji. Sprawdź klucz.'],
		402: [en: 'The OpenRouter account has no credits left, or the key has reached its limit.',
			  de: 'Das OpenRouter-Konto hat kein Guthaben mehr, oder der Key hat sein Limit erreicht.',
			  pl: 'Na koncie OpenRouter skończyły się środki albo klucz osiągnął swój limit.'],
		403: [en: 'OpenRouter refused the request, for example through moderation or a guardrail.',
			  de: 'OpenRouter hat die Anfrage verweigert, etwa durch Moderation oder eine Guardrail.',
			  pl: 'OpenRouter odmówił wykonania zapytania, na przykład z powodu moderacji lub guardraila.'],
		404: [en: 'The model is not available at this API endpoint. Pick another model for the agent.',
			  de: 'Das Modell ist an diesem API-Endpunkt nicht verfügbar. Bitte ein anderes Modell für den Agenten wählen.',
			  pl: 'Model nie jest dostępny pod tym adresem API. Wybierz inny model dla agenta.'],
		408: [en: 'The request timed out. Ask again.',
			  de: 'Die Anfrage hat zu lange gedauert. Bitte erneut fragen.',
			  pl: 'Zapytanie przekroczyło limit czasu. Zapytaj ponownie.'],
		429: [en: 'Too many requests in a short time. Wait a minute and ask again.',
			  de: 'Zu viele Anfragen in kurzer Zeit. Eine Minute warten und erneut fragen.',
			  pl: 'Zbyt wiele zapytań w krótkim czasie. Odczekaj minutę i zapytaj ponownie.'],
		502: [en: 'The model\'s provider is not responding right now. Ask again later or pick another model.',
			  de: 'Der Anbieter des Modells antwortet gerade nicht. Später erneut fragen oder ein anderes Modell wählen.',
			  pl: 'Dostawca modelu w tej chwili nie odpowiada. Zapytaj później lub wybierz inny model.']
	]
	// A model that goes away within this many days says so in its name.
	static final long EXPIRY_NOTICE_DAYS = 365L
	// What an answer says when the output token limit cut it off, per language.
	static final Map<String, String> TRUNCATION_NOTES = [
		en: 'Answer cut off at the output token limit.',
		de: 'Antwort am Ausgabe-Token-Limit abgeschnitten.',
		pl: 'Odpowiedź ucięta na limicie tokenów wyjściowych.'
	]
	// One or more italic usage or truncation lines at the very end of an answer.
	static final String USAGE_FOOTER_PATTERN = '(?:\\s*\\*(?:(?:Tokens|Cost|Kosten|Koszt): [^*\\n]*|Answer cut off at the output token limit\\.|Antwort am Ausgabe-Token-Limit abgeschnitten\\.|Odpowiedź ucięta na limicie tokenów wyjściowych\\.)\\*)+\\s*$'
	// Enough to pick the footer's language, not a language detector: answers in each
	// language are full of its words and next to never contain the others'. Letters
	// only one of the languages uses count extra.
	static final Map<String, Set<String>> LANGUAGE_MARKERS = [
		en: ['the', 'and', 'is', 'are', 'of', 'to', 'with', 'not', 'no', 'for', 'there', 'this', 'that', 'it', 'be', 'on', 'as', 'by', 'has', 'have',
			 'who', 'what', 'which', 'how', 'many', 'you', 'hello', 'please'] as Set,
		de: ['der', 'die', 'das', 'den', 'dem', 'und', 'ist', 'sind', 'nicht', 'keine', 'mit', 'auf', 'ein', 'eine', 'gibt', 'es', 'auch', 'wird', 'oder', 'bei', 'zu', 'von', 'im', 'sich', 'wie',
			 'wer', 'welche', 'viele', 'bist', 'du', 'ich', 'hallo', 'bitte'] as Set,
		pl: ['jest', 'są', 'się', 'nie', 'oraz', 'dla', 'czy', 'które', 'który', 'która', 'będzie', 'żadnych', 'działa', 'działają', 'wszystkie', 'w', 'i', 'na',
			 'kim', 'co', 'jak', 'ile', 'jesteś', 'cześć', 'proszę'] as Set
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
			// Not empty by default: Morpheus 9.0.1 does not save a text field that is emptied on
			// edit, so an allow list, once set, could never be removed again.
			defaultValue: '*',
			helpText: 'Comma-separated model ids, * as wildcard, e.g. openai/gpt-5*, google/gemini-3*. * alone lists every model that supports tool calling; use it instead of an empty field, which Morpheus 9.0.1 does not save. The checkboxes above still apply. A model that drops out is removed on save unless an agent uses it.'
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

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.chatErrors",
			name: 'Errors in Chat',
			fieldName: 'chatErrors',
			fieldLabel: 'Show OpenRouter Errors in Chat',
			fieldContext: 'config',
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 11,
			required: false,
			defaultValue: 'on',
			helpText: 'On by default. When OpenRouter refuses a question - no credits left, a rate limit, a model that is down - the chat shows OpenRouter\'s message and what to do about it, instead of Morpheus\' generic error. The message is left out when the conversation is replayed to the model. A rate limit or an overloaded provider is waited out twice before that.'
		)

		optionTypes << new OptionType(
			code: "${PROVIDER_CODE}.dropEmptyToolArguments",
			name: 'Drop Empty Tool Arguments',
			fieldName: 'dropEmptyToolArguments',
			fieldLabel: 'Drop Empty Tool Arguments',
			fieldContext: 'config',
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 12,
			required: false,
			defaultValue: 'on',
			helpText: 'On by default. Some models fill every optional parameter of a tool call with an empty string, 0 or false, and the built-in Morpheus tools treat those as filters: list_servers then finds no servers. With this on, such top-level arguments are removed before Morpheus runs the tool, which is the same as leaving them out. Untick it to forward tool calls exactly as the model wrote them.'
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
					message = "${message.endsWith('.') ? message : message + '.'} OpenRouter keys start with \"${API_KEY_PREFIX}\". Check that the whole key was pasted."
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

		Map result = callWithRetries { apiService.createChatCompletion(baseUrl, apiKey, requestBody, requestOpts) }

		if (result?.success && result.data instanceof Map) {
			Map data = result.data as Map
			if (data.error instanceof Map) {
				Map error = data.error as Map
				String message = explainError(OpenRouterApiService.describeError(error))
				return chatFailure(accountIntegration, request, requestBody, OpenRouterApiService.statusCodeOf(error.code),
					"Chat completion failed: ${message}".toString())
			}
			LlmChatResponse response = trackQuestionCost(requestBody, parseChatResponse(data))
			dropEmptyToolArguments(response, accountIntegration)
			return ServiceResponse.success(appendUsageFooter(response, accountIntegration))
		}
		return chatFailure(accountIntegration, request, requestBody, failureStatus(result),
			explainError(result?.msg?.toString()) ?: 'Chat completion failed')
	}

	/**
	 * A chat request that failed for good. Morpheus replaces any provider error with a
	 * generic text - "The AI model is no longer available", "An error occurred while
	 * processing your request" - so unless the integration says otherwise, the failure
	 * goes back as an answer that shows OpenRouter's message.
	 */
	protected ServiceResponse<LlmChatResponse> chatFailure(AccountIntegration accountIntegration, LlmChatRequest request, Map requestBody,
														   Integer status, String message) {
		log.warn("OpenRouter chat completion failed: ${message}")
		if (!isChatErrorsEnabled(accountIntegration)) {
			return errorResponse(message)
		}
		return ServiceResponse.success(buildErrorAnswer(accountIntegration, request, requestBody, status, message, null))
	}

	/**
	 * Runs a call until it succeeds or is not worth repeating. A connection failure is
	 * tried twice more; a rate limit or an overloaded provider twice more after a wait.
	 * {@code mayRetry} lets a stream stop retrying once text has reached the chat.
	 */
	protected Map callWithRetries(Closure<Map> call, Closure<Boolean> mayRetry = { true }) {
		Map result = null
		int connectionRetries = 0
		int waitRetries = 0
		while (true) {
			try {
				result = call.call()
			} catch (Exception e) {
				result = [success: false, msg: e.message]
			}
			if (result?.success || !mayRetry.call()) {
				return result
			}
			if (isRetryableError(result?.msg?.toString()) && connectionRetries < MAX_CONNECTION_RETRIES) {
				connectionRetries++
				log.warn("OpenRouter API retry ${connectionRetries}/${MAX_CONNECTION_RETRIES} after connection failure: ${result?.msg}")
				sleeper.call(1500L * connectionRetries)
				continue
			}
			Long wait = retryWaitMillis(result, waitRetries)
			if (wait == null) {
				return result
			}
			waitRetries++
			log.warn("OpenRouter answered ${failureStatus(result)}, asking again in ${wait.intdiv(1000)} s (${waitRetries}/${RETRY_WAITS_MS.size()}): ${result?.msg}")
			sleeper.call(wait)
		}
	}

	/** Waits between retries; tests replace it. */
	protected Closure sleeper = { long millis -> Thread.sleep(millis) }

	/** How long to wait before asking again, or null when the failure is not worth another try. */
	protected static Long retryWaitMillis(Map result, int retriesSoFar) {
		if (retriesSoFar >= RETRY_WAITS_MS.size() || !(failureStatus(result) in WAIT_AND_RETRY_STATUSES)) {
			return null
		}
		Integer retryAfter = toInteger(result?.retryAfter)
		long wait = retryAfter != null ? retryAfter * 1000L : RETRY_WAITS_MS[retriesSoFar]
		return Math.min(Math.max(wait, 0L), MAX_RETRY_WAIT_MS)
	}

	/** The HTTP status of a failed call: as the api service reports it, else from the message. */
	protected static Integer failureStatus(Map result) {
		if (result?.statusCode instanceof Number) {
			return ((Number) result.statusCode).intValue()
		}
		def matcher = (result?.msg?.toString() ?: '') =~ /returned (\d{3})\b/
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null
	}

	/**
	 * The answer that stands in for a failed request: OpenRouter's message under a heading
	 * with the status, what to do about it, and - with the footer on - what the question
	 * cost until then. In the language of the question, the only text there is to go by.
	 */
	protected LlmChatResponse buildErrorAnswer(AccountIntegration accountIntegration, LlmChatRequest request, Map requestBody,
											   Integer status, String message, String streamedText) {
		String language = answerLanguage(lastUserMessage(request))
		StringBuilder content = new StringBuilder()
		if (streamedText?.trim()) {
			content.append(streamedText.trim()).append('\n\n')
		}
		content.append("**${ERROR_LABELS[language]}${status ? ' ' + status : ''}:** ${displayMessage(message)}")
		String hint = errorHint(status, message, language)
		if (hint) {
			content.append('\n\n').append(hint)
		}

		LlmChatMessage chatMessage = new LlmChatMessage()
		chatMessage.role = 'assistant'
		chatMessage.content = content.toString()
		LlmChatResponse response = new LlmChatResponse()
		response.message = chatMessage
		response.finishReason = 'stop'
		response.metadata = [error: true, error_status: status, answer_language: language]

		// The failed request ends the question, so its cost tally closes here.
		Map tally = requestBody != null ? QUESTION_COSTS.remove(questionKey(requestBody)) : null
		if (tally) {
			response.metadata.put('question_cost', tally.total)
			response.metadata.put('question_requests', tally.requests)
		}
		return appendUsageFooter(response, accountIntegration)
	}

	protected static String lastUserMessage(LlmChatRequest request) {
		LlmChatMessage last = request?.messages?.reverse()?.find { LlmChatMessage msg -> msg?.role?.toLowerCase() == 'user' }
		return last?.content?.toString() ?: ''
	}

	/** OpenRouter's message without the prefixes the heading already says. */
	protected static String displayMessage(String message) {
		String stripped = (message ?: '').replaceFirst(/^(?:(?:Chat completion failed|OpenRouter stream error): )?(?:OpenRouter )?(?:API returned \d{3}:? ?)?/, '').trim()
		return stripped ?: message
	}

	/** Null without a status, and for the regional 403, whose message carries its own explanation. */
	protected static String errorHint(Integer status, String message, String language) {
		if (status == null || message?.contains(REGIONAL_ROUTING_DENIED)) {
			return null
		}
		Map<String, String> hints = ERROR_HINTS[status] ?: (status >= 500 ? ERROR_HINTS[502] : null)
		return hints ? (hints[language] ?: hints.en) : null
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
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		Map requestBody = null
		StringBuilder streamed = new StringBuilder()
		try {
			if (!accountIntegration) {
				handler?.onError(new IllegalArgumentException('Account integration is required for the OpenRouter integration'))
				return
			}
			String apiKey = resolveApiKey(accountIntegration)
			String baseUrl = resolveBaseUrl(accountIntegration)
			requestBody = buildChatRequestBody(request, accountIntegration, true)
			Map clientOpts = buildClientOpts(accountIntegration, opts)

			// Once text has reached the chat, another try would repeat it.
			Map result = callWithRetries({
				apiService.streamChatCompletion(baseUrl, apiKey, requestBody, { String chunk ->
					streamed.append(chunk)
					handler?.onPartialResponse(chunk)
				}, clientOpts)
			}, { streamed.length() == 0 })

			if (result?.success && result.data instanceof Map) {
				LlmChatResponse response = trackQuestionCost(requestBody, parseChatResponse(result.data as Map))
				dropEmptyToolArguments(response, accountIntegration)
				handler?.onCompleteResponse(appendUsageFooter(response, accountIntegration))
				return
			}
			String message = explainError(result?.msg?.toString()) ?: 'Streaming chat completion failed'
			log.warn("OpenRouter streaming chat failed: ${message}")
			streamFailure(accountIntegration, request, requestBody, handler, failureStatus(result), message, streamed.toString(), null)
		} catch (Exception e) {
			log.error("Error during OpenRouter streaming chat: ${e.message}", e)
			streamFailure(accountIntegration, request, requestBody, handler, null, e.message ?: e.class.simpleName, streamed.toString(), e)
		}
	}

	/** Like chatFailure, for a stream: an answer after the text that already arrived, or the error. */
	protected void streamFailure(AccountIntegration accountIntegration, LlmChatRequest request, Map requestBody, LlmStreamingResponseHandler handler,
								 Integer status, String message, String streamedText, Exception cause) {
		if (accountIntegration == null || !isChatErrorsEnabled(accountIntegration)) {
			handler?.onError(cause ?: new RuntimeException(message))
			return
		}
		handler?.onCompleteResponse(buildErrorAnswer(accountIntegration, request, requestBody, status, message, streamedText))
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
				// usage footer teaches the model to write one itself, with invented numbers,
				// and an error answer is the plugin speaking, not the model.
				String replayed = stripErrorAnswer(stripUsageFooter(content))
				List<Map> toolCalls = normalizeToolCalls(metadata.tool_calls)
				if (content.trim() && !replayed.trim() && !toolCalls) {
					return
				}
				Map assistant = [role: 'assistant', content: replayed]
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
		// The shape of the request, never its content: enough to see from the log what a
		// model was asked for when its answer comes back empty or cut off.
		log.info("OpenRouter request: model=${requestBody.model} messages=${messages.size()} tools=${tools ? tools.size() : 0} " +
			"max_tokens=${requestBody.max_tokens} temperature=${requestBody.temperature} reasoning=${requestBody.reasoning?.effort} stream=${requestBody.stream}")

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

	/** Name and arguments of a tool call, the arguments cut to a log-friendly length. */
	protected static String describeToolCall(Map call) {
		Map function = call.function as Map
		String arguments = function.arguments?.toString() ?: '{}'
		return "${function.name} ${arguments.length() > 300 ? arguments.substring(0, 300) + '...' : arguments}".toString()
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
			// With the arguments: Morpheus' built-in tools treat "" and 0 as filters, and some
			// models fill every optional parameter that way - "0 servers" then has a reason.
			log.info("OpenRouter tool calls: ${toolCalls.collect { Map call -> describeToolCall(call) }.join('; ')}")
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
		// An answer the token limit cut off ends mid-sentence, and the chat gives no sign of
		// it. The note is in the answer's language, and stripped again with the footer. With
		// no text at all - a reasoning model that thought its whole budget away - the note is
		// the answer, or Morpheus shows its generic "empty response" text.
		if (response.finishReason == 'length' && !toolCalls) {
			String language = answerLanguage(content)
			chatMessage.content = (content.trim() ? content.trim() + '\n\n' : '') + "*${TRUNCATION_NOTES[language]}*".toString()
			response.metadata.put('truncated', true)
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

	/**
	 * Removes the arguments a model only filled in for the sake of it. gpt-5.4-mini and
	 * gpt-5.4-nano send list_servers every optional parameter as "", 0 or false; Morpheus'
	 * built-in tools take each of those as a filter and answer with nothing (verified
	 * 2026-09-21 on 9.0.2). A dropped argument is the same as one the model never wrote.
	 */
	protected void dropEmptyToolArguments(LlmChatResponse response, AccountIntegration accountIntegration) {
		if (!isDropEmptyToolArgumentsEnabled(accountIntegration)) {
			return
		}
		List<Map> toolCalls = response?.metadata?.get('tool_calls') instanceof List ? response.metadata.get('tool_calls') as List<Map> : null
		if (!toolCalls) {
			return
		}
		toolCalls.each { Map call ->
			Map function = call.function as Map
			Map<String, List<String>> result = pruneEmptyArguments(function.arguments?.toString())
			if (result.dropped) {
				function.arguments = result.json
				log.info("OpenRouter dropped empty arguments from ${function.name}: ${result.dropped.join(', ')}")
			}
		}
		// The message metadata holds the same list Morpheus reads; keep both the same object.
		if (response.message?.metadata instanceof Map) {
			response.message.metadata.put('tool_calls', toolCalls)
		}
	}

	/**
	 * The arguments JSON without its empty top-level entries, and the names of those
	 * entries. Anything that is not a JSON object is returned as it is.
	 */
	protected static Map pruneEmptyArguments(String json) {
		if (!json?.trim()) {
			return [json: json, dropped: []]
		}
		def parsed
		try {
			parsed = new JsonSlurper().parseText(json)
		} catch (Exception ignored) {
			return [json: json, dropped: []]
		}
		if (!(parsed instanceof Map)) {
			return [json: json, dropped: []]
		}
		List<String> dropped = []
		Map kept = [:]
		(parsed as Map).each { key, value ->
			if (isEmptyArgument(value)) {
				dropped << key.toString()
			} else {
				kept.put(key, value)
			}
		}
		return [json: dropped ? JsonOutput.toJson(kept) : json, dropped: dropped]
	}

	/** "", 0, false, null, [] and {} - the values a model writes for "not set". */
	protected static boolean isEmptyArgument(def value) {
		if (value == null || value == false) {
			return true
		}
		if (value instanceof CharSequence) {
			return !value.toString().trim()
		}
		if (value instanceof Number) {
			return ((Number) value) == 0
		}
		if (value instanceof Collection) {
			return (value as Collection).isEmpty()
		}
		if (value instanceof Map) {
			return (value as Map).isEmpty()
		}
		return false
	}

	// ------------------------------------------------------------------
	// Model catalog
	// ------------------------------------------------------------------

	protected List<LlmModel> buildModelsFromApiResponse(LlmIntegration llmIntegration, Map apiResponse) {
		AccountIntegration accountIntegration = llmIntegration?.accountIntegration
		boolean includeAnthropic = isIncludeAnthropicModels(accountIntegration)
		boolean includeFree = isIncludeFreeModels(accountIntegration)
		String configuredAllowList = accountIntegration?.getConfigProperty('modelAllowList')?.toString()
		List<Pattern> allowList = parseModelAllowList(configuredAllowList)
		LocalDate today = LocalDate.now(ZoneOffset.UTC)
		List<Map> candidates = apiResponse?.data instanceof List ? (apiResponse.data as List).findAll { entry ->
			entry instanceof Map && isListedModel(entry as Map, includeAnthropic, includeFree, today)
		} as List<Map> : []
		List<Map> selected = candidates.findAll { Map entry -> matchesAllowList(entry.id?.toString()?.trim(), allowList) }
		if (allowList && !selected && candidates) {
			// Applied, an allow list that matches nothing removes every model of the
			// integration - which a typo, or a value the edit form mangled, once did.
			log.warn("OpenRouter integration ${llmIntegration?.id}: Only List These Models '${configuredAllowList}' matches none of " +
				"${candidates.size()} models and is ignored, so the model list is not emptied")
			selected = candidates
		}
		List<LlmModel> models = []
		selected.each { Map entryMap ->
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
		models.sort { LlmModel a, LlmModel b -> (a.name ?: '').compareToIgnoreCase(b.name ?: '') }
		return models
	}

	/**
	 * Which catalog entries become Morpheus models.
	 *
	 *  - Agents work through MCP tools, so a model has to list {@code tools}.
	 *  - Text output only, no image or audio output.
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
		// Text only: an agent answers in text, and a model that also generates images or audio
		// ("Nano Banana Pro") costs more for nothing an agent uses.
		List<String> outputs = modalities(entry, 'output_modalities')
		if (outputs && outputs.toSet() != ['text'] as Set) {
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
		return matchesAllowList(id, allowList)
	}

	protected static boolean matchesAllowList(String id, List<Pattern> allowList) {
		return !allowList || (id && allowList.any { Pattern pattern -> pattern.matcher(id).matches() })
	}

	/**
	 * The allow list as case-insensitive patterns matching a whole model id, with * as
	 * the only wildcard. Empty when nothing is configured. Quotes around an entry are
	 * dropped: a value emptied through the REST API comes back from the edit form as "".
	 */
	protected static List<Pattern> parseModelAllowList(String configured) {
		if (!configured?.trim()) {
			return []
		}
		return configured.split(/[,\s]+/).collect { String token -> token.replaceAll(/^["']+|["']+$/, '') }.findAll { it }.collect { String glob ->
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
			String language = response.metadata?.get('answer_language')?.toString() ?: answerLanguage(response.message.content.toString())
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

	/** Removes an error answer, and everything after its heading, from a replayed answer. */
	protected static String stripErrorAnswer(String content) {
		return content?.replaceFirst(ERROR_ANSWER_PATTERN, '')
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

	/**
	 * The key of the stored credential when the integration has one, else the local field.
	 *
	 * Morpheus does not always hand the stored credential over. An update through
	 * PUT /api/integrations/<id> arrives with credentialData unloaded, and the local
	 * servicePassword - whatever was once typed into the hidden local field - won
	 * (verified on 9.0.1). The credential is then loaded explicitly, the way cloud
	 * plugins load theirs.
	 */
	protected String resolveApiKey(AccountIntegration accountIntegration) {
		Map handedOver = accountIntegration.credentialData
		if (!handedOver?.password) {
			// Not only when credentialData is null: 0.1.0-rc.3 checked for exactly that and
			// the REST API update still failed, so the check is on the key itself.
			AccountCredential credential = null
			String lookup
			try {
				credential = loadAccountCredential(accountIntegration)
				lookup = credential?.data?.password ? 'stored credential found' : (credential ? 'stored credential without a password' : 'no stored credential')
			} catch (Exception e) {
				lookup = "lookup failed: ${e.message}"
			}
			if (credential?.data?.password) {
				accountIntegration.credentialData = credential.data
				accountIntegration.credentialLoaded = true
			}
			logCredentialDiagnosis(accountIntegration, handedOver, lookup)
		}
		return accountIntegration.credentialData?.password ?: accountIntegration.serviceToken ?: accountIntegration.servicePassword
	}

	// Integrations whose credential handover has been logged, so a Local Credentials
	// integration does not write the line on every chat request.
	protected static final Set<String> CREDENTIAL_DIAGNOSED = ConcurrentHashMap.newKeySet()

	/** What Morpheus handed over and what the lookup found - never a key. Once per integration and outcome. */
	protected void logCredentialDiagnosis(AccountIntegration accountIntegration, Map handedOver, String lookup) {
		String data = handedOver == null ? 'null' : (handedOver.isEmpty() ? 'empty' : "keys ${handedOver.keySet().sort()}")
		String line = "OpenRouter integration ${accountIntegration.id}: credentialData ${data}, credentialLoaded ${accountIntegration.credentialLoaded}, " +
			"${lookup}, local key ${accountIntegration.servicePassword ? 'set' : 'empty'}"
		if (CREDENTIAL_DIAGNOSED.add("${accountIntegration.id}|${line}".toString())) {
			log.info(line)
		}
	}

	/** The appliance lookup, on its own so tests can answer it. Null for Local Credentials. */
	protected AccountCredential loadAccountCredential(AccountIntegration accountIntegration) {
		return morpheusContext?.services?.accountCredential?.loadCredentials(accountIntegration)
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

	/** On unless unticked: integrations saved before the option existed have no value for it. */
	protected boolean isChatErrorsEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('chatErrors'), true)
	}

	/** On unless unticked, for the same reason. */
	protected boolean isDropEmptyToolArgumentsEnabled(AccountIntegration accountIntegration) {
		return toBoolean(accountIntegration?.getConfigProperty('dropEmptyToolArguments'), true)
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
