# Local LLM plugin 1.0.0: "OpenAI Compatible" cannot connect to endpoints that require SNI (OpenRouter, api.openai.com)

## Summary

An **OpenAI Compatible** integration from the Local LLM plugin cannot be created against OpenRouter
or the OpenAI API. Saving fails with **"Failed to create integration"**, and nothing is written to
the appliance log.

The TLS handshake never completes. The plugin always sets `ignoreSSL: true`. With that option, the
`HttpApiClient` in plugin API 1.4.1 explicitly removes the SNI server name from the TLS ClientHello.
Endpoints behind Cloudflare, which includes both hosts above, reject a handshake without SNI.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.0.1, all-in-one, Ubuntu 24.04.4 LTS |
| Java on the appliance | Temurin 25.0.2 (bundled) |
| Plugin API | `morpheus-plugin-api` 1.4.1, as shipped with 9.0.1 |
| Plugin | Local LLM 1.0.0 (`local-llm-plugin`, `com.morpheusdata.localllm.LocalLlmPlugin`) |
| Integration type | OpenAI Compatible (`openai-compatible`) |

## Steps to reproduce

1. *Tools > AI Services > Integrations > Add*, type **OpenAI Compatible**.
2. **API Endpoint:** `https://openrouter.ai/api/v1`. The same happens with `https://api.openai.com/v1`.
3. **Credentials:** a valid API key.
4. Save.

**Expected:** the integration validates and the model list is synced.

**Actual:** "Failed to create integration". No entry in `/var/log/morpheus/morpheus-ui/current`.

Other URL spellings fail the same way: `https://openrouter.ai/api`, `https://openrouter.ai/api/v1/`
and `https://openrouter.ai`.

## Root cause

1. **The plugin forces `ignoreSSL: true`.**
   `com.morpheusdata.localllm.LocalLlmApiService.buildRequestOptions(...)` sets `ignoreSSL: true`
   unconditionally. The integration form has no option to change it.
2. **`HttpApiClient` removes SNI when `ignoreSSL` is true.** For that case, plugin API 1.4.1
   installs an anonymous `SSLConnectionSocketFactory` (`HttpApiClient$5`). Its `prepareSocket()`
   does the equivalent of:

   ```java
   SSLParameters params = socket.getSSLParameters();
   params.setServerNames(Collections.emptyList());
   socket.setSSLParameters(params);
   ```

   The ClientHello therefore carries no `server_name` extension. `RequestOptions.ignoreSSL` also
   defaults to `true`: a request with default options behaves the same.
3. **Cloudflare rejects a handshake without SNI:** `Received fatal alert: handshake_failure`.
4. **The failure is not shown.** `OpenAICompatibleProvider.validate()` calls
   `LocalLlmApiService.listModelsOpenAI(...)`, gets `success: false`, and returns a
   `ServiceResponse.error`. That message is neither logged nor shown in the form.

## Evidence

**TLS from the appliance** (`openssl s_client -connect <host>:443`):

| Host | with `-servername <host>` | with `-noservername` |
|---|---|---|
| openrouter.ai | handshake completes (`TLS_AES_256_GCM_SHA384`) | `handshake failure` |
| api.openai.com | handshake completes (`TLS_AES_256_GCM_SHA384`) | `handshake failure` |

**Plugin API 1.4.1 `HttpApiClient`, called standalone** with
`callJsonApi("https://openrouter.ai/api", "/v1/models", null, null, options, "GET")`:

| `options.ignoreSSL` | Result | `server_name` in ClientHello (`-Djavax.net.debug=ssl:handshake:verbose`) |
|---|---|---|
| `true` | `success: false`, handshake failure | absent |
| `false` | `success: true`, model list returned | present |

Against `https://api.openai.com` + `/v1/models` with a dummy key: `true` fails the handshake;
`false` reaches the API and gets `401`, as expected.

**The plugin's own code, called standalone:**
`LocalLlmApiService.listModelsOpenAI("https://openrouter.ai/api/v1", <key>, [:])` returns:

```
success=false
msg=Error occurred processing the response for https://openrouter.ai/api/v1/models : (handshake_failure) Received fatal alert: handshake_failure
```

**Not tested:** a complete chat with a valid OpenAI key. The connection fails before any
authentication, so the key makes no difference.

## Suggested fix

- **Local LLM plugin**
  - Default to `ignoreSSL: false`.
  - Offer an **"Ignore SSL certificate errors"** checkbox, off by default, for self-hosted servers
    with self-signed certificates.
  - Log the validation error, and show its message in the form instead of the generic "Failed to
    create integration".
- **Plugin API `HttpApiClient`**
  - Keep SNI when `ignoreSSL` is true. Skipping certificate and hostname verification does not
    require hiding the server name.
  - If clearing the server names was added for servers that answer with `unrecognized_name`, make
    that a fallback rather than the default.
  - This affects every plugin that uses `HttpApiClient` with `ignoreSSL` left at its default.

## Secondary issue: `normalizeBaseUrl` removes a required path prefix

`LocalLlmApiService.normalizeBaseUrl(String)` strips a trailing `/`, then either `/v1` or, if the
URL does not end in `/v1`, `/api`. The model list is then requested at
`normalizeBaseUrl(url) + "/v1/models"`. Results from calling the method directly:

| API Endpoint entered | After `normalizeBaseUrl` | Models request |
|---|---|---|
| `https://openrouter.ai/api/v1` | `https://openrouter.ai/api` | `/api/v1/models`: correct |
| `https://openrouter.ai/api/v1/` | `https://openrouter.ai/api` | `/api/v1/models`: correct |
| `https://openrouter.ai/api` | `https://openrouter.ai` | `/v1/models`: OpenRouter's website, HTML with status 200 |

Any provider whose OpenAI-compatible API lives under an `/api` prefix is affected once the
entered URL does not end in `/v1`. Suggested fix: strip only a trailing `/v1`, and treat a
non-JSON response as a validation error that names the URL requested.

## Workaround

None inside the plugin. A reverse proxy that Morpheus reaches over plain HTTP and that connects
upstream over HTTPS with SNI works around it, for example nginx with `proxy_ssl_server_name on;`.
The API key then crosses the local network unencrypted.
