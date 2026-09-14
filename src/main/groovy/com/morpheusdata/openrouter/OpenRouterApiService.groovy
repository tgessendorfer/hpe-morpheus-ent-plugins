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

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.util.EntityUtils

import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP client service for OpenRouter's OpenAI-compatible API.
 *
 *  - authentication with an {@code Authorization: Bearer} header
 *  - POST /chat/completions for both streaming and non-streaming completions
 *  - GET /models for the catalog, GET /key to check the key
 *
 * HttpApiClient appends a path to the base URL's own path verbatim, so the base
 * URL carries the API prefix: https://openrouter.ai/api/v1.
 */
@Slf4j
class OpenRouterApiService {

	static final String CHAT_COMPLETIONS_PATH = '/chat/completions'
	static final String MODELS_PATH = '/models'
	static final String KEY_PATH = '/key'
	static final String CLIENT_SCOPE_KEY = 'clientScopeKey'
	/**
	 * opts key carrying the {@link com.morpheusdata.model.NetworkProxy} the
	 * appliance should route this call through. A proxy is a property of the
	 * client rather than of a request, so it is applied on every borrow of a
	 * pooled client - that way changing the proxy on the integration takes
	 * effect on the next call instead of when the pooled client expires.
	 */
	static final String NETWORK_PROXY_KEY = 'networkProxy'
	static final Integer DEFAULT_CONNECTION_TIMEOUT = 30000
	static final Integer DEFAULT_READ_TIMEOUT = 30000
	static final Integer DEFAULT_INTERACT_READ_TIMEOUT = 300000
	static final Long SESSION_CLIENT_TTL_MS = 60L * 60L * 1000L
	static final Integer MAX_ERROR_BODY_LENGTH = 300

	protected final ConcurrentHashMap<String, SessionClientHolder> sessionClients = new ConcurrentHashMap<>()

	protected static class SessionClientHolder {
		final HttpApiClient apiClient
		volatile long lastUsedAt

		SessionClientHolder(HttpApiClient apiClient, long lastUsedAt) {
			this.apiClient = apiClient
			this.lastUsedAt = lastUsedAt
		}
	}

	/**
	 * The model catalog. OpenRouter serves it to any key or none, so a successful
	 * call proves the endpoint is reachable, not that the key works.
	 */
	Map listModels(String baseUrl, String apiKey, Map opts = [:]) {
		return executeGet(baseUrl, MODELS_PATH, apiKey, opts ?: [:])
	}

	/**
	 * The record of the calling key. Answers 401 to an unknown key and costs
	 * nothing, which makes it the credential check on integration save.
	 */
	Map getKey(String baseUrl, String apiKey, Map opts = [:]) {
		return executeGet(baseUrl, KEY_PATH, apiKey, opts ?: [:])
	}

	/**
	 * Non-streaming completion against POST /chat/completions.
	 */
	Map createChatCompletion(String baseUrl, String apiKey, Map requestBody, Map opts = [:]) {
		return executePost(baseUrl, CHAT_COMPLETIONS_PATH, apiKey, requestBody, opts ?: [:])
	}

	/**
	 * Streaming completion against POST /chat/completions with SSE.
	 *
	 * The chunks are accumulated into a payload shaped like a non-streaming
	 * response, so the provider parses both the same way.
	 */
	Map streamChatCompletion(String baseUrl, String apiKey, Map requestBody, Closure onText, Map opts = [:]) {
		CloseableHttpResponse response = null
		try {
			requestBody.stream = true
			HttpApiClient.RequestOptions requestOptions = buildRequestOptions(apiKey, [
				'Content-Type': 'application/json',
				'Accept'      : 'text/event-stream'
			], requestBody, DEFAULT_INTERACT_READ_TIMEOUT)

			return withApiClient(opts ?: [:]) { HttpApiClient apiClient ->
				ServiceResponse<CloseableHttpResponse> apiResponse = apiClient.callStreamApi(baseUrl, CHAT_COMPLETIONS_PATH, null, null, requestOptions, 'POST')
				response = apiResponse?.data
				if (apiResponse?.success != true || response == null) {
					String statusCode = apiResponse?.errorCode ?: response?.statusLine?.statusCode?.toString() ?: 'unknown'
					String detail = describeErrorBody(readErrorBody(response)) ?: buildErrorMessage(apiResponse)
					return [success: false, msg: "OpenRouter API returned ${statusCode}: ${detail}".toString()]
				}
				return [success: true, data: consumeEventStream(response, onText)]
			}
		} catch (Exception e) {
			log.error("Error during OpenRouter streaming completion: ${e.message}", e)
			return [success: false, msg: e.message]
		} finally {
			response?.close()
		}
	}

