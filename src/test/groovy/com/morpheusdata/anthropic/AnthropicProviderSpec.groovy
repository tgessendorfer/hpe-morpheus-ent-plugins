package com.morpheusdata.anthropic

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.llm.LlmChatMessage
import com.morpheusdata.model.llm.LlmChatRequest
import com.morpheusdata.model.llm.LlmChatResponse
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

	def "a blank max uses means no cap"() {
		expect:
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
}
