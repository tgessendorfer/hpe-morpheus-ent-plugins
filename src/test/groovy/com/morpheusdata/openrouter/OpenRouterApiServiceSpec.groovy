package com.morpheusdata.openrouter

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.NetworkProxy
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import org.apache.http.HttpEntity
import org.apache.http.client.methods.CloseableHttpResponse
import spock.lang.Specification

/**
 * Covers request construction, the bytes that leave the plugin, error messages
 * and the SSE accumulator. The chunks below are shaped like what OpenRouter sent
 * on 2026-09-14.
 */
class OpenRouterApiServiceSpec extends Specification {

	OpenRouterApiService service = new OpenRouterApiService()
	HttpServer server

	def cleanup() {
		server?.stop(0)
	}

	/** A local endpoint under /api/v1 that records each request and answers with the given body. */
	private String serve(String path, String contentType, String responseBody, int status, List<Map> received, Map<String, String> headers = [:]) {
		server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
		server.createContext("/api/v1${path}", { HttpExchange exchange ->
			received << [method       : exchange.requestMethod,
						 path         : exchange.requestURI.path,
						 authorization: exchange.requestHeaders.getFirst('Authorization'),
						 body         : exchange.requestBody.bytes]
			byte[] bytes = responseBody.getBytes('UTF-8')
			exchange.responseHeaders.add('Content-Type', contentType)
			headers.each { String name, String value -> exchange.responseHeaders.add(name, value) }
			exchange.sendResponseHeaders(status, bytes.length)
			exchange.responseBody.withStream { it.write(bytes) }
		} as HttpHandler)
		server.start()
		return "http://127.0.0.1:${server.address.port}/api/v1"
	}

	private CloseableHttpResponse streamOf(List<String> lines) {
		HttpEntity entity = Stub(HttpEntity) {
			getContent() >> new ByteArrayInputStream(lines.join('\n').getBytes('UTF-8'))
		}
		return Stub(CloseableHttpResponse) {
			getEntity() >> entity
		}
	}

	def "requests authenticate with a bearer token and keep certificate checks, and with them SNI"() {
		when:
		HttpApiClient.RequestOptions options = service.buildRequestOptions('sk-or-v1-test', ['Accept': 'application/json'], null, 30000)

		then:
		options.headers['Authorization'] == 'Bearer sk-or-v1-test'
		options.ignoreSSL == false
		options.body == null
	}

	def "ASCII-only JSON keeps emoji and umlauts decodable"() {
		expect:
		OpenRouterApiService.toAsciiJson([text: 'Läuft 👋']) ==~ /[\x00-\x7E]*/
		new JsonSlurper().parseText(OpenRouterApiService.toAsciiJson([text: 'Läuft 👋'])).text == 'Läuft 👋'
	}

	def "a chat request leaves as plain ASCII bytes, under the base URL's path, with the key"() {
		given:
		List<Map> received = []
		String baseUrl = serve('/chat/completions', 'application/json', '{"choices":[]}', 200, received)

		when:
		Map result = service.createChatCompletion(baseUrl, 'sk-or-v1-test',
			[model: 'openai/gpt-5.4-nano', messages: [[role: 'user', content: 'Antworte mit dem Wort Läuft für „alle“']]])

		then:
		result.success
		received.size() == 1
		received[0].method == 'POST'
		received[0].path == '/api/v1/chat/completions'
		received[0].authorization == 'Bearer sk-or-v1-test'

		and: 'no byte above 0x7F, so no plugin API version can re-encode them wrongly'
		(received[0].body as byte[]).every { byte b -> b >= 0 }

		and: 'they parse as the JSON that was built, text intact'
		new JsonSlurper().parseText(new String(received[0].body as byte[], 'US-ASCII')).messages[0].content == 'Antworte mit dem Wort Läuft für „alle“'
	}

	def "the key check is a GET on /key"() {
		given:
		List<Map> received = []
		String baseUrl = serve('/key', 'application/json', '{"data":{"limit":null,"usage":1.5,"is_free_tier":false}}', 200, received)

		when:
		Map result = service.getKey(baseUrl, 'sk-or-v1-test')

		then:
		result.success
		result.data.data.usage == 1.5
		received[0].method == 'GET'
		received[0].authorization == 'Bearer sk-or-v1-test'
	}

	def "a JSON response without a charset is read as UTF-8"() {
		given: 'answering like OpenRouter does: application/json, no charset'
		String baseUrl = serve('/models', 'application/json', '{"data":[{"id":"mistralai/x","name":"Mistral: Läuft"}]}', 200, [])

		when:
		Map result = service.listModels(baseUrl, 'sk-or-v1-test')

		then:
		result.success
		result.data.data[0].name == 'Mistral: Läuft'
	}

	def "an error is reduced to its message, without the account's user id"() {
		given:
		String baseUrl = serve('/chat/completions', 'application/json',
			'{"error":{"message":"openai/does-not-exist is not a valid model ID","code":400},"user_id":"user_abc123"}', 400, [])

		when:
		Map result = service.createChatCompletion(baseUrl, 'sk-or-v1-test', [model: 'openai/does-not-exist', messages: []])

		then:
		!result.success
		result.msg.contains('openai/does-not-exist is not a valid model ID')
		!result.msg.contains('user_abc123')
	}

