package com.morpheusdata.anthropic

import com.morpheusdata.core.util.HttpApiClient
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
import com.morpheusdata.anthropic.sync.LlmUsageSync
import spock.lang.Specification

/**
 * Covers the translation layer between the OpenAI-shaped conventions Morpheus
 * uses internally and the native Anthropic Messages API.
 */
class AnthropicProviderSpec extends Specification {

	AnthropicProvider provider = new AnthropicProvider(null, null)
	AccountIntegration integration = new AccountIntegration(serviceUrl: 'https://api.anthropic.com')

	private static LlmChatMessage message(String role, String content, Map metadata = null) {
		LlmChatMessage msg = new LlmChatMessage()
		msg.role = role
		msg.content = content
		msg.metadata = metadata
		return msg
	}

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
		AnthropicPlugin.DESCRIPTION.length() <= 255
		provider.description.length() <= 255
	}

	def "system messages are hoisted into the top level system field"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			model: 'claude-sonnet-4-6',
			messages: [
				message('system', 'You are a Morpheus operator.'),
				message('system', 'Always be terse.'),
				message('user', 'List my instances')
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then: 'all system turns are concatenated into the single system field'
		body.system[0].text == 'You are a Morpheus operator.\n\nAlways be terse.'
		body.messages.size() == 1
		body.messages[0].role == 'user'
		body.max_tokens == AnthropicProvider.DEFAULT_MAX_OUTPUT_TOKENS
	}

	def "assistant tool_calls become tool_use blocks with parsed input"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [
				message('user', 'How many VMs?'),
				message('assistant', 'Let me check.', [
					tool_calls: [[
						id      : 'toolu_01',
						type    : 'function',
						function: [name: 'list_instances', arguments: '{"max":25}']
					]]
				])
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)
		def assistant = body.messages[1]

		then:
		assistant.role == 'assistant'
		assistant.content[0].type == 'text'
		assistant.content[1].type == 'tool_use'
		assistant.content[1].id == 'toolu_01'
		assistant.content[1].name == 'list_instances'
		assistant.content[1].input == [max: 25]
	}

	def "consecutive tool results are merged into a single user message"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [
				message('user', 'Check both clouds'),
				message('assistant', null, [tool_calls: [
					[id: 'toolu_a', type: 'function', function: [name: 'list_clouds', arguments: '{}']],
					[id: 'toolu_b', type: 'function', function: [name: 'list_zones', arguments: '{}']]
				]]),
				message('tool', '{"clouds":2}', [tool_call_id: 'toolu_a']),
				message('tool', '{"zones":5}', [tool_call_id: 'toolu_b'])
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then:
		body.messages.size() == 3
		body.messages[2].role == 'user'
		body.messages[2].content.size() == 2
		body.messages[2].content*.type.every { it == 'tool_result' }
		body.messages[2].content*.tool_use_id == ['toolu_a', 'toolu_b']
	}

	def "OpenAI tool definitions are converted to the Anthropic schema"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [message('user', 'hi')],
			options: [
				tools      : [[
					type    : 'function',
					function: [
						name       : 'use_instances_tools',
						description: 'Load the instance tool group',
						parameters : [type: 'object', properties: [scope: [type: 'string']]]
					]
				]],
				tool_choice: 'auto'
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then:
		body.tools.size() == 1
		body.tools[0].name == 'use_instances_tools'
		body.tools[0].description == 'Load the instance tool group'
		body.tools[0].input_schema.type == 'object'
		body.tools[0].containsKey('function') == false
		body.tool_choice == [type: 'auto']
	}

	def "tool_choice required maps to any and a named tool maps to tool"() {
		expect:
		provider.convertToolChoice('required') == [type: 'any']
		provider.convertToolChoice('none') == [type: 'none']
		provider.convertToolChoice([type: 'function', function: [name: 'get_appliance_health']]) == [type: 'tool', name: 'get_appliance_health']
	}

	def "tool_use response blocks map back onto OpenAI-shaped tool_calls"() {
		given:
		Map apiResponse = [
			id         : 'msg_123',
			model      : 'claude-sonnet-4-6',
			role       : 'assistant',
			stop_reason: 'tool_use',
			content    : [
				[type: 'text', text: 'Checking your instances.'],
				[type: 'tool_use', id: 'toolu_99', name: 'list_instances', input: [max: 10]]
			],
			usage      : [input_tokens: 1200, output_tokens: 80]
		]

		when:
		LlmChatResponse response = provider.parseMessageResponse(apiResponse)

		then:
		response.id == 'msg_123'
		response.finishReason == 'tool_calls'
		response.message.content == 'Checking your instances.'
		response.metadata.tool_calls.size() == 1
		response.metadata.tool_calls[0].id == 'toolu_99'
		response.metadata.tool_calls[0].type == 'function'
		response.metadata.tool_calls[0].function.name == 'list_instances'
		response.metadata.tool_calls[0].function.arguments == '{"max":10}'
		response.tokenUsage.inputTokens == 1200
		response.tokenUsage.totalTokens == 1280
	}

	def "stop reasons map to OpenAI finish reasons"() {
		expect:
		provider.mapStopReason(anthropic) == openai

		where:
		anthropic       || openai
		'end_turn'      || 'stop'
		'max_tokens'    || 'length'
		'tool_use'      || 'tool_calls'
		'stop_sequence' || 'stop'
		null            || null
	}

	def "base url tolerates a full messages endpoint being pasted in"() {
		expect:
		provider.resolveBaseUrl(new AccountIntegration(serviceUrl: url)) == 'https://api.anthropic.com'

		where:
		url << [
			'https://api.anthropic.com',
			'https://api.anthropic.com/',
			'https://api.anthropic.com/v1',
			'https://api.anthropic.com/v1/messages'
		]
	}

	private static AccountIntegration configured(Map config) {
		AccountIntegration ai = new AccountIntegration(serviceUrl: 'https://api.anthropic.com')
		ai.setConfigMap(config)
		return ai
	}

	def "prompt caching marks the system prompt and the last tool by default"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [message('system', 'You are a Morpheus operator.'), message('user', 'hi')],
			options: [tools: [
				[type: 'function', function: [name: 'use_instances_tools', parameters: [type: 'object']]],
				[type: 'function', function: [name: 'use_clouds_tools', parameters: [type: 'object']]]
			]]
		)

		when: 'caching is left at its default (on)'
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then: 'the system prompt becomes a cacheable block'
		body.system instanceof List
		body.system[0].type == 'text'
		body.system[0].cache_control == [type: 'ephemeral']

		and: 'only the final tool carries the breakpoint, which caches the whole block'
		body.tools.size() == 2
		body.tools[0].cache_control == null
		body.tools[1].cache_control == [type: 'ephemeral']
	}

	def "prompt caching can be switched off"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [message('system', 'Be terse.'), message('user', 'hi')],
			options: [tools: [[type: 'function', function: [name: 'ping', parameters: [type: 'object']]]]]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, configured([promptCaching: 'off']), false)

		then:
		body.system == 'Be terse.'
		body.tools[0].cache_control == null
	}

	def "extended thinking adds the thinking block and drops sampling params"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [message('user', 'Plan the migration')],
			temperature: 0.7d,
			topP: 0.9d
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, configured([thinkingEnabled: 'on', thinkingBudgetTokens: '10000']), false)

		then:
		body.thinking == [type: 'enabled', budget_tokens: 10000]
		!body.containsKey('temperature')
		!body.containsKey('top_p')

		and: 'max_tokens is raised above the budget so an answer still fits'
		body.max_tokens > 10000
	}

	def "the thinking budget is clamped to the API minimum"() {
		expect:
		provider.resolveThinkingBudget(configured([thinkingBudgetTokens: '100'])) == AnthropicProvider.MIN_THINKING_BUDGET_TOKENS
		provider.resolveThinkingBudget(integration) == AnthropicProvider.DEFAULT_THINKING_BUDGET_TOKENS
	}

	def "the 1M beta header is only sent when enabled"() {
		expect:
		provider.resolveBetas(integration) == []
		provider.resolveBetas(configured([longContext: 'on'])) == [AnthropicApiService.LONG_CONTEXT_BETA]
	}

	def "the long context window is only reported for models that support it"() {
		expect:
		provider.estimateContextWindow('claude-sonnet-4-6', longContext) == window

		where:
		longContext || window
		false       || AnthropicProvider.STANDARD_CONTEXT_WINDOW
		true        || AnthropicProvider.LONG_CONTEXT_WINDOW
	}

	def "an older model keeps the standard window even with the beta enabled"() {
		expect:
		provider.estimateContextWindow('claude-3-5-haiku-latest', true) == AnthropicProvider.STANDARD_CONTEXT_WINDOW
	}

	def "a gateway model id with a vendor prefix and dotted version is sized like the native one"() {
		expect:
		provider.estimateContextWindow('anthropic/claude-sonnet-4.6', true) == AnthropicProvider.LONG_CONTEXT_WINDOW
		provider.estimateMaxOutputTokens('anthropic/claude-sonnet-4.6') == provider.estimateMaxOutputTokens('claude-sonnet-4-6')
	}

	def "the catalog keeps Claude models in both the Anthropic and the OpenRouter spelling"() {
		given: 'a models response mixing both spellings with the rest of a gateway catalog'
		Map apiResponse = [data: [
			[id: 'claude-sonnet-4-6', display_name: 'Claude Sonnet 4.6'],
			[id: 'anthropic/claude-haiku-4.5', name: 'Anthropic: Claude Haiku 4.5'],
			[id: 'anthropic/claude-haiku-4.5:batch', name: 'Anthropic: Claude Haiku 4.5 (batch)'],
			[id: 'openai/gpt-5', name: 'OpenAI: GPT-5']
		]]

		when:
		def models = provider.buildModelsFromApiResponse(null, apiResponse)

		then: 'ids stay as listed, since that is the spelling the endpoint expects back'
		models*.code == ['anthropic/claude-haiku-4.5', 'claude-sonnet-4-6']
		and: "OpenRouter's vendor prefix is dropped from the name"
		models*.name == ['Claude Haiku 4.5', 'Claude Sonnet 4.6']
	}

	def "an Anthropic-format listing from OpenRouter keeps its [1m] ids, drops the vendor prefix and reports the long window"() {
		when:
		def models = provider.buildModelsFromApiResponse(null, [data: [
			[id: 'anthropic/claude-sonnet-4.6[1m]', display_name: 'Anthropic: Claude Sonnet 4.6', type: 'model'],
			[id: 'anthropic/claude-fable-5.1:batch[1m]', display_name: 'Anthropic: Claude Fable 5.1 (batch)', type: 'model'],
			[id: 'anthropic/~anthropic/claude-sonnet-latest[1m]', display_name: 'Anthropic: Claude Sonnet Latest', type: 'model'],
			[id: 'anthropic/openai/gpt-5.5[1m]', display_name: 'OpenAI: GPT-5.5', type: 'model']
		]])

		then:
		models*.code == ['anthropic/claude-sonnet-4.6[1m]']
		models*.name == ['Claude Sonnet 4.6']
		models*.contextWindow == [AnthropicProvider.LONG_CONTEXT_WINDOW]
		provider.supportsDynamicFiltering('anthropic/claude-sonnet-4.6[1m]')
	}

	def "thinking blocks and cache usage are surfaced in the response"() {
		given:
		Map apiResponse = [
			id         : 'msg_9',
			model      : 'claude-sonnet-4-6',
			stop_reason: 'end_turn',
			content    : [
				[type: 'thinking', thinking: 'The user wants a count.'],
				[type: 'text', text: 'You have 12 instances.']
			],
			usage      : [input_tokens: 120, output_tokens: 40, cache_read_input_tokens: 18000, cache_creation_input_tokens: 0]
		]

		when:
		LlmChatResponse response = provider.parseMessageResponse(apiResponse)

		then:
		response.message.content == 'You have 12 instances.'
		response.metadata.thinking == 'The user wants a count.'
		response.metadata.cache_read_input_tokens == 18000
		response.tokenUsage.totalTokens == 18160
	}

	def "configured max output tokens override the default"() {
		given:
		AccountIntegration withConfig = configured([maxOutputTokens: '16384'])

		expect:
		provider.resolveDefaultMaxOutputTokens(withConfig) == 16384
		provider.resolveDefaultMaxOutputTokens(integration) == AnthropicProvider.DEFAULT_MAX_OUTPUT_TOKENS
	}

	def "the integration's max output tokens are a floor for what Morpheus asks for"() {
		given: 'Morpheus asks for 1000 output tokens on every chat request'
		LlmChatRequest request = new LlmChatRequest(model: 'claude-sonnet-5', maxOutputTokens: 1000,
			messages: [message('user', 'GA?')])
		AccountIntegration withConfig = configured([maxOutputTokens: '8192'])

		expect:
		provider.buildMessagesRequestBody(request, withConfig, false).max_tokens == 8192
		provider.buildMessagesRequestBody(request, integration, false).max_tokens == AnthropicProvider.DEFAULT_MAX_OUTPUT_TOKENS
		provider.resolveMaxTokens(32000, withConfig) == 32000
		provider.resolveMaxTokens(null, withConfig) == 8192
	}

	def "temperature and top_p are withheld by default"() {
		given: 'Morpheus supplies sampling parameters on every chat request'
		LlmChatRequest request = new LlmChatRequest(
			model: 'claude-sonnet-5',
			temperature: 0.7,
			topP: 0.9,
			messages: [message('user', 'How many clouds?')]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then: 'newer Claude models reject them with a 400, so they are not sent'
		!body.containsKey('temperature')
		!body.containsKey('top_p')
	}

	def "temperature and top_p are sent once the integration opts in"() {
		given:
		AccountIntegration withSampling = configured([samplingParams: 'on'])
		LlmChatRequest request = new LlmChatRequest(
			model: 'claude-sonnet-4-6',
			temperature: 0.7,
			topP: 0.9,
			messages: [message('user', 'How many clouds?')]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, withSampling, false)

		then:
		body.temperature == 0.7
		body.top_p == 0.9
	}

	def "the usage footer is appended to a final answer once enabled"() {
		given:
		AccountIntegration withFooter = configured([usageFooter: 'on'])
		LlmChatResponse response = provider.parseMessageResponse([
			role       : 'assistant',
			stop_reason: 'end_turn',
			content    : [[type: 'text', text: 'One cloud is configured.']],
			usage      : [input_tokens: 4198, output_tokens: 300, cache_read_input_tokens: 20181]
		])

		when:
		provider.appendUsageFooter(response, withFooter)

		then: 'italics only - the chat renderer escapes raw HTML, so no <sub> wrapper'
		response.message.content == 'One cloud is configured.\n\n*Tokens: 20,181 cached, 4,198 input, 300 output*'
		!response.message.content.contains('<')

		and: 'ASCII only - Morpheus replays this text to Anthropic as history and corrupts non-ASCII on the way'
		response.message.content.every { it.toCharacter() < 128 as char }
	}

	def "OpenRouter cost replaces the token line, summed over every request of the question"() {
		given: 'a question that took one tool round before the answer'
		AccountIntegration withFooter = configured([usageFooter: 'on'])
		Map firstRound = [model: 'anthropic/claude-sonnet-4.6', messages: [[role: 'user', content: 'Which servers run?']]]
		Map secondRound = [model: 'anthropic/claude-sonnet-4.6', messages: [
			[role: 'user', content: 'Which servers run?'],
			[role: 'assistant', content: [[type: 'tool_use', id: 't1', name: 'list_servers', input: [:]]]],
			[role: 'user', content: [[type: 'tool_result', tool_use_id: 't1', content: '[]']]]
		]]

		when:
		LlmChatResponse toolTurn = provider.trackQuestionCost(firstRound, provider.parseMessageResponse([
			stop_reason: 'tool_use',
			content    : [[type: 'tool_use', id: 't1', name: 'list_servers', input: [:]]],
			usage      : [input_tokens: 10, output_tokens: 5, cost: 0.0170345]
		]))
		LlmChatResponse answer = provider.trackQuestionCost(secondRound, provider.parseMessageResponse([
			stop_reason: 'end_turn',
			content    : [[type: 'text', text: 'None.']],
			usage      : [input_tokens: 20, output_tokens: 3, cost: 0.0013922]
		]))
		provider.appendUsageFooter(answer, withFooter)

		then:
		toolTurn.metadata.question_requests == 1
		answer.message.content == 'None.\n\n*Cost: $0.0184 (2 requests)*'
		AnthropicProvider.stripUsageFooter(answer.message.content) == 'None.'
	}

	def "the cost line follows the language of the answer"() {
		given:
		AccountIntegration withFooter = configured([usageFooter: 'on'])
		LlmChatResponse answer = provider.trackQuestionCost([model: 'm', messages: [[role: 'user', content: text]]], provider.parseMessageResponse([
			stop_reason: 'end_turn', content: [[type: 'text', text: text]], usage: [input_tokens: 5, output_tokens: 2, cost: 0.0021]
		]))

		when:
		provider.appendUsageFooter(answer, withFooter)

		then:
		answer.message.content == "${text}\n\n*${label}: \$0.0021*"
		AnthropicProvider.stripUsageFooter(answer.message.content) == text

		where:
		text                                                             || label
		'Es gibt keine Instanzen, und alle 4 Server laufen.'             || 'Kosten'
		'There are no instances, and all 4 servers are running.'         || 'Cost'
		'Alle Server laufen: web01, db01 und app01 sind aktiv.'          || 'Kosten'
		'Wszystkie 4 serwery działają, nie ma żadnych instancji.'        || 'Koszt'
	}

	def "request counts take the plural form of the answer's language"() {
		expect:
		AnthropicProvider.requestCount(count, language) == text

		where:
		count | language || text
		5     | 'en'     || '5 requests'
		5     | 'de'     || '5 Anfragen'
		2     | 'pl'     || '2 zapytania'
		5     | 'pl'     || '5 zapytań'
		12    | 'pl'     || '12 zapytań'
		22    | 'pl'     || '22 zapytania'
	}

	def "a single request that reports no cost keeps the token line"() {
		given:
		AccountIntegration withFooter = configured([usageFooter: 'on'])
		LlmChatResponse answer = provider.trackQuestionCost([messages: [[role: 'user', content: 'Hi']]], provider.parseMessageResponse([
			stop_reason: 'end_turn', content: [[type: 'text', text: 'Hello.']], usage: [input_tokens: 7, output_tokens: 2]
		]))

		when:
		provider.appendUsageFooter(answer, withFooter)

		then:
		answer.message.content == 'Hello.\n\n*Tokens: 7 input, 2 output*'
	}

	def "decimal usage such as cost adds up across paused segments"() {
		when:
		Map merged = provider.mergeMessageSegments([
			[content: [], usage: [input_tokens: 1, cost: 0.01]],
			[content: [], usage: [input_tokens: 2, cost: 0.0025]]
		])

		then:
		merged.usage.input_tokens == 3
		merged.usage.cost == 0.0125
	}

	def "the usage footer stays off by default"() {
		given:
		LlmChatResponse response = provider.parseMessageResponse([
			role       : 'assistant',
			stop_reason: 'end_turn',
			content    : [[type: 'text', text: 'One cloud is configured.']],
			usage      : [input_tokens: 4198, output_tokens: 300]
		])

		when:
		provider.appendUsageFooter(response, integration)

		then:
		response.message.content == 'One cloud is configured.'
	}

	def "the usage footer is withheld from tool-call turns"() {
		given: 'a turn that ends in tool_use is replayed to the model as history'
		AccountIntegration withFooter = configured([usageFooter: 'on'])
		LlmChatResponse response = provider.parseMessageResponse([
			role       : 'assistant',
			stop_reason: 'tool_use',
			content    : [
				[type: 'text', text: 'Checking.'],
				[type: 'tool_use', id: 'toolu_1', name: 'list_clouds', input: [:]]
			],
			usage      : [input_tokens: 635, output_tokens: 28]
		])

		when:
		provider.appendUsageFooter(response, withFooter)

		then: 'a footer here would be billed back as context on every later turn'
		response.finishReason == 'tool_calls'
		response.message.content == 'Checking.'
	}

	def "replacement characters are counted across nested message content"() {
		expect:
		AnthropicProvider.countReplacementChars([[content: 'L�uft'], [content: [[text: '��']]]]) == 3
		AnthropicProvider.countReplacementChars([[content: 'Läuft']]) == 0
	}

	def "history that lost characters to U+FFFD gets a note telling the model not to copy them"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			model: 'anthropic/claude-sonnet-4.6[1m]',
			messages: [
				message('system', 'You are a Morpheus operator.'),
				message('user', 'Which servers run?'),
				message('assistant', 'Status: L�uft'),
				message('user', 'And VMs?')
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then: 'the note sits after the cached system prompt, so the cached prefix stays byte-identical'
		body.system[0].text == 'You are a Morpheus operator.'
		body.system[0].cache_control
		body.system[-1].text == AnthropicProvider.REPLACEMENT_CHARACTER_NOTE
		!body.system[-1].cache_control
	}

	def "clean history gets no replacement-character note"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			model: 'claude-sonnet-5',
			messages: [message('system', 'You are a Morpheus operator.'), message('user', 'Which servers run?')]
		)

		expect:
		provider.buildMessagesRequestBody(request, integration, false).system*.text == ['You are a Morpheus operator.']
	}

	def "usage footers are stripped from answers replayed as history"() {
		given: 'an answer with the plugin footer, and a later one where the model imitated it above the real one'
		LlmChatRequest request = new LlmChatRequest(
			model: 'anthropic/claude-haiku-4.5',
			messages: [
				message('user', 'How many instances are there?'),
				message('assistant', 'There are 0 instances.\n\n*Tokens: 13,229 cached, 1,405 input, 61 output*'),
				message('user', 'How many are unmanaged?'),
				message('assistant', 'There are 0 unmanaged instances.\n\n*Tokens: 13,229 cached, 1,534 input, 69 output*\n\n*Tokens: 13,229 cached, 1,747 input, 91 output*'),
				message('user', 'And stopped ones?')
			]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then: 'the model never sees a footer it could learn to write itself'
		body.messages*.content == [
			'How many instances are there?',
			'There are 0 instances.',
			'How many are unmanaged?',
			'There are 0 unmanaged instances.',
			'And stopped ones?'
		]
	}

	def "extended thinking still wins over an explicit sampling opt-in"() {
		given: 'the Messages API rejects sampling parameters alongside thinking'
		AccountIntegration thinkingAndSampling = configured([thinkingEnabled: 'on', samplingParams: 'on'])
		LlmChatRequest request = new LlmChatRequest(
			model: 'claude-sonnet-4-6',
			temperature: 0.7,
			messages: [message('user', 'How many clouds?')]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, thinkingAndSampling, false)

		then:
		body.thinking.type == 'enabled'
		!body.containsKey('temperature')
	}

	// ------------------------------------------------------------------
	// Network proxy
	// ------------------------------------------------------------------

	def "no proxy is applied when the integration has not selected one"() {
		expect:
		provider.resolveNetworkProxy(integration) == null
		provider.buildClientOpts(integration, [clientScopeKey: 'chat-1']) == [clientScopeKey: 'chat-1']
	}

	def "a selected proxy is ignored while the checkbox is off"() {
		given: 'the dropdown offers no empty entry on 9.0.1, so the tick is the only way back'
		NetworkProxy proxy = new NetworkProxy(name: 'egress-emea', proxyHost: 'proxy.example.com')
		AnthropicProvider withContext = providerWithProxyLookup([7L: proxy])

		when: 'a proxy is still selected, but the box has been unticked'
		Map clientOpts = withContext.buildClientOpts(configured([networkProxy: '7']), [:])

		then:
		!clientOpts.containsKey(AnthropicApiService.NETWORK_PROXY_KEY)

		and: 'and it comes straight back when ticked again'
		withContext.buildClientOpts(configured([useNetworkProxy: 'on', networkProxy: '7']), [:])
			.get(AnthropicApiService.NETWORK_PROXY_KEY).is(proxy)
	}

	def "the selected proxy is looked up and passed to the api service"() {
		given: 'Morpheus stores the selection as the proxy id'
		NetworkProxy proxy = new NetworkProxy(name: 'egress-emea', proxyHost: 'proxy.example.com', proxyPort: 3128)
		AnthropicProvider withContext = providerWithProxyLookup([7L: proxy])

		when:
		Map clientOpts = withContext.buildClientOpts(
			configured([useNetworkProxy: 'on', networkProxy: '7']), [clientScopeKey: 'chat-1'])

		then: 'the caller options survive alongside the proxy'
		clientOpts.clientScopeKey == 'chat-1'
		clientOpts[AnthropicApiService.NETWORK_PROXY_KEY].is(proxy)
	}

	def "a proxy that has been deleted falls back to a direct connection"() {
		given: 'the id still on the integration, but nothing behind it'
		AnthropicProvider withContext = providerWithProxyLookup([:])

		when:
		Map clientOpts = withContext.buildClientOpts(configured([useNetworkProxy: 'on', networkProxy: '7']), [:])

		then: 'failing the request would be worse than the direct call it would have made anyway'
		!clientOpts.containsKey(AnthropicApiService.NETWORK_PROXY_KEY)
	}

	def "a lookup failure does not take the chat down with it"() {
		given:
		AnthropicProvider exploding = new AnthropicProvider(null, null) {
			@Override
			protected NetworkProxy resolveNetworkProxy(AccountIntegration accountIntegration) {
				// Exercise the real guard rather than the stub.
				return super.resolveNetworkProxy(accountIntegration)
			}
		}

		when: 'morpheusContext is null, as it is in any unit-test construction'
		Map clientOpts = exploding.buildClientOpts(configured([useNetworkProxy: 'on', networkProxy: '7']), [:])

		then:
		noExceptionThrown()
		!clientOpts.containsKey(AnthropicApiService.NETWORK_PROXY_KEY)
	}

	def "the proxy is offered as a select backed by the proxy list"() {
		when:
		OptionType proxyOption = provider.optionTypes.find { it.fieldName == 'networkProxy' }

		then:
		proxyOption.inputType == OptionType.InputType.SELECT
		proxyOption.optionSource == 'networkProxies'
		proxyOption.fieldContext == 'config'
		!proxyOption.required

		and: 'a direct connection stays selectable - noSelection alone renders no empty entry'
		proxyOption.noSelection == 'No Proxy'
		proxyOption.noBlank == false
	}

	def "the api service applies the proxy to the client and clears it again"() {
		given: 'a proxy is a property of the client, not of one request'
		AnthropicApiService service = new AnthropicApiService()
		HttpApiClient client = new HttpApiClient()
		NetworkProxy proxy = new NetworkProxy(name: 'egress-emea', proxyHost: 'proxy.example.com')

		when:
		service.applyNetworkProxy(client, [(AnthropicApiService.NETWORK_PROXY_KEY): proxy])

		then:
		client.networkProxy.is(proxy)

		when: 'the integration is later switched back to a direct connection'
		service.applyNetworkProxy(client, [:])

		then: 'a pooled client outlives the call, so this has to be cleared too'
		client.networkProxy == null
	}

	/**
	 * A provider whose appliance lookup answers from a map. Only the lookup is
	 * replaced, so the checkbox gate and id handling around it are the real ones.
	 */
	private static AnthropicProvider providerWithProxyLookup(Map<Long, NetworkProxy> proxies) {
		return new AnthropicProvider(null, null) {
			@Override
			protected NetworkProxy loadNetworkProxy(Long proxyId) {
				return proxies[proxyId]
			}
		}
	}

	// ------------------------------------------------------------------
	// Server-side web tools
	// ------------------------------------------------------------------

	def "no server tools are sent unless the integration opts in"() {
		given:
		LlmChatRequest request = new LlmChatRequest(model: 'claude-sonnet-5', messages: [message('user', 'hi')])

		when:
		Map body = provider.buildMessagesRequestBody(request, integration, false)

		then:
		!body.containsKey('tools')
	}

	def "web search adds both server tools ahead of the MCP catalog"() {
		given:
		AccountIntegration withSearch = configured([webSearch: 'on'])
		LlmChatRequest request = new LlmChatRequest(
			model: 'claude-sonnet-5',
			messages: [message('system', 'You are a Morpheus operator.'), message('user', 'What is the current GA release?')],
			options: [tools: [
				[type: 'function', function: [name: 'use_instances_tools', parameters: [type: 'object']]],
				[type: 'function', function: [name: 'use_clouds_tools', parameters: [type: 'object']]]
			]]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, withSearch, false)

		then: 'search and fetch are paired - fetch alone can only read URLs already in the conversation'
		body.tools.size() == 4
		body.tools[0].name == 'web_search'
		body.tools[1].name == 'web_fetch'
		body.tools[1].citations == [enabled: true]

		and: 'a default cap on searches, since a looping agent has no other ceiling'
		body.tools[0].max_uses == AnthropicProvider.DEFAULT_WEB_SEARCH_MAX_USES

		and: 'the breakpoint stays on the last MCP tool, so the server tools are inside the cached prefix'
		body.tools[0].cache_control == null
		body.tools[2].cache_control == null
		body.tools[3].name == 'use_clouds_tools'
		body.tools[3].cache_control == [type: 'ephemeral']
	}

	def "web search adds today's date after the cache breakpoint, not inside it"() {
		given: 'a model that does not know the date calls a past release "the future"'
		AccountIntegration withSearch = configured([webSearch: 'on'])
		LlmChatRequest request = new LlmChatRequest(
			model: 'claude-sonnet-5',
			messages: [message('system', 'You are a Morpheus operator.'), message('user', 'Is 9.0.2 out?')]
		)

		when:
		Map body = provider.buildMessagesRequestBody(request, withSearch, false)

		then: 'the agent prompt keeps the breakpoint and stays byte-identical between turns'
		body.system.size() == 2
		body.system[0].text == 'You are a Morpheus operator.'
		body.system[0].cache_control == [type: 'ephemeral']

		and: 'the volatile date sits after it, so it costs nothing in cache terms'
		body.system[1].cache_control == null
		body.system[1].text.startsWith("Today's date is ")
		body.system[1].text.contains(java.time.LocalDate.now().year.toString())
	}

	def "the date note is withheld unless web search is on"() {
		given:
		LlmChatRequest request = new LlmChatRequest(
			messages: [message('system', 'Be terse.'), message('user', 'hi')])

		expect: 'the plain-string system field is preserved when nothing needs a block'
		provider.buildMessagesRequestBody(request, configured([promptCaching: 'off']), false)
			.system == 'Be terse.'

		and:
		provider.buildMessagesRequestBody(request, integration, false).system.size() == 1
	}

	def "by default the web tools are called directly, without code execution"() {
		expect: 'the basic variants even on a model that could filter - code execution slows MCP agents down'
		provider.buildServerTools(configured([webSearch: 'on']), 'claude-sonnet-5')*.type ==
			[AnthropicProvider.WEB_SEARCH_TOOL_TYPE_BASIC, AnthropicProvider.WEB_FETCH_TOOL_TYPE_BASIC]
	}

	def "with code filtering opted in, the tool version follows the model, because filtering needs 4.6 or newer"() {
		expect:
		provider.buildServerTools(configured([webSearch: 'on', webSearchCodeFiltering: 'on']), model)*.type == types

		where:
		model                 || types
		'claude-sonnet-5'     || [AnthropicProvider.WEB_SEARCH_TOOL_TYPE, AnthropicProvider.WEB_FETCH_TOOL_TYPE]
		'claude-opus-4-8'     || [AnthropicProvider.WEB_SEARCH_TOOL_TYPE, AnthropicProvider.WEB_FETCH_TOOL_TYPE]
		'claude-sonnet-4-6'   || [AnthropicProvider.WEB_SEARCH_TOOL_TYPE, AnthropicProvider.WEB_FETCH_TOOL_TYPE]
		// Older than 4.6 despite the family number, and a 400 with the dated variant.
		'claude-haiku-4-5'    || [AnthropicProvider.WEB_SEARCH_TOOL_TYPE_BASIC, AnthropicProvider.WEB_FETCH_TOOL_TYPE_BASIC]
		'claude-sonnet-4-5'   || [AnthropicProvider.WEB_SEARCH_TOOL_TYPE_BASIC, AnthropicProvider.WEB_FETCH_TOOL_TYPE_BASIC]
		// OpenRouter spells the same models with a vendor prefix and a dotted version.
		'anthropic/claude-sonnet-4.6' || [AnthropicProvider.WEB_SEARCH_TOOL_TYPE, AnthropicProvider.WEB_FETCH_TOOL_TYPE]
		'anthropic/claude-haiku-4.5'  || [AnthropicProvider.WEB_SEARCH_TOOL_TYPE_BASIC, AnthropicProvider.WEB_FETCH_TOOL_TYPE_BASIC]
	}

	def "an allow list is normalised to the bare hostnames the API accepts"() {
		when:
		List<Map> tools = provider.buildServerTools(
			configured([webSearch: 'on', webSearchAllowedDomains: 'https://docs.morpheusdata.com/, community.hpe.com , support.hpe.com/x']), 'claude-sonnet-5')

		then:
		tools[0].allowed_domains == ['docs.morpheusdata.com', 'community.hpe.com', 'support.hpe.com/x']
		tools[1].allowed_domains == tools[0].allowed_domains
	}

	def "a blank max uses means the default and 0 means no cap"() {
		expect:
		AnthropicProvider.DEFAULT_WEB_SEARCH_MAX_USES == 3
		provider.resolveWebSearchMaxUses(configured([webSearch: 'on'])) == AnthropicProvider.DEFAULT_WEB_SEARCH_MAX_USES
		provider.resolveWebSearchMaxUses(configured([webSearch: 'on', webSearchMaxUses: '12'])) == 12
		provider.resolveWebSearchMaxUses(configured([webSearch: 'on', webSearchMaxUses: '0'])) == null
		!provider.buildServerTools(configured([webSearch: 'on', webSearchMaxUses: '0']), 'claude-sonnet-5')[0].containsKey('max_uses')
	}

	def "server tool blocks are ignored rather than replayed as MCP tool calls"() {
		when: 'Anthropic ran the search itself, so none of this needs executing'
		LlmChatResponse response = provider.parseMessageResponse([
			role       : 'assistant',
			stop_reason: 'end_turn',
			content    : [
				[type: 'server_tool_use', id: 'srvtoolu_1', name: 'web_search', input: [query: 'morpheus 9 ga']],
				[type: 'web_search_tool_result', tool_use_id: 'srvtoolu_1', content: [
					[type: 'web_search_result', url: 'https://example.com/a', title: 'A', encrypted_content: 'Eqgf...']
				]],
				[type: 'text', text: '9.0.1 is current.']
			]
		])

		then:
		response.finishReason == 'stop'
		response.message.content == '9.0.1 is current.'
		response.metadata.tool_calls == null
	}

	def "cited sources are appended to a final answer"() {
		when:
		LlmChatResponse response = provider.parseMessageResponse([
			role       : 'assistant',
			stop_reason: 'end_turn',
			content    : [
				[type: 'text', text: 'The current release is 9.0.1.', citations: [
					[type: 'web_search_result_location', url: 'https://community.hpe.com/post', title: 'HPE Morpheus Software 9.0'],
					[type: 'web_search_result_location', url: 'https://community.hpe.com/post', title: 'duplicate, dropped']
				]],
				[type: 'web_fetch_tool_result', tool_use_id: 'srvtoolu_2', content: [
					type   : 'web_fetch_result',
					url    : 'https://docs.morpheusdata.com/notes',
					content: [type: 'document', title: 'Release Notes — 9.0']
				]]
			]
		])
		provider.appendSourceList(response)

		then: 'plain text, because the chat renderer leaves [label](url) as literal characters'
		response.message.content == 'The current release is 9.0.1.\n\n**Sources**\n\n' +
			'HPE Morpheus Software 9.0 - https://community.hpe.com/post\n\n' +
			'Release Notes  9.0 - https://docs.morpheusdata.com/notes'
	}

	def "a fetched page with no title borrows one from the search results"() {
		when: 'the fetch result carried no document title, as HPE pages often do not'
		LlmChatResponse response = provider.parseMessageResponse([
			role       : 'assistant',
			stop_reason: 'end_turn',
			content    : [
				[type: 'web_search_tool_result', tool_use_id: 'srvtoolu_1', content: [
					[type: 'web_search_result', url: 'https://example.com/post',
					 title: 'Morpheus 9.0 - Take Back Control']
				]],
				[type: 'web_fetch_tool_result', tool_use_id: 'srvtoolu_2', content: [
					type: 'web_fetch_result', url: 'https://example.com/post',
					content: [type: 'document']
				]],
				[type: 'text', text: 'Answer.']
			]
		])
		provider.appendSourceList(response)

		then: 'the title is used rather than falling back to the bare hostname'
		response.message.content.endsWith(
			'Morpheus 9.0 - Take Back Control - https://example.com/post')
	}

	def "a long title is shortened but the URL never is"() {
		given:
		String longUrl = 'https://community.hpe.com/t5/the-cloud-experience-everywhere/' + ('x' * 120)
		LlmChatResponse response = provider.parseMessageResponse([
			role       : 'assistant',
			stop_reason: 'end_turn',
			content    : [[type: 'text', text: 'Answer.', citations: [[url: longUrl, title: 'T' * 200]]]]
		])

		when:
		provider.appendSourceList(response)

		then: 'a shortened URL is a broken link'
		response.message.content.endsWith(('T' * 87) + '... - ' + longUrl)
	}

	def "a source with no usable title falls back to the host"() {
		given:
		LlmChatResponse response = provider.parseMessageResponse([
			role       : 'assistant',
			stop_reason: 'end_turn',
			content    : [[type: 'text', text: 'Answer.', citations: [[url: 'https://docs.morpheusdata.com/notes', title: '—']]]]
		])

		when:
		provider.appendSourceList(response)

		then:
		response.message.content.endsWith(
			'docs.morpheusdata.com - https://docs.morpheusdata.com/notes')
	}

	def "sources are withheld from tool-call turns"() {
		when: 'the same reasoning as the token footer - this turn is replayed as history'
		LlmChatResponse response = provider.parseMessageResponse([
			role       : 'assistant',
			stop_reason: 'tool_use',
			content    : [
				[type: 'text', text: 'Checking.', citations: [[url: 'https://example.com', title: 'X']]],
				[type: 'tool_use', id: 'toolu_1', name: 'list_clouds', input: [:]]
			]
		])
		provider.appendSourceList(response)

		then:
		response.message.content == 'Checking.'
	}

	def "a web-search turn is described by stop reason, block sequence, cache read and container"() {
		expect:
		AnthropicProvider.hasWebSearchTools([tools: [[type: 'web_search_20260318', name: 'web_search'], [name: 'list_servers']]])
		!AnthropicProvider.hasWebSearchTools([tools: [[name: 'list_servers', input_schema: [:]]]])
		AnthropicProvider.describeTurn([
			stop_reason: 'pause_turn',
			content    : [[type: 'text', text: 'Checking.'], [type: 'server_tool_use', name: 'code_execution'], [type: 'tool_use', name: 'list_servers']],
			usage      : [cache_read_input_tokens: 33228, output_tokens: 57],
			container  : [id: 'container_011', expires_at: '2026-09-14T12:00:00Z']
		]) == 'stop_reason=pause_turn blocks=[text, server_tool_use:code_execution, tool_use:list_servers] read=33228 output=57 container=container_011'
	}

	def "a paused turn is resumed and the segments are folded into one answer"() {
		given: 'the server-side tool loop hit its iteration limit mid answer'
		List<Map> sent = []
		Closure<Map> call = { Map body ->
			sent << body
			return sent.size() == 1 ?
				[success: true, data: [
					stop_reason: 'pause_turn',
					content    : [[type: 'text', text: 'Searching for the '],
								  [type: 'server_tool_use', id: 'srvtoolu_1', name: 'web_search', input: [query: 'ga']]],
					usage      : [input_tokens: 100, output_tokens: 20, service_tier: 'standard']
				]] :
				[success: true, data: [
					stop_reason: 'end_turn',
					content    : [[type: 'text', text: 'current release: 9.0.1.']],
					usage      : [input_tokens: 400, output_tokens: 30]
				]]
		}

		when:
		Map result = provider.runToCompletion([model: 'claude-sonnet-5', messages: [[role: 'user', content: 'GA?']]], call)
		LlmChatResponse response = provider.parseMessageResponse(result.data as Map)

		then: 'the paused turn goes back verbatim - no "continue" message is added'
		sent.size() == 2
		sent[1].messages.size() == 2
		sent[1].messages[1].role == 'assistant'
		sent[1].messages[1].content[1].type == 'server_tool_use'

		and: 'the caller sees one finished answer, and both billed segments in the totals'
		response.finishReason == 'stop'
		response.message.content == 'Searching for the current release: 9.0.1.'
		response.tokenUsage.inputTokens == 500
		response.tokenUsage.outputTokens == 50
	}

	def "a failed continuation returns the partial answer instead of nothing"() {
		given:
		int calls = 0
		Closure<Map> call = { Map body ->
			calls++
			return calls == 1 ?
				[success: true, data: [stop_reason: 'pause_turn', content: [[type: 'text', text: 'Half an answer.']]]] :
				[success: false, msg: 'Anthropic API returned 529: overloaded']
		}

		when:
		Map result = provider.runToCompletion([messages: []], call)

		then:
		result.success
		provider.parseMessageResponse(result.data as Map).message.content == 'Half an answer.'
	}

	def "a continuation that throws does not re-run the whole paid turn"() {
		given:
		int calls = 0
		Closure<Map> call = { Map body ->
			calls++
			if (calls == 1) {
				return [success: true, data: [stop_reason: 'pause_turn', content: [[type: 'text', text: 'Half an answer.']]]]
			}
			throw new RuntimeException('Connection reset')
		}

		when:
		Map result = provider.runToCompletion([messages: []], call)

		then: 'the caller\'s retry loop would pay for every search in the turn again'
		noExceptionThrown()
		result.success
		provider.parseMessageResponse(result.data as Map).message.content == 'Half an answer.'
	}

	def "resuming stops at the cap rather than looping forever"() {
		given:
		int calls = 0
		Closure<Map> call = { Map body ->
			calls++
			return [success: true, data: [stop_reason: 'pause_turn', content: [[type: 'text', text: "s${calls} ".toString()]]]]
		}

		when:
		Map result = provider.runToCompletion([messages: []], call)

		then:
		calls == AnthropicProvider.MAX_PAUSE_TURN_CONTINUATIONS + 1
		provider.parseMessageResponse(result.data as Map).message.content.startsWith('s1 s2 ')
	}

	// ------------------------------------------------------------------
	// Web fetch caps, empty answers, context overflow, usage probe (1.6.0)
	// ------------------------------------------------------------------

	def "web fetch gets its own max uses and a content token cap by default"() {
		when: 'an integration saved before the fields existed'
		List<Map> tools = provider.buildServerTools(configured([webSearch: 'on']), 'claude-sonnet-5')

		then:
		tools[0].max_uses == 3
		!tools[0].containsKey('max_content_tokens')
		tools[1].max_uses == AnthropicProvider.DEFAULT_WEB_FETCH_MAX_USES
		tools[1].max_content_tokens == AnthropicProvider.DEFAULT_WEB_FETCH_MAX_CONTENT_TOKENS
		tools[1].max_content_tokens == 25000
	}

	def "the fetch caps are configured independently of the search cap"() {
		when:
		Map body = provider.buildMessagesRequestBody(new LlmChatRequest(model: 'claude-sonnet-5', messages: [message('user', 'hi')]),
			configured([webSearch: 'on', webSearchMaxUses: '2', webFetchMaxUses: '1', webFetchMaxContentTokens: '8000',
						webSearchAllowedDomains: 'docs.morpheusdata.com']), false)

		then: 'both caps and the allow list reach the request body'
		body.tools[0].name == 'web_search'
		body.tools[0].max_uses == 2
		body.tools[1].name == 'web_fetch'
		body.tools[1].max_uses == 1
		body.tools[1].max_content_tokens == 8000
		body.tools[1].allowed_domains == ['docs.morpheusdata.com']
		AnthropicApiService.toAsciiJson(body).contains('"max_content_tokens":8000')
	}

	def "0 removes a fetch cap, and an unreadable value falls back to the default"() {
		when:
		Map fetch = provider.buildServerTools(configured([webSearch: 'on', webFetchMaxUses: '0', webFetchMaxContentTokens: '0']), 'claude-sonnet-5')[1]

		then:
		!fetch.containsKey('max_uses')
		!fetch.containsKey('max_content_tokens')
		provider.resolveWebFetchMaxContentTokens(configured([webFetchMaxContentTokens: 'lots'])) == 25000
	}

	private static Map serverToolsOnlyTurn(String stopReason = 'end_turn') {
		return [
			id         : 'msg_empty',
			role       : 'assistant',
			stop_reason: stopReason,
			content    : [
				[type: 'server_tool_use', id: 'srvtoolu_1', name: 'web_search', input: [query: 'morpheus 9 release notes']],
				[type: 'web_search_tool_result', tool_use_id: 'srvtoolu_1', content: [
					[type: 'web_search_result', url: 'https://example.com/notes', title: 'Release Notes 9.0', encrypted_content: 'Eqgf...']
				]],
				[type: 'server_tool_use', id: 'srvtoolu_2', name: 'web_fetch', input: [url: 'https://example.com/notes']],
				[type: 'web_fetch_tool_result', tool_use_id: 'srvtoolu_2', content: [
					type: 'web_fetch_result', url: 'https://example.com/notes', content: [type: 'document', title: 'Release Notes 9.0']
				]]
			],
			usage      : [input_tokens: 500, output_tokens: 300, cache_read_input_tokens: 1000,
						  server_tool_use: [web_search_requests: 1, web_fetch_requests: 1]]
		]
	}

	def "a turn with only server tool blocks gets one request for the final answer, without tools"() {
		given:
		List<Map> sent = []
		Closure<Map> call = { Map body ->
			sent << body
			return sent.size() == 1 ? [success: true, data: serverToolsOnlyTurn()] :
				[success: true, data: [stop_reason: 'end_turn', content: [[type: 'text', text: 'The current release is 9.0.2.']],
									   usage: [input_tokens: 700, output_tokens: 40]]]
		}
		Map request = [model: 'claude-sonnet-5', max_tokens: 1000,
					   tools: [[type: 'web_search_20250305', name: 'web_search'], [name: 'list_servers', input_schema: [type: 'object']]],
					   messages: [[role: 'user', content: 'Which release is current?']]]

		when:
		Map result = provider.runToCompletion(request, call)
		LlmChatResponse response = provider.ensureAnswerText(provider.parseMessageResponse(result.data as Map), result.data as Map)

		then: 'the gathered turn goes back verbatim, followed by the request for an answer'
		sent.size() == 2
		sent[1].tool_choice == [type: 'none']
		sent[1].tools == request.tools
		sent[1].messages.size() == 3
		sent[1].messages[1].role == 'assistant'
		sent[1].messages[1].content == serverToolsOnlyTurn().content
		sent[1].messages[2] == [role: 'user', content: AnthropicProvider.FINAL_ANSWER_PROMPT]

		and: 'the original request is left untouched'
		!request.containsKey('tool_choice')
		request.messages.size() == 1

		and: 'the caller sees the answer, with both requests in the totals'
		response.message.content == 'The current release is 9.0.2.'
		response.tokenUsage.inputTokens == 1200
		response.tokenUsage.outputTokens == 340
	}

	def "an answer that stays empty becomes an explanation with the pages found, never an empty string"() {
		given:
		int calls = 0
		Closure<Map> call = { Map body ->
			calls++
			return calls == 1 ? [success: true, data: serverToolsOnlyTurn()] :
				[success: true, data: [stop_reason: 'end_turn', content: []]]
		}

		when:
		Map result = provider.runToCompletion([model: 'claude-sonnet-5', messages: [[role: 'user', content: 'GA?']]], call)
		LlmChatResponse response = provider.appendSourceList(
			provider.ensureAnswerText(provider.parseMessageResponse(result.data as Map), result.data as Map))

		then: 'one extra request only'
		calls == 2

		and:
		response.message.content.startsWith('The model ended its turn without writing an answer (stop_reason: end_turn).')
		response.message.content.contains('It ran 2 web search or fetch call(s)')
		response.message.content.contains('Release Notes 9.0 - https://example.com/notes')

		and: 'listed once, not again as a Sources block, and ASCII only for the replayed history'
		!response.message.content.contains('**Sources**')
		response.message.content.every { it.toCharacter() < 128 as char }
	}

	def "a failed final-answer request still ends in an explanation"() {
		given:
		int calls = 0
		Closure<Map> call = { Map body ->
			calls++
			if (calls == 1) {
				return [success: true, data: serverToolsOnlyTurn()]
			}
			throw new RuntimeException('Connection reset')
		}

		when:
		Map result = provider.runToCompletion([messages: [[role: 'user', content: 'GA?']]], call)
		LlmChatResponse response = provider.ensureAnswerText(provider.parseMessageResponse(result.data as Map), result.data as Map)

		then:
		noExceptionThrown()
		result.success
		response.message.content.contains('stop_reason: end_turn')
	}

	def "max_tokens without text asks for the answer, dropping a search call that never got its result"() {
		given:
		List<Map> sent = []
		Map truncated = serverToolsOnlyTurn('max_tokens')
		truncated.content = truncated.content + [[type: 'server_tool_use', id: 'srvtoolu_3', name: 'web_search', input: [query: 'more']]]
		Closure<Map> call = { Map body ->
			sent << body
			return sent.size() == 1 ? [success: true, data: truncated] :
				[success: true, data: [stop_reason: 'max_tokens', content: []]]
		}

		when:
		Map result = provider.runToCompletion([model: 'claude-sonnet-5', max_tokens: 1000, messages: [[role: 'user', content: 'GA?']]], call)
		LlmChatResponse response = provider.ensureAnswerText(provider.parseMessageResponse(result.data as Map), result.data as Map)

		then:
		sent.size() == 2
		sent[1].messages[1].content*.id.findAll { it } == ['srvtoolu_1', 'srvtoolu_2']

		and: 'no tools were declared, so no tool_choice either'
		!sent[1].containsKey('tool_choice')

		and:
		response.finishReason == 'length'
		response.message.content.startsWith('The model reached its output limit (stop_reason: max_tokens)')
	}

	def "a refusal is not asked again, and is explained"() {
		given:
		int calls = 0
		Closure<Map> call = { Map body ->
			calls++
			return [success: true, data: [stop_reason: 'refusal', content: []]]
		}

		when:
		Map result = provider.runToCompletion([messages: [[role: 'user', content: 'x']]], call)
		LlmChatResponse response = provider.ensureAnswerText(provider.parseMessageResponse(result.data as Map), result.data as Map)

		then:
		calls == 1
		response.finishReason == 'content_filter'
		response.message.content == 'The model declined to answer this request (stop_reason: refusal).'
	}

	def "a tool-call turn without text is left to Morpheus"() {
		given:
		int calls = 0
		Closure<Map> call = { Map body ->
			calls++
			return [success: true, data: [stop_reason: 'tool_use', content: [[type: 'tool_use', id: 't1', name: 'list_servers', input: [:]]]]]
		}

		when:
		Map result = provider.runToCompletion([messages: [[role: 'user', content: 'x']]], call)
		LlmChatResponse response = provider.ensureAnswerText(provider.parseMessageResponse(result.data as Map), result.data as Map)

		then:
		calls == 1
		response.finishReason == 'tool_calls'
		response.message.content == ''
	}

	def "redacted thinking is never shown, and is replayed verbatim when the answer is asked for"() {
		given:
		Map redacted = [type: 'redacted_thinking', data: 'EmwKAhgBEgy3va3pzix/LafPsn4a...']
		List<Map> sent = []
		Closure<Map> call = { Map body ->
			sent << body
			return sent.size() == 1 ? [success: true, data: [stop_reason: 'end_turn', content: [redacted]]] :
				[success: true, data: [stop_reason: 'end_turn', content: [redacted, [type: 'text', text: 'Done.']]]]
		}

		when:
		Map result = provider.runToCompletion([messages: [[role: 'user', content: 'x']]], call)
		LlmChatResponse response = provider.parseMessageResponse(result.data as Map)

		then:
		sent[1].messages[1].content == [redacted]
		response.message.content == 'Done.'
		response.metadata.thinking == null
	}

	def "the finished-turn line names stop reason, max_tokens, block counts, server tools and usage"() {
		expect:
		AnthropicProvider.describeFinishedTurn(serverToolsOnlyTurn(), 1000, 0, true) ==
			'stop_reason=end_turn max_tokens=1000 text_chars=0 ' +
			'blocks={server_tool_use=2, web_search_tool_result=1, web_fetch_tool_result=1} ' +
			'server_tool_use={web_search=1, web_fetch=1} ' +
			'usage={input=500 output=300 read=1000 created=0 web_search_requests=1 web_fetch_requests=1} ' +
			'pause_turn_continuations=0 final_answer_request=yes'
	}

	private static AnthropicProvider providerAnswering(Map result) {
		AnthropicProvider withApi = new AnthropicProvider(null, null)
		withApi.apiService = new AnthropicApiService() {
			@Override
			Map createMessage(String baseUrl, String apiKey, Map requestBody, String apiVersion, List<String> betas, Map opts) {
				return result
			}
		}
		return withApi
	}

	def "a prompt that no longer fits the context comes back as an explanation, not as an error"() {
		given:
		AnthropicProvider withApi = providerAnswering([success: false, statusCode: '400', errorType: 'invalid_request_error',
			errorMessage: 'prompt is too long: 1065647 tokens > 1000000 maximum', requestId: 'req_test',
			msg: 'API returned 400: prompt is too long: 1065647 tokens > 1000000 maximum'])
		AccountIntegration ai = configured([webSearch: 'on'])
		ai.servicePassword = 'sk-ant-test'

		when:
		ServiceResponse<LlmChatResponse> response = withApi.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(model: 'claude-sonnet-5', messages: [message('user', 'Summarise every release note')]), [:])

		then: 'Morpheus would show a generic error and drop the question'
		response.success
		response.data.message.content.startsWith("This request did not fit into the model's context window " +
			'(Anthropic: prompt is too long: 1065647 tokens > 1000000 maximum).')
		response.data.message.content.contains('Web Fetch Max Content Tokens')
	}

	def "any other API error still fails the request"() {
		given:
		AnthropicProvider withApi = providerAnswering([success: false, statusCode: '400', msg: 'API returned 400: credit balance is too low'])
		AccountIntegration ai = configured([:])
		ai.servicePassword = 'sk-ant-test'

		when:
		ServiceResponse<LlmChatResponse> response = withApi.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(messages: [message('user', 'hi')]), [:])

		then:
		!response.success
		response.errors.error.contains('credit balance')
	}

	private static LlmModel model(String code, Boolean enabled = true) {
		return new LlmModel(code: code, enabled: enabled)
	}

	def "the usage probe prefers the newest enabled Haiku, not the first one listed"() {
		given:
		LlmIntegration llm = new LlmIntegration(models: [
			model('anthropic/claude-3-haiku'),
			model('anthropic/claude-sonnet-4.6'),
			model('anthropic/claude-haiku-4.5'),
			model('anthropic/claude-3.5-haiku-20241022'),
			model('anthropic/claude-haiku-5', false)
		])

		expect:
		provider.resolveUsageProbeModel(llm) == 'anthropic/claude-haiku-4.5'
		provider.resolveUsageProbeModels(llm) ==
			['anthropic/claude-haiku-4.5', 'anthropic/claude-3.5-haiku-20241022', 'anthropic/claude-3-haiku', 'anthropic/claude-sonnet-4.6']
		provider.resolveUsageProbeModel(new LlmIntegration(models: [])) == 'claude-haiku-4-5'
	}

	def "a retired probe model is skipped for the next candidate, and other errors are not retried"() {
		given:
		List<String> probed = []
		AnthropicApiService api = new AnthropicApiService() {
			@Override
			Map fetchUsageHeaders(String baseUrl, String apiKey, String apiVersion, String model, Map opts) {
				probed << model
				if (model == 'claude-3-haiku') {
					return [success: false, statusCode: '404', msg: 'API returned 404: claude-3-haiku has reached the end of its life']
				}
				if (model == 'claude-broken') {
					return [success: false, statusCode: '529', msg: 'API returned 529: overloaded']
				}
				return [success: true, headers: ['anthropic-ratelimit-requests-limit': '50', 'anthropic-ratelimit-requests-remaining': '49']]
			}
		}
		LlmIntegration llm = new LlmIntegration()

		when:
		new LlmUsageSync(llm, null).execute(api, 'https://api.anthropic.com', 'k', null, ['claude-3-haiku', 'claude-haiku-4-5', 'claude-x'], [:])

		then:
		probed == ['claude-3-haiku', 'claude-haiku-4-5']
		llm.requestUsageLimit == 50L

		when:
		probed.clear()
		new LlmUsageSync(llm, null).execute(api, 'https://api.anthropic.com', 'k', null, ['claude-broken', 'claude-haiku-4-5'], [:])

		then:
		probed == ['claude-broken']
	}

	def "a request whose question the chat memory dropped gets it back from the tool calls that worked on it"() {
		given: 'the model answered the question with a tool call'
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
		LlmChatRequest first = new LlmChatRequest(messages: [message('system', 'You are Murph.'), message('user', 'List the AWS servers')])
		Map firstBody = provider.buildMessagesRequestBody(first, integration, false)
		AnthropicProvider.rememberQuestion(firstBody, [content: [[type: 'tool_use', id: 'toolu_01AbCdEfGhIjKlMn', name: 'list_servers', input: [:]]]],
			AnthropicProvider.questionScope(integration, first))

		and: 'a later request where Morpheus evicted the question, only the system prompt and the tool loop are left'
		LlmChatRequest evicted = new LlmChatRequest(messages: [
			message('system', 'You are Murph.'),
			message('assistant', null, [tool_calls: [[id: 'toolu_01AbCdEfGhIjKlMn', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]),
			message('tool', '{"artifactId":"a1","truncated":true}', [tool_call_id: 'toolu_01AbCdEfGhIjKlMn'])
		])

		when:
		Map body = provider.buildMessagesRequestBody(evicted, integration, false)

		then:
		body.messages.size() == 3
		body.messages[0].role == 'user'
		body.messages[0].content == AnthropicProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'
		body.messages[1].role == 'assistant'
		body.messages[2].content[0].tool_use_id == 'toolu_01AbCdEfGhIjKlMn'
		AnthropicProvider.latestQuestion(body.messages) == 'List the AWS servers'

		cleanup:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "the restored question carries on to the next tool calls without the note piling up"() {
		given:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
		AnthropicProvider.QUESTION_BY_TOOL_CALL.put('toolu_01AbCdEfGhIjKlMn', [question: 'List the AWS servers', updated: System.currentTimeMillis()])
		List<Map> messages = [
			[role: 'assistant', content: [[type: 'tool_use', id: 'toolu_01AbCdEfGhIjKlMn', name: 'get_result_excerpt', input: [:]]]],
			[role: 'user', content: [[type: 'tool_result', tool_use_id: 'toolu_01AbCdEfGhIjKlMn', content: 'part 2']]]
		]

		when:
		boolean restored = AnthropicProvider.restoreLostQuestion(messages)
		AnthropicProvider.rememberQuestion([messages: messages], [content: [[type: 'tool_use', id: 'toolu_02AbCdEfGhIjKlMn', name: 'get_result_excerpt', input: [:]]]])

		then:
		restored
		AnthropicProvider.QUESTION_BY_TOOL_CALL.get('toolu_02AbCdEfGhIjKlMn').question == 'List the AWS servers'

		cleanup:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a request that still has its question, or whose tool calls are unknown or too old, is left alone"() {
		given:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
		long old = System.currentTimeMillis() - AnthropicProvider.QUESTION_BY_TOOL_CALL_TTL_MS - 1000L
		AnthropicProvider.QUESTION_BY_TOOL_CALL.put('toolu_0OldAbCdEfGhIj', [question: 'Stale question', updated: old])
		List<Map> withQuestion = [
			[role: 'user', content: 'How many VMs?'],
			[role: 'assistant', content: [[type: 'tool_use', id: 'toolu_0OldAbCdEfGhIj', name: 'list_instances', input: [:]]]]
		]
		List<Map> unknown = [[role: 'assistant', content: [[type: 'tool_use', id: 'toolu_0UnknownAbCdEf', name: 'list_instances', input: [:]]]]]
		List<Map> expired = [[role: 'assistant', content: [[type: 'tool_use', id: 'toolu_0OldAbCdEfGhIj', name: 'list_instances', input: [:]]]]]

		expect:
		!AnthropicProvider.restoreLostQuestion(withQuestion)
		withQuestion.size() == 2
		!AnthropicProvider.restoreLostQuestion(unknown)
		unknown.size() == 1
		!AnthropicProvider.restoreLostQuestion(expired)
		expired.size() == 1
		!AnthropicProvider.restoreLostQuestion([])

		cleanup:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "only tool calls of a request with a question are remembered"() {
		given:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()

		when:
		AnthropicProvider.rememberQuestion([messages: [[role: 'user', content: [[type: 'tool_result', tool_use_id: 't0', content: 'x']]]]],
			[content: [[type: 'tool_use', id: 'toolu_t1AbCdEfGhIjKl', name: 'a', input: [:]]]])
		AnthropicProvider.rememberQuestion([messages: [[role: 'user', content: 'Q']]], [content: [[type: 'text', text: 'Done.']]])

		then:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.isEmpty()

		cleanup:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "numbered tool call ids are never trusted, so another conversation's question cannot come back"() {
		given:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'call_0'
		Map firstBody = provider.buildMessagesRequestBody(new LlmChatRequest(messages: [message('user', 'List the AWS servers')]), integration, false)
		AnthropicProvider.rememberQuestion(firstBody, [content: [[type: 'tool_use', id: ID, name: 'list_servers', input: [:]]]])

		expect:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.isEmpty()
		!AnthropicProvider.isDistinctToolCallId('functions.list_servers:0')
		!AnthropicProvider.isDistinctToolCallId('call_AbCdEfGhIjKlMn_12')
		AnthropicProvider.isDistinctToolCallId('call_AbCdEfGhIjKlMnOp')

		cleanup:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a restored question keeps counting on its original cost key and carries on to the next tool calls"() {
		given:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
		String ID = 'call_AbCdEfGhIjKlMnOp'
		String ID2 = 'call_ZyXwVuTsRqPoNmLk'
		Map firstBody = provider.buildMessagesRequestBody(new LlmChatRequest(messages: [message('user', 'List the AWS servers')]), integration, false)
		String originalKey = AnthropicProvider.questionKey(firstBody)
		AnthropicProvider.rememberQuestion(firstBody, [content: [[type: 'tool_use', id: ID, name: 'list_servers', input: [:]]]])
		List<Map> msgs = [[role: 'assistant', content: [[type: 'tool_use', id: ID, name: 'list_servers', input: [:]]]], [role: 'user', content: [[type: 'tool_result', tool_use_id: ID, content: 'part']]]]

		when:
		boolean restored = AnthropicProvider.restoreLostQuestion(msgs)
		AnthropicProvider.rememberQuestion([messages: msgs], [content: [[type: 'tool_use', id: ID2, name: 'get_result_excerpt', input: [:]]]])

		then:
		restored
		AnthropicProvider.questionKey([model: firstBody.model, messages: msgs]) == originalKey
		AnthropicProvider.QUESTION_BY_TOOL_CALL.get(ID2).question == 'List the AWS servers'
		AnthropicProvider.QUESTION_BY_TOOL_CALL.get(ID2).costKey == originalKey

		cleanup:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "a full map drops only its oldest entries"() {
		given:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
		long now = System.currentTimeMillis()
		(AnthropicProvider.QUESTION_BY_TOOL_CALL_MAX).times { int i ->
			AnthropicProvider.QUESTION_BY_TOOL_CALL.put('call_old' + String.format('%012d', i) + 'x', [question: 'old', costKey: 'k', updated: now - 1000L + (i % 500)])
		}
		String ID = 'call_NewAbCdEfGhIjKlMn'
		Map firstBody = provider.buildMessagesRequestBody(new LlmChatRequest(messages: [message('user', 'List the AWS servers')]), integration, false)

		when:
		AnthropicProvider.rememberQuestion(firstBody, [content: [[type: 'tool_use', id: ID, name: 'list_servers', input: [:]]]])

		then:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.size() == AnthropicProvider.QUESTION_BY_TOOL_CALL_MAX
		AnthropicProvider.QUESTION_BY_TOOL_CALL.get(ID).question == 'List the AWS servers'

		cleanup:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	def "generateResponse remembers the question and restores it on a later request that lost it"() {
		given:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
		List<Map> sent = []
		AnthropicProvider withApi = new AnthropicProvider(null, null)
		withApi.apiService = new AnthropicApiService() {
			@Override
			Map createMessage(String baseUrl, String apiKey, Map requestBody, String apiVersion, List<String> betas, Map opts) {
				sent << requestBody
				return [success: true, data: [id: 'msg_1', model: 'claude-sonnet-5', role: 'assistant', stop_reason: 'tool_use',
					content: [[type: 'tool_use', id: 'toolu_01E2eAbCdEfGhIjKl', name: 'list_servers', input: [:]]],
					usage: [input_tokens: 10, output_tokens: 5]]]
			}
		}
		AccountIntegration ai = configured([:])
		ai.servicePassword = 'sk-ant-test'
		LlmIntegration llm = new LlmIntegration(accountIntegration: ai)

		when:
		withApi.generateResponse(llm, new LlmChatRequest(model: 'claude-sonnet-5', messages: [message('user', 'List the AWS servers')]), [:])
		withApi.generateResponse(llm, new LlmChatRequest(model: 'claude-sonnet-5', messages: [
			message('assistant', null, [tool_calls: [[id: 'toolu_01E2eAbCdEfGhIjKl', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]),
			message('tool', '{"truncated":true}', [tool_call_id: 'toolu_01E2eAbCdEfGhIjKl'])
		]), [:])

		then:
		sent.size() == 2
		sent[1].messages[0].role == 'user'
		sent[1].messages[0].content == AnthropicProvider.RESTORED_QUESTION_NOTE + 'List the AWS servers'

		cleanup:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
	}

	private static LlmChatRequest toolLoop(String toolCallId) {
		new LlmChatRequest(messages: [
			message('user', 'List the AWS servers'),
			message('assistant', 'Checking.', [tool_calls: [[id: toolCallId, type: 'function', function: [name: 'list_servers', arguments: '{}']]]]),
			message('tool', '{"total":3}', [tool_call_id: toolCallId])
		])
	}

	def "with thinking on, a tool loop gets the thinking blocks of its tool-call turn back, signature included"() {
		given: 'a response that thought, then called a tool'
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()
		AccountIntegration ai = configured([thinkingEnabled: 'on'])
		LlmChatRequest loop = toolLoop('toolu_01AbCdEfGhIjKlMn')
		provider.parseMessageResponse([id: 'msg_1', role: 'assistant', stop_reason: 'tool_use', content: [
			[type: 'thinking', thinking: 'I should list the servers.', signature: 'sig-abc'],
			[type: 'redacted_thinking', data: 'opaque'],
			[type: 'text', text: 'Checking.'],
			[type: 'tool_use', id: 'toolu_01AbCdEfGhIjKlMn', name: 'list_servers', input: [:]]
		]], AnthropicProvider.questionScope(ai, loop))

		when: 'Morpheus sends the loop back, rebuilt from its stored tool_calls'
		Map body = provider.buildMessagesRequestBody(loop, ai, false)

		then:
		body.thinking.type == 'enabled'
		body.messages[1].role == 'assistant'
		body.messages[1].content*.type == ['thinking', 'redacted_thinking', 'text', 'tool_use']
		body.messages[1].content[0] == [type: 'thinking', thinking: 'I should list the servers.', signature: 'sig-abc']
		body.messages[1].content[1] == [type: 'redacted_thinking', data: 'opaque']

		cleanup:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()
	}

	def "with thinking on but the loop's thinking blocks unknown, the request goes out without thinking instead of failing"() {
		given:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()

		when:
		Map body = provider.buildMessagesRequestBody(toolLoop('toolu_01UnknownToolCallId'), configured([thinkingEnabled: 'on']), false)

		then:
		!body.containsKey('thinking')
		body.messages[1].content*.type == ['text', 'tool_use']
	}

	def "with thinking off, remembered thinking blocks are never sent"() {
		given:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()
		AnthropicProvider.rememberThinking(['toolu_01AbCdEfGhIjKlMn'], [[type: 'thinking', thinking: 't', signature: 's']])

		when:
		Map body = provider.buildMessagesRequestBody(toolLoop('toolu_01AbCdEfGhIjKlMn'), integration, false)

		then:
		!body.containsKey('thinking')
		body.messages[1].content*.type == ['text', 'tool_use']

		cleanup:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()
	}

	def "a new question after a finished tool loop keeps thinking although the old loop's blocks are unknown"() {
		given:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()
		LlmChatRequest request = new LlmChatRequest(messages: toolLoop('toolu_01UnknownToolCallId').messages + [
			message('assistant', 'There are 3 servers.'),
			message('user', 'And in Azure?')
		])

		when:
		Map body = provider.buildMessagesRequestBody(request, configured([thinkingEnabled: 'on']), false)

		then:
		body.thinking.type == 'enabled'
	}

	def "numbered tool call ids are not trusted for thinking blocks either"() {
		given:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()

		when:
		AnthropicProvider.rememberThinking(['call_0', 'functions.list_servers:0'], [[type: 'thinking', thinking: 't', signature: 's']])

		then:
		AnthropicProvider.THINKING_BY_TOOL_CALL.isEmpty()
	}

	def "a loop with several tool rounds keeps thinking: only its first tool-call message carries thinking"() {
		given: 'round 1 thought and called a tool; round 2 called the next tool without thinking'
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()
		AccountIntegration ai = configured([thinkingEnabled: 'on'])
		LlmChatRequest round3 = new LlmChatRequest(messages: toolLoop('toolu_01AbCdEfGhIjKlMn').messages + [
			message('assistant', null, [tool_calls: [[id: 'toolu_02OpQrStUvWxYzAb', type: 'function', function: [name: 'get_server', arguments: '{"id":1}']]]]),
			message('tool', '{"name":"web-1"}', [tool_call_id: 'toolu_02OpQrStUvWxYzAb'])
		])
		String scope = AnthropicProvider.questionScope(ai, round3)
		provider.parseMessageResponse([role: 'assistant', stop_reason: 'tool_use', content: [
			[type: 'thinking', thinking: 'List first.', signature: 'sig-1'],
			[type: 'tool_use', id: 'toolu_01AbCdEfGhIjKlMn', name: 'list_servers', input: [:]]]], scope)
		provider.parseMessageResponse([role: 'assistant', stop_reason: 'tool_use', content: [
			[type: 'tool_use', id: 'toolu_02OpQrStUvWxYzAb', name: 'get_server', input: [id: 1]]]], scope)

		when:
		Map body = provider.buildMessagesRequestBody(round3, ai, false)

		then:
		body.thinking.type == 'enabled'
		body.messages[1].content*.type == ['thinking', 'text', 'tool_use']
		body.messages[3].content*.type == ['tool_use']

		cleanup:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()
	}

	def "a thinking block without its signature is not remembered"() {
		given:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()

		when:
		AnthropicProvider.rememberThinking(['toolu_01AbCdEfGhIjKlMn'], [[type: 'thinking', thinking: 't', signature: null]])

		then:
		AnthropicProvider.THINKING_BY_TOOL_CALL.isEmpty()
	}

	// ------------------------------------------------------------------
	// Retries, stored credentials, scoped maps, cut-off turns (1.6.6)
	// ------------------------------------------------------------------

	private static AnthropicProvider providerAnsweringInTurn(List<Map> results, List<Map> sent) {
		AnthropicProvider withApi = new AnthropicProvider(null, null)
		withApi.sleeper = { long millis -> }
		withApi.apiService = new AnthropicApiService() {
			@Override
			Map createMessage(String baseUrl, String apiKey, Map requestBody, String apiVersion, List<String> betas, Map opts) {
				sent << requestBody
				return results[Math.min(sent.size(), results.size()) - 1]
			}
		}
		return withApi
	}

	def "a chat request that lost its connection, or met an overloaded API, is sent again"() {
		given:
		List<Map> sent = []
		Map answered = [success: true, data: [stop_reason: 'end_turn', content: [[type: 'text', text: 'Done.']], usage: [input_tokens: 1, output_tokens: 1]]]
		AnthropicProvider withApi = providerAnsweringInTurn([failure, answered], sent)
		AccountIntegration ai = configured([:])
		ai.servicePassword = 'sk-ant-test'

		when:
		ServiceResponse<LlmChatResponse> response = withApi.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(messages: [message('user', 'hi')]), [:])

		then: 'the API service reports these as a failed result, it never throws'
		response.success
		response.data.message.content == 'Done.'
		sent.size() == 2

		where:
		failure << [
			[success: false, msg: 'Connection reset'],
			[success: false, msg: 'api.example.com:443 failed to respond'],
			[success: false, statusCode: '529', errorType: 'overloaded_error', msg: 'API returned 529: Overloaded']
		]
	}

	def "an HTTP error or a read timeout is not sent again, and a broken connection at most three times"() {
		given:
		List<Map> sent = []
		AnthropicProvider withApi = providerAnsweringInTurn([failure], sent)
		AccountIntegration ai = configured([:])
		ai.servicePassword = 'sk-ant-test'

		when:
		ServiceResponse<LlmChatResponse> response = withApi.generateResponse(new LlmIntegration(accountIntegration: ai),
			new LlmChatRequest(messages: [message('user', 'hi')]), [:])

		then:
		!response.success
		sent.size() == attempts

		where:
		failure                                                                               || attempts
		[success: false, statusCode: '400', msg: 'API returned 400: the connection tool failed'] || 1
		[success: false, statusCode: '401', msg: 'API returned 401: invalid x-api-key']        || 1
		[success: false, msg: 'Read timed out']                                                || 1
		[success: false, msg: 'Connection reset']                                              || AnthropicProvider.MAX_CHAT_ATTEMPTS
	}

	def "a chat request without any API key is not sent"() {
		given:
		List<Map> sent = []
		AnthropicProvider withApi = providerAnsweringInTurn([[success: true, data: [content: []]]], sent)
		LlmStreamingResponseHandler handler = Mock()
		LlmIntegration llm = new LlmIntegration(accountIntegration: configured([:]))
		LlmChatRequest request = new LlmChatRequest(messages: [message('user', 'hi')])

		when:
		ServiceResponse<LlmChatResponse> response = withApi.generateResponse(llm, request, [:])
		withApi.streamResponse(llm, request, handler, [:])

		then:
		!response.success
		response.errors.error.contains('API key is required')
		1 * handler.onError({ it.message == AnthropicProvider.MISSING_API_KEY_MESSAGE })
		sent.isEmpty()
	}

	def "a key kept in a stored credential is loaded when Morpheus did not hand it over"() {
		given: 'an update through the REST API or a refresh: no credentialData, an old value in the local field'
		int lookups = 0
		AnthropicProvider withLookup = new AnthropicProvider(null, null) {
			@Override
			protected AccountCredential loadAccountCredential(AccountIntegration accountIntegration) {
				lookups++
				return new AccountCredential(data: [password: 'sk-ant-stored'])
			}
		}
		AccountIntegration ai = new AccountIntegration(id: 3L, servicePassword: 'sk-ant-stale')

		expect:
		withLookup.resolveApiKey(ai) == 'sk-ant-stored'
		withLookup.resolveApiKey(ai) == 'sk-ant-stored'

		and: 'looked up once, then kept on the integration'
		lookups == 1
		ai.credentialLoaded
	}

	def "a handed-over key is used as it is, and Local Credentials or a failing lookup fall back to the local field"() {
		given:
		List<Long> looked = []
		AnthropicProvider withLookup = new AnthropicProvider(null, null) {
			@Override
			protected AccountCredential loadAccountCredential(AccountIntegration accountIntegration) {
				looked << accountIntegration.id
				if (accountIntegration.id == 6L) {
					throw new RuntimeException('database unavailable')
				}
				return null
			}
		}

		expect:
		withLookup.resolveApiKey(new AccountIntegration(id: 99L, credentialData: [password: 'sk-ant-handed'], servicePassword: 'stale')) == 'sk-ant-handed'
		withLookup.resolveApiKey(new AccountIntegration(id: 5L, servicePassword: 'sk-ant-local')) == 'sk-ant-local'
		withLookup.resolveApiKey(new AccountIntegration(id: 6L, servicePassword: 'sk-ant-local')) == 'sk-ant-local'
		looked == [5L, 6L]
	}

	def "saving an integration whose key sits in a stored credential succeeds"() {
		given:
		AnthropicProvider withLookup = new AnthropicProvider(null, null) {
			@Override
			protected AccountCredential loadAccountCredential(AccountIntegration accountIntegration) {
				return new AccountCredential(data: [password: 'sk-ant-stored'])
			}
		}
		List<String> keys = []
		withLookup.apiService = new AnthropicApiService() {
			@Override
			Map listModels(String baseUrl, String apiKey, String apiVersion, Map opts) {
				keys << apiKey
				return [success: true, data: [data: []]]
			}
		}

		when:
		ServiceResponse response = withLookup.validate(new LlmIntegration(accountIntegration: new AccountIntegration(id: 3L)), [:])

		then:
		response.success
		keys == ['sk-ant-stored']
	}

	def "a failed continuation of a turn without text asks for the answer with the paused turn sent back once"() {
		given:
		List<Map> sent = []
		Closure<Map> call = { Map body ->
			sent << body
			if (sent.size() == 1) {
				return [success: true, data: serverToolsOnlyTurn('pause_turn')]
			}
			if (sent.size() == 2) {
				return [success: false, statusCode: '529', msg: 'API returned 529: Overloaded']
			}
			return [success: true, data: [stop_reason: 'end_turn', content: [[type: 'text', text: '9.0.2 is current.']]]]
		}

		when:
		Map result = provider.runToCompletion([model: 'claude-sonnet-5', messages: [[role: 'user', content: 'GA?']]], call)

		then: 'twice would repeat its server_tool_use ids, which the API rejects'
		sent.size() == 3
		sent[1].messages*.role == ['user', 'assistant']
		sent[2].messages*.role == ['user', 'assistant', 'user']
		sent[2].messages.collectMany { it.content instanceof List ? it.content as List : [] }
			.count { it.type == 'server_tool_use' && it.id == 'srvtoolu_1' } == 1
		provider.parseMessageResponse(result.data as Map).message.content == '9.0.2 is current.'
	}

	def "a tool call cut off at max_tokens is dropped instead of being run with part of its arguments"() {
		given:
		Closure<Map> call = { Map body ->
			[success: true, data: [stop_reason: 'max_tokens', content: [
				[type: 'text', text: 'Creating both tasks.'],
				[type: 'tool_use', id: 'toolu_01AbCdEfGhIjKlMn', name: 'create_task', input: [name: 'backup']],
				[type: 'tool_use', id: 'toolu_02AbCdEfGhIjKlMn', name: 'create_task', input: [:]]
			]]]
		}

		when:
		Map result = provider.runToCompletion([messages: [[role: 'user', content: 'Create two tasks']]], call)
		LlmChatResponse response = provider.parseMessageResponse(result.data as Map)

		then: 'the complete call before it stays'
		response.finishReason == 'length'
		(response.metadata.tool_calls as List)*.id == ['toolu_01AbCdEfGhIjKlMn']
		response.message.content == 'Creating both tasks.'
	}

	def "with extended thinking a forced tool choice becomes auto, the only one the API accepts besides none"() {
		given:
		LlmChatRequest request = new LlmChatRequest(messages: [message('user', 'hi')],
			options: [tools: [[type: 'function', function: [name: 'list_servers', parameters: [type: 'object']]]], tool_choice: choice])

		expect:
		provider.buildMessagesRequestBody(request, configured([thinkingEnabled: 'on']), false).tool_choice == [type: 'auto']
		provider.buildMessagesRequestBody(request, integration, false).tool_choice == withoutThinking

		where:
		choice                                               || withoutThinking
		'required'                                           || [type: 'any']
		[type: 'function', function: [name: 'list_servers']] || [type: 'tool', name: 'list_servers']
	}

	def "nested usage counts such as web search requests add up across paused segments"() {
		given:
		Map first = [content: [], usage: [input_tokens: 1, server_tool_use: [web_search_requests: 1],
										  cache_creation: [ephemeral_5m_input_tokens: 10], service_tier: 'standard']]
		Map second = [content: [], usage: [input_tokens: 2, server_tool_use: [web_search_requests: 2, web_fetch_requests: 1],
										   cache_creation: [ephemeral_5m_input_tokens: 5]]]

		when:
		Map merged = provider.mergeMessageSegments([first, second])

		then:
		merged.usage.input_tokens == 3
		merged.usage.server_tool_use == [web_search_requests: 3, web_fetch_requests: 1]
		merged.usage.cache_creation == [ephemeral_5m_input_tokens: 15]
		merged.usage.service_tier == 'standard'

		and: 'the first segment keeps its own counts'
		first.usage.server_tool_use == [web_search_requests: 1]
	}

	def "a lost question only comes back in the integration and system prompt it was asked in"() {
		given:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()
		AccountIntegration asking = new AccountIntegration(id: 2L, serviceUrl: 'https://api.anthropic.com')
		LlmChatRequest asked = new LlmChatRequest(messages: [message('system', 'You are Murph.'), message('user', 'List the AWS servers')])
		AnthropicProvider.rememberQuestion(provider.buildMessagesRequestBody(asked, asking, false),
			[content: [[type: 'tool_use', id: 'toolu_01AbCdEfGhIjKlMn', name: 'list_servers', input: [:]]]],
			AnthropicProvider.questionScope(asking, asked))
		LlmChatRequest evicted = new LlmChatRequest(messages: [
			message('system', system),
			message('assistant', null, [tool_calls: [[id: 'toolu_01AbCdEfGhIjKlMn', type: 'function', function: [name: 'list_servers', arguments: '{}']]]]),
			message('tool', '[]', [tool_call_id: 'toolu_01AbCdEfGhIjKlMn'])
		])

		when:
		Map body = provider.buildMessagesRequestBody(evicted, new AccountIntegration(id: integrationId, serviceUrl: 'https://api.anthropic.com'), false)

		then:
		(body.messages[0].role == 'user') == restored

		cleanup:
		AnthropicProvider.QUESTION_BY_TOOL_CALL.clear()

		where:
		integrationId | system                       || restored
		2L            | 'You are Murph.'             || true
		7L            | 'You are Murph.'             || false
		2L            | 'You are the billing agent.' || false
	}

	def "thinking blocks are only put back in the scope they were kept in"() {
		given:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()
		AccountIntegration ai = configured([thinkingEnabled: 'on'])
		ai.id = 2L
		AnthropicProvider.rememberThinking(['toolu_01AbCdEfGhIjKlMn'], [[type: 'thinking', thinking: 't', signature: 's']], '7:0000000000000000')

		when:
		Map body = provider.buildMessagesRequestBody(toolLoop('toolu_01AbCdEfGhIjKlMn'), ai, false)

		then: 'the blocks of another integration are not used; the request goes out without thinking'
		!body.containsKey('thinking')
		body.messages[1].content*.type == ['text', 'tool_use']

		cleanup:
		AnthropicProvider.THINKING_BY_TOOL_CALL.clear()
	}

	def "the same question through two integrations keeps two cost totals"() {
		given:
		AnthropicProvider.QUESTION_COSTS.clear()
		Map body = [model: 'anthropic/claude-sonnet-4.6', messages: [[role: 'user', content: 'Which servers run?']]]
		Map toolTurn = [stop_reason: 'tool_use', content: [[type: 'tool_use', id: 't1', name: 'list_servers', input: [:]]],
						usage: [input_tokens: 10, output_tokens: 5, cost: 0.01]]

		when:
		LlmChatResponse first = provider.trackQuestionCost(body, provider.parseMessageResponse(toolTurn), '2:abc')
		LlmChatResponse other = provider.trackQuestionCost(body, provider.parseMessageResponse(toolTurn), '7:abc')
		LlmChatResponse again = provider.trackQuestionCost(body, provider.parseMessageResponse(toolTurn), '2:abc')

		then:
		first.metadata.question_requests == 1
		other.metadata.question_requests == 1
		again.metadata.question_requests == 2

		cleanup:
		AnthropicProvider.QUESTION_COSTS.clear()
	}

	def "eviction drops the expired entries and, above the cap, exactly the oldest ones"() {
		given:
		java.util.concurrent.ConcurrentHashMap<String, Map> entries = new java.util.concurrent.ConcurrentHashMap<>()
		long now = System.currentTimeMillis()
		entries.put('expired', [updated: now - 10000L])
		entries.put('oldest', [updated: now - 500L])
		entries.put('older', [updated: now - 400L])
		entries.put('newer', [updated: now - 300L])
		entries.put('newest', [updated: now])

		when:
		AnthropicProvider.evictEntries(entries, 5000L, 3)

		then:
		entries.keySet() == ['older', 'newer', 'newest'] as Set
	}
}