	/**
	 * Read an OpenAI-style SSE stream into a payload shaped like a non-streaming
	 * /chat/completions response.
	 *
	 * What OpenRouter sends, as observed:
	 *  - text in {@code choices[0].delta.content}; chunks that carry reasoning or a
	 *    tool call have it empty or null, and only real text reaches onText
	 *  - reasoning in {@code delta.reasoning}, kept apart from the answer
	 *  - tool calls as fragments per {@code index}: id and name in the first,
	 *    the arguments in pieces
	 *  - a last chunk before {@code [DONE]} that repeats finish_reason and carries usage
	 *  - {@code : OPENROUTER PROCESSING} comment lines as keep-alive
	 *  - an error after the response has started as a chunk with a top-level
	 *    {@code error}, the HTTP status having been 200
	 */
	protected Map consumeEventStream(CloseableHttpResponse response, Closure onText) {
		Map accumulated = [id: null, model: null, provider: null, usage: null]
		String finishReason = null
		String nativeFinishReason = null
		StringBuilder content = new StringBuilder()
		StringBuilder reasoning = new StringBuilder()
		Map<Integer, Map> toolCalls = new TreeMap<>()
		JsonSlurper slurper = new JsonSlurper()

		response.entity?.content?.withReader('UTF-8') { Reader reader ->
			String line
			while ((line = reader.readLine()) != null) {
				// Comment lines start with ':' and are not JSON.
				if (!line.startsWith('data:')) {
					continue
				}
				String payload = line.substring(5).trim()
				if (payload == '[DONE]') {
					break
				}
				if (!payload) {
					continue
				}
				Map event
				try {
					event = slurper.parseText(payload) as Map
				} catch (Exception ignored) {
					log.debug("Skipping unparseable SSE payload of ${payload.length()} characters")
					continue
				}
				if (event.error instanceof Map) {
					throw new RuntimeException("OpenRouter stream error: ${describeError(event.error as Map)}")
				}
				accumulated.id = accumulated.id ?: event.id
				accumulated.model = accumulated.model ?: event.model
				accumulated.provider = accumulated.provider ?: event.provider
				if (event.usage instanceof Map) {
					accumulated.usage = event.usage
				}
				List choices = event.choices instanceof List ? event.choices as List : []
				Map choice = choices && choices[0] instanceof Map ? choices[0] as Map : [:]
				Map delta = choice.delta instanceof Map ? choice.delta as Map : [:]
				if (delta.content instanceof CharSequence && delta.content.toString()) {
					String chunk = delta.content.toString()
					content.append(chunk)
					onText?.call(chunk)
				}
				if (delta.reasoning instanceof CharSequence) {
					reasoning.append(delta.reasoning.toString())
				}
				if (delta.tool_calls instanceof List) {
					accumulateToolCalls(toolCalls, delta.tool_calls as List)
				}
				if (choice.finish_reason != null) {
					finishReason = choice.finish_reason.toString()
				}
				if (choice.native_finish_reason != null) {
					nativeFinishReason = choice.native_finish_reason.toString()
				}
			}
		}

		Map message = [role: 'assistant', content: content.toString()]
		if (reasoning.length() > 0) {
			message.reasoning = reasoning.toString()
		}
		if (toolCalls) {
			message.tool_calls = toolCalls.values().collect { Map call ->
				[id: call.id, type: call.type, function: [name: call.name, arguments: call.arguments.toString()]]
			}
		}
		accumulated.choices = [[index: 0, finish_reason: finishReason, native_finish_reason: nativeFinishReason, message: message]]
		return accumulated
	}

	/**
	 * Joins streamed tool-call fragments. The name is taken once rather than
	 * appended, so a provider that repeats it in every fragment does not double it.
	 */
	protected static void accumulateToolCalls(Map<Integer, Map> calls, List fragments) {
		fragments.each { fragment ->
			if (!(fragment instanceof Map)) {
				return
			}
			Map part = fragment as Map
			Map function = part.function instanceof Map ? part.function as Map : [:]
			Integer index = part.index instanceof Number ? ((Number) part.index).intValue() : indexWithoutPosition(calls, part.id?.toString())
			Map call = calls.computeIfAbsent(index) { Integer ignored ->
				[id: null, type: 'function', name: null, arguments: '']
			}
			if (part.id) {
				call.id = part.id.toString()
			}
			if (part.type) {
				call.type = part.type.toString()
			}
			if (function.name && !call.name) {
				call.name = function.name.toString()
			}
			if (function.arguments != null) {
				call.arguments = call.arguments.toString() + function.arguments.toString()
			}
		}
	}

