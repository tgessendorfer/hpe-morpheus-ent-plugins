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
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

import java.time.LocalDate

/**
 * Covers the form, validation, request and response handling, the cost footer and
 * the catalog filter.
 */
class OpenRouterProviderSpec extends Specification {

	OpenRouterProvider provider = new OpenRouterProvider(null, null)
	AccountIntegration integration = new AccountIntegration(serviceUrl: OpenRouterProvider.DEFAULT_API_URL)

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
		LlmChatRequest request = new LlmChatRequest(model: 'mistralai/ministral-8b-2512', messages: [message('user', 'hi')])

		when:
		ServiceResponse response = provider.generateResponse(new LlmIntegration(accountIntegration: ai), request, [:])

		then:
		!response.success
		response.msg.startsWith('API returned 403: Regional routing not enabled')
		response.msg.contains('Business or Enterprise plan')
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

		where:
		value << [null, '', '  ', ' , ']
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
		provider.optionTypes*.displayOrder == (0..10).toList()
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
}
