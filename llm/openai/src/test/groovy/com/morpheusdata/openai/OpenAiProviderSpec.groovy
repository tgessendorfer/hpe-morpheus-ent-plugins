package com.morpheusdata.openai

import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.llm.LlmChatMessage
import com.morpheusdata.model.llm.LlmChatRequest
import com.morpheusdata.model.llm.LlmChatResponse
import com.morpheusdata.model.llm.LlmIntegration
import com.morpheusdata.model.llm.LlmModel
import com.morpheusdata.response.LlmStreamingResponseHandler
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

import java.time.LocalDate

/**
 * Covers the form, validation, request and response handling, the cost footer and
 * the catalog filter.
 */
class OpenAiProviderSpec extends Specification {

	OpenAiProvider provider = new OpenAiProvider(null, null)
	AccountIntegration integration = new AccountIntegration(serviceUrl: OpenAiProvider.DEFAULT_API_URL)

	private static LlmChatMessage message(String role, String content, Map metadata = null) {
		LlmChatMessage msg = new LlmChatMessage()
		msg.role = role
		msg.content = content
		msg.metadata = metadata
		return msg
	}

	private static AccountIntegration configured(Map config) {
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenAiProvider.DEFAULT_API_URL)
		ai.setConfigMap(config)
		return ai
	}

	/** A catalog entry shaped like one from GET /api/v1/models. */
	private static Map catalogEntry(String id, Map overrides = [:]) {
		Map entry = [
			id                  : id,
			name                : "Vendor: ${id}".toString(),
			context_length      : 128000,
			architecture        : [input_modalities: ['text', 'image'], output_modalities: ['text']],
			top_provider        : [context_length: 128000, max_completion_tokens: 16384],
			supported_parameters: ['max_tokens', 'temperature', 'tools', 'tool_choice', 'reasoning'],
			expiration_date     : null
		]
		entry.putAll(overrides)
		return entry
	}

	// ------------------------------------------------------------------
	// Form and plugin registration
	// ------------------------------------------------------------------

	def "every form label and help text has an entry in each language bundle"() {
		given:
		List<String> keys = provider.optionTypes.collectMany { OptionType optionType ->
			[optionType.fieldCode] + (optionType.helpText ? [optionType.helpTextI18nCode] : [])
		}
		Map<String, List<String>> missing = bundle.collectEntries { String name ->
			Properties properties = new Properties()
			InputStream stream = getClass().getResourceAsStream("/i18n/${name}.properties")
			assert stream != null: "i18n/${name}.properties is not on the classpath"
			stream.withStream { properties.load(it) }
			[(name): keys.findAll { !properties.getProperty(it)?.trim() }]
		}

		expect:
		keys.every { it }
		missing.every { it.value.isEmpty() }

		where:
		bundle << [['messages', 'messages_de', 'messages_pl']]
	}

	def "descriptions fit Morpheus' 255-character columns, or plugin registration fails outright"() {
		expect:
		OpenAiPlugin.DESCRIPTION.length() <= 255
		provider.description.length() <= 255
	}

	def "the reasoning effort is a select served by the plugin's option source"() {
		when:
		OptionType option = provider.optionTypes.find { it.fieldName == 'reasoningEffort' }
		OpenAiOptionSourceProvider source = new OpenAiOptionSourceProvider(null, null)

		then:
		option.inputType == OptionType.InputType.SELECT
		option.optionSource in source.methodNames
		option.defaultValue == OpenAiProvider.REASONING_EFFORT_DEFAULT
		source.openAiReasoningEfforts(null)*.value == ['default', 'low', 'medium', 'high']
	}

	def "base url is taken as entered, less whitespace, trailing slashes and a pasted endpoint path"() {
		expect:
		provider.resolveBaseUrl(new AccountIntegration(serviceUrl: entered)) == expected

		where:
		entered                                          || expected
		null                                             || 'https://api.openai.com/v1'
		'https://api.openai.com/v1'                      || 'https://api.openai.com/v1'
		' http://192.0.2.10:4000/v1 '                    || 'http://192.0.2.10:4000/v1'
		'https://openrouter.ai/api/v1/'                  || 'https://openrouter.ai/api/v1'
		'https://openrouter.ai/api/v1/chat/completions'  || 'https://openrouter.ai/api/v1'
		'https://openrouter.ai/api/v1/models'            || 'https://openrouter.ai/api/v1'
		'http://ollama.example.com:11434'                || 'http://ollama.example.com:11434'
		'https://openrouter.ai/api'                      || 'https://openrouter.ai/api'
		'https://gateway.example.com/openai/v1'          || 'https://gateway.example.com/openai/v1'
	}

	// ------------------------------------------------------------------
	// Validation
	// ------------------------------------------------------------------

	def "the stored credential is loaded when Morpheus did not hand it over, and wins over a stale local key"() {
		given: 'an update through the REST API: no credentialData, and an old value in the hidden local field'
		int lookups = 0
		OpenAiProvider withLookup = new OpenAiProvider(null, null) {
			@Override
			protected AccountCredential loadAccountCredential(AccountIntegration accountIntegration) {
				lookups++
				return new AccountCredential(data: [password: 'sk-or-v1-stored'])
			}
		}
		AccountIntegration ai = new AccountIntegration(id: 4L, servicePassword: 'sk-or-v1-0000000000')

		expect:
		withLookup.resolveApiKey(ai) == 'sk-or-v1-stored'
		withLookup.resolveApiKey(ai) == 'sk-or-v1-stored'

		and: 'looked up once, then remembered on the integration'
		lookups == 1
		ai.credentialLoaded
	}

	def "a handed-over credential is used as it is, and Local Credentials fall back to the local field"() {
		given:
		OpenAiProvider withLookup = new OpenAiProvider(null, null) {
			@Override
			protected AccountCredential loadAccountCredential(AccountIntegration accountIntegration) {
				if (accountIntegration.id == 99L) {
					throw new IllegalStateException('lookup must not run when the credential is already there')
				}
				return null
			}
		}

		expect:
		withLookup.resolveApiKey(new AccountIntegration(id: 99L, credentialData: [password: 'sk-or-v1-stored'], servicePassword: 'stale')) == 'sk-or-v1-stored'
		withLookup.resolveApiKey(new AccountIntegration(id: 5L, servicePassword: 'sk-or-v1-local')) == 'sk-or-v1-local'
	}

	def "credential data without a key, or marked as loaded, still gets the lookup"() {
		given: 'shapes Morpheus might hand over on the REST API update path'
		OpenAiProvider withLookup = new OpenAiProvider(null, null) {
			@Override
			protected AccountCredential loadAccountCredential(AccountIntegration accountIntegration) {
				return new AccountCredential(data: [password: 'sk-or-v1-stored'])
			}
		}

		expect:
		withLookup.resolveApiKey(new AccountIntegration(id: 7L, credentialData: [:], servicePassword: 'stale')) == 'sk-or-v1-stored'
		withLookup.resolveApiKey(new AccountIntegration(id: 8L, credentialLoaded: true, servicePassword: 'stale')) == 'sk-or-v1-stored'
	}

	def "a failing credential lookup does not take the chat down"() {
		given:
		OpenAiProvider failing = new OpenAiProvider(null, null) {
			@Override
			protected AccountCredential loadAccountCredential(AccountIntegration accountIntegration) {
				throw new RuntimeException('database unavailable')
			}
		}

		expect:
		failing.resolveApiKey(new AccountIntegration(id: 6L, servicePassword: 'sk-or-v1-local')) == 'sk-or-v1-local'
	}

	def "saving loads the model list with the key, and a refused key says so"() {
		given:
		OpenAiApiService api = Mock()
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenAiProvider.DEFAULT_API_URL, servicePassword: 'sk-bad')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		1 * api.listModels('https://api.openai.com/v1', 'sk-bad', _) >> [success: false, statusCode: 401, msg: 'API returned 401: Incorrect API key provided: sk-bad.']
		!response.success
		response.msg == 'Could not load the model list from https://api.openai.com/v1/models: API returned 401: Incorrect API key provided: sk-bad.. Check the API key.'
	}

	def "an OpenRouter endpoint also checks the key, since its model list answers anyone"() {
		given: 'what openrouter.ai answered on 2026-09-21 to a LiteLLM key'
		OpenAiApiService api = Mock()
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: url, servicePassword: 'sk-not-an-openrouter-key')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		1 * api.listModels(*_) >> [success: true, data: [data: [catalogEntry('openai/gpt-5.5')]]]
		(openRouter ? 1 : 0) * api.getKey(url, 'sk-not-an-openrouter-key', _) >> [success: false, statusCode: 401, msg: 'API returned 401: User not found.']
		response.success == !openRouter
		!openRouter || response.msg == "OpenRouter did not accept the API key at ${url}/key: API returned 401: User not found.. Check the API key."

		where:
		url                                    || openRouter
		'https://openrouter.ai/api/v1'         || true
		'https://eu.openrouter.ai/api/v1'      || true
		'https://api.openai.com/v1'            || false
		'https://notopenrouter.ai/v1'          || false
		'http://192.0.2.10:4000/v1'            || false
	}

	def "a 404 on the model list points at the version path"() {
		given:
		OpenAiApiService api = Stub()
		api.listModels(*_) >> [success: false, statusCode: 404, msg: 'API returned 404: Not Found']
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'https://api.openai.com', servicePassword: 'sk-test')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		!response.success
		response.msg.startsWith('Could not load the model list from https://api.openai.com/models: API returned 404: Not Found. Check the API Endpoint')
		response.msg.contains('https://api.openai.com/v1')
	}

	def "a TLS handshake failure gets a certificate hint"() {
		given: 'what HttpApiClient reports for an endpoint whose handshake fails'
		OpenAiApiService api = Stub()
		api.listModels(*_) >> [success: false, msg: 'Error occurred processing the response for https://gateway.example.com/v1/models : (handshake_failure) Received fatal alert: handshake_failure']
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'https://gateway.example.com/v1', servicePassword: 'sk-test')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		!response.success
		response.msg.endsWith('The TLS handshake failed; the appliance may not trust the endpoint\'s certificate.')
	}

	def "a base URL that answers with a web page fails validation"() {
		given:
		OpenAiApiService api = Stub()
		api.listModels(*_) >> [success: true, data: [data: '<!DOCTYPE html>']]
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'https://gateway.example.com/v1', servicePassword: 'sk-test')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		!response.success
		response.msg.contains('returned no model list')
		response.msg.contains('most OpenAI-compatible APIs sit under /v1')
	}

	def "an endpoint without a key validates when it lists models"() {
		given: 'a LAN endpoint that takes no key'
		OpenAiApiService api = Mock()
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'http://ollama.example.com:11434/v1')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		1 * api.listModels('http://ollama.example.com:11434/v1', null, _) >> [success: true, data: [data: [[id: 'qwen3:8b', object: 'model']]]]
		response.success
	}

	def "a URL without a scheme is refused before any call"() {
		given:
		OpenAiApiService api = Mock()
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'api.openai.com/v1', servicePassword: 'sk-test')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		0 * api._
		!response.success
		response.msg.contains('http:// or https://')
	}

	// ------------------------------------------------------------------
	// Failures
	// ------------------------------------------------------------------

	def "a rate limit is waited out for as long as the API asks, and the answer comes through"() {
		given:
		OpenAiApiService api = Stub()
		api.createChatCompletion(*_) >>> [
			[success: false, msg: 'API returned 429: Rate limit exceeded', statusCode: 429, retryAfter: 7],
			[success: true, data: [choices: [[finish_reason: 'stop', message: [content: 'Es gibt eine Gruppe.']]]]]
		]
		provider.apiService = api
		List<Long> waits = []
		provider.sleeper = { long millis -> waits << millis }
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenAiProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		when:
		ServiceResponse<LlmChatResponse> response = provider.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'google/gemini-3.5-flash', messages: [message('user', 'Wie viele Gruppen gibt es?')]), [:])

		then:
		waits == [7000L]
		response.success
		response.data.message.content == 'Es gibt eine Gruppe.'
	}

	def "a rate limit that stays ends as an answer with the API's message, in the language of the question"() {
		given: 'what OpenRouter answered a new account on 2026-09-15'
		OpenAiApiService api = Stub()
		api.createChatCompletion(*_) >> [success: false, statusCode: 429,
										 msg    : 'API returned 429: Rate limit exceeded: new-account-rpm/google/gemini-3.5-flash-20260519. Rate limit reached: new accounts are limited to 20 requests per minute for this model. Please retry shortly.']
		provider.apiService = api
		List<Long> waits = []
		provider.sleeper = { long millis -> waits << millis }
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenAiProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		when:
		ServiceResponse<LlmChatResponse> response = provider.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'google/gemini-3.5-flash', messages: [message('user', 'Wie viele Server gibt es?')]), [:])

		then: 'two waits, then the failure reaches the chat as an answer Morpheus shows'
		waits == [10000L, 20000L]
		response.success
		response.data.finishReason == 'stop'
		response.data.message.content == '**LLM-API-Fehler 429:** Rate limit exceeded: new-account-rpm/google/gemini-3.5-flash-20260519. ' +
			'Rate limit reached: new accounts are limited to 20 requests per minute for this model. Please retry shortly.\n\n' +
			'Zu viele Anfragen in kurzer Zeit. Eine Minute warten und erneut fragen.'
	}

	def "an error answer carries what the question cost until it failed"() {
		given: 'a billed tool round, then a request refused for missing credits'
		OpenAiApiService api = Stub()
		api.createChatCompletion(*_) >>> [
			[success: true, data: [choices: [[finish_reason: 'tool_calls', message: [content: null, tool_calls: [[id: 't1', function: [name: 'list_servers', arguments: '{}']]]]]],
								   usage  : [prompt_tokens: 10, completion_tokens: 5, cost: 0.1408]]],
			[success: false, statusCode: 402, msg: 'API returned 402: Insufficient credits']
		]
		provider.apiService = api
		AccountIntegration ai = configured([usageFooter: true])
		ai.servicePassword = 'sk-or-v1-test'
		LlmIntegration llm = new LlmIntegration(accountIntegration: ai)
		LlmChatMessage question = message('user', 'How many servers are there?')

		when:
		provider.generateResponse(llm, new LlmChatRequest(model: 'openai/gpt-5.5', messages: [question]), [:])
		ServiceResponse<LlmChatResponse> failed = provider.generateResponse(llm, new LlmChatRequest(model: 'openai/gpt-5.5', messages: [
			question,
			message('assistant', '', [tool_calls: [[id: 't1', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]),
			message('tool', '[]', [tool_call_id: 't1'])
		]), [:])

		then:
		failed.success
		failed.data.message.content == '**LLM API error 402:** Insufficient credits\n\n' +
			'The account has no credits left, or the key has reached its limit.\n\n*Cost: $0.1408*'
	}

	def "with the error option unticked, a failure stays an error for Morpheus"() {
		given:
		OpenAiApiService api = Stub()
		api.createChatCompletion(*_) >> [success: false, statusCode: 402, msg: 'API returned 402: Insufficient credits']
		provider.apiService = api
		AccountIntegration ai = configured([chatErrors: false])
		ai.servicePassword = 'sk-or-v1-test'

		when:
		ServiceResponse response = provider.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'm', messages: [message('user', 'hi')]), [:])

		then:
		!response.success
		response.msg == 'API returned 402: Insufficient credits'
	}

	def "the error option is on unless it was unticked"() {
		expect:
		provider.isChatErrorsEnabled(configured(config)) == enabled

		where:
		config              || enabled
		[:]                 || true
		[chatErrors: '']    || true
		[chatErrors: 'on']  || true
		[chatErrors: true]  || true
		[chatErrors: false] || false
		[chatErrors: 'off'] || false
	}

	def "a stream refused before any text is retried like a request"() {
		given:
		OpenAiApiService api = Stub()
		api.streamChatCompletion(*_) >>> [
			[success: false, statusCode: 503, msg: 'OpenRouter API returned 503: No instances available'],
			[success: true, data: [choices: [[finish_reason: 'stop', message: [content: 'One.']]]]]
		]
		provider.apiService = api
		List<Long> waits = []
		provider.sleeper = { long millis -> waits << millis }
		List<LlmChatResponse> completed = []
		LlmStreamingResponseHandler handler = [onCompleteResponse: { LlmChatResponse response -> completed << response }] as LlmStreamingResponseHandler
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenAiProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		when:
		provider.streamResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'openai/gpt-5.5', messages: [message('user', 'How many clouds?')]), handler, [:])

		then:
		waits == [10000L]
		completed*.message*.content == ['One.']
	}

	def "a stream that fails after its first words ends with the error under them, and is not retried"() {
		given:
		OpenAiApiService api = Mock()
		provider.apiService = api
		List<Long> waits = []
		provider.sleeper = { long millis -> waits << millis }
		List<String> parts = []
		List<LlmChatResponse> completed = []
		List<Throwable> errors = []
		LlmStreamingResponseHandler handler = [
			onPartialResponse : { String chunk -> parts << chunk },
			onCompleteResponse: { LlmChatResponse response -> completed << response },
			onError           : { Throwable t -> errors << t }
		] as LlmStreamingResponseHandler
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenAiProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		when:
		provider.streamResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'openai/gpt-5.5', messages: [message('user', 'Which servers are running?')]), handler, [:])

		then: 'one call only: another try would repeat the words already shown'
		1 * api.streamChatCompletion(*_) >> { List args ->
			(args[3] as Closure).call('Two servers run')
			[success: false, statusCode: 429, msg: 'Stream error: Rate limit exceeded (provider: OpenAI)']
		}
		waits.isEmpty()
		parts == ['Two servers run']
		errors.isEmpty()
		completed*.message*.content == ['Two servers run\n\n**LLM API error 429:** Rate limit exceeded (provider: OpenAI)\n\n' +
			'Too many requests in a short time. Wait a minute and ask again.']
	}

	def "error answers are left out of replayed history, and text before one is kept"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'openai/gpt-5.5', messages: [
			message('user', 'Wie viele Server gibt es?'),
			message('assistant', '**LLM-API-Fehler 429:** Rate limit exceeded\n\nZu viele Anfragen in kurzer Zeit. Eine Minute warten und erneut fragen.\n\n*Kosten: $0.1408 (22 Anfragen)*'),
			message('user', 'Which servers run?'),
			message('assistant', 'Two servers run\n\n**LLM API error 502:** Provider returned error'),
			message('user', 'Und jetzt?')
		])

		when:
		Map body = provider.buildChatRequestBody(request, integration, false)

		then:
		body.messages*.role == ['user', 'user', 'assistant', 'user']
		body.messages*.content == ['Wie viele Server gibt es?', 'Which servers run?', 'Two servers run', 'Und jetzt?']
	}

	def "a short question is enough to pick the language of an error answer"() {
		expect: 'the question the owner asked on the appliance got an English error answer with 0.1.1-rc.1'
		OpenAiProvider.answerLanguage(question) == language

		where:
		question                         || language
		'Hallo, wer bist du?'            || 'de'
		'Wie viele Gruppen gibt es?'     || 'de'
		'Hi, who are you?'               || 'en'
		'How many servers are there?'    || 'en'
		'Cześć, kim jesteś?'             || 'pl'
		'Ile jest serwerów?'             || 'pl'
	}

	def "every error hint and heading speaks each language of the footer"() {
		expect:
		OpenAiProvider.ERROR_LABELS.keySet() == ['en', 'de', 'pl'] as Set
		OpenAiProvider.ERROR_HINTS.every { Integer status, Map<String, String> hints ->
			hints.keySet() == ['en', 'de', 'pl'] as Set && hints.values().every { it?.trim() }
		}
		OpenAiProvider.errorHint(504, 'Gateway timeout', 'de') == OpenAiProvider.ERROR_HINTS[502].de
		OpenAiProvider.errorHint(null, 'Connection reset', 'en') == null
	}

	def "a working key and catalog validate"() {
		given:
		OpenAiApiService api = Stub()
		api.listModels(*_) >> [success: true, data: [data: [catalogEntry('openai/gpt-5.5')]]]
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenAiProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		expect:
		provider.validate(new LlmIntegration(accountIntegration: ai), [:]).success
	}

	// ------------------------------------------------------------------
	// Request
	// ------------------------------------------------------------------

	def "messages pass through in OpenAI format, tool loop included"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'openai/gpt-5.4-nano', messages: [
			message('system', 'You are a Morpheus operator.'),
			message('user', 'How many instances run in lab?'),
			message('assistant', '', [tool_calls: [[id: 'call_1', type: 'function', function: [name: 'get_instance_count', arguments: '{"cloud":"lab"}']]]]),
			message('tool', '{"count": 7}', [tool_call_id: 'call_1'])
		])

		when:
		Map body = provider.buildChatRequestBody(request, integration, false)

		then:
		body.model == 'openai/gpt-5.4-nano'
		body.messages == [
			[role: 'system', content: 'You are a Morpheus operator.'],
			[role: 'user', content: 'How many instances run in lab?'],
			[role: 'assistant', content: '', tool_calls: [[id: 'call_1', type: 'function', function: [name: 'get_instance_count', arguments: '{"cloud":"lab"}']]]],
			[role: 'tool', tool_call_id: 'call_1', content: '{"count": 7}']
		]
		body.stream == false
	}

	def "tool arguments given as an object are sent as a JSON string"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'm', messages: [
			message('assistant', null, [tool_calls: [[id: 'call_1', function: [name: 'list_clouds', arguments: [max: 10]]]]])
		])

		expect:
		provider.buildChatRequestBody(request, integration, null).messages[0].tool_calls[0].function.arguments == '{"max":10}'
	}

	def "tools and tool_choice are forwarded, and a bare definition is wrapped"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'm', messages: [message('user', 'hi')], options: [
			tools      : [
				[type: 'function', function: [name: 'use_instances_tools', parameters: [type: 'object']]],
				[name: 'list_clouds', description: 'Lists clouds', parameters: [type: 'object', properties: [:]]]
			],
			tool_choice: 'auto'
		])

		when:
		Map body = provider.buildChatRequestBody(request, integration, false)

		then:
		body.tools == [
			[type: 'function', function: [name: 'use_instances_tools', parameters: [type: 'object']]],
			[type: 'function', function: [name: 'list_clouds', description: 'Lists clouds', parameters: [type: 'object', properties: [:]]]]
		]
		body.tool_choice == 'auto'
	}

	def "sampling parameters and stop sequences are forwarded unchanged, and nothing else is added"() {
		given: 'Morpheus sends a temperature on every request; OpenRouter drops it for models that do not take it'
		LlmChatRequest request = new LlmChatRequest(model: 'anthropic/claude-sonnet-5', temperature: 0.7, topP: 0.9,
			stopSequences: ['END'], messages: [message('user', 'hi')])

		when:
		Map body = provider.buildChatRequestBody(request, integration, false)

		then:
		body.temperature == 0.7
		body.top_p == 0.9
		body.stop == ['END']
		!body.containsKey('max_tokens')
		!body.containsKey('reasoning')
		!body.containsKey('tools')
		!body.containsKey('tool_choice')
	}

	def "max_tokens comes from the request, then from the integration, else it is left out"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'm', maxOutputTokens: requested, messages: [message('user', 'hi')])

		expect:
		provider.buildChatRequestBody(request, configured([maxOutputTokens: configuredValue]), false).max_tokens == expected

		where:
		requested | configuredValue || expected
		1000      | '4096'          || 1000
		null      | '4096'          || 4096
		null      | null            || null
		null      | '0'             || null
	}

	def "OpenAI reasoning models get max_completion_tokens and no sampling parameters"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: model, messages: [message('user', 'hi')], temperature: 0.7d, topP: 0.9d, maxOutputTokens: 1000)

		when:
		Map body = provider.buildChatRequestBody(request, integration, false)

		then: 'a reasoning model gets a budget that leaves room for an answer after its reasoning'
		body.max_tokens == (reasoning ? null : 1000)
		body.max_completion_tokens == (reasoning ? 8192 : null)
		(body.temperature == null) == reasoning
		(body.top_p == null) == reasoning

		where:
		model                  || reasoning
		'gpt-5.5'              || true
		'openai/gpt-5-mini'    || true
		'o3'                   || true
		'O4-mini'              || true
		'codex-mini-latest'    || true
		'gpt-4.1'              || false
		'deepseek-v3.2'        || false
		'google/gemini-3-pro'  || false
		'claude-sonnet-5'      || false
	}

	def "a reasoning model keeps the integration's own output limit, and a caller's limit above the floor"() {
		expect:
		provider.buildChatRequestBody(new LlmChatRequest(model: 'gpt-5.5', messages: [message('user', 'hi')], maxOutputTokens: caller),
			configured(config), false).max_completion_tokens == expected

		where:
		caller | config                     || expected
		1000   | [:]                        || 8192
		1000   | [maxOutputTokens: '2000']  || 2000
		20000  | [:]                        || 20000
		null   | [:]                        || null
		null   | [maxOutputTokens: '500']   || 500
	}

	def "an empty answer cut off by the token limit becomes the note itself"() {
		given: 'gpt-5-mini through OpenRouter on 2026-09-21: 960 reasoning tokens, no text'
		Map data = [choices: [[finish_reason: 'length', message: [role: 'assistant', content: '']]], usage: [prompt_tokens: 13847, completion_tokens: 960]]

		expect:
		provider.parseChatResponse(data).message.content == '*Answer cut off at the output token limit.*'
	}

	def "an answer the token limit cut off says so, in its own language, and the note is stripped on replay"() {
		given:
		Map data = [choices: [[finish_reason: 'length', message: [role: 'assistant', content: content]]], usage: [prompt_tokens: 1, completion_tokens: 1000]]

		when:
		LlmChatResponse response = provider.parseChatResponse(data)

		then:
		response.finishReason == 'length'
		response.metadata.truncated == true
		response.message.content == content + '\n\n*' + note + '*'
		OpenAiProvider.stripUsageFooter(response.message.content) == content

		where:
		content                                                     || note
		'The history of virtualization begins with the mainframe'   || 'Answer cut off at the output token limit.'
		'Die Geschichte der Virtualisierung beginnt mit dem'         || 'Antwort am Ausgabe-Token-Limit abgeschnitten.'
	}

	def "reasoning_content is read like reasoning, and stays out of the answer"() {
		given: 'what LiteLLM relays from DeepSeek'
		Map data = [choices: [[finish_reason: 'stop', message: [role: 'assistant', content: 'Pong', reasoning_content: 'The user wants a ping.']]]]

		expect:
		provider.parseChatResponse(data).message.content == 'Pong'
		provider.parseChatResponse(data).metadata.reasoning == 'The user wants a ping.'
	}

	def "reasoning is only requested when the integration sets an effort"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'm', messages: [message('user', 'hi')])

		expect:
		provider.buildChatRequestBody(request, configured([reasoningEffort: effort]), false).reasoning_effort == expected

		where:
		effort    || expected
		null      || null
		'default' || null
		'high'    || 'high'
		'Low'     || 'low'
		'extreme' || null
	}

	def "usage footers are stripped from answers replayed as history"() {
		given: 'an answer with the plugin footer, and a later one where the model imitated it above the real one'
		LlmChatRequest request = new LlmChatRequest(model: 'openai/gpt-5.5', messages: [
			message('user', 'How many instances are there?'),
			message('assistant', 'There are 0 instances.\n\n*Cost: $0.0021 (3 requests)*'),
			message('user', 'Wie viele sind gestoppt?'),
			message('assistant', 'Keine.\n\n*Kosten: $0.0010*\n\n*Tokens: 1,200 input (800 cached), 20 output*'),
			message('user', 'And stopped ones?')
		])

		when:
		Map body = provider.buildChatRequestBody(request, integration, false)

		then:
		body.messages*.content == ['How many instances are there?', 'There are 0 instances.', 'Wie viele sind gestoppt?', 'Keine.', 'And stopped ones?']
	}

	def "history that lost characters to U+FFFD gets a note after the system prompt"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'm', messages: [
			message('system', 'You are a Morpheus operator.'),
			message('user', 'Which servers run?'),
			message('assistant', 'Status: L�uft'),
			message('user', 'And VMs?')
		])

		when:
		Map body = provider.buildChatRequestBody(request, integration, false)

		then: 'the system prompt stays first and unchanged'
		body.messages[0] == [role: 'system', content: 'You are a Morpheus operator.']
		body.messages[1] == [role: 'system', content: OpenAiProvider.REPLACEMENT_CHARACTER_NOTE]
		body.messages.size() == 5
	}

	// ------------------------------------------------------------------
	// Response
	// ------------------------------------------------------------------

	def "empty tool arguments a model filled in are dropped before Morpheus runs the tool"() {
		given: 'what gpt-5.4-mini sent for list_servers on 2026-09-21'
		OpenAiApiService api = Stub()
		api.createChatCompletion(*_) >> [success: true, data: [choices: [[finish_reason: 'tool_calls', message: [content: null, tool_calls: [
			[id: 't1', type: 'function', function: [name: 'list_servers', arguments: '{"name":"","phrase":"","zoneId":0,"managed":false,"status":"","max":100,"offset":0,"tags":[],"filters":{},"note":null,"powerState":"on"}']],
			[id: 't2', type: 'function', function: [name: 'use_servers_tools', arguments: '{"filter":"list_servers"}']]
		]]]]]]
		provider.apiService = api
		AccountIntegration ai = configured(config)
		ai.servicePassword = 'sk-test'

		when:
		ServiceResponse<LlmChatResponse> response = provider.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'gpt-5.4-mini', messages: [message('user', 'List all servers.')]), [:])
		List<Map> calls = response.data.metadata.tool_calls as List<Map>

		then:
		calls*.function*.arguments == expected
		response.data.message.metadata.tool_calls.is(calls)

		where:
		config                            || expected
		[:]                               || ['{"max":100,"powerState":"on"}', '{"filter":"list_servers"}']
		[dropEmptyToolArguments: 'on']    || ['{"max":100,"powerState":"on"}', '{"filter":"list_servers"}']
		[dropEmptyToolArguments: false]   || ['{"name":"","phrase":"","zoneId":0,"managed":false,"status":"","max":100,"offset":0,"tags":[],"filters":{},"note":null,"powerState":"on"}', '{"filter":"list_servers"}']
	}

	def "pruning leaves non-object arguments and nested values alone"() {
		expect:
		OpenAiProvider.pruneEmptyArguments(json) == [json: expected, dropped: dropped]

		where:
		json                                        || expected                            | dropped
		'{"a":{"b":""},"c":[0],"d":1}'              || '{"a":{"b":""},"c":[0],"d":1}'      | []
		'{"a":"","b":"x"}'                          || '{"b":"x"}'                         | ['a']
		'{"a":""}'                                  || '{}'                                | ['a']
		'[1,2]'                                     || '[1,2]'                             | []
		'not json'                                  || 'not json'                          | []
		''                                          || ''                                  | []
		null                                        || null                                | []
	}

	def "a tool-call answer maps onto the tool_calls metadata Morpheus acts on"() {
		given: 'what Gemini returned through OpenRouter: content null, and encrypted reasoning details'
		Map data = [
			id     : 'gen-1', model: 'google/gemini-3.5-flash-lite', provider: 'Google',
			choices: [[index: 0, finish_reason: 'tool_calls', message: [
				role             : 'assistant', content: null, reasoning: null,
				tool_calls       : [[type: 'function', index: 0, id: 'call_5032837', function: [name: 'get_instance_count', arguments: '{"cloud":"lab"}']]],
				reasoning_details: [[type: 'reasoning.encrypted', data: 'AY89', format: 'google-gemini-v1', id: 'call_5032837', index: 0]]
			]]],
			usage  : [prompt_tokens: 51, completion_tokens: 18, total_tokens: 69, cost: 0.0000603]
		]

		when:
		LlmChatResponse response = provider.parseChatResponse(data)

		then:
		response.finishReason == 'tool_calls'
		response.message.content == ''
		response.metadata.tool_calls == [[id: 'call_5032837', type: 'function', function: [name: 'get_instance_count', arguments: '{"cloud":"lab"}']]]
		response.message.metadata.tool_calls == response.metadata.tool_calls
		response.metadata.provider == 'Google'
	}

	def "tool calls end the turn even when the stop reason says otherwise"() {
		expect:
		provider.parseChatResponse([choices: [[finish_reason: 'stop', message: [content: '',
			tool_calls: [[id: 'c1', function: [name: 'list_clouds', arguments: '{}']]]]]]]).finishReason == 'tool_calls'
	}

	def "usage, cached tokens and cost are read, and reasoning stays out of the answer"() {
		when:
		LlmChatResponse response = provider.parseChatResponse([
			model  : 'deepseek/deepseek-v4-flash-0731',
			choices: [[finish_reason: 'stop', message: [role: 'assistant', content: 'Red, green, blue.', reasoning: 'Simple. Need three words.']]],
			usage  : [prompt_tokens: 91, completion_tokens: 42, total_tokens: 133, cost: 0.0000124124,
					  prompt_tokens_details: [cached_tokens: 64], completion_tokens_details: [reasoning_tokens: 30]]
		])

		then:
		response.message.content == 'Red, green, blue.'
		response.metadata.reasoning == 'Simple. Need three words.'
		response.tokenUsage.inputTokens == 91
		response.tokenUsage.outputTokens == 42
		response.tokenUsage.totalTokens == 133
		response.metadata.cached_tokens == 64
		response.metadata.reasoning_tokens == 30
		response.metadata.cost == 0.0000124124
		!response.metadata.containsKey('tool_calls')
	}

	// ------------------------------------------------------------------
	// Cost footer
	// ------------------------------------------------------------------

	def "the footer shows the cost of the whole question, summed over its requests"() {
		given: 'a question that took one tool round before the answer'
		AccountIntegration withFooter = configured([usageFooter: 'on'])
		Map firstRound = [model: 'openai/gpt-5.5', messages: [[role: 'user', content: 'Which servers run?']]]
		Map secondRound = [model: 'openai/gpt-5.5', messages: [
			[role: 'user', content: 'Which servers run?'],
			[role: 'assistant', content: '', tool_calls: [[id: 't1', type: 'function', function: [name: 'list_servers', arguments: '{}']]]],
			[role: 'tool', tool_call_id: 't1', content: '[]']
		]]

		when:
		LlmChatResponse toolTurn = provider.trackQuestionCost(firstRound, provider.parseChatResponse([
			choices: [[finish_reason: 'tool_calls', message: [content: null, tool_calls: [[id: 't1', function: [name: 'list_servers', arguments: '{}']]]]]],
			usage  : [prompt_tokens: 10, completion_tokens: 5, cost: 0.0170345]
		]))
		LlmChatResponse answer = provider.trackQuestionCost(secondRound, provider.parseChatResponse([
			choices: [[finish_reason: 'stop', message: [content: 'None.']]],
			usage  : [prompt_tokens: 20, completion_tokens: 3, cost: 0.0013922]
		]))
		provider.appendUsageFooter(toolTurn, withFooter)
		provider.appendUsageFooter(answer, withFooter)

		then:
		toolTurn.metadata.question_requests == 1
		toolTurn.message.content == ''
		answer.message.content == 'None.\n\n*Cost: $0.0184 (2 requests)*'
		OpenAiProvider.stripUsageFooter(answer.message.content) == 'None.'

		and: 'ASCII only - the answer is replayed as history'
		answer.message.content.every { it.toCharacter() < 128 as char }
	}

	def "the cost line follows the language of the answer"() {
		given:
		AccountIntegration withFooter = configured([usageFooter: 'on'])
		LlmChatResponse answer = provider.trackQuestionCost([model: 'm', messages: [[role: 'user', content: text]]], provider.parseChatResponse([
			choices: [[finish_reason: 'stop', message: [content: text]]], usage: [prompt_tokens: 5, completion_tokens: 2, cost: 0.0021]
		]))

		when:
		provider.appendUsageFooter(answer, withFooter)

		then:
		answer.message.content == "${text}\n\n*${label}: \$0.0021*"
		OpenAiProvider.stripUsageFooter(answer.message.content) == text

		where:
		text                                                             || label
		'Es gibt keine Instanzen, und alle 4 Server laufen.'             || 'Kosten'
		'There are no instances, and all 4 servers are running.'         || 'Cost'
		'Wszystkie 4 serwery działają, nie ma żadnych instancji.'        || 'Koszt'
	}

	def "request counts take the plural form of the answer's language"() {
		expect:
		OpenAiProvider.requestCount(count, language) == text

		where:
		count | language || text
		5     | 'en'     || '5 requests'
		5     | 'de'     || '5 Anfragen'
		2     | 'pl'     || '2 zapytania'
		5     | 'pl'     || '5 zapytań'
		22    | 'pl'     || '22 zapytania'
	}

	def "without a reported cost the footer shows tokens, cached ones included"() {
		given:
		AccountIntegration withFooter = configured([usageFooter: 'on'])
		LlmChatResponse answer = provider.parseChatResponse([
			choices: [[finish_reason: 'stop', message: [content: 'Hello.']]],
			usage  : [prompt_tokens: 1200, completion_tokens: 2, prompt_tokens_details: [cached_tokens: 800]]
		])

		when:
		provider.appendUsageFooter(provider.trackQuestionCost([messages: []], answer), withFooter)

		then:
		answer.message.content == 'Hello.\n\n*Tokens: 1,200 input (800 cached), 2 output*'
	}

	def "the footer stays off by default"() {
		given:
		LlmChatResponse answer = provider.parseChatResponse([
			choices: [[finish_reason: 'stop', message: [content: 'One cloud.']]], usage: [prompt_tokens: 5, completion_tokens: 2, cost: 0.001]
		])

		when:
		provider.appendUsageFooter(provider.trackQuestionCost([messages: [[role: 'user', content: 'Clouds?']]], answer), integration)

		then:
		answer.message.content == 'One cloud.'
	}

	// ------------------------------------------------------------------
	// Catalog
	// ------------------------------------------------------------------

	def "only chat models an agent can use are listed"() {
		given: 'an OpenRouter-style catalog, which says what each model supports'
		Map catalog = [data: [
			catalogEntry('openai/gpt-5.5'),
			catalogEntry('openai/gpt-5.5:batch'),
			catalogEntry('mistralai/no-tools', [supported_parameters: ['max_tokens', 'temperature']]),
			catalogEntry('google/image-maker', [architecture: [input_modalities: ['text'], output_modalities: ['image']]]),
			catalogEntry('google/gemini-3-pro-image-preview', [architecture: [input_modalities: ['text', 'image'], output_modalities: ['image', 'text']]]),
			catalogEntry('openai/gpt-audio', [architecture: [input_modalities: ['text', 'audio'], output_modalities: ['text', 'audio']]]),
			catalogEntry('anthropic/claude-sonnet-5'),
			catalogEntry('google/gemma-4-31b-it:free'),
			catalogEntry('z-ai/retired', [expiration_date: '2020-01-01'])
		]]

		when:
		List<LlmModel> models = provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: integration), catalog)

		then:
		models*.code == ['anthropic/claude-sonnet-5', 'google/gemma-4-31b-it:free', 'openai/gpt-5.5']
	}

	def "a bare catalog is reduced to chat models by their ids"() {
		given: 'what api.openai.com lists: ids only, every kind of model under one /models'
		Map catalog = [data: ['gpt-5.5', 'gpt-4.1-mini', 'o3', 'codex-mini-latest', 'chatgpt-4o-latest', 'gpt-5-search-api',
							  'text-embedding-3-small', 'whisper-1', 'tts-1', 'gpt-4o-mini-tts', 'dall-e-3', 'gpt-image-1',
							  'omni-moderation-latest', 'gpt-4o-realtime-preview', 'gpt-4o-audio-preview', 'gpt-4o-transcribe',
							  'babbage-002', 'davinci-002', 'gpt-3.5-turbo-instruct', 'computer-use-preview', 'sora-2',
							  'gpt-4o-search-preview'].collect { [id: it, object: 'model', owned_by: 'openai'] }]

		when:
		List<LlmModel> models = provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: integration), catalog)

		then: 'named by id, since there is nothing else, and sorted'
		models*.code == ['chatgpt-4o-latest', 'codex-mini-latest', 'gpt-4.1-mini', 'gpt-5.5', 'o3']
		models*.name == models*.code
		models.find { it.code == 'o3' }.metadata.supportsReasoning
		!models.find { it.code == 'gpt-4.1-mini' }.metadata.supportsReasoning
		models.every { it.contextWindow == null && it.maxOutputTokens == null && it.modelType == 'chat' && it.enabled }
	}

	def "a model is described by its catalog entry"() {
		given:
		LlmIntegration llmIntegration = new LlmIntegration(accountIntegration: integration)

		when:
		LlmModel model = provider.buildModelsFromApiResponse(llmIntegration, [data: [catalogEntry('openai/gpt-5.5', [name: 'OpenAI: GPT-5.5'])]])[0]

		then:
		model.code == 'openai/gpt-5.5'
		model.externalId == 'openai/gpt-5.5'
		model.name == 'OpenAI: GPT-5.5'
		model.providerCode == 'openai-llm'
		model.modelType == 'chat'
		model.contextWindow == 128000L
		model.maxOutputTokens == 16384L
		model.enabled
		model.llmIntegration.is(llmIntegration)
		model.metadata.supportsToolUse
		model.metadata.supportsReasoning
		model.metadata.supportsVision
	}

	def "an allow list keeps only matching models, case-insensitively and with wildcards"() {
		given:
		AccountIntegration narrowed = configured([modelAllowList: 'openai/gpt-5*,  GOOGLE/gemini-3* deepseek/deepseek-v4-flash-0731'])
		Map catalog = [data: [
			catalogEntry('openai/gpt-5.5'),
			catalogEntry('openai/gpt-4.1'),
			catalogEntry('google/gemini-3.5-flash-lite'),
			catalogEntry('google/gemini-2.5-pro'),
			catalogEntry('deepseek/deepseek-v4-flash-0731'),
			catalogEntry('deepseek/deepseek-v4-flash-0731-extra'),
			catalogEntry('mistralai/mistral-large')
		]]

		when:
		List<LlmModel> models = provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: narrowed), catalog)

		then: 'a pattern without * matches one id exactly'
		models*.code as Set == ['openai/gpt-5.5', 'google/gemini-3.5-flash-lite', 'deepseek/deepseek-v4-flash-0731'] as Set
	}

	def "an allow list never brings back a model the other rules leave out"() {
		given:
		AccountIntegration narrowed = configured([modelAllowList: 'text-*, openai/*'])
		Map catalog = [data: [[id: 'text-embedding-3-small'], catalogEntry('openai/gpt-5.5:batch'), catalogEntry('openai/gpt-5.5')]]

		expect:
		provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: narrowed), catalog)*.code == ['openai/gpt-5.5']
	}

	def "an empty allow list lists everything"() {
		expect:
		OpenAiProvider.parseModelAllowList(value).isEmpty()

		where: '"" is what the edit form wrote back after the value was emptied through the REST API'
		value << [null, '', '  ', ' , ', '""', "''", '"", ""']
	}

	def "quotes around an allow list entry are ignored"() {
		given:
		AccountIntegration quoted = configured([modelAllowList: '"openai/gpt-5*"'])

		expect:
		provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: quoted),
			[data: [catalogEntry('openai/gpt-5.5'), catalogEntry('mistralai/mistral-large')]])*.code == ['openai/gpt-5.5']
	}

	def "an allow list that matches no model is ignored instead of emptying the list"() {
		given: 'a typo in the vendor'
		AccountIntegration typo = configured([modelAllowList: 'opnai/gpt-5*'])

		when:
		List<LlmModel> models = provider.buildModelsFromApiResponse(new LlmIntegration(id: 4L, accountIntegration: typo),
			[data: [catalogEntry('openai/gpt-5.5'), catalogEntry('mistralai/mistral-large')]])

		then:
		models*.code as Set == ['openai/gpt-5.5', 'mistralai/mistral-large'] as Set
	}

	def "the allow list has a label and help text"() {
		when:
		OptionType option = provider.optionTypes.find { it.fieldName == 'modelAllowList' }

		then: 'it starts at *, since Morpheus 9.0.1 does not save the field once it is emptied'
		option.inputType == OptionType.InputType.TEXT
		option.fieldContext == 'config'
		option.defaultValue == '*'
		!option.required

		and: '* on its own lets every model through'
		OpenAiProvider.isListedModel(catalogEntry('mistralai/mistral-large'), LocalDate.of(2026, 9, 15),
			OpenAiProvider.parseModelAllowList('*'))
		provider.optionTypes*.displayOrder == (0..10).toList()
	}

	def "an expiry within a year goes into the name, a placeholder date does not"() {
		given:
		LocalDate today = LocalDate.of(2026, 9, 14)

		expect:
		OpenAiProvider.displayName(catalogEntry('dots-studio/dots-3-note-preview:free', [name: 'Dots 3', expiration_date: expires]), today) == name
		OpenAiProvider.isListedModel(catalogEntry('dots-studio/dots-3-note-preview', [expiration_date: expires]), today) == listed

		where:
		expires      || name                         | listed
		'2026-09-30' || 'Dots 3 (expires 2026-09-30)' | true
		'2026-09-14' || 'Dots 3 (expires 2026-09-14)' | true
		'2098-12-31' || 'Dots 3'                     | true
		'2026-09-13' || 'Dots 3 (expires 2026-09-13)' | false
		null         || 'Dots 3'                     | true
	}

	// ------------------------------------------------------------------
	// Network proxy
	// ------------------------------------------------------------------

	def "a selected proxy is only used while the checkbox is ticked"() {
		given: 'the dropdown offers no empty entry on 9.0.1, so the tick is the only way back'
		NetworkProxy proxy = new NetworkProxy(name: 'egress', proxyHost: 'proxy.example.com')
		OpenAiProvider withContext = providerWithProxyLookup([7L: proxy])

		expect:
		!withContext.buildClientOpts(configured([networkProxy: '7']), [:]).containsKey(OpenAiApiService.NETWORK_PROXY_KEY)
		withContext.buildClientOpts(configured([useNetworkProxy: 'on', networkProxy: '7']), [clientScopeKey: 'chat-1'])
			.get(OpenAiApiService.NETWORK_PROXY_KEY).is(proxy)
	}

	def "a proxy that has been deleted falls back to a direct connection"() {
		given:
		OpenAiProvider withContext = providerWithProxyLookup([:])

		expect:
		!withContext.buildClientOpts(configured([useNetworkProxy: 'on', networkProxy: '7']), [:]).containsKey(OpenAiApiService.NETWORK_PROXY_KEY)
	}

	def "the proxy is offered as a select backed by the proxy list"() {
		when:
		OptionType proxyOption = provider.optionTypes.find { it.fieldName == 'networkProxy' }

		then:
		proxyOption.inputType == OptionType.InputType.SELECT
		proxyOption.optionSource == 'networkProxies'
		proxyOption.fieldContext == 'config'
		!proxyOption.required
	}

	/**
	 * A provider whose appliance lookup answers from a map. Only the lookup is
	 * replaced, so the checkbox gate and id handling around it are the real ones.
	 */
	private static OpenAiProvider providerWithProxyLookup(Map<Long, NetworkProxy> proxies) {
		return new OpenAiProvider(null, null) {
			@Override
			protected NetworkProxy loadNetworkProxy(Long proxyId) {
				return proxies[proxyId]
			}
		}
	}
}