	def "a rejected stream reports the status and the message, without the user id"() {
		given:
		String baseUrl = serve('/chat/completions', 'application/json',
			'{"error":{"message":"User not found.","code":401},"user_id":"user_abc123"}', 401, [])

		when:
		Map result = service.streamChatCompletion(baseUrl, 'sk-or-v1-bad', [model: 'openai/gpt-5.4-nano', messages: []], null)

		then:
		!result.success
		result.msg.contains('401')
		result.msg.contains('User not found.')
		!result.msg.contains('user_abc123')
	}

	def "a stream is read through callStreamApi to the end"() {
		given:
		List<Map> received = []
		String stream = [
			': OPENROUTER PROCESSING',
			'',
			'data: {"id":"gen-1","model":"deepseek/deepseek-v4-flash-0731","provider":"StreamLake","choices":[{"index":0,"delta":{"content":"Red","role":"assistant"},"finish_reason":null}]}',
			'',
			'data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":", green, blue.","role":"assistant"},"finish_reason":"stop"}]}',
			'',
			'data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":"","role":"assistant"},"finish_reason":"stop"}],"usage":{"prompt_tokens":91,"completion_tokens":42,"total_tokens":133,"cost":0.0000124124}}',
			'',
			'data: [DONE]',
			''
		].join('\n')
		String baseUrl = serve('/chat/completions', 'text/event-stream', stream, 200, received)
		List<String> chunks = []

		when:
		Map result = service.streamChatCompletion(baseUrl, 'sk-or-v1-test',
			[model: 'deepseek/deepseek-v4-flash-0731', messages: [[role: 'user', content: 'Name three colours.']]], { String chunk -> chunks << chunk })

		then:
		result.success
		chunks == ['Red', ', green, blue.']
		result.data.choices[0].message.content == 'Red, green, blue.'
		result.data.choices[0].finish_reason == 'stop'
		result.data.usage.cost == 0.0000124124
		result.data.provider == 'StreamLake'

		and: 'the request asked for a stream'
		new JsonSlurper().parseText(new String(received[0].body as byte[], 'US-ASCII')).stream == true
	}

	def "the stream accumulator rebuilds a tool call from its fragments"() {
		given: 'id and name arrive once, the arguments in pieces, and usage in a last chunk that repeats finish_reason'
		CloseableHttpResponse response = streamOf([
			'data: {"id":"gen-2","model":"openai/gpt-5.4-nano","provider":"OpenAI","choices":[{"index":0,"delta":{"content":null,"role":"assistant","tool_calls":[{"index":0,"id":"call_Hv","type":"function","function":{"name":"get_instance_count","arguments":""}}]},"finish_reason":null}]}',
			'data: {"id":"gen-2","choices":[{"index":0,"delta":{"content":null,"role":"assistant","tool_calls":[{"index":0,"function":{"arguments":"{\\""}}]},"finish_reason":null}]}',
			'data: {"id":"gen-2","choices":[{"index":0,"delta":{"content":null,"role":"assistant","tool_calls":[{"index":0,"function":{"arguments":"cloud"}}]},"finish_reason":null}]}',
			'data: {"id":"gen-2","choices":[{"index":0,"delta":{"content":null,"role":"assistant","tool_calls":[{"index":0,"function":{"arguments":"\\":\\"lab\\"}"}}]},"finish_reason":null}]}',
			'data: {"id":"gen-2","choices":[{"index":0,"delta":{"content":"","role":"assistant"},"finish_reason":"tool_calls","native_finish_reason":"completed"}]}',
			'data: {"id":"gen-2","choices":[{"index":0,"delta":{"content":"","role":"assistant"},"finish_reason":"tool_calls","native_finish_reason":"completed"}],"usage":{"prompt_tokens":71,"completion_tokens":19,"cost":0.00003795}}',
			'data: [DONE]'
		])
		List<String> chunks = []

		when:
		Map accumulated = service.consumeEventStream(response, { String chunk -> chunks << chunk })

		then: 'nothing empty was streamed as text'
		chunks.isEmpty()

		and:
		accumulated.id == 'gen-2'
		accumulated.provider == 'OpenAI'
		accumulated.usage.prompt_tokens == 71
		Map choice = accumulated.choices[0]
		choice.finish_reason == 'tool_calls'
		choice.message.content == ''
		choice.message.tool_calls == [[id: 'call_Hv', type: 'function', function: [name: 'get_instance_count', arguments: '{"cloud":"lab"}']]]
	}

	def "parallel tool calls stay apart by index"() {
		given:
		CloseableHttpResponse response = streamOf([
			'data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"list_clouds","arguments":"{}"}},{"index":1,"id":"call_b","function":{"name":"list_groups","arguments":"{"}}]}}]}',
			'data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}',
			'data: [DONE]'
		])

		when:
		Map accumulated = service.consumeEventStream(response, null)

		then:
		accumulated.choices[0].message.tool_calls*.id == ['call_a', 'call_b']
		accumulated.choices[0].message.tool_calls*.function*.arguments == ['{}', '{}']
	}

