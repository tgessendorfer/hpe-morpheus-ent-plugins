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
		'Can I see my VMs?'              || 'en'
		'Ile serwerów i instancji?'      || 'pl'
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

	def "the integration's max_tokens is a floor under the request's, and without one the request's goes out"() {
		given: 'Morpheus asks for 1000 on every chat request'
		LlmChatRequest request = new LlmChatRequest(model: 'm', maxOutputTokens: requested, messages: [message('user', 'hi')])

		expect:
		provider.buildChatRequestBody(request, configured([maxOutputTokens: configuredValue]), false).max_tokens == expected

		where:
		requested | configuredValue || expected
		1000      | '4096'          || 4096
		8192      | '4096'          || 8192
		0         | '4096'          || 4096
		null      | '4096'          || 4096
		1000      | null            || 1000
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
		'gpt-6.1-sol'          || true
		'openai/gpt-6-astra'   || true
		'GPT-6-luna'           || true
		'gpt-6.1-terra'        || true
		'gpt-6-sol'            || true
		'gpt-4.1'              || false
		'gpt-oss-120b'         || false
		'deepseek-v3.2'        || false
		'google/gemini-3-pro'  || false
		'claude-sonnet-5'      || false
	}

	def "a GPT-6 model with a configured effort gets reasoning_effort and still no sampling parameters"() {
		given: 'OpenAI wants temperature and top_p removed unless the effort is none, which Reasoning Effort does not offer'
		LlmChatRequest request = new LlmChatRequest(model: model, messages: [message('user', 'hi')], temperature: 0.7d, topP: 0.9d, maxOutputTokens: 1000)

		when:
		Map body = provider.buildChatRequestBody(request, configured([reasoningEffort: 'low']), false)

		then:
		body.reasoning_effort == 'low'
		!body.containsKey('temperature')
		!body.containsKey('top_p')
		!body.containsKey('max_tokens')
		body.max_completion_tokens == 8192

		where:
		model << ['gpt-6-astra', 'gpt-6.1-sol', 'gpt-6.1-terra', 'gpt-6-sol', 'gpt-6-luna']
	}

	def "a reasoning model keeps the integration's own output limit, and a caller's limit above the floor"() {
		expect:
		provider.buildChatRequestBody(new LlmChatRequest(model: 'gpt-5.5', messages: [message('user', 'hi')], maxOutputTokens: caller),
			configured(config), false).max_completion_tokens == expected

		where:
		caller | config                     || expected
		1000   | [:]                        || 8192
		1000   | [maxOutputTokens: '2000']  || 2000
		5000   | [maxOutputTokens: '2000']  || 5000
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

		then: 'named by id, since there is nothing else, and sorted; codex-mini-latest works only through the Responses API'
		models*.code == ['chatgpt-4o-latest', 'gpt-4.1-mini', 'gpt-5.5', 'o3']
		models*.name == models*.code
		models.find { it.code == 'o3' }.metadata.supportsReasoning
		!models.find { it.code == 'gpt-4.1-mini' }.metadata.supportsReasoning
		models.every { it.contextWindow == null && it.maxOutputTokens == null && it.modelType == 'chat' && it.enabled }
	}

	def "api.openai.com does not list the models it serves only through its Responses API"() {
		given: 'pro, codex, deep research and cyber models with their dated snapshots, among chat models that share a prefix'
		List<String> responsesOnly = ['o1-pro', 'o1-pro-2025-03-19', 'o3-pro', 'o3-pro-2025-06-10', 'gpt-5-pro', 'gpt-5-pro-2025-10-06',
									  'gpt-5.2-pro', 'gpt-5.4-pro-2026-03-05', 'gpt-5.5-pro', 'gpt-5.5-pro-2026-04-23',
									  'codex-mini-latest', 'gpt-5-codex', 'gpt-5.1-codex', 'gpt-5.1-codex-mini', 'gpt-5.1-codex-max',
									  'gpt-5.2-codex', 'gpt-5.3-codex', 'o3-deep-research', 'o4-mini-deep-research-2025-06-26',
									  'gpt-5.5-cyber', 'gpt-5.6-cyber', 'gpt-daybreak-red-latest', 'gpt-daybreak-blue-latest']
		List<String> chat = ['gpt-6-astra', 'gpt-6.1-sol', 'gpt-6.1-terra', 'gpt-6-luna', 'gpt-5.6-sol', 'gpt-5.5', 'gpt-5.5-2026-04-23',
							 'gpt-5.4', 'gpt-5.4-mini', 'gpt-5.2', 'o3', 'o4-mini', 'chat-latest', 'chatgpt-4o-latest', 'gpt-5-chat-latest']
		Map catalog = [data: (responsesOnly + chat).collect { [id: it, object: 'model', owned_by: 'openai'] }]

		expect:
		provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: new AccountIntegration(serviceUrl: url)), catalog)*.code as Set ==
			(openAi ? chat : responsesOnly + chat) as Set

		where: 'only OpenAI itself; a router or proxy in front of these models may serve them over chat completions'
		url                                          || openAi
		'https://api.openai.com/v1'                  || true
		'https://API.OpenAI.com/v1/'                 || true
		'https://eu.api.openai.com/v1'               || true
		null                                         || true
		'http://litellm.example.com:4000/v1'         || false
		'https://openrouter.ai/api/v1'               || false
		'https://example.openai.azure.com/openai/v1' || false
		'https://api.openai.com.example.com/v1'      || false
		'http://192.0.2.10:11434/v1'                 || false
	}

	def "the Responses-only rule matches whole id parts, not any id that contains pro or codex"() {
		expect:
		OpenAiProvider.isResponsesOnlyModel(id) == responsesOnly

		where:
		id                         || responsesOnly
		'o3-pro'                   || true
		'gpt-5.5-pro-2026-04-23'   || true
		'gpt-5.1-codex-max'        || true
		'codex-mini-latest'        || true
		'o4-mini-deep-research'    || true
		'gpt-5.6-cyber'            || true
		'gpt-daybreak-blue-latest' || true
		'gpt-5.5'                  || false
		'gpt-6.1-sol'              || false
		'chatgpt-4o-latest'        || false
		'gpt-4o-mini'              || false
		'o3'                       || false
		'gpt-5-professional'       || false
		'my-codexer'               || false
		null                       || false
	}

	def "a ! entry removes the models it matches after the plain entries have been applied"() {
		given:
		Map catalog = [data: ['gpt-5.5', 'gpt-5.4-mini', 'gpt-4.1', 'gpt-4.1-mini', 'o3', 'o4-mini'].collect { [id: it, object: 'model'] }]
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'http://litellm.example.com:4000/v1')
		ai.setConfigMap([modelAllowList: configured])

		expect:
		provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: ai), catalog)*.code as Set == expected as Set

		where:
		configured                    || expected
		'*, !*-mini'                  || ['gpt-5.5', 'gpt-4.1', 'o3']
		'gpt-*, !gpt-4*'              || ['gpt-5.5', 'gpt-5.4-mini']
		'!o*'                         || ['gpt-5.5', 'gpt-5.4-mini', 'gpt-4.1', 'gpt-4.1-mini']
		'* !O3 !gpt-4.1'              || ['gpt-5.5', 'gpt-5.4-mini', 'gpt-4.1-mini', 'o4-mini']
		'*, ! *-mini'                 || ['gpt-5.5', 'gpt-4.1', 'o3']
		'"*", "!*-mini"'              || ['gpt-5.5', 'gpt-4.1', 'o3']
		'*, !"*-mini"'                || ['gpt-5.5', 'gpt-4.1', 'o3']
		'gpt-5*, !*-pro*, !*codex*'   || ['gpt-5.5', 'gpt-5.4-mini']
		'*, !'                        || ['gpt-5.5', 'gpt-5.4-mini', 'gpt-4.1', 'gpt-4.1-mini', 'o3', 'o4-mini']
	}

	def "*, !*-pro*, !*codex* keeps pro and codex models out on any endpoint"() {
		given: 'a LiteLLM catalog, where the Responses-only rule of api.openai.com does not apply'
		Map catalog = [data: ['gpt-5.5', 'gpt-5.5-pro', 'gpt-5.3-codex', 'codex-mini-latest', 'openai/gpt-5-codex', 'gpt-5.4-mini']
			.collect { [id: it, object: 'model'] }]
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'http://litellm.example.com:4000/v1')
		ai.setConfigMap([modelAllowList: '*, !*-pro*, !*codex*'])

		expect:
		provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: ai), catalog)*.code == ['gpt-5.4-mini', 'gpt-5.5']
	}

	def "GPT-6 models stay listed on api.openai.com as reasoning models, and *, !gpt-6* hides them"() {
		given: 'they answer over chat completions but call tools only through the Responses API, so they are no Responses-only models'
		Map catalog = [data: ['gpt-6-astra', 'gpt-6.1-sol', 'gpt-6.1-terra', 'gpt-6-sol', 'gpt-6-luna', 'gpt-5.5', 'gpt-4.1']
			.collect { [id: it, object: 'model', owned_by: 'openai'] }]

		when:
		List<LlmModel> listed = provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: integration), catalog)
		List<LlmModel> hidden = provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: configured([modelAllowList: '*, !gpt-6*'])), catalog)

		then:
		listed*.code as Set == ['gpt-6-astra', 'gpt-6.1-sol', 'gpt-6.1-terra', 'gpt-6-sol', 'gpt-6-luna', 'gpt-5.5', 'gpt-4.1'] as Set
		listed.findAll { it.code.startsWith('gpt-6') }.every { it.metadata.supportsReasoning }
		hidden*.code as Set == ['gpt-5.5', 'gpt-4.1'] as Set
	}

	def "a field without ! lists exactly what it listed before"() {
		expect:
		OpenAiProvider.parseModelExcludeList(value).isEmpty()
		OpenAiProvider.parseModelAllowList(value)*.pattern() == includes

		where:
		value                                 || includes
		null                                  || []
		''                                    || []
		'*'                                   || ['\\Q\\E.*\\Q\\E']
		'gpt-5*, gpt-4.1-mini'                || ['\\Qgpt-5\\E.*\\Q\\E', '\\Qgpt-4.1-mini\\E']
		'"openai/gpt-5*"'                     || ['\\Qopenai/gpt-5\\E.*\\Q\\E']
	}

	def "! entries that would remove every model are ignored instead of emptying the list"() {
		given:
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'http://litellm.example.com:4000/v1')
		ai.setConfigMap([modelAllowList: configured])

		when:
		List<LlmModel> models = provider.buildModelsFromApiResponse(new LlmIntegration(id: 1L, accountIntegration: ai),
			[data: ['gpt-5.5', 'gpt-5.4-mini', 'o3'].collect { [id: it, object: 'model'] }])

		then:
		models*.code as Set == expected as Set

		where: 'a typo in the plain entries is ignored as before, and the ! entries still apply'
		configured        || expected
		'!*'              || ['gpt-5.5', 'gpt-5.4-mini', 'o3']
		'gpt-5*, !gpt-*'  || ['gpt-5.5', 'gpt-5.4-mini']
		'gtp-5*, !o*'     || ['gpt-5.5', 'gpt-5.4-mini']
	}

	def "an allow list of Responses-only models lists every chat model on api.openai.com, and only them elsewhere"() {
		given: 'entries that name only models api.openai.com serves through its Responses API'
		Map catalog = [data: ['gpt-5.5', 'gpt-5.4-mini', 'o3', 'o3-pro', 'gpt-5.5-pro', 'gpt-5.5-pro-2026-04-23']
			.collect { [id: it, object: 'model', owned_by: 'openai'] }]
		AccountIntegration ai = new AccountIntegration(serviceUrl: url)
		ai.setConfigMap([modelAllowList: configured])

		expect: 'on api.openai.com they match nothing that is left, so the allow list is ignored rather than emptying the integration'
		provider.buildModelsFromApiResponse(new LlmIntegration(id: 1L, accountIntegration: ai), catalog)*.code as Set == expected as Set

		where:
		url                                  | configured             || expected
		'https://api.openai.com/v1'          | 'o3-pro'               || ['gpt-5.5', 'gpt-5.4-mini', 'o3']
		'https://api.openai.com/v1'          | 'gpt-5.5-pro*'         || ['gpt-5.5', 'gpt-5.4-mini', 'o3']
		'https://api.openai.com/v1'          | 'o3-pro, gpt-5.5'      || ['gpt-5.5']
		'https://api.openai.com/v1'          | 'o3-pro, !gpt-5*'      || ['o3']
		'http://litellm.example.com:4000/v1' | 'o3-pro'               || ['o3-pro']
		'http://litellm.example.com:4000/v1' | 'gpt-5.5-pro*'         || ['gpt-5.5-pro', 'gpt-5.5-pro-2026-04-23']
	}

	def "open-weight *-Instruct chat models stay listed; only OpenAI's legacy instruct model is dropped"() {
		given: 'what vLLM, Ollama and LiteLLM list (ids only) and an OpenRouter entry with metadata'
		Map catalog = [data: ['meta-llama/Llama-3.3-70B-Instruct', 'llama3.1:8b-instruct-q4_K_M', 'Qwen/Qwen2.5-72B-Instruct',
							  'gpt-3.5-turbo-instruct', 'gpt-3.5-turbo-instruct-0914', 'openai/gpt-3.5-turbo-instruct']
			.collect { [id: it, object: 'model'] } + [catalogEntry('mistralai/mistral-small-3.2-24b-instruct')]]

		when:
		List<LlmModel> models = provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: integration), catalog)

		then:
		models*.code as Set == ['meta-llama/Llama-3.3-70B-Instruct', 'llama3.1:8b-instruct-q4_K_M', 'Qwen/Qwen2.5-72B-Instruct',
								'mistralai/mistral-small-3.2-24b-instruct'] as Set
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

	def "a request whose question the chat memory dropped gets it back after the system messages"() {
		given: 'the model answered the question with a tool call'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		Map firstBody = provider.buildChatRequestBody(new LlmChatRequest(messages: [message('system', 'You are Murph.'), message('user', 'List the AWS servers')]), integration, false)
		LlmChatResponse first = new LlmChatResponse()
		first.metadata = [tool_calls: [[id: 'call_AbCdEfGhIjKlMnOp', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]
		OpenAiProvider.rememberQuestion(firstBody, first)

		and: 'a later request where Morpheus evicted the question, only the tool loop is left'
		LlmChatRequest evicted = new LlmChatRequest(messages: [
			message('system', 'You are Murph.'),
			message('assistant', '', [tool_calls: [[id: 'call_AbCdEfGhIjKlMnOp', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]),
			message('tool', '{"artifactId":"a1","truncated":true}', [tool_call_id: 'call_AbCdEfGhIjKlMnOp'])
		])

		when:
		Map body = provider.buildChatRequestBody(evicted, integration, false)

		then:
		body.messages*.role == ['system', 'user', 'assistant', 'tool']
		body.messages[1].content == OpenAiProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'
		OpenAiProvider.latestQuestion(body.messages) == 'List the AWS servers'

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a request that still has its question, or whose tool calls are unknown or too old, is left alone"() {
		given:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		long old = System.currentTimeMillis() - OpenAiProvider.QUESTION_BY_TOOL_CALL_TTL_MS - 1000L
		OpenAiProvider.QUESTION_BY_TOOL_CALL.put(OpenAiProvider.toolCallKey(OpenAiProvider.questionScope(null, []), 'call_OldAbCdEfGhIjKl'),
			[question: 'Stale question', updated: old])
		List<Map> withQuestion = [[role: 'user', content: 'How many VMs?'], [role: 'assistant', content: '', tool_calls: [[id: 'call_OldAbCdEfGhIjKl']]]]
		List<Map> unknown = [[role: 'assistant', content: '', tool_calls: [[id: 'call_UnknownAbCdEfGh']]]]
		List<Map> expired = [[role: 'tool', tool_call_id: 'call_OldAbCdEfGhIjKl', content: 'x']]

		expect:
		!OpenAiProvider.restoreLostQuestion(withQuestion)
		withQuestion.size() == 2
		!OpenAiProvider.restoreLostQuestion(unknown)
		unknown.size() == 1
		!OpenAiProvider.restoreLostQuestion(expired)
		expired.size() == 1

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	private static LlmChatResponse response(List calls) {
		LlmChatResponse r = new LlmChatResponse()
		r.metadata = [tool_calls: calls]
		return r
	}

	def "numbered tool call ids are never trusted, so another conversation's question cannot come back"() {
		given:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'call_0'
		Map firstBody = provider.buildChatRequestBody(new LlmChatRequest(messages: [message('user', 'List the AWS servers')]), integration, false)
		OpenAiProvider.rememberQuestion(firstBody, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))

		expect:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.isEmpty()
		!OpenAiProvider.isDistinctToolCallId('functions.list_servers:0')
		!OpenAiProvider.isDistinctToolCallId('call_AbCdEfGhIjKlMn_12')
		OpenAiProvider.isDistinctToolCallId('call_AbCdEfGhIjKlMnOp')

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a restored question keeps counting on its original cost key and carries on to the next tool calls"() {
		given:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'call_AbCdEfGhIjKlMnOp'
		String ID2 = 'call_ZyXwVuTsRqPoNmLk'
		Map firstBody = provider.buildChatRequestBody(new LlmChatRequest(messages: [message('system', 'You are Murph.'), message('user', 'List the AWS servers')]), integration, false)
		String originalKey = OpenAiProvider.questionKey(firstBody)
		OpenAiProvider.rememberQuestion(firstBody, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		List<Map> msgs = [[role: 'system', content: 'You are Murph.'], [role: 'assistant', content: '', tool_calls: [[id: ID]]], [role: 'tool', tool_call_id: ID, content: 'part']]

		when:
		boolean restored = OpenAiProvider.restoreLostQuestion(msgs)
		OpenAiProvider.rememberQuestion([messages: msgs], response([[id: ID2, type: 'function', function: [name: 'get_result_excerpt', arguments: '{}']]]))

		then:
		restored
		OpenAiProvider.questionKey([model: firstBody.model, messages: msgs]) == originalKey
		Map entry = OpenAiProvider.QUESTION_BY_TOOL_CALL.get(OpenAiProvider.toolCallKey(OpenAiProvider.questionScope(null, msgs), ID2))
		entry.question == 'List the AWS servers'
		entry.costKey == originalKey

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a full map drops only its oldest entries"() {
		given:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		long now = System.currentTimeMillis()
		(OpenAiProvider.QUESTION_BY_TOOL_CALL_MAX).times { int i ->
			OpenAiProvider.QUESTION_BY_TOOL_CALL.put('call_old' + String.format('%012d', i) + 'x', [question: 'old', costKey: 'k', updated: now - 1000L + (i % 500)])
		}
		String ID = 'call_NewAbCdEfGhIjKlMn'
		Map firstBody = provider.buildChatRequestBody(new LlmChatRequest(messages: [message('user', 'List the AWS servers')]), integration, false)

		when:
		OpenAiProvider.rememberQuestion(firstBody, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))

		then:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.size() == OpenAiProvider.QUESTION_BY_TOOL_CALL_MAX
		OpenAiProvider.QUESTION_BY_TOOL_CALL.get(OpenAiProvider.toolCallKey(OpenAiProvider.questionScope(null, firstBody.messages as List), ID)).question == 'List the AWS servers'

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	/** A request of the agent 'You are Murph.' whose question the chat memory dropped; only its tool loop is left. */
	private static List<Map> evictedLoop(String id, String result) {
		return [[role: 'system', content: 'You are Murph.'], [role: 'assistant', content: '', tool_calls: [[id: id]]],
				[role: 'tool', tool_call_id: id, content: result]]
	}

	/** Remembers the question asked of the agent 'You are Murph.' for these tool call ids. */
	private static void ask(String question, List<String> ids) {
		OpenAiProvider.rememberQuestion([model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: question]]],
			response(ids.collect { String id -> [id: id, type: 'function', function: [name: 'list_servers', arguments: '{}']] }))
	}

	/** The remembered entry of a tool call id of the agent 'You are Murph.'. */
	private static Map remembered(String id) {
		return OpenAiProvider.QUESTION_BY_TOOL_CALL.get(OpenAiProvider.toolCallKey(OpenAiProvider.questionScope(null, evictedLoop(id, 'x')), id))
	}

	def "two tool loops that give the same tool call id different questions at the same time restore neither"() {
		given: 'a backend that names its tool calls in a fixed pattern, and two users of one agent asking at once'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		Map first = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'List the AWS servers']]]
		Map second = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'Which instances are stopped?']]]
		OpenAiProvider.rememberQuestion(first, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		OpenAiProvider.rememberQuestion(second, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		List<Map> firstLoop = evictedLoop(ID, '{"total":2}')
		List<Map> secondLoop = evictedLoop(ID, '{"total":5}')

		expect: 'the id is a marker: the time of the clash and two hashes, no question and no cost key'
		OpenAiProvider.isAmbiguous(remembered(ID))
		remembered(ID).keySet() == ['ambiguous', 'clashed', 'updated'] as Set
		remembered(ID).clashed.size() == 2

		and:
		!OpenAiProvider.restoreLostQuestion(firstLoop)
		firstLoop*.role == ['system', 'assistant', 'tool']
		!OpenAiProvider.restoreLostQuestion(secondLoop)
		secondLoop*.role == ['system', 'assistant', 'tool']

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "the same question for the same tool call id only refreshes the entry, and the restore still works"() {
		given:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		Map first = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'List the AWS servers']]]
		Map again = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'List the AWS servers'],
											[role: 'assistant', content: 'Two servers.'], [role: 'user', content: 'List the AWS servers']]]
		OpenAiProvider.rememberQuestion(first, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		OpenAiProvider.rememberQuestion(again, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		List<Map> loop = evictedLoop(ID, '{"total":2}')

		when:
		boolean restored = OpenAiProvider.restoreLostQuestion(loop)

		then: 'the entry keeps the cost key of the request that stored the question first'
		!OpenAiProvider.QUESTION_BY_TOOL_CALL.values().any { Map entry -> entry.ambiguous != null }
		OpenAiProvider.questionKey(again) != OpenAiProvider.questionKey(first)
		remembered(ID).costKey == OpenAiProvider.questionKey(first)
		restored
		loop[1].content == OpenAiProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a different question after the clash window replaces the entry, and the restore brings back the new one"() {
		given: 'the id held an older question, or a mark, and nothing was stored on it for longer than the window'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		long before = System.currentTimeMillis() - OpenAiProvider.QUESTION_CLASH_WINDOW_MS - 1000L
		OpenAiProvider.QUESTION_BY_TOOL_CALL.put(OpenAiProvider.toolCallKey(OpenAiProvider.questionScope(null, evictedLoop(ID, 'x')), ID),
			held + [updated: before])
		Map later = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'Which instances are stopped?']]]
		OpenAiProvider.rememberQuestion(later, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		List<Map> loop = evictedLoop(ID, '{"total":5}')

		when:
		boolean restored = OpenAiProvider.restoreLostQuestion(loop)

		then:
		remembered(ID).ambiguous == null
		restored
		loop[1].content == OpenAiProvider.RESTORED_QUESTION_NOTE + 'Which instances are stopped?'

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()

		where:
		held << [[question: 'List the AWS servers', costKey: 'k'],
			[ambiguous: System.currentTimeMillis() - OpenAiProvider.QUESTION_CLASH_WINDOW_MS - 1000L, clashed: ['a', 'b'] as Set]]
	}

	def "after the mark ends, a tool call id the loops still store on hands neither loop the other one's question"() {
		given: 'loops A and B clashed on X more than 10 minutes ago and both kept storing their questions on it'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String X = 'tool_0_list_servers'
		ask('List the AWS servers', [X])
		ask('Which instances are stopped?', [X])
		remembered(X).ambiguous = System.currentTimeMillis() - OpenAiProvider.QUESTION_CLASH_WINDOW_MS - 1000L
		remembered(X).updated = System.currentTimeMillis() - 60000L

		when: 'A stores its question on X again; B lost its question and has only X left'
		ask('List the AWS servers', [X])
		List<Map> lostB = evictedLoop(X, '{"total":5}')

		then: "B does not get A's question"
		remembered(X).question == null
		!OpenAiProvider.restoreLostQuestion(lostB)
		lostB*.role == ['system', 'assistant', 'tool']

		when: 'a question that was not part of the clash is stored on X within the window'
		ask('Which clouds are offline?', [X])

		then: 'it is a new clash'
		OpenAiProvider.isAmbiguous(remembered(X))
		remembered(X).clashed.size() == 3

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a loop that lost its question marks its tool calls even when nothing was known of it, so another loop's question cannot come back"() {
		given: 'the plugin knows nothing of loop B (it was reloaded), and B lost its question'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String Y = 'tool_0_list_servers'
		List<Map> lostB = evictedLoop('tool_7_list_clouds', '{"total":1}')

		when: 'B calls Y, and loop A, with its own question, gets the same id a moment later'
		boolean restoredBefore = OpenAiProvider.restoreLostQuestion(lostB)
		OpenAiProvider.rememberQuestion([model: 'm', messages: lostB], response([[id: Y, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		Map marked = new LinkedHashMap(remembered(Y))
		ask('List the AWS servers', [Y])
		List<Map> nextB = evictedLoop(Y, '{"total":5}')

		then: 'B left a marker without question on Y'
		!restoredBefore
		marked.question == null
		OpenAiProvider.isAmbiguous(marked)

		and: "A's question on Y is a clash, and B gets nothing back"
		remembered(Y).question == null
		OpenAiProvider.isAmbiguous(remembered(Y))
		!OpenAiProvider.restoreLostQuestion(nextB)
		nextB*.role == ['system', 'assistant', 'tool']

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a restored request counts on the cost key of its tool calls only while they name one question"() {
		given: 'a restored request whose tool calls Y and Z are known'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String Y = 'tool_0_list_servers'
		String Z = 'tool_1_list_instances'
		long now = System.currentTimeMillis()
		List<Map> restored = [[role: 'system', content: 'You are Murph.'],
							  [role: 'user', content: OpenAiProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'],
							  [role: 'assistant', content: '', tool_calls: [[id: Y]]], [role: 'tool', tool_call_id: Y, content: '{"total":2}'],
							  [role: 'assistant', content: '', tool_calls: [[id: Z]]], [role: 'tool', tool_call_id: Z, content: '{"total":3}']]
		String scope = OpenAiProvider.questionScope(null, restored)
		OpenAiProvider.QUESTION_BY_TOOL_CALL.put(OpenAiProvider.toolCallKey(scope, Y), [question: 'List the AWS servers', costKey: 'kA', updated: now])
		OpenAiProvider.QUESTION_BY_TOOL_CALL.put(OpenAiProvider.toolCallKey(scope, Z), z + [updated: now])

		expect: 'with Z marked or holding another question, neither cost key is used'
		!(OpenAiProvider.questionKey([model: 'm', messages: restored]) in ['kA', 'kB'])

		when: 'Z is no longer known'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.remove(OpenAiProvider.toolCallKey(scope, Z))

		then: "Y's cost key is used"
		OpenAiProvider.questionKey([model: 'm', messages: restored]) == 'kA'

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()

		where:
		z << [[ambiguous: System.currentTimeMillis(), clashed: ['a', 'b'] as Set], [question: 'Which instances are stopped?', costKey: 'kB']]
	}

	def "the loops that clashed storing their own questions again do not renew the mark, which ends 10 minutes after the last clash"() {
		given: 'a clash on the shared id nine minutes ago; the first loop also called a tool of its own'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		String OWN = 'tool_1_get_result_excerpt'
		ask('List the AWS servers', [ID])
		ask('Which instances are stopped?', [ID])
		long clash = System.currentTimeMillis() - OpenAiProvider.QUESTION_CLASH_WINDOW_MS + 60000L
		remembered(ID).ambiguous = clash
		remembered(ID).updated = clash
		Closure<List<Map>> lostLoop = {
			[[role: 'system', content: 'You are Murph.'],
			 [role: 'assistant', content: '', tool_calls: [[id: OWN]]], [role: 'tool', tool_call_id: OWN, content: '{"total":2}'],
			 [role: 'assistant', content: '', tool_calls: [[id: ID]]], [role: 'tool', tool_call_id: ID, content: '{"total":2}']]
		}

		when: 'both loops call the tool again within the window, each with its own question'
		ask('Which instances are stopped?', [ID])
		ask('List the AWS servers', [ID])
		ask('List the AWS servers', [OWN])

		then: 'the entry is refreshed, but the mark keeps the time of the clash and still holds'
		(remembered(ID).updated as long) > clash
		remembered(ID).ambiguous == clash
		remembered(ID).clashed.size() == 2
		!OpenAiProvider.restoreLostQuestion(lostLoop())

		when: 'the window passes since the clash'
		remembered(ID).ambiguous = System.currentTimeMillis() - OpenAiProvider.QUESTION_CLASH_WINDOW_MS - 1000L
		List<Map> lost = lostLoop()

		then: 'the mark is over: the loop gets its question back through its own id'
		!OpenAiProvider.isAmbiguous(remembered(ID))
		OpenAiProvider.restoreLostQuestion(lost)
		lost[1].content == OpenAiProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		when: 'one loop stores its question on the shared id again, while the id is still stored on within the window'
		ask('List the AWS servers', [ID])
		List<Map> shared = evictedLoop(ID, '{"total":2}')

		then: 'the id holds no question, so a loop with only that id left gets nothing back'
		remembered(ID).question == null
		!OpenAiProvider.isAmbiguous(remembered(ID))
		!OpenAiProvider.restoreLostQuestion(shared)
		shared*.role == ['system', 'assistant', 'tool']

		when: 'nothing is stored on it for the window, and then a question is'
		remembered(ID).updated = System.currentTimeMillis() - OpenAiProvider.QUESTION_CLASH_WINDOW_MS - 1000L
		ask('List the AWS servers', [ID])
		shared = evictedLoop(ID, '{"total":2}')

		then: 'that question takes it over'
		remembered(ID).question == 'List the AWS servers'
		OpenAiProvider.restoreLostQuestion(shared)
		shared[1].content == OpenAiProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a third question on a marked tool call id is a new clash and renews the mark"() {
		given:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		ask('List the AWS servers', [ID])
		ask('Which instances are stopped?', [ID])
		long earlier = System.currentTimeMillis() - 60000L
		remembered(ID).ambiguous = earlier

		when:
		ask('Which clouds are offline?', [ID])

		then:
		(remembered(ID).ambiguous as long) > earlier
		remembered(ID).clashed.size() == 3
		!OpenAiProvider.restoreLostQuestion(evictedLoop(ID, '{"total":5}'))

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "the next round of a loop that clashed keeps the mark, so the other loop's question does not come back"() {
		given: 'loop B wrote the tool call id last, after loop A, and writes it again with its own question'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		Map first = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'List the AWS servers']]]
		Map second = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'Which instances are stopped?']]]
		OpenAiProvider.rememberQuestion(first, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		OpenAiProvider.rememberQuestion(second, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		OpenAiProvider.rememberQuestion(second, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		List<Map> firstLoop = evictedLoop(ID, '{"total":2}')

		expect:
		OpenAiProvider.isAmbiguous(OpenAiProvider.QUESTION_BY_TOOL_CALL.get(OpenAiProvider.toolCallKey(OpenAiProvider.questionScope(null, firstLoop), ID)))
		!OpenAiProvider.restoreLostQuestion(firstLoop)
		firstLoop*.role == ['system', 'assistant', 'tool']

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a loop refused its question for a clash marks its next tool calls, so the other loop's question cannot come back through them"() {
		given: 'loops A and B clashed on Y, then A called Z with its question still in memory'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String Y = 'tool_0_list_servers'
		String Z = 'tool_1_list_instances'
		String W = 'tool_2_get_instance'
		Map a = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'List the AWS servers']]]
		Map b = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'Which instances are stopped?']]]
		OpenAiProvider.rememberQuestion(a, response([[id: Y, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		OpenAiProvider.rememberQuestion(b, response([[id: Y, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		OpenAiProvider.rememberQuestion(a, response([[id: Z, type: 'function', function: [name: 'list_instances', arguments: '{}']]]))
		String scope = OpenAiProvider.questionScope(null, evictedLoop(Y, 'x'))
		Closure<Map> entryOf = { String id -> OpenAiProvider.QUESTION_BY_TOOL_CALL.get(OpenAiProvider.toolCallKey(scope, id)) }
		long clash = System.currentTimeMillis() - 300000L
		entryOf(Y).ambiguous = clash

		when: 'B lost its question, is refused it through Y and calls Z without it'
		List<Map> refused = evictedLoop(Y, '{"total":5}')
		boolean restoredThroughY = OpenAiProvider.restoreLostQuestion(refused)
		long refusedAt = System.currentTimeMillis()
		OpenAiProvider.rememberQuestion([model: 'm', messages: refused], response([[id: Z, type: 'function', function: [name: 'list_instances', arguments: '{}']]]))

		and: 'the chat memory dropped the calls on Y, and B calls W before A does'
		List<Map> later = evictedLoop(Z, '{"total":3}')
		boolean restoredThroughZ = OpenAiProvider.restoreLostQuestion(later)
		OpenAiProvider.rememberQuestion([model: 'm', messages: later], response([[id: W, type: 'function', function: [name: 'get_instance', arguments: '{}']]]))
		long markedW = (entryOf(W).ambiguous as long) - 60000L
		entryOf(W).ambiguous = markedW
		OpenAiProvider.rememberQuestion(a, response([[id: W, type: 'function', function: [name: 'get_instance', arguments: '{}']]]))
		List<Map> last = evictedLoop(W, '{"id":7}')

		then:
		!restoredThroughY
		OpenAiProvider.isAmbiguous(entryOf(Z))
		entryOf(Z).question == null
		!restoredThroughZ
		later*.role == ['system', 'assistant', 'tool']

		and: 'the mark B passed on starts at its own call, not at the clash on Y'
		(entryOf(Z).ambiguous as long) >= refusedAt
		entryOf(Y).ambiguous == clash

		and: "A's question on W is part of the clash, so it neither lifts nor renews the mark B left there"
		OpenAiProvider.isAmbiguous(entryOf(W))
		entryOf(W).ambiguous == markedW
		entryOf(W).question == null
		!OpenAiProvider.restoreLostQuestion(last)
		last*.role == ['system', 'assistant', 'tool']

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a request whose known tool calls hold different questions, or any ambiguous one, gets no question back"() {
		given: 'a loop with two tool calls left, the first of them clean'
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		String Y = 'tool_0_list_servers'
		String Z = 'tool_1_list_instances'
		long now = System.currentTimeMillis()
		Closure<List<Map>> loop = {
			[[role: 'system', content: 'You are Murph.'],
			 [role: 'assistant', content: '', tool_calls: [[id: Y]]], [role: 'tool', tool_call_id: Y, content: '{"total":5}'],
			 [role: 'assistant', content: '', tool_calls: [[id: Z]]], [role: 'tool', tool_call_id: Z, content: '{"total":3}']]
		}
		String scope = OpenAiProvider.questionScope(null, loop())
		OpenAiProvider.QUESTION_BY_TOOL_CALL.put(OpenAiProvider.toolCallKey(scope, Y), [question: 'Which instances are stopped?', costKey: 'k', updated: now])

		when:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.put(OpenAiProvider.toolCallKey(scope, Z),
			zMarked ? [ambiguous: now, clashed: ['a'] as Set, updated: now] : [question: zQuestion, costKey: 'k', updated: now])
		List<Map> messages = loop()
		boolean restored = OpenAiProvider.restoreLostQuestion(messages)

		then:
		restored == expected
		messages*.role == (expected ? ['system', 'user'] : ['system']) + ['assistant', 'tool', 'assistant', 'tool']

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()

		where:
		zQuestion                      | zMarked || expected
		'List the AWS servers'         | false   || false
		null                           | true    || false
		'Which instances are stopped?' | false   || true
	}

	def "generateResponse remembers the question and restores it on a later request that lost it"() {
		given:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
		List<Map> sent = []
		OpenAiApiService api = Stub()
		api.createChatCompletion(*_) >> { args ->
			sent << (args[2] as Map)
			[success: true, data: [id: 'chatcmpl-1', model: 'm', choices: [[index: 0, finish_reason: 'tool_calls',
				message: [role: 'assistant', content: null, tool_calls: [[id: 'call_E2eAbCdEfGhIjKlMn', type: 'function',
					function: [name: 'list_servers', arguments: '{}']]]]]], usage: [prompt_tokens: 10, completion_tokens: 5]]]
		}
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(id: 4L, serviceUrl: OpenAiProvider.DEFAULT_API_URL, servicePassword: 'sk-test-0000000000')
		LlmIntegration llm = new LlmIntegration(accountIntegration: ai)
		LlmIntegration otherIntegration = new LlmIntegration(accountIntegration: new AccountIntegration(id: 5L,
			serviceUrl: OpenAiProvider.DEFAULT_API_URL, servicePassword: 'sk-test-0000000000'))
		Closure<LlmChatRequest> evicted = { String systemPrompt ->
			new LlmChatRequest(model: 'm', messages: [
				message('system', systemPrompt),
				message('assistant', '', [tool_calls: [[id: 'call_E2eAbCdEfGhIjKlMn', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]),
				message('tool', '{"truncated":true}', [tool_call_id: 'call_E2eAbCdEfGhIjKlMn'])
			])
		}

		when:
		provider.generateResponse(llm, new LlmChatRequest(model: 'm', messages: [message('system', 'You are Murph.'), message('user', 'List the AWS servers')]), [:])
		provider.generateResponse(llm, evicted('You are Murph.'), [:])
		provider.generateResponse(otherIntegration, evicted('You are Murph.'), [:])
		provider.generateResponse(llm, evicted('You are another agent.'), [:])

		then:
		sent.size() == 4
		sent[1].messages*.role == ['system', 'user', 'assistant', 'tool']
		sent[1].messages[1].content == OpenAiProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		and: 'the same tool call id from another integration or another agent prompt never gets that question'
		sent[2].messages*.role == ['system', 'assistant', 'tool']
		sent[3].messages*.role == ['system', 'assistant', 'tool']

		cleanup:
		OpenAiProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "the cost key keeps integrations apart and ignores the plugin's own U+FFFD note"() {
		given:
		Map body = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: 'Which servers run?']]]
		Map withNote = [model: 'm', messages: [[role: 'system', content: 'You are Murph.'], [role: 'system', content: OpenAiProvider.REPLACEMENT_CHARACTER_NOTE],
											   [role: 'user', content: 'Which servers run?'], [role: 'tool', tool_call_id: 't1', content: 'L�uft']]]

		expect:
		OpenAiProvider.questionKey(body, 4L) == OpenAiProvider.questionKey(withNote, 4L)
		OpenAiProvider.questionKey(body, 4L) != OpenAiProvider.questionKey(body, 5L)
	}

	// ------------------------------------------------------------------
	// Parameters an API rejects, streamed usage and cut-off tool calls
	// ------------------------------------------------------------------

	def "a model that rejects reasoning_effort is asked again without it, and is not sent it again"() {
		given: 'what api.openai.com answers for gpt-4.1 with a reasoning effort'
		OpenAiProvider.REJECTED_PARAMETERS.clear()
		List<Map> sent = []
		OpenAiApiService api = Stub()
		api.createChatCompletion(*_) >> { args ->
			Map body = args[2] as Map
			sent << new LinkedHashMap(body)
			body.containsKey('reasoning_effort') ?
				[success: false, statusCode: 400, msg: "API returned 400: Unsupported parameter: 'reasoning_effort' is not supported with this model."] :
				[success: true, data: [choices: [[finish_reason: 'stop', message: [content: 'Two servers.']]]]]
		}
		provider.apiService = api
		LlmIntegration llm = new LlmIntegration(accountIntegration: configured([reasoningEffort: 'high', chatErrors: false]))
		LlmChatRequest request = new LlmChatRequest(model: 'gpt-4.1', messages: [message('user', 'How many servers?')])

		when:
		ServiceResponse<LlmChatResponse> first = provider.generateResponse(llm, request, [:])
		ServiceResponse<LlmChatResponse> second = provider.generateResponse(llm, request, [:])

		then:
		first.success
		first.data.message.content == 'Two servers.'
		second.data.message.content == 'Two servers.'
		sent*.containsKey('reasoning_effort') == [true, false, false]

		and: 'a reasoning model at the same endpoint still gets it'
		provider.buildChatRequestBody(new LlmChatRequest(model: 'gpt-5.5', messages: [message('user', 'hi')]),
			configured([reasoningEffort: 'high']), false).reasoning_effort == 'high'

		cleanup:
		OpenAiProvider.REJECTED_PARAMETERS.clear()
	}

	def "a 400 that names no parameter the plugin added is not asked again"() {
		given:
		OpenAiProvider.REJECTED_PARAMETERS.clear()
		int calls = 0
		OpenAiApiService api = Stub()
		api.createChatCompletion(*_) >> { calls++; [success: false, statusCode: 400, msg: 'API returned 400: Invalid value for messages.'] }
		provider.apiService = api
		LlmIntegration llm = new LlmIntegration(accountIntegration: configured([reasoningEffort: 'high', chatErrors: false]))

		when:
		ServiceResponse<LlmChatResponse> response = provider.generateResponse(llm, new LlmChatRequest(model: 'gpt-4.1', messages: [message('user', 'hi')]), [:])

		then:
		!response.success
		calls == 1
		OpenAiProvider.REJECTED_PARAMETERS.isEmpty()
	}

	def "a stream asks for its usage, and an API that rejects stream_options is asked again without it"() {
		given:
		OpenAiProvider.REJECTED_PARAMETERS.clear()
		List<Map> sent = []
		OpenAiApiService api = Stub()
		api.streamChatCompletion(*_) >> { args ->
			Map body = args[2] as Map
			sent << new LinkedHashMap(body)
			body.containsKey('stream_options') ?
				[success: false, statusCode: 400, msg: 'API returned 400: Unrecognized request argument supplied: stream_options'] :
				[success: true, data: [choices: [[finish_reason: 'stop', message: [content: 'Pong']]]]]
		}
		provider.apiService = api
		LlmStreamingResponseHandler handler = Mock()
		LlmIntegration llm = new LlmIntegration(accountIntegration: configured([:]))

		when:
		provider.streamResponse(llm, new LlmChatRequest(model: 'm', messages: [message('user', 'Ping')]), handler, [:])

		then:
		1 * handler.onCompleteResponse({ LlmChatResponse r -> r.message.content == 'Pong' })
		0 * handler.onError(_)
		sent*.containsKey('stream_options') == [true, false]

		and: 'only a stream asks for usage, and a model that never refused it still does'
		provider.buildChatRequestBody(new LlmChatRequest(model: 'm2', messages: [message('user', 'hi')]), integration, true).stream_options == [include_usage: true]
		!provider.buildChatRequestBody(new LlmChatRequest(model: 'm2', messages: [message('user', 'hi')]), integration, false).containsKey('stream_options')
		!provider.buildChatRequestBody(new LlmChatRequest(model: 'm', messages: [message('user', 'hi')]), integration, true).containsKey('stream_options')

		cleanup:
		OpenAiProvider.REJECTED_PARAMETERS.clear()
	}

	def "a tool call the token limit cut off is dropped, and with none left the answer says it was cut off"() {
		given:
		Map complete = [id: 'call_a', type: 'function', function: [name: 'list_clouds', arguments: '{"max":10}']]
		Map cutOff = [id: 'call_b', type: 'function', function: [name: 'list_servers', arguments: '{"name":"web-']]
		Map noArguments = [id: 'call_c', type: 'function', function: [name: 'list_groups', arguments: '']]

		when:
		LlmChatResponse partly = provider.parseChatResponse([choices: [[finish_reason: 'length', message: [content: null, tool_calls: [complete, cutOff]]]]])
		LlmChatResponse none = provider.parseChatResponse([choices: [[finish_reason: 'length', message: [content: 'Checking.', tool_calls: [cutOff, noArguments]]]]])

		then: 'the complete call still runs'
		partly.finishReason == 'tool_calls'
		partly.metadata.tool_calls*.id == ['call_a']

		and: 'without one, the turn is an answer that says it was cut off'
		none.finishReason == 'length'
		!none.metadata.containsKey('tool_calls')
		none.message.content == 'Checking.\n\n*Answer cut off at the output token limit.*'

		and: 'a turn that ended on its own keeps every call'
		provider.parseChatResponse([choices: [[finish_reason: 'tool_calls', message: [content: null, tool_calls: [complete, noArguments]]]]])
			.metadata.tool_calls*.id == ['call_a', 'call_c']
	}

	def "empty arguments the tool definition needs are kept: required ones, and false where the default is true"() {
		given:
		List tools = [
			[type: 'function', function: [name: 'get_result_excerpt', parameters: [type: 'object', required: ['artifactId', 'offset'],
				properties: [artifactId: [type: 'string'], offset: [type: 'integer'], length: [type: 'integer']]]]],
			[type: 'function', function: [name: 'list_servers', parameters: [type: 'object',
				properties: [name: [type: 'string'], managed: [type: 'boolean'], includeDetails: [type: 'boolean', 'default': true],
							 showDeleted: [type: 'boolean', 'default': false]]]]]
		]
		LlmChatResponse response = provider.parseChatResponse([choices: [[finish_reason: 'tool_calls', message: [content: null, tool_calls: [
			[id: 't1', type: 'function', function: [name: 'get_result_excerpt', arguments: '{"artifactId":"a1","offset":0,"length":0}']],
			[id: 't2', type: 'function', function: [name: 'list_servers', arguments: '{"name":"","managed":false,"includeDetails":false,"showDeleted":false}']],
			[id: 't3', type: 'function', function: [name: 'not_in_request', arguments: '{"offset":0}']]
		]]]]])

		when:
		provider.dropEmptyToolArguments(response, integration, tools)

		then:
		response.metadata.tool_calls*.function*.arguments == ['{"artifactId":"a1","offset":0}', '{"includeDetails":false}', '{}']
	}
}
