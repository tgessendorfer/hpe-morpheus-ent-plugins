package com.morpheusdata.anthropic

import com.morpheusdata.core.util.HttpApiClient
import org.apache.http.HttpEntity
import org.apache.http.client.methods.CloseableHttpResponse
import spock.lang.Specification

/**
 * Covers header construction and the SSE accumulator.
 */
class AnthropicApiServiceSpec extends Specification {

	AnthropicApiService service = new AnthropicApiService()

	def "requests authenticate with x-api-key and never with a bearer token"() {
		when:
		HttpApiClient.RequestOptions options = service.buildRequestOptions('sk-ant-test', null, ['Accept': 'application/json'], null, 30000)

		then:
		options.headers[AnthropicApiService.API_KEY_HEADER] == 'sk-ant-test'
		options.headers[AnthropicApiService.VERSION_HEADER] == AnthropicApiService.DEFAULT_API_VERSION
		options.apiToken == null
	}

	def "a custom api version overrides the default"() {
		when:
		HttpApiClient.RequestOptions options = service.buildRequestOptions('sk-ant-test', '2026-01-01', [:], null, 30000)

		then:
		options.headers[AnthropicApiService.VERSION_HEADER] == '2026-01-01'
	}

	def "the sse accumulator rebuilds text and tool_use blocks"() {
		given: 'a representative Anthropic event stream'
		String stream = [
			'data: {"type":"message_start","message":{"id":"msg_1","model":"claude-sonnet-4-6","usage":{"input_tokens":42}}}',
			'data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}',
			'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking "}}',
			'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"instances."}}',
			'data: {"type":"content_block_stop","index":0}',
			'data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"list_instances"}}',
			'data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"max\\":"}}',
			'data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"10}"}}',
			'data: {"type":"content_block_stop","index":1}',
			'data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":17}}',
			'data: {"type":"message_stop"}'
		].join('\n')

		HttpEntity entity = Stub(HttpEntity) {
			getContent() >> new ByteArrayInputStream(stream.getBytes('UTF-8'))
		}
		CloseableHttpResponse response = Stub(CloseableHttpResponse) {
			getEntity() >> entity
		}
		List<String> streamedText = []

		when:
		Map accumulated = service.consumeEventStream(response, { String chunk -> streamedText << chunk })

		then: 'partial text was streamed to the caller as it arrived'
		streamedText == ['Checking ', 'instances.']

		and: 'the accumulated payload looks like a non-streaming response'
		accumulated.id == 'msg_1'
		accumulated.model == 'claude-sonnet-4-6'
		accumulated.stop_reason == 'tool_use'
		accumulated.usage.input_tokens == 42
		accumulated.usage.output_tokens == 17
		accumulated.content.size() == 2
		accumulated.content[0].text == 'Checking instances.'
		accumulated.content[1].type == 'tool_use'
		accumulated.content[1].name == 'list_instances'
		accumulated.content[1].input == [max: 10]

		and: 'internal buffers are not leaked into the result'
		accumulated.content.every { !it.containsKey('__jsonBuffer') }
	}

	def "an error event aborts the stream"() {
		given:
		HttpEntity entity = Stub(HttpEntity) {
			getContent() >> new ByteArrayInputStream('data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}'.getBytes('UTF-8'))
		}
		CloseableHttpResponse response = Stub(CloseableHttpResponse) {
			getEntity() >> entity
		}

		when:
		service.consumeEventStream(response, null)

		then:
		RuntimeException e = thrown()
		e.message.contains('overloaded_error')
	}

	def "listModels passes limit as a query parameter and keeps the path clean"() {
		given: 'a service whose transport is intercepted'
		AnthropicApiService spy = Spy(AnthropicApiService)

		when:
		spy.listModels('https://api.anthropic.com', 'sk-ant-test')

		then: 'the path carries no query string - HttpApiClient would percent-encode the ? into %3F and the call would 404'
		1 * spy.executeGet('https://api.anthropic.com', '/v1/models', 'sk-ant-test', _, null, [:], [limit: '1000']) >> [success: true]
	}

	def "executeGet puts query parameters on the request options rather than the path"() {
		when:
		HttpApiClient.RequestOptions options = service.buildRequestOptions('sk-ant-test', null, ['Accept': 'application/json'], null, 30000)
		options.queryParams = [limit: '100']

		then:
		options.queryParams == [limit: '100']
		!AnthropicApiService.MODELS_PATH.contains('?')
	}

	def "ASCII-only JSON keeps emoji and umlauts decodable"() {
		expect:
		AnthropicApiService.toAsciiJson([text: 'Läuft 👋']) ==~ /[\x00-\x7E]*/
		new groovy.json.JsonSlurper().parseText(AnthropicApiService.toAsciiJson([text: 'Läuft 👋'])).text == 'Läuft 👋'
	}

	def "a request body with non-ASCII text leaves as valid UTF-8"() {
		given: 'a local endpoint that keeps the raw request bytes'
		byte[] received = null
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
		server.createContext('/v1/messages', { com.sun.net.httpserver.HttpExchange exchange ->
			received = exchange.requestBody.bytes
			byte[] body = '{"type":"message","content":[]}'.getBytes('UTF-8')
			exchange.responseHeaders.add('Content-Type', 'application/json')
			exchange.sendResponseHeaders(200, body.length)
			exchange.responseBody.withStream { it.write(body) }
		} as com.sun.net.httpserver.HttpHandler)
		server.start()

		when:
		new AnthropicApiService().createMessage("http://127.0.0.1:${server.address.port}", 'sk-ant-test',
			[model: 'claude-sonnet-5', max_tokens: 1, messages: [[role: 'user', content: 'Antworte mit dem Wort Läuft für „alle“']]])

		then: 'the bytes are plain ASCII, so no Morpheus version can re-encode them wrongly'
		received != null
		received.every { byte b -> b >= 0 }

		and: 'they parse as the JSON that was built, text intact'
		def json = new groovy.json.JsonSlurper().parseText(new String(received, 'US-ASCII'))
		json.messages[0].content == 'Antworte mit dem Wort Läuft für „alle“'

		cleanup:
		server.stop(0)
	}

	def "a JSON response without a charset is read as UTF-8"() {
		given: 'a local endpoint answering like OpenRouter and api.anthropic.com do: application/json, no charset'
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
		server.createContext('/v1/messages', { com.sun.net.httpserver.HttpExchange exchange ->
			byte[] body = '{"type":"message","content":[{"type":"text","text":"Läuft"}]}'.getBytes('UTF-8')
			exchange.responseHeaders.add('Content-Type', 'application/json')
			exchange.sendResponseHeaders(200, body.length)
			exchange.responseBody.withStream { it.write(body) }
		} as com.sun.net.httpserver.HttpHandler)
		server.start()

		when:
		Map result = new AnthropicApiService().createMessage("http://127.0.0.1:${server.address.port}", 'sk-ant-test',
			[model: 'claude-haiku-4-5', max_tokens: 1, messages: []])

		then:
		result.success
		result.data.content[0].text == 'Läuft'

		cleanup:
		server.stop(0)
	}
}