	def "reasoning is kept apart from the streamed answer"() {
		given:
		CloseableHttpResponse response = streamOf([
			'data: {"choices":[{"index":0,"delta":{"content":"","role":"assistant","reasoning":"We need","reasoning_details":[{"type":"reasoning.text","text":"We need","format":"unknown","index":0}]},"finish_reason":null}]}',
			'data: {"choices":[{"index":0,"delta":{"content":"","role":"assistant","reasoning":" three words.","reasoning_details":[{"type":"reasoning.text","text":" three words.","format":"unknown","index":0}]},"finish_reason":null}]}',
			'data: {"choices":[{"index":0,"delta":{"content":"Red","role":"assistant"},"finish_reason":null}]}',
			'data: {"choices":[{"index":0,"delta":{"content":"","role":"assistant","reasoning":null},"finish_reason":"stop"}]}',
			'data: [DONE]'
		])
		List<String> chunks = []

		when:
		Map accumulated = service.consumeEventStream(response, { String chunk -> chunks << chunk })

		then:
		chunks == ['Red']
		accumulated.choices[0].message.content == 'Red'
		accumulated.choices[0].message.reasoning == 'We need three words.'
	}

	def "an error event after the stream started aborts it"() {
		given: 'HTTP 200 is already sent, so the error arrives as a chunk'
		CloseableHttpResponse response = streamOf([
			'data: {"choices":[{"index":0,"delta":{"content":"Partial"},"finish_reason":null}]}',
			'data: {"id":"gen-3","error":{"code":429,"message":"Rate limit exceeded","metadata":{"error_type":"rate_limit_exceeded","provider_name":"OpenAI"}},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}'
		])

		when:
		service.consumeEventStream(response, null)

		then:
		OpenRouterApiService.StreamErrorException e = thrown()
		e.message.contains('Rate limit exceeded')
		e.message.contains('OpenAI')
		e.statusCode == 429
	}

	def "a refused request reports its status; HttpApiClient hands over no headers for it"() {
		given:
		String baseUrl = serve('/chat/completions', 'application/json',
			'{"error":{"message":"Rate limit exceeded","code":429},"user_id":"user_abc123"}', 429, [], ['Retry-After': '7'])

		when:
		Map result = service.createChatCompletion(baseUrl, 'sk-or-v1-test', [model: 'm', messages: []])

		then:
		!result.success
		result.statusCode == 429
		result.msg.contains('Rate limit exceeded')

		and: 'callJsonApi returns an error without its headers, so the provider falls back to its own waits'
		result.retryAfter == null
	}

	def "a refused stream reports its status and how long OpenRouter asks to wait"() {
		given:
		String baseUrl = serve('/chat/completions', 'application/json',
			'{"error":{"message":"Rate limit exceeded","code":429},"user_id":"user_abc123"}', 429, [], ['Retry-After': '7'])

		when:
		Map result = service.streamChatCompletion(baseUrl, 'sk-or-v1-test', [model: 'm', messages: []], null)

		then:
		!result.success
		result.statusCode == 429
		result.retryAfter == 7
	}

	def "an error event inside a stream comes back as a failure with its status"() {
		given:
		String stream = [
			'data: {"choices":[{"index":0,"delta":{"content":"Partial"},"finish_reason":null}]}',
			'',
			'data: {"error":{"code":502,"message":"Provider returned error"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}',
			''
		].join('\n')
		String baseUrl = serve('/chat/completions', 'text/event-stream', stream, 200, [])
		List<String> chunks = []

		when:
		Map result = service.streamChatCompletion(baseUrl, 'sk-or-v1-test', [model: 'm', messages: []], { String chunk -> chunks << chunk })

		then:
		chunks == ['Partial']
		!result.success
		result.statusCode == 502
		result.msg == 'OpenRouter stream error: Provider returned error'
	}

	def "status codes and Retry-After values are read defensively"() {
		expect:
		OpenRouterApiService.statusCodeOf(status) == expectedStatus
		OpenRouterApiService.retryAfterSeconds(retryAfter) == expectedWait

		where:
		status    | retryAfter                         || expectedStatus | expectedWait
		'429'     | '7'                                || 429            | 7
		429       | ['12']                             || 429            | 12
		'unknown' | 'Wed, 16 Sep 2026 07:28:00 GMT'    || null           | null
		null      | null                               || null           | null
	}

	def "the api service applies the proxy to the client and clears it again"() {
		given: 'a proxy is a property of the client, not of one request'
		HttpApiClient client = new HttpApiClient()
		NetworkProxy proxy = new NetworkProxy(name: 'egress', proxyHost: 'proxy.example.com')

		when:
		service.applyNetworkProxy(client, [(OpenRouterApiService.NETWORK_PROXY_KEY): proxy])

		then:
		client.networkProxy.is(proxy)

		when: 'the integration is later switched back to a direct connection'
		service.applyNetworkProxy(client, [:])

		then: 'a pooled client outlives the call, so this has to be cleared too'
		client.networkProxy == null
	}
}