	/** A fragment without an index continues the latest call, unless it brings a new id. */
	protected static Integer indexWithoutPosition(Map<Integer, Map> calls, String id) {
		if (calls.isEmpty()) {
			return 0
		}
		Integer last = calls.keySet().max()
		String lastId = calls.get(last).id
		return id && lastId && id != lastId ? last + 1 : last
	}

	protected Map executeGet(String baseUrl, String path, String apiKey, Map opts = [:]) {
		try {
			HttpApiClient.RequestOptions requestOptions = buildRequestOptions(apiKey, ['Accept': 'application/json'], null, DEFAULT_READ_TIMEOUT)
			return withApiClient(opts) { HttpApiClient apiClient ->
				ServiceResponse apiResponse = apiClient.callJsonApi(baseUrl, path, null, null, requestOptions, 'GET')
				return normalizeResponse(apiResponse)
			}
		} catch (Exception e) {
			log.error("Error executing GET ${baseUrl}${path}: ${e.message}", e)
			return [success: false, msg: e.message]
		}
	}

	protected Map executePost(String baseUrl, String path, String apiKey, Map requestBody, Map opts = [:]) {
		try {
			HttpApiClient.RequestOptions requestOptions = buildRequestOptions(apiKey, [
				'Content-Type': 'application/json',
				'Accept'      : 'application/json'
			], requestBody, DEFAULT_INTERACT_READ_TIMEOUT)
			return withApiClient(opts) { HttpApiClient apiClient ->
				ServiceResponse apiResponse = apiClient.callJsonApi(baseUrl, path, null, null, requestOptions, 'POST')
				return normalizeResponse(apiResponse)
			}
		} catch (Exception e) {
			log.error("Error executing POST ${baseUrl}${path}: ${e.message}", e)
			return [success: false, msg: e.message]
		}
	}

	protected Map normalizeResponse(ServiceResponse apiResponse) {
		if (apiResponse?.success) {
			Map responseData = apiResponse?.data instanceof Map ? apiResponse.data as Map : [:]
			if (!(apiResponse?.data instanceof Map) && apiResponse?.data != null) {
				responseData.data = apiResponse.data
			}
			return [success: true, data: responseData, headers: apiResponse?.headers]
		}
		return [success: false, msg: buildErrorMessage(apiResponse), headers: apiResponse?.headers]
	}

	protected <T> T withApiClient(Map opts = [:], Closure<T> work) {
		evictExpiredSessionClients()
		String clientScopeKey = opts?.get(CLIENT_SCOPE_KEY)?.toString()?.trim() ?: null
		if (clientScopeKey) {
			long now = System.currentTimeMillis()
			SessionClientHolder sessionClient = sessionClients.compute(clientScopeKey) { String key, SessionClientHolder existing ->
				if (existing && !isExpired(existing, now)) {
					existing.lastUsedAt = now
					return existing
				}
				if (existing?.apiClient) {
					existing.apiClient.shutdownClient()
				}
				return new SessionClientHolder(new HttpApiClient(true), now)
			}
			return work.call(applyNetworkProxy(sessionClient.apiClient, opts))
		}
		HttpApiClient apiClient = new HttpApiClient()
		try {
			return work.call(applyNetworkProxy(apiClient, opts))
		} finally {
			apiClient?.shutdownClient()
		}
	}

	/**
	 * Route this client through the configured proxy, if there is one.
	 *
	 * Assigned unconditionally, null included: a pooled client outlives a single
	 * call, so clearing the proxy on the integration has to clear it here too.
	 */
	protected HttpApiClient applyNetworkProxy(HttpApiClient apiClient, Map opts) {
		def proxy = opts?.get(NETWORK_PROXY_KEY)
		apiClient.networkProxy = proxy instanceof NetworkProxy ? (NetworkProxy) proxy : null
		return apiClient
	}

	protected void evictExpiredSessionClients() {
		long now = System.currentTimeMillis()
		sessionClients.each { String clientScopeKey, SessionClientHolder holder ->
			if (holder && isExpired(holder, now) && sessionClients.remove(clientScopeKey, holder)) {
				holder.apiClient?.shutdownClient()
			}
		}
	}

	protected boolean isExpired(SessionClientHolder holder, long now = System.currentTimeMillis()) {
		return holder == null || (now - holder.lastUsedAt) > SESSION_CLIENT_TTL_MS
	}

