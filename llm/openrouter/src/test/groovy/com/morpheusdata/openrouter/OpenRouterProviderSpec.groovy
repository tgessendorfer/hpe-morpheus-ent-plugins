package com.morpheusdata.openrouter

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
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the form, validation, request and response handling, the cost footer and
 * the catalog filter.
 */
class OpenRouterProviderSpec extends Specification {

	OpenRouterProvider provider = new OpenRouterProvider(null, null)
	AccountIntegration integration = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL)
	HttpServer server

	def cleanup() {
		server?.stop(0)
	}

	/**
	 * A local /chat/completions that answers its n-th request with the n-th SSE stream (the
	 * last one again after that) and then closes the connection; requests counts them.
	 */
	private String serveStreams(List<List<String>> streams, AtomicInteger requests) {
		server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
		server.createContext('/api/v1/chat/completions', { HttpExchange exchange ->
			exchange.requestBody.bytes
			int index = Math.min(requests.getAndIncrement(), streams.size() - 1)
			byte[] bytes = streams[index].join('\n').getBytes('UTF-8')
			exchange.responseHeaders.add('Content-Type', 'text/event-stream')
			exchange.sendResponseHeaders(200, bytes.length)
			exchange.responseBody.withStream { it.write(bytes) }
		} as HttpHandler)
		server.start()
		return "http://127.0.0.1:${server.address.port}/api/v1"
	}

	private static LlmChatMessage message(String role, String content, Map metadata = null) {
		LlmChatMessage msg = new LlmChatMessage()
		msg.role = role
		msg.content = content
		msg.metadata = metadata
		return msg
	}

	private static AccountIntegration configured(Map config) {
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL)
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
		OpenRouterPlugin.DESCRIPTION.length() <= 255
		provider.description.length() <= 255
	}

	def "the reasoning effort is a select served by the plugin's option source"() {
		when:
		OptionType option = provider.optionTypes.find { it.fieldName == 'reasoningEffort' }
		OpenRouterOptionSourceProvider source = new OpenRouterOptionSourceProvider(null, null)

		then:
		option.inputType == OptionType.InputType.SELECT
		option.optionSource in source.methodNames
		option.defaultValue == OpenRouterProvider.REASONING_EFFORT_DEFAULT
		source.openRouterReasoningEfforts(null)*.value == ['default', 'low', 'medium', 'high']
	}

	def "base url tolerates pasted endpoints and the Anthropic-compatible base"() {
		expect:
		provider.resolveBaseUrl(new AccountIntegration(serviceUrl: entered)) == expected

		where:
		entered                                          || expected
		null                                             || 'https://openrouter.ai/api/v1'
		'https://openrouter.ai/api/v1'                   || 'https://openrouter.ai/api/v1'
		'https://openrouter.ai/api/v1/'                  || 'https://openrouter.ai/api/v1'
		'https://openrouter.ai/api/v1/chat/completions'  || 'https://openrouter.ai/api/v1'
		'https://openrouter.ai/api'                      || 'https://openrouter.ai/api/v1'
		'https://openrouter.ai'                          || 'https://openrouter.ai/api/v1'
		'https://eu.openrouter.ai'                       || 'https://eu.openrouter.ai/api/v1'
		'https://us.openrouter.ai/api'                   || 'https://us.openrouter.ai/api/v1'
		'https://eu.openrouter.ai/api/v1/models'         || 'https://eu.openrouter.ai/api/v1'
		'https://gateway.example.com/openai/v1'          || 'https://gateway.example.com/openai/v1'
	}

	// ------------------------------------------------------------------
	// Validation
	// ------------------------------------------------------------------

	def "the stored credential is loaded when Morpheus did not hand it over, and wins over a stale local key"() {
		given: 'an update through the REST API: no credentialData, and an old value in the hidden local field'
		int lookups = 0
		OpenRouterProvider withLookup = new OpenRouterProvider(null, null) {
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
		OpenRouterProvider withLookup = new OpenRouterProvider(null, null) {
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
		OpenRouterProvider withLookup = new OpenRouterProvider(null, null) {
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
		OpenRouterProvider failing = new OpenRouterProvider(null, null) {
			@Override
			protected AccountCredential loadAccountCredential(AccountIntegration accountIntegration) {
				throw new RuntimeException('database unavailable')
			}
		}

		expect:
		failing.resolveApiKey(new AccountIntegration(id: 6L, servicePassword: 'sk-or-v1-local')) == 'sk-or-v1-local'
	}

	def "saving checks the key itself, since the model list answers any key"() {
		given:
		OpenRouterApiService api = Mock()
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-bad')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		1 * api.getKey('https://openrouter.ai/api/v1', 'sk-or-v1-bad', _) >> [success: false, msg: 'API returned 401: User not found.']
		0 * api.listModels(*_)
		!response.success
		response.msg.contains('User not found.')
		!response.msg.contains('keys start with')
	}

	def "a key in the wrong format gets a hint, since OpenRouter's own message points elsewhere"() {
		given: 'what OpenRouter answered on 2026-09-15 to a Bearer token not starting with sk-or-'
		OpenRouterApiService api = Stub()
		api.getKey(*_) >> [success: false, msg: 'API returned 401: Missing Authentication header']
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL, servicePassword: '1234567890')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		!response.success
		response.msg == 'OpenRouter did not accept the API key at https://openrouter.ai/api/v1/key: API returned 401: ' +
			'Missing Authentication header. OpenRouter keys start with "sk-or-". Check that the whole key was pasted.'
	}

	def "a base URL that answers with a web page fails validation"() {
		given:
		OpenRouterApiService api = Stub()
		api.getKey(*_) >> [success: true, data: [data: '<!DOCTYPE html>']]
		api.listModels(*_) >> [success: true, data: [data: '<!DOCTYPE html>']]
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'https://gateway.example.com/v1', servicePassword: 'sk-or-v1-test')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		!response.success
		response.msg.contains('returned no model list')
	}

	def "a regional domain is refused on save when the account lacks in-region routing"() {
		given: 'what eu.openrouter.ai answered on 2026-09-15 to an account without the Business plan'
		OpenRouterApiService api = Mock()
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'https://eu.openrouter.ai', servicePassword: 'sk-or-v1-test')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then: '/key and /models answer as usual on a regional domain'
		1 * api.getKey('https://eu.openrouter.ai/api/v1', 'sk-or-v1-test', _) >> [success: true, data: [data: [limit: null]]]
		1 * api.listModels(*_) >> [success: true, data: [data: [catalogEntry('mistralai/ministral-8b-2512')]]]

		and: 'the plan check asks for a model that cannot exist, so it never runs one'
		1 * api.createChatCompletion('https://eu.openrouter.ai/api/v1', 'sk-or-v1-test', { Map body ->
			body.model == OpenRouterProvider.REGIONAL_PROBE_MODEL && body.max_tokens == 1
		}, _) >> [success: false, msg: 'API returned 403: Regional routing not enabled for this account. Please reach out to our enterprise sales team to enable this feature.']

		and:
		!response.success
		response.msg.contains('Regional routing not enabled')
		response.msg.contains('Business or Enterprise plan')
		response.msg.contains('Settings > Preferences > Account Type')
	}

	def "a regional domain with the plan validates, since the made-up model only fails as an invalid model"() {
		given:
		OpenRouterApiService api = Stub()
		api.getKey(*_) >> [success: true, data: [data: [limit: null]]]
		api.listModels(*_) >> [success: true, data: [data: [catalogEntry('mistralai/ministral-8b-2512')]]]
		api.createChatCompletion(*_) >> [success: false, msg: 'API returned 400: openrouter/regional-routing-check is not a valid model ID']
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'https://us.openrouter.ai/api/v1', servicePassword: 'sk-or-v1-test')

		expect:
		provider.validate(new LlmIntegration(accountIntegration: ai), [:]).success
	}

	def "the main domain gets no plan check"() {
		given:
		OpenRouterApiService api = Mock()
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		when:
		ServiceResponse response = provider.validate(new LlmIntegration(accountIntegration: ai), [:])

		then:
		1 * api.getKey(*_) >> [success: true, data: [data: [limit: null]]]
		1 * api.listModels(*_) >> [success: true, data: [data: [catalogEntry('openai/gpt-5.5')]]]
		0 * api.createChatCompletion(*_)
		response.success
	}

	def "a chat refused for missing in-region routing says which plan it needs"() {
		given:
		OpenRouterApiService api = Stub()
		api.createChatCompletion(*_) >> [success: false, msg: 'API returned 403: Regional routing not enabled for this account. Please reach out to our enterprise sales team to enable this feature.']
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'https://eu.openrouter.ai/api/v1', servicePassword: 'sk-or-v1-test')
		ai.setConfigMap(config)
		LlmChatRequest request = new LlmChatRequest(model: 'mistralai/ministral-8b-2512', messages: [message('user', 'hi')])

		when:
		ServiceResponse<LlmChatResponse> response = provider.generateResponse(new LlmIntegration(accountIntegration: ai), request, [:])
		String text = response.success ? response.data.message.content : response.msg

		then:
		response.success == inChat
		text.contains('Regional routing not enabled')
		text.contains('Business or Enterprise plan')

		and: 'no generic 403 hint next to the plan explanation'
		!text.contains('moderation')

		where:
		config               || inChat
		[:]                  || true
		[chatErrors: false]  || false
	}

	// ------------------------------------------------------------------
	// Failures
	// ------------------------------------------------------------------

	def "a rate limit is waited out for as long as OpenRouter asks, and the answer comes through"() {
		given:
		OpenRouterApiService api = Stub()
		api.createChatCompletion(*_) >>> [
			[success: false, msg: 'API returned 429: Rate limit exceeded', statusCode: 429, retryAfter: 7],
			[success: true, data: [choices: [[finish_reason: 'stop', message: [content: 'Es gibt eine Gruppe.']]]]]
		]
		provider.apiService = api
		List<Long> waits = []
		provider.sleeper = { long millis -> waits << millis }
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		when:
		ServiceResponse<LlmChatResponse> response = provider.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'google/gemini-3.5-flash', messages: [message('user', 'Wie viele Gruppen gibt es?')]), [:])

		then:
		waits == [7000L]
		response.success
		response.data.message.content == 'Es gibt eine Gruppe.'
	}

	def "a rate limit that stays ends as an answer with OpenRouter's message, in the language of the question"() {
		given: 'what OpenRouter answered a new account on 2026-09-15'
		OpenRouterApiService api = Stub()
		api.createChatCompletion(*_) >> [success: false, statusCode: 429,
										 msg    : 'API returned 429: Rate limit exceeded: new-account-rpm/google/gemini-3.5-flash-20260519. Rate limit reached: new accounts are limited to 20 requests per minute for this model. Please retry shortly.']
		provider.apiService = api
		List<Long> waits = []
		provider.sleeper = { long millis -> waits << millis }
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		when:
		ServiceResponse<LlmChatResponse> response = provider.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'google/gemini-3.5-flash', messages: [message('user', 'Wie viele Server gibt es?')]), [:])

		then: 'two waits, then the failure reaches the chat as an answer Morpheus shows'
		waits == [10000L, 20000L]
		response.success
		response.data.finishReason == 'stop'
		response.data.message.content == '**OpenRouter-Fehler 429:** Rate limit exceeded: new-account-rpm/google/gemini-3.5-flash-20260519. ' +
			'Rate limit reached: new accounts are limited to 20 requests per minute for this model. Please retry shortly.\n\n' +
			'Zu viele Anfragen in kurzer Zeit. Eine Minute warten und erneut fragen.'
	}

	def "an error answer carries what the question cost until it failed"() {
		given: 'a billed tool round, then a request refused for missing credits'
		OpenRouterApiService api = Stub()
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
		failed.data.message.content == '**OpenRouter error 402:** Insufficient credits\n\n' +
			'The OpenRouter account has no credits left, or the key has reached its limit.\n\n*Cost: $0.1408*'
	}

	def "with the error option unticked, a failure stays an error for Morpheus"() {
		given:
		OpenRouterApiService api = Stub()
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
		OpenRouterApiService api = Stub()
		api.streamChatCompletion(*_) >>> [
			[success: false, statusCode: 503, msg: 'OpenRouter API returned 503: No instances available'],
			[success: true, data: [choices: [[finish_reason: 'stop', message: [content: 'One.']]]]]
		]
		provider.apiService = api
		List<Long> waits = []
		provider.sleeper = { long millis -> waits << millis }
		List<LlmChatResponse> completed = []
		LlmStreamingResponseHandler handler = [onCompleteResponse: { LlmChatResponse response -> completed << response }] as LlmStreamingResponseHandler
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		when:
		provider.streamResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'openai/gpt-5.5', messages: [message('user', 'How many clouds?')]), handler, [:])

		then:
		waits == [10000L]
		completed*.message*.content == ['One.']
	}

	def "a stream that fails after its first words ends with the error under them, and is not retried"() {
		given:
		OpenRouterApiService api = Mock()
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
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

		when:
		provider.streamResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'openai/gpt-5.5', messages: [message('user', 'Which servers are running?')]), handler, [:])

		then: 'one call only: another try would repeat the words already shown'
		1 * api.streamChatCompletion(*_) >> { List args ->
			(args[3] as Closure).call('Two servers run')
			[success: false, statusCode: 429, msg: 'OpenRouter stream error: Rate limit exceeded (provider: OpenAI)']
		}
		waits.isEmpty()
		parts == ['Two servers run']
		errors.isEmpty()
		completed*.message*.content == ['Two servers run\n\n**OpenRouter error 429:** Rate limit exceeded (provider: OpenAI)\n\n' +
			'Too many requests in a short time. Wait a minute and ask again.']
	}

	def "a stream that closes before the answer is complete, with nothing shown yet, is asked again and the second answer is used"() {
		given: 'the first stream breaks off inside a tool call, before any text; the second one completes'
		AtomicInteger requests = new AtomicInteger()
		String baseUrl = serveStreams([
			[': OPENROUTER PROCESSING', '',
			 'data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"list_servers","arguments":"{\\"na"}}]},"finish_reason":null}]}', ''],
			['data: {"choices":[{"index":0,"delta":{"content":"Two servers run."},"finish_reason":"stop"}]}', '', 'data: [DONE]', '']
		], requests)
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
		AccountIntegration ai = new AccountIntegration(serviceUrl: baseUrl, servicePassword: 'sk-or-v1-test')

		when:
		provider.streamResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'openai/gpt-5.5', messages: [message('user', 'Which servers are running?')]), handler, [:])

		then: 'asked once more, after the short wait of a connection failure'
		requests.get() == 2
		waits == [1500L]
		errors.isEmpty()
		parts == ['Two servers run.']
		completed*.message*.content == ['Two servers run.']

		and: 'nothing of the broken tool call reaches Morpheus'
		completed[0].finishReason == 'stop'
		!completed[0].metadata.tool_calls
	}

	def "a stream that closes after its first words ends with the error under them, and is not asked again"() {
		given:
		AtomicInteger requests = new AtomicInteger()
		String baseUrl = serveStreams([
			['data: {"choices":[{"index":0,"delta":{"content":"Two servers"},"finish_reason":null}]}', '']
		], requests)
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
		AccountIntegration ai = new AccountIntegration(serviceUrl: baseUrl, servicePassword: 'sk-or-v1-test')

		when:
		provider.streamResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'openai/gpt-5.5', messages: [message('user', 'Which servers are running?')]), handler, [:])

		then: 'one request only: another try would repeat the words already shown'
		requests.get() == 1
		waits.isEmpty()
		errors.isEmpty()
		parts == ['Two servers']
		completed*.message*.content == ['Two servers\n\n**OpenRouter error:** stream closed before the answer was complete']
		completed[0].metadata.error
	}

	def "error answers are left out of replayed history, and text before one is kept"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'openai/gpt-5.5', messages: [
			message('user', 'Wie viele Server gibt es?'),
			message('assistant', '**OpenRouter-Fehler 429:** Rate limit exceeded\n\nZu viele Anfragen in kurzer Zeit. Eine Minute warten und erneut fragen.\n\n*Kosten: $0.1408 (22 Anfragen)*'),
			message('user', 'Which servers run?'),
			message('assistant', 'Two servers run\n\n**OpenRouter error 502:** Provider returned error'),
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
		OpenRouterProvider.answerLanguage(question) == language

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
		OpenRouterProvider.ERROR_LABELS.keySet() == ['en', 'de', 'pl'] as Set
		OpenRouterProvider.ERROR_HINTS.every { Integer status, Map<String, String> hints ->
			hints.keySet() == ['en', 'de', 'pl'] as Set && hints.values().every { it?.trim() }
		}
		OpenRouterProvider.errorHint(504, 'Gateway timeout', 'de') == OpenRouterProvider.ERROR_HINTS[502].de
		OpenRouterProvider.errorHint(null, 'Connection reset', 'en') == null
	}

	def "a working key and catalog validate"() {
		given:
		OpenRouterApiService api = Stub()
		api.getKey(*_) >> [success: true, data: [data: [limit: null]]]
		api.listModels(*_) >> [success: true, data: [data: [catalogEntry('openai/gpt-5.5')]]]
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL, servicePassword: 'sk-or-v1-test')

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

	def "the integration's max_tokens is a floor over the request; without it the request is sent as asked"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'm', maxOutputTokens: requested, messages: [message('user', 'hi')])

		expect:
		provider.buildChatRequestBody(request, configured([maxOutputTokens: configuredValue]), false).max_tokens == expected

		where:
		requested | configuredValue || expected
		1000      | '4096'          || 4096
		8192      | '4096'          || 8192
		4096      | '4096'          || 4096
		null      | '4096'          || 4096
		0         | '4096'          || 4096
		1000      | null            || 1000
		1000      | '0'             || 1000
		null      | null            || null
		null      | '0'             || null
	}

	def "reasoning is only requested when the integration sets an effort"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'm', messages: [message('user', 'hi')])

		expect:
		provider.buildChatRequestBody(request, configured([reasoningEffort: effort]), false).reasoning == expected

		where:
		effort    || expected
		null      || null
		'default' || null
		'high'    || [effort: 'high']
		'Low'     || [effort: 'low']
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
		body.messages[1] == [role: 'system', content: OpenRouterProvider.REPLACEMENT_CHARACTER_NOTE]
		body.messages.size() == 5
	}

	// ------------------------------------------------------------------
	// Response
	// ------------------------------------------------------------------

	def "empty tool arguments a model filled in are dropped before Morpheus runs the tool"() {
		given: 'what openai/gpt-5.4-mini sent for list_servers on 2026-09-21'
		OpenRouterApiService api = Stub()
		api.createChatCompletion(*_) >> [success: true, data: [choices: [[finish_reason: 'tool_calls', message: [content: null, tool_calls: [
			[id: 't1', type: 'function', function: [name: 'list_servers', arguments: '{"name":"","phrase":"","zoneId":0,"managed":false,"status":"","max":100,"offset":0,"tags":[],"filters":{},"note":null,"powerState":"on"}']],
			[id: 't2', type: 'function', function: [name: 'use_servers_tools', arguments: '{"filter":"list_servers"}']]
		]]]]]]
		provider.apiService = api
		AccountIntegration ai = configured(config)
		ai.servicePassword = 'sk-or-v1-test'

		when:
		ServiceResponse<LlmChatResponse> response = provider.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'openai/gpt-5.4-mini', messages: [message('user', 'List all servers.')]), [:])
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
		OpenRouterProvider.pruneEmptyArguments(json) == [json: expected, dropped: dropped]

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

	def "an answer the token limit cut off says so, in its own language, and the note is stripped on replay"() {
		given:
		Map data = [choices: [[finish_reason: 'length', message: [role: 'assistant', content: content]]], usage: [prompt_tokens: 1, completion_tokens: 1000]]

		when:
		LlmChatResponse response = provider.parseChatResponse(data)

		then:
		response.finishReason == 'length'
		response.metadata.truncated == true
		response.message.content == content + '\n\n*' + note + '*'
		OpenRouterProvider.stripUsageFooter(response.message.content) == content

		where:
		content                                                     || note
		'The history of virtualization begins with the mainframe'   || 'Answer cut off at the output token limit.'
		'Die Geschichte der Virtualisierung beginnt mit dem'         || 'Antwort am Ausgabe-Token-Limit abgeschnitten.'
	}

	def "an empty answer cut off by the token limit becomes the note itself"() {
		given: 'a reasoning model that spent its whole budget thinking'
		Map data = [choices: [[finish_reason: 'length', message: [role: 'assistant', content: '']]], usage: [prompt_tokens: 13847, completion_tokens: 960]]

		expect:
		provider.parseChatResponse(data).message.content == '*Answer cut off at the output token limit.*'
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
		OpenRouterProvider.stripUsageFooter(answer.message.content) == 'None.'

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
		OpenRouterProvider.stripUsageFooter(answer.message.content) == text

		where:
		text                                                             || label
		'Es gibt keine Instanzen, und alle 4 Server laufen.'             || 'Kosten'
		'There are no instances, and all 4 servers are running.'         || 'Cost'
		'Wszystkie 4 serwery działają, nie ma żadnych instancji.'        || 'Koszt'
	}

	def "request counts take the plural form of the answer's language"() {
		expect:
		OpenRouterProvider.requestCount(count, language) == text

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

	def "only models an agent can use are listed"() {
		given:
		Map catalog = [data: [
			catalogEntry('openai/gpt-5.5'),
			catalogEntry('openai/gpt-5.5:batch'),
			catalogEntry('mistralai/no-tools', [supported_parameters: ['max_tokens', 'temperature']]),
			catalogEntry('google/image-maker', [architecture: [input_modalities: ['text'], output_modalities: ['image']]]),
			catalogEntry('google/gemini-3-pro-image-preview', [architecture: [input_modalities: ['text', 'image'], output_modalities: ['image', 'text']]]),
			catalogEntry('openai/gpt-audio', [architecture: [input_modalities: ['text', 'audio'], output_modalities: ['text', 'audio']]]),
			catalogEntry('openrouter/auto'),
			catalogEntry('~openai/gpt-mini-latest'),
			catalogEntry('anthropic/claude-sonnet-5'),
			catalogEntry('google/gemma-4-31b-it:free'),
			catalogEntry('z-ai/retired', [expiration_date: '2020-01-01'])
		]]

		when:
		List<LlmModel> models = provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: integration), catalog)

		then:
		models*.code == ['openai/gpt-5.5']
	}

	def "the Anthropic and free-variant checkboxes list those models again"() {
		given:
		AccountIntegration both = configured([includeAnthropicModels: 'on', includeFreeModels: 'on'])
		Map catalog = [data: [
			catalogEntry('anthropic/claude-sonnet-5'),
			catalogEntry('google/gemma-4-31b-it:free'),
			catalogEntry('anthropic/claude-sonnet-5:batch')
		]]

		when:
		List<LlmModel> models = provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: both), catalog)

		then:
		models*.code as Set == ['anthropic/claude-sonnet-5', 'google/gemma-4-31b-it:free'] as Set
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
		model.providerCode == 'openrouter'
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
		AccountIntegration narrowed = configured([modelAllowList: 'anthropic/*, openai/*'])
		Map catalog = [data: [catalogEntry('anthropic/claude-sonnet-5'), catalogEntry('openai/gpt-5.5:batch'), catalogEntry('openai/gpt-5.5')]]

		expect:
		provider.buildModelsFromApiResponse(new LlmIntegration(accountIntegration: narrowed), catalog)*.code == ['openai/gpt-5.5']
	}

	def "an empty allow list lists everything"() {
		expect:
		OpenRouterProvider.parseModelAllowList(value).isEmpty()

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
		OpenRouterProvider.isListedModel(catalogEntry('mistralai/mistral-large'), false, false, LocalDate.of(2026, 9, 15),
			OpenRouterProvider.parseModelAllowList('*'))
		provider.optionTypes*.displayOrder == (0..12).toList()
	}

	def "an expiry within a year goes into the name, a placeholder date does not"() {
		given:
		LocalDate today = LocalDate.of(2026, 9, 14)

		expect:
		OpenRouterProvider.displayName(catalogEntry('dots-studio/dots-3-note-preview:free', [name: 'Dots 3', expiration_date: expires]), today) == name
		OpenRouterProvider.isListedModel(catalogEntry('dots-studio/dots-3-note-preview', [expiration_date: expires]), false, false, today) == listed

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
		OpenRouterProvider withContext = providerWithProxyLookup([7L: proxy])

		expect:
		!withContext.buildClientOpts(configured([networkProxy: '7']), [:]).containsKey(OpenRouterApiService.NETWORK_PROXY_KEY)
		withContext.buildClientOpts(configured([useNetworkProxy: 'on', networkProxy: '7']), [clientScopeKey: 'chat-1'])
			.get(OpenRouterApiService.NETWORK_PROXY_KEY).is(proxy)
	}

	def "a proxy that has been deleted falls back to a direct connection"() {
		given:
		OpenRouterProvider withContext = providerWithProxyLookup([:])

		expect:
		!withContext.buildClientOpts(configured([useNetworkProxy: 'on', networkProxy: '7']), [:]).containsKey(OpenRouterApiService.NETWORK_PROXY_KEY)
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
	private static OpenRouterProvider providerWithProxyLookup(Map<Long, NetworkProxy> proxies) {
		return new OpenRouterProvider(null, null) {
			@Override
			protected NetworkProxy loadNetworkProxy(Long proxyId) {
				return proxies[proxyId]
			}
		}
	}

	def "a request whose question the chat memory dropped gets it back after the system messages"() {
		given: 'the model answered the question with a tool call'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		Map firstBody = provider.buildChatRequestBody(new LlmChatRequest(messages: [message('system', 'You are Murph.'), message('user', 'List the AWS servers')]), integration, false)
		LlmChatResponse first = new LlmChatResponse()
		first.metadata = [tool_calls: [[id: 'call_AbCdEfGhIjKlMnOp', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]
		OpenRouterProvider.rememberQuestion(firstBody, first)

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
		body.messages[1].content == OpenRouterProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'
		OpenRouterProvider.latestQuestion(body.messages) == 'List the AWS servers'

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a request that still has its question, or whose tool calls are unknown or too old, is left alone"() {
		given:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		long old = System.currentTimeMillis() - OpenRouterProvider.QUESTION_BY_TOOL_CALL_TTL_MS - 1000L
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.put(OpenRouterProvider.toolCallKey(OpenRouterProvider.questionScope(null, []), 'call_OldAbCdEfGhIjKl'),
			[question: 'Stale question', updated: old])
		List<Map> withQuestion = [[role: 'user', content: 'How many VMs?'], [role: 'assistant', content: '', tool_calls: [[id: 'call_OldAbCdEfGhIjKl']]]]
		List<Map> unknown = [[role: 'assistant', content: '', tool_calls: [[id: 'call_UnknownAbCdEfGh']]]]
		List<Map> expired = [[role: 'tool', tool_call_id: 'call_OldAbCdEfGhIjKl', content: 'x']]

		expect:
		!OpenRouterProvider.restoreLostQuestion(withQuestion)
		withQuestion.size() == 2
		!OpenRouterProvider.restoreLostQuestion(unknown)
		unknown.size() == 1
		!OpenRouterProvider.restoreLostQuestion(expired)
		expired.size() == 1

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	private static LlmChatResponse response(List calls) {
		LlmChatResponse r = new LlmChatResponse()
		r.metadata = [tool_calls: calls]
		return r
	}

	/** The remembered question of a tool call id, whatever its scope. */
	private static Map remembered(String id) {
		return OpenRouterProvider.QUESTION_BY_TOOL_CALL.find { String key, Map entry -> key.endsWith('|' + id) }?.value as Map
	}

	def "numbered tool call ids are never trusted, so another conversation's question cannot come back"() {
		given:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'call_0'
		Map firstBody = provider.buildChatRequestBody(new LlmChatRequest(messages: [message('user', 'List the AWS servers')]), integration, false)
		OpenRouterProvider.rememberQuestion(firstBody, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))

		expect:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.isEmpty()
		!OpenRouterProvider.isDistinctToolCallId('functions.list_servers:0')
		!OpenRouterProvider.isDistinctToolCallId('call_AbCdEfGhIjKlMn_12')
		OpenRouterProvider.isDistinctToolCallId('call_AbCdEfGhIjKlMnOp')

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a restored question keeps counting on its original cost key and carries on to the next tool calls"() {
		given:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'call_AbCdEfGhIjKlMnOp'
		String ID2 = 'call_ZyXwVuTsRqPoNmLk'
		Map firstBody = provider.buildChatRequestBody(new LlmChatRequest(messages: [message('system', 'You are Murph.'), message('user', 'List the AWS servers')]), integration, false)
		String originalKey = OpenRouterProvider.questionKey(firstBody)
		OpenRouterProvider.rememberQuestion(firstBody, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))
		List<Map> msgs = [[role: 'system', content: 'You are Murph.'], [role: 'assistant', content: '', tool_calls: [[id: ID]]], [role: 'tool', tool_call_id: ID, content: 'part']]

		when:
		boolean restored = OpenRouterProvider.restoreLostQuestion(msgs)
		OpenRouterProvider.rememberQuestion([messages: msgs], response([[id: ID2, type: 'function', function: [name: 'get_result_excerpt', arguments: '{}']]]))

		then:
		restored
		OpenRouterProvider.questionKey([model: firstBody.model, messages: msgs]) == originalKey
		remembered(ID2).question == 'List the AWS servers'
		remembered(ID2).costKey == originalKey

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a full map drops only its oldest entries"() {
		given:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		long now = System.currentTimeMillis()
		(OpenRouterProvider.QUESTION_BY_TOOL_CALL_MAX).times { int i ->
			OpenRouterProvider.QUESTION_BY_TOOL_CALL.put('call_old' + String.format('%012d', i) + 'x', [question: 'old', costKey: 'k', updated: now - 1000L + (i % 500)])
		}
		String ID = 'call_NewAbCdEfGhIjKlMn'
		Map firstBody = provider.buildChatRequestBody(new LlmChatRequest(messages: [message('user', 'List the AWS servers')]), integration, false)

		when:
		OpenRouterProvider.rememberQuestion(firstBody, response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]))

		then:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.size() == OpenRouterProvider.QUESTION_BY_TOOL_CALL_MAX
		remembered(ID).question == 'List the AWS servers'

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "generateResponse remembers the question and restores it on a later request that lost it"() {
		given:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		List<Map> sent = []
		OpenRouterApiService api = Stub()
		api.createChatCompletion(*_) >> { args ->
			sent << (args[2] as Map)
			[success: true, data: [id: 'chatcmpl-1', model: 'm', choices: [[index: 0, finish_reason: 'tool_calls',
				message: [role: 'assistant', content: null, tool_calls: [[id: 'call_E2eAbCdEfGhIjKlMn', type: 'function',
					function: [name: 'list_servers', arguments: '{}']]]]]], usage: [prompt_tokens: 10, completion_tokens: 5]]]
		}
		provider.apiService = api
		AccountIntegration ai = new AccountIntegration(id: 4L, serviceUrl: OpenRouterProvider.DEFAULT_API_URL, servicePassword: 'sk-test-0000000000')
		LlmIntegration llm = new LlmIntegration(accountIntegration: ai)

		when:
		provider.generateResponse(llm, new LlmChatRequest(model: 'm', messages: [message('system', 'You are Murph.'), message('user', 'List the AWS servers')]), [:])
		provider.generateResponse(llm, new LlmChatRequest(model: 'm', messages: [
			message('system', 'You are Murph.'),
			message('assistant', '', [tool_calls: [[id: 'call_E2eAbCdEfGhIjKlMn', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]),
			message('tool', '{"truncated":true}', [tool_call_id: 'call_E2eAbCdEfGhIjKlMn'])
		]), [:])

		then:
		sent.size() == 2
		sent[1].messages*.role == ['system', 'user', 'assistant', 'tool']
		sent[1].messages[1].content == OpenRouterProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a remembered question stays with its integration and its system prompt"() {
		given: 'a backend whose tool call ids repeat across conversations, and one question on integration 4'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		List<Map> asked = [[role: 'system', content: 'You are Murph for tenant A.'], [role: 'user', content: 'List the AWS servers']]
		OpenRouterProvider.rememberQuestion([messages: asked], response([[id: ID, type: 'function', function: [name: 'list_servers', arguments: '{}']]]), 4L)
		Closure<List<Map>> lost = { String system ->
			[[role: 'system', content: system], [role: 'assistant', content: '', tool_calls: [[id: ID]]], [role: 'tool', tool_call_id: ID, content: 'part']]
		}

		expect: 'another integration or another system prompt never gets the question'
		!OpenRouterProvider.restoreLostQuestion(lost('You are Murph for tenant A.'), 5L)
		!OpenRouterProvider.restoreLostQuestion(lost('You are Murph for tenant B.'), 4L)

		and: 'the conversation it came from does, also with the plugin\'s U+FFFD note among the system messages'
		List<Map> own = lost('You are Murph for tenant A.')
		own.add(1, [role: 'system', content: OpenRouterProvider.REPLACEMENT_CHARACTER_NOTE])
		OpenRouterProvider.restoreLostQuestion(own, 4L)
		own[2].content == OpenRouterProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	/** A request of one agent on integration 4 that lost its question; only the tool loop on these ids is left. */
	private static List<Map> lostQuestion(List<String> ids) {
		List<Map> messages = [[role: 'system', content: 'You are Murph.']]
		ids.each { String id ->
			messages << [role: 'assistant', content: '', tool_calls: [[id: id, type: 'function', function: [name: 'list_servers', arguments: '{}']]]]
			messages << [role: 'tool', tool_call_id: id, content: 'part']
		}
		return messages
	}

	/** Remembers the question asked of that agent for these tool call ids. */
	private static void ask(String question, List<String> ids) {
		OpenRouterProvider.rememberQuestion([messages: [[role: 'system', content: 'You are Murph.'], [role: 'user', content: question]]],
			response(ids.collect { String id -> [id: id, type: 'function', function: [name: 'list_servers', arguments: '{}']] }), 4L)
	}

	def "two tool loops that store different questions on the same tool call id at once restore neither"() {
		given: 'two users of one agent, on a backend whose tool call ids repeat; the first loop is a round further'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		String ID2 = 'tool_1_get_result_excerpt'
		ask('List the AWS servers', [ID])
		ask('List the AWS servers', [ID2])
		ask('List the Azure servers', [ID])
		List<Map> first = lostQuestion([ID2, ID])
		List<Map> second = lostQuestion([ID])

		when:
		boolean firstRestored = OpenRouterProvider.restoreLostQuestion(first, 4L)
		boolean secondRestored = OpenRouterProvider.restoreLostQuestion(second, 4L)

		then: 'the shared id is a marker: the time of the clash and two hashes, no question and no cost key'
		OpenRouterProvider.isAmbiguous(remembered(ID))
		remembered(ID).keySet() == ['ambiguous', 'clashed', 'updated'] as Set
		remembered(ID).clashed.size() == 2
		!remembered(ID).clashed.any { String hash -> hash.contains('AWS') || hash.contains('Azure') }

		and: 'neither request gets a question, not even through the id only the first loop used'
		!firstRestored
		!secondRestored
		first.size() == 5
		second.size() == 3

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "the loops that clashed storing their own questions again do not renew the mark, which ends 10 minutes after the last clash"() {
		given: 'a clash on the shared id nine minutes ago; the first loop also called a tool of its own'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		String OWN = 'tool_1_get_result_excerpt'
		ask('List the AWS servers', [ID])
		ask('List the Azure servers', [ID])
		long clash = System.currentTimeMillis() - OpenRouterProvider.QUESTION_CLASH_WINDOW_MS + 60000L
		remembered(ID).ambiguous = clash
		remembered(ID).updated = clash

		when: 'both loops call the tool again within the window, each with its own question'
		ask('List the Azure servers', [ID])
		ask('List the AWS servers', [ID])
		ask('List the AWS servers', [OWN])

		then: 'the key is refreshed, but the mark keeps the time of the clash and still holds'
		(remembered(ID).updated as long) > clash
		remembered(ID).ambiguous == clash
		remembered(ID).clashed.size() == 2
		!OpenRouterProvider.restoreLostQuestion(lostQuestion([OWN, ID]), 4L)

		when: 'the window passes since the clash'
		remembered(ID).ambiguous = System.currentTimeMillis() - OpenRouterProvider.QUESTION_CLASH_WINDOW_MS - 1000L
		List<Map> lost = lostQuestion([OWN, ID])

		then: 'the mark is over: the loop gets its question back through its own id'
		!OpenRouterProvider.isAmbiguous(remembered(ID))
		OpenRouterProvider.restoreLostQuestion(lost, 4L)
		lost[1].content == OpenRouterProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		when: 'one loop stores its question on the shared id again, while the key is still stored on within the window'
		ask('List the AWS servers', [ID])
		lost = lostQuestion([ID])

		then: 'the key holds no question, so a loop with only that id left gets nothing back'
		remembered(ID).question == null
		!OpenRouterProvider.isAmbiguous(remembered(ID))
		!OpenRouterProvider.restoreLostQuestion(lost, 4L)
		lost*.role == ['system', 'assistant', 'tool']

		when: 'nothing is stored on it for the window, and then a question is'
		remembered(ID).updated = System.currentTimeMillis() - OpenRouterProvider.QUESTION_CLASH_WINDOW_MS - 1000L
		ask('List the AWS servers', [ID])
		lost = lostQuestion([ID])

		then: 'that question takes it over'
		remembered(ID).question == 'List the AWS servers'
		OpenRouterProvider.restoreLostQuestion(lost, 4L)
		lost[1].content == OpenRouterProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "after the mark ends, a key the loops still store on hands neither loop the other one's question"() {
		given: 'loops A and B clashed on X more than 10 minutes ago and both kept storing their questions on it'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String X = 'tool_0_list_servers'
		ask('List the AWS servers', [X])
		ask('Which VMs are stopped?', [X])
		remembered(X).ambiguous = System.currentTimeMillis() - OpenRouterProvider.QUESTION_CLASH_WINDOW_MS - 1000L
		remembered(X).updated = System.currentTimeMillis() - 60000L

		when: 'A stores its question on X again; B lost its question and has only X left'
		ask('List the AWS servers', [X])
		List<Map> lostB = lostQuestion([X])

		then: "B does not get A's question"
		remembered(X).question == null
		!OpenRouterProvider.restoreLostQuestion(lostB, 4L)
		lostB*.role == ['system', 'assistant', 'tool']

		when: 'a question that was not part of the clash is stored on X within the window'
		ask('Which clouds are offline?', [X])

		then: 'it is a new clash'
		OpenRouterProvider.isAmbiguous(remembered(X))
		remembered(X).clashed.size() == 3

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a loop that lost its question marks its tool calls even when nothing was known of it, so another loop's question cannot come back"() {
		given: 'the plugin knows nothing of loop B (it was reloaded), and B lost its question'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String Y = 'tool_0_list_servers'
		List<Map> lostB = lostQuestion(['tool_7_list_clouds'])

		when: 'B calls Y, and loop A, with its own question, gets the same id a moment later'
		boolean restoredBefore = OpenRouterProvider.restoreLostQuestion(lostB, 4L)
		OpenRouterProvider.rememberQuestion([messages: lostB], response([[id: Y, type: 'function', function: [name: 'list_servers', arguments: '{}']]]), 4L)
		Map marked = new LinkedHashMap(remembered(Y))
		ask('List the AWS servers', [Y])
		List<Map> nextB = lostQuestion([Y])

		then: 'B left a marker without question on Y'
		!restoredBefore
		marked.question == null
		OpenRouterProvider.isAmbiguous(marked)

		and: "A's question on Y is a clash, and B gets nothing back"
		remembered(Y).question == null
		OpenRouterProvider.isAmbiguous(remembered(Y))
		!OpenRouterProvider.restoreLostQuestion(nextB, 4L)
		nextB*.role == ['system', 'assistant', 'tool']

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a restored request counts on the cost key of its tool calls only while they name one question"() {
		given: 'a restored request whose tool calls Y and Z are known'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String Y = 'tool_0_list_servers'
		String Z = 'tool_1_list_instances'
		long now = System.currentTimeMillis()
		String scope = OpenRouterProvider.questionScope(4L, [[role: 'system', content: 'You are Murph.']])
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.put(OpenRouterProvider.toolCallKey(scope, Y), [question: 'List the AWS servers', costKey: 'kA', updated: now])
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.put(OpenRouterProvider.toolCallKey(scope, Z), z + [updated: now])
		List<Map> restored = lostQuestion([Y, Z])
		restored.add(1, [role: 'user', content: OpenRouterProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'])

		expect: 'with Z marked or holding another question, neither cost key is used'
		!(OpenRouterProvider.questionKey([model: 'm', messages: restored], 4L) in ['kA', 'kB'])

		when: 'Z is no longer known'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.remove(OpenRouterProvider.toolCallKey(scope, Z))

		then: "Y's cost key is used"
		OpenRouterProvider.questionKey([model: 'm', messages: restored], 4L) == 'kA'

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()

		where:
		z << [[ambiguous: System.currentTimeMillis(), clashed: ['a', 'b'] as Set], [question: 'Which VMs are stopped?', costKey: 'kB']]
	}

	def "a third question on a marked key is a new clash and renews the mark"() {
		given:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		ask('List the AWS servers', [ID])
		ask('List the Azure servers', [ID])
		long earlier = System.currentTimeMillis() - 60000L
		remembered(ID).ambiguous = earlier

		when:
		ask('Which VMs are stopped?', [ID])

		then:
		(remembered(ID).ambiguous as long) > earlier
		remembered(ID).clashed.size() == 3
		!OpenRouterProvider.restoreLostQuestion(lostQuestion([ID]), 4L)

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "the same question on a key again only refreshes it, and the restore still works"() {
		given: 'the key holds the cost key of the conversation that stored the question first'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		ask('List the AWS servers', [ID])
		long earlier = System.currentTimeMillis() - 60000L
		remembered(ID).updated = earlier
		remembered(ID).costKey = 'first'

		when:
		ask('List the AWS servers', [ID])
		List<Map> lost = lostQuestion([ID])

		then:
		remembered(ID).ambiguous == null
		(remembered(ID).updated as long) > earlier
		remembered(ID).costKey == 'first'
		OpenRouterProvider.restoreLostQuestion(lost, 4L)
		lost[1].content == OpenRouterProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a different question on a key not written for 10 minutes replaces what it held, and is restored"() {
		given: 'an older question, or a marker nothing was stored on for the window'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'tool_0_list_servers'
		String scope = OpenRouterProvider.questionScope(4L, [[role: 'system', content: 'You are Murph.']])
		long old = System.currentTimeMillis() - OpenRouterProvider.QUESTION_CLASH_WINDOW_MS - 1000L
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.put(OpenRouterProvider.toolCallKey(scope, ID), held + [updated: old])

		when:
		boolean restoredBefore = OpenRouterProvider.restoreLostQuestion(lostQuestion([ID]), 4L)
		ask('List the Azure servers', [ID])
		List<Map> lost = lostQuestion([ID])

		then: 'the old question still came back on its own; the marker never hands one back'
		restoredBefore == (held.question != null)
		remembered(ID).ambiguous == null
		OpenRouterProvider.restoreLostQuestion(lost, 4L)
		lost[1].content == OpenRouterProvider.RESTORED_QUESTION_NOTE + 'List the Azure servers'

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()

		where:
		held << [[question: 'List the AWS servers', costKey: 'k'],
			[ambiguous: System.currentTimeMillis() - OpenRouterProvider.QUESTION_CLASH_WINDOW_MS - 1000L, clashed: ['a', 'b'] as Set]]
	}

	def "a request whose known tool calls hold different questions, or a marked one, gets no question back"() {
		given: 'a loop with two tool calls left, the first of them clean'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String Y = 'tool_0_list_servers'
		String Z = 'tool_1_list_instances'
		long now = System.currentTimeMillis()
		String scope = OpenRouterProvider.questionScope(4L, [[role: 'system', content: 'You are Murph.']])
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.put(OpenRouterProvider.toolCallKey(scope, Y), [question: 'Which VMs are stopped?', costKey: 'k', updated: now])
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.put(OpenRouterProvider.toolCallKey(scope, Z), z + [updated: now])
		List<Map> lost = lostQuestion([Y, Z])

		expect:
		OpenRouterProvider.restoreLostQuestion(lost, 4L) == expected
		lost*.role == ['system'] + (expected ? ['user'] : []) + ['assistant', 'tool', 'assistant', 'tool']

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()

		where:
		z                                                             || expected
		[question: 'List the AWS servers', costKey: 'k']              || false
		[ambiguous: System.currentTimeMillis(), clashed: ['a'] as Set] || false
		[question: 'Which VMs are stopped?', costKey: 'k']            || true
	}

	def "a loop refused its question marks its next tool calls, so the other loop's question cannot come back through them"() {
		given: 'loops A and B clashed on Y; A called Z with its question still in memory'
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
		String Y = 'tool_0_list_servers'
		String Z = 'tool_1_list_instances'
		String W = 'tool_2_get_instance'
		ask('List the AWS servers', [Y])
		ask('Which VMs are stopped?', [Y])
		ask('List the AWS servers', [Z])
		long clash = System.currentTimeMillis() - 300000L
		remembered(Y).ambiguous = clash

		when: 'B lost its question, is refused it through Y and calls Z without it'
		List<Map> refused = lostQuestion([Y])
		boolean restoredThroughY = OpenRouterProvider.restoreLostQuestion(refused, 4L)
		long refusedAt = System.currentTimeMillis()
		OpenRouterProvider.rememberQuestion([messages: refused], response([[id: Z, type: 'function', function: [name: 'list_instances', arguments: '{}']]]), 4L)

		and: 'the chat memory dropped the calls on Y; B calls W, then A does'
		List<Map> later = lostQuestion([Z])
		boolean restoredThroughZ = OpenRouterProvider.restoreLostQuestion(later, 4L)
		OpenRouterProvider.rememberQuestion([messages: later], response([[id: W, type: 'function', function: [name: 'get_instance', arguments: '{}']]]), 4L)
		long markedW = (remembered(W).ambiguous as long) - 60000L
		remembered(W).ambiguous = markedW
		ask('List the AWS servers', [W])
		List<Map> last = lostQuestion([W])

		then: 'Z holds a marker without question, and B gets nothing back through it'
		!restoredThroughY
		OpenRouterProvider.isAmbiguous(remembered(Z))
		remembered(Z).question == null
		!restoredThroughZ
		later*.role == ['system', 'assistant', 'tool']

		and: 'the mark B passed on starts at its own call, not at the clash on Y'
		(remembered(Z).ambiguous as long) >= refusedAt
		remembered(Y).ambiguous == clash

		and: "A's question on W is part of the clash, so it neither lifts nor renews the mark B left there"
		OpenRouterProvider.isAmbiguous(remembered(W))
		remembered(W).ambiguous == markedW
		remembered(W).question == null
		!OpenRouterProvider.restoreLostQuestion(last, 4L)
		last*.role == ['system', 'assistant', 'tool']

		cleanup:
		OpenRouterProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "the cost of a question is kept per integration"() {
		given:
		Map body = [model: 'm', messages: [[role: 'user', content: 'Clouds?']]]

		expect:
		OpenRouterProvider.questionKey(body, 4L) != OpenRouterProvider.questionKey(body, 5L)
		OpenRouterProvider.questionKey(body, 4L) == OpenRouterProvider.questionKey(body + [messages: body.messages + [[role: 'tool', tool_call_id: 'x', content: 'y']]], 4L)
	}

	def "required arguments, and false for a flag that defaults to true, are not dropped"() {
		given:
		OpenRouterApiService api = Stub()
		api.createChatCompletion(*_) >> [success: true, data: [choices: [[finish_reason: 'tool_calls', message: [content: null, tool_calls: [
			[id: 't1', type: 'function', function: [name: 'get_result_excerpt', arguments: '{"artifactId":"a1","offset":0,"query":""}']],
			[id: 't2', type: 'function', function: [name: 'list_servers', arguments: '{"agentInstalled":false,"managed":false,"name":""}']]
		]]]]]]
		provider.apiService = api
		AccountIntegration ai = configured([:])
		ai.servicePassword = 'sk-or-v1-test'
		List tools = [
			[type: 'function', function: [name: 'get_result_excerpt', parameters: [type: 'object', required: ['artifactId', 'offset'],
				properties: [artifactId: [type: 'string'], offset: [type: 'integer'], query: [type: 'string']]]]],
			[type: 'function', function: [name: 'list_servers', parameters: [type: 'object',
				properties: [agentInstalled: [type: 'boolean', default: true], managed: [type: 'boolean'], name: [type: 'string']]]]]
		]

		when:
		ServiceResponse<LlmChatResponse> response = provider.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'openai/gpt-5.4-mini', messages: [message('user', 'Page through it.')], options: [tools: tools]), [:])

		then:
		response.data.metadata.tool_calls*.function*.arguments == ['{"artifactId":"a1","offset":0}', '{"agentInstalled":false}']
	}

	def "a tool call the token limit cut off is dropped, and the complete ones still run"() {
		given:
		Map data = [choices: [[finish_reason: 'length', message: [role: 'assistant', content: '', tool_calls: [
			[id: 'c1', type: 'function', function: [name: 'list_clouds', arguments: '{}']],
			[id: 'c2', type: 'function', function: [name: 'list_servers', arguments: '{"name":"web']]
		]]]]]

		when:
		LlmChatResponse response = provider.parseChatResponse(data)

		then:
		response.finishReason == 'tool_calls'
		response.metadata.tool_calls*.id == ['c1']
		!response.metadata.truncated
	}

	def "with no complete tool call left, the answer says it was cut off"() {
		when:
		LlmChatResponse response = provider.parseChatResponse([choices: [[finish_reason: 'length', message: [role: 'assistant', content: '',
			tool_calls: [[id: 'c2', type: 'function', function: [name: 'list_servers', arguments: '{"name":"web']]]]]]])

		then:
		response.finishReason == 'length'
		!response.metadata.tool_calls
		response.message.content == '*Answer cut off at the output token limit.*'
	}

	def "a tool call the token limit cut off before its first argument character is dropped, not run with {}"() {
		given:
		List calls = [
			[id: 'c1', type: 'function', function: [name: 'list_clouds', arguments: '{}']],
			[id: 'c2', type: 'function', function: [name: 'list_servers', arguments: '']],
			[id: 'c3', type: 'function', function: [name: 'list_groups']],
			[id: 'c4', type: 'function', function: [name: 'list_networks', arguments: '[]']],
			[id: 'c5', type: 'function', function: [name: 'list_instances', arguments: [name: 'web']]]
		]

		when:
		LlmChatResponse cut = provider.parseChatResponse([choices: [[finish_reason: 'length', message: [role: 'assistant', content: '', tool_calls: calls]]]])

		then: 'only arguments that are a JSON object survive the cut'
		cut.finishReason == 'tool_calls'
		cut.metadata.tool_calls*.id == ['c1', 'c5']
		cut.metadata.tool_calls*.function*.arguments == ['{}', '{"name":"web"}']

		when: 'the same calls in a turn that was not cut off'
		LlmChatResponse whole = provider.parseChatResponse([choices: [[finish_reason: 'tool_calls', message: [role: 'assistant', content: '', tool_calls: calls]]]])

		then: 'a tool without arguments still runs with {}, as before'
		whole.metadata.tool_calls*.id == ['c1', 'c2', 'c3', 'c4', 'c5']
		whole.metadata.tool_calls*.function*.arguments == ['{}', '{}', '{}', '[]', '{"name":"web"}']
	}

	def "a U+FFFD in a tool result of the running question adds no note, so its cost key stays"() {
		given:
		List<LlmChatMessage> question = [message('system', 'You are Murph.'), message('user', 'Which servers run?')]
		List<LlmChatMessage> nextRound = question + [
			message('assistant', '', [tool_calls: [[id: 'call_AbCdEfGhIjKlMnOp', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]),
			message('tool', '{"name":"L\uFFFDuft"}', [tool_call_id: 'call_AbCdEfGhIjKlMnOp'])
		]

		when:
		Map first = provider.buildChatRequestBody(new LlmChatRequest(model: 'm', messages: question), integration, false)
		Map second = provider.buildChatRequestBody(new LlmChatRequest(model: 'm', messages: nextRound), integration, false)

		then:
		!second.messages.any { it.content == OpenRouterProvider.REPLACEMENT_CHARACTER_NOTE }
		OpenRouterProvider.questionKey(first) == OpenRouterProvider.questionKey(second)
	}

	def "the API key is sent without the spaces it was pasted with"() {
		given:
		AccountIntegration ai = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL)
		ai.credentialData = [password: '  sk-or-v1-abc\n']

		expect:
		provider.resolveApiKey(ai) == 'sk-or-v1-abc'
	}
}