	/**
	 * The key travels in an explicit Authorization header.
	 *
	 * ignoreSSL is set to false on purpose, not left at its default. RequestOptions
	 * defaults it to true, and with true the HttpApiClient of plugin API 1.4.1 sends
	 * no SNI in the TLS handshake, which Cloudflare in front of openrouter.ai answers
	 * with handshake_failure. That is why HPE's Local LLM plugin cannot reach it.
	 */
	protected HttpApiClient.RequestOptions buildRequestOptions(String apiKey, Map<CharSequence, CharSequence> headers, Object body, Integer readTimeout) {
		Map<CharSequence, CharSequence> allHeaders = [:]
		allHeaders.putAll(headers ?: [:])
		if (apiKey) {
			allHeaders.put('Authorization', "Bearer ${apiKey}".toString())
		}

		HttpApiClient.RequestOptions options = new HttpApiClient.RequestOptions(
			headers          : allHeaders,
			ignoreSSL        : false,
			connectionTimeout: DEFAULT_CONNECTION_TIMEOUT,
			readTimeout      : readTimeout
		)
		if (body != null) {
			// Serialised here, to ASCII-only JSON, rather than handed over as a map. The
			// HttpApiClient in plugin API 1.4.1 - the one Morpheus 9.0.1 ships - wraps the
			// JSON in a StringEntity without a charset, which Apache HttpCore encodes as
			// ISO-8859-1, and OpenRouter turned every umlaut into U+FFFD. With every
			// non-ASCII character escaped as \\uXXXX the body is the same bytes in any of
			// those encodings. A byte array is no way out: callJsonApi serialises it
			// again, as Base64.
			options.body = toAsciiJson(body)
		}
		return options
	}

	/**
	 * JSON with every character above U+007E written as a \\uXXXX escape. Groovy's
	 * JsonOutput already does this, but that is a default of one library version; a
	 * non-ASCII character can only sit inside a JSON string, so escaping whatever is
	 * left is always valid, and a surrogate pair becomes two escapes that decode back
	 * into the one character.
	 */
	static String toAsciiJson(Object body) {
		String json = JsonOutput.toJson(body)
		StringBuilder ascii = null
		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i)
			if ((int) c > 0x7E) {
				if (ascii == null) {
					ascii = new StringBuilder(json.length() + 16).append(json, 0, i)
				}
				ascii.append(String.format('\\u%04x', (int) c))
			} else {
				ascii?.append(c)
			}
		}
		return ascii != null ? ascii.toString() : json
	}

	/**
	 * OpenRouter errors look like
	 * {"error":{"message":"...","code":400,"metadata":{...}},"user_id":"user_..."}.
	 * Only the message is used: the body also names the account's user id, which
	 * has no business in the appliance log.
	 */
	protected String buildErrorMessage(ServiceResponse response) {
		if (response == null) {
			return 'Unknown error'
		}
		Map responseData = response.data instanceof Map ? response.data as Map : [:]
		String errorMessage = responseData.error instanceof Map ? describeError(responseData.error as Map) : null
		errorMessage = errorMessage ?: describeErrorBody(response.content) ?: response.error ?: response.msg
		String errorCode = response.errorCode ?: response.statusCode
		if (errorCode && errorMessage) {
			return "API returned ${errorCode}: ${errorMessage}"
		}
		return errorCode ? "API returned ${errorCode}" : (errorMessage ?: 'Unknown error')
	}

	/** The message of an OpenRouter error object, with the upstream provider when it names one. */
	protected static String describeError(Map error) {
		String message = error.message?.toString() ?: error.code?.toString() ?: 'unknown error'
		Map metadata = error.metadata instanceof Map ? error.metadata as Map : [:]
		return metadata.provider_name ? "${message} (provider: ${metadata.provider_name})".toString() : message
	}

	/** The message of a JSON error body, or the start of any other body. */
	protected static String describeErrorBody(String body) {
		if (!body?.trim()) {
			return null
		}
		try {
			def parsed = new JsonSlurper().parseText(body)
			if (parsed instanceof Map && (parsed as Map).error instanceof Map) {
				return describeError((parsed as Map).error as Map)
			}
		} catch (Exception ignored) {
		}
		String flat = body.trim().replaceAll(/\s+/, ' ')
		return flat.length() > MAX_ERROR_BODY_LENGTH ? flat.substring(0, MAX_ERROR_BODY_LENGTH) + '...' : flat
	}

	protected String readErrorBody(CloseableHttpResponse response) {
		try {
			if (response?.entity) {
				return EntityUtils.toString(response.entity, 'UTF-8')
			}
		} catch (Exception ignored) {
		}
		return null
	}
}
