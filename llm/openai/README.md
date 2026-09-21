# Morpheus OpenAI-Compatible LLM Plugin

An `LlmProvider` plugin for HPE Morpheus Enterprise 9.0 that adds **OpenAI-Compatible API** as an AI
integration type under *Tools > AI Services > Integrations*, so Morpheus AI agents can use the chat
models of any API that speaks OpenAI's `/chat/completions` and `/models`: **api.openai.com**,
**OpenRouter**, **LiteLLM**, vLLM, Ollama, LM Studio, llama.cpp and the gateways in front of them,
over HTTP or HTTPS.

> **Independent community project.** Not an official OpenAI, OpenRouter, Anthropic or HPE product,
> and neither endorsed by nor affiliated with any of them. See [Trademarks](#trademarks).

- **Why not the Local LLM plugin's "OpenAI Compatible" integration?** HPE's Local LLM plugin 1.0.0,
  which Morpheus 9.0.2 still ships, cannot reach `api.openai.com`, `openrouter.ai` or any other
  endpoint behind a CDN: it sends no SNI in the TLS handshake, and the save fails with
  `handshake_failure`. It has no proxy setting, no local key field, no model allow list and a fixed
  output limit. See [docs/hpe/hpe-bug-report-local-llm-sni.md](../../docs/hpe/hpe-bug-report-local-llm-sni.md).
  On a plain-HTTP endpoint on your own network both plugins work.
- **OpenRouter as your only endpoint?** The [OpenRouter plugin](../openrouter/README.md) knows
  OpenRouter's catalog and in-region routing. This plugin works against OpenRouter too, and shows
  the same cost footer there.
- **Claude models:** use the [Anthropic Claude plugin](../anthropic/README.md), which caches the MCP
  tool catalog. Through an OpenAI-compatible API the catalog is billed on every tool round.

## What it does

| Capability | Status |
|---|---|
| Chat completions and streaming | yes |
| Tool use / function calling (MCP) | yes — OpenAI format, passed through |
| Model catalog sync | yes — chat models only, with an optional allow list |
| Key check on save | yes — `GET /models` with the key; OpenRouter also `GET /key`, since its model list is public |
| API key | optional — a LAN endpoint may take none |
| OpenAI reasoning models (o-series, GPT-5, codex) | `max_completion_tokens`, no sampling parameters, at least 8192 output tokens |
| Empty tool arguments | dropped before Morpheus runs the tool — optional, on by default |
| Cut-off answers | say so |
| Errors visible in chat | yes — the API's message and what to do, instead of Morpheus' generic error |
| Rate limits | waited out twice before a question fails |
| Usage visible in chat | optional footer: tokens, or the cost of the whole question where the API reports one |
| Reasoning effort | optional — `reasoning_effort` low, medium or high; reasoning never shown |
| Outbound proxy | yes — select a Morpheus network proxy per integration |
| Form translations | English, German, Polish |
| Certificate checks | always on; an HTTPS endpoint needs a certificate the appliance trusts |
| Embeddings | no |

---

## Quick start

### Prerequisites

- **HPE Morpheus Enterprise 9.0.0 or newer.** Built and tested on 9.0.2 (plugin API 1.4.1).
- **A route from the appliance to the endpoint**, directly or through a Morpheus network proxy. The
  appliance makes the calls, not your browser. An HTTPS endpoint needs a certificate the appliance
  trusts: public CAs are fine, a self-signed one is not.
- **An API key**, when the endpoint wants one. For api.openai.com a key with a spending limit; for
  OpenRouter a key with a credit limit; for LiteLLM a virtual key with a budget. A looping agent is
  many requests.

### 1. Install the plugin

Download `morpheus-openai-plugin-<version>-all.jar` from the
[release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/openai-v0.1.0) and
upload it under *Administration > Integrations > Plugins > Add*. The plugin registers two providers:
`LLM OpenAI-Compatible API` and `OPTION OpenAI-Compatible API Options`.

### 2. Create the integration

*Tools > AI Services > Integrations > + New Integration > OpenAI-Compatible API* (not HPE's
*OpenAI Compatible*, which sits next to it in the list):

| Field | Value |
|---|---|
| **API Endpoint** | The base URL including the version path: `https://api.openai.com/v1`, `https://openrouter.ai/api/v1`, `http://litellm.example.com:4000/v1`, `http://ollama.example.com:11434/v1`. The plugin appends `/models` and `/chat/completions` and adds nothing else. A pasted endpoint path or trailing slash is removed. |
| **Credentials** | *Local Credentials* with the key below, or an *API Key* credential from *Infrastructure > Trust > Credentials*. Leave the key empty for an endpoint that takes none. |
| **API Key** | the key, for *Local Credentials* |
| **Route Outbound Calls Through a Proxy** | off means a direct connection; see [Network proxy](#network-proxy) |
| **Network Proxy** | the proxy to use when the box above is ticked |
| **Default Max Output Tokens** | leave empty to let each model use its own maximum; see [Output limit](#output-limit-and-reasoning-models) |
| **Reasoning Effort** | *Model default* sends nothing; see [Reasoning](#reasoning) |
| **Only List These Models** | `*` lists every chat model; see [Allow list](#allow-list) |
| **Append Usage to Answers** | optional; see [Usage footer](#usage-footer) |
| **Show API Errors in Chat** | on; see [Errors in the chat](#errors-in-the-chat) |
| **Drop Empty Tool Arguments** | on; see [Empty tool arguments](#empty-tool-arguments) |

**Save.** The plugin loads `GET /models` with the key and rejects the save with the reason in the
form when that fails: a `401` or `403` says *Check the API key*, a `404` or a web page instead of a
model list points at the version path (`/v1`), a failed TLS handshake at the certificate. On
`openrouter.ai` the plugin also calls `GET /key`, because OpenRouter serves its model list to any
key. A healthy integration reports status `ok` and its model count.

The form follows the viewer's *Default Locale* in *User Settings* — English, German and Polish.

### 3. Build an agent

*Tools > AI Services > Agents > Create Agent*: pick the integration and a model, and attach the
built-in **Morpheus** MCP server. Leave **Read-only mode** on for a first run: the built-in MCP
server also offers tools that change or delete infrastructure. The chat's agent picker loads its
list with the page — reload the page if a new agent is missing.

### Verified endpoints

| Endpoint | API Endpoint value | Verified on 2026-09-21 |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | 47 models with the allow list `gpt-5*, gpt-4.1*`; agent on `gpt-5.4-mini` lists servers through MCP tools; OpenAI's prompt caching serves the tool catalog from the second request |
| OpenRouter | `https://openrouter.ai/api/v1` | 25 models with `openai/gpt-5*`, 287 without an allow list; agent on `openai/gpt-5.4-mini`; a wrong key is refused on save |
| LiteLLM (plain HTTP on the LAN) | `http://<host>:4000/v1` | 3 models, ids as names; agent on `deepseek-v3.2` with tool rounds; umlauts intact in both directions |

---

## Which models are listed

`GET /models` answers differently everywhere. api.openai.com lists ids only, every kind of model
under one endpoint; OpenRouter says what each model supports; LiteLLM lists what its config names.
The plugin lists a model when

- its id does not say it is something else than a chat model: no `embedding`, `whisper`, `tts`,
  `transcribe`, `dall-e`, `image`, `moderation`, `realtime`, `audio`, `sora`, `search`,
  `babbage`, `davinci`, `instruct`, `computer-use`, `rerank`, `guard` or `batch`;
- the catalog, when it says so, lists tool calling (`tools` in `supported_parameters`) and text-only
  output;
- it has not passed its expiration date, when the catalog has one.

Names are taken from the catalog when it has them (OpenRouter: *OpenAI: GPT-5.5*) and are the id
otherwise (`gpt-5.4-mini`, `deepseek-v3.2`) — never title-cased, so the agent form shows the spelling
the endpoint expects.

### Allow list

The model tab of an integration has no search box, and api.openai.com alone lists more than a
hundred models. **Only List These Models** narrows the list to the models you actually use:

- Comma-separated model ids, `*` as wildcard, case-insensitive, matching the whole id:
  `gpt-5*, gpt-4.1-mini` or `openai/gpt-5*, google/gemini-3*`.
- It only narrows. It never brings back a model the rules above leave out.
- **`*` lists every chat model, and it is the default.** Morpheus 9.0.1 does not save a text field
  that is emptied on edit — the form puts the old value back — so enter `*` instead of clearing it.
- An allow list that matches no model at all is ignored, with a warning in the log, instead of
  emptying the integration.

### When a model drops out

Saving the integration refreshes the list, as does *Actions > Refresh* and Morpheus' periodic sync.
A model that is no longer listed — gone from the endpoint, or filtered out by a changed setting —
is **removed**, because Morpheus offers disabled models in the agent form like enabled ones. A model
that an agent still uses cannot be removed; it stays **disabled** until it is listed again, and the
log names it. Each refresh logs one line:

```
Model sync for integration 8: 45 listed, 0 added, 0 updated, 2 removed, 0 disabled because an agent still uses them
```

---

## Output limit and reasoning models

Morpheus asks for **1000 output tokens** on every chat request. For most models that is enough for
an answer built from MCP data. OpenAI's reasoning models — the o-series, GPT-5 and codex, with or
without a vendor prefix such as `openai/` — think inside that budget: `gpt-5-mini` spent 960 tokens
reasoning and answered nothing. The plugin therefore

- sends them `max_completion_tokens` instead of the deprecated `max_tokens`, and **at least 8192**
  when the caller asks for less, unless **Default Max Output Tokens** sets a limit of its own;
- sends them no `temperature` and no `top_p`, which api.openai.com would reject with `400`;
- forwards `temperature`, `top_p` and stop sequences unchanged to every other model.

An answer that hits the limit anyway ends mid-sentence, and the chat gives no sign of it. The
plugin appends a line — *Answer cut off at the output token limit.* — in the language of the answer,
and shows only that line when there was no text at all. The line is removed before the answer is
replayed as history.

## Reasoning

**Reasoning Effort** decides what the plugin asks for:

- **Model default** sends nothing; each model does what it does by default.
- **Low**, **Medium** or **High** is sent as `reasoning_effort` on every request, OpenAI's
  parameter, which OpenRouter and LiteLLM accept as well. An endpoint that does not know it may
  reject the request.

Reasoning text — `reasoning` or `reasoning_content` in the response — is never put into the answer
or the stream. The appliance log shows the reasoning token count of every request.

## Empty tool arguments

Some models fill every optional parameter of a tool call with an empty value, and Morpheus' built-in
MCP tools take `""`, `0` and `false` as filters: `list_servers` with `status: ""` or `zoneId: 0`
finds nothing, while `list_servers` without arguments lists every server. Seen with
`openai/gpt-5.4-mini` through OpenRouter (every one of 20 parameters filled in, answer *No servers
were found*) and with `openai/gpt-5.4-nano`.

With **Drop Empty Tool Arguments** on, the default, the plugin removes top-level arguments whose
value is `""`, `0`, `false`, `null`, `[]` or `{}` from the model's tool calls before Morpheus runs
the tool, which is the same as the model leaving them out. The log names what was dropped:

```
Tool calls: list_servers {"name":"","phrase":"","zoneId":0,"siteId":0,"managed":false,...}
Dropped empty arguments from list_servers: name, phrase, zoneId, siteId, managed, ...
```

Untick it to forward tool calls exactly as the model wrote them.

## Errors in the chat

Morpheus replaces every error a provider reports with a text of its own — *The AI model is no
longer available*, *An error occurred while processing your request* — which says nothing about
the cause. With **Show API Errors in Chat** ticked, the default, the plugin answers a failed
question itself, with the API's message and what to do about it:

> **LLM API error 401:** Incorrect API key provided: sk-proj-...
>
> The API did not accept the API key of this integration. Check the key.

- The heading carries the HTTP status; the advice covers 400, 401, 402, 403, 404, 408, 429 and
  the 5xx errors. Both follow the language of the question (English, German, Polish).
- With the usage footer on, the answer ends with what the question cost until it failed, where the
  API reports costs.
- Text a stream had already shown stays above the error.
- Error answers are left out when the conversation is replayed to the model.
- **Before a question fails, a rate limit (`429`) or an overloaded provider (`503`) is waited out
  twice**, 10 and then 20 seconds, or as long as a stream's `Retry-After` asks, up to 30 seconds.
  A stream that has already shown text is not retried, since that would repeat it.

Unticked, the plugin reports the error to Morpheus as before. Every failure is in the log either
way: `grep 'chat completion failed' /var/log/morpheus/morpheus-ui/current`.

## Usage footer

Morpheus shows no token or cost figures in the chat. With **Append Usage to Answers** ticked, each
final answer ends with an italic line:

- where the API reports a cost per request, as OpenRouter does, the cost of the whole question
  summed over all of its requests — *Cost: $0.0070 (3 requests)*;
- otherwise the tokens of the last request — *Tokens: 15,829 input (14,464 cached), 54 output*.

The label follows the language of the answer (English, German, Polish). Tool-call turns get no
footer, and footers are removed from answers before they are replayed as history, so a model never
learns to write its own.

### Every request in the log

```bash
grep OpenAiProvider /var/log/morpheus/morpheus-ui/current
```

```
Request: model=gpt-5.4-mini messages=2 tools=68 max_completion_tokens=8192 temperature=null reasoning_effort=null stream=false
Tool calls: use_servers_tools {"filter":"list_servers"}
Usage: model=gpt-5.4-mini-2026-03-17 provider=unknown input=13928 cached=0 output=19 reasoning=0 cost=n/a
```

The request line shows the shape of every request, never its content. A question answered without
a `Tool calls` line did not use MCP data. `provider` and `cost` come from OpenRouter; api.openai.com
reports neither.

### Prompt caching

The plugin sets no cache breakpoints. Providers that cache on their own still do: with OpenAI models,
direct or through OpenRouter, the MCP tool catalog came from OpenAI's cache from the second request
of a question on. Claude through an OpenAI-compatible API is billed the whole catalog on every tool
round — 25,000 tokens per request on 2026-09-21 — which is what the Anthropic plugin avoids.

## Network proxy

Morpheus keeps proxies under *Infrastructure > Networks > Proxies*. **Route Outbound Calls Through a
Proxy** is the switch and **Network Proxy** the choice. The checkbox exists because Morpheus 9.0.1
renders the proxy dropdown without an empty entry: a chosen proxy can be changed, never removed, and
unticking the box is how you go back to a direct connection. The proxy applies to every call the
plugin makes — the model sync and the chat.

## Certificates

The plugin always verifies the endpoint's certificate and always sends SNI. There is no *ignore
certificate errors* option on purpose: in plugin API 1.4.1 that setting also drops SNI, which is the
very defect that keeps HPE's Local LLM plugin from reaching CDN-fronted endpoints. A self-signed
endpoint therefore fails the save with a handshake error; give it a certificate the appliance
trusts, or use plain HTTP on a trusted network.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| The chat says **"The AI model is no longer available"** | Morpheus shows most provider errors this way. Tick **Show API Errors in Chat** to see the API's message instead; the real reason is also in the appliance log: `grep OpenAiProvider /var/log/morpheus/morpheus-ui/current`. |
| Save fails with `Could not load the model list from .../models: API returned 401 ... Check the API key.` | The endpoint rejected the key. api.openai.com and LiteLLM answer `401`, OpenRouter's `/key` answers `401: User not found.` or `Missing Authentication header`. |
| Save fails with `... returned 404 ... Check the API Endpoint` or `... returned no model list` | The API Endpoint stops short of the version path, or points at a web page. Most APIs sit under `/v1`; OpenRouter under `/api/v1`. |
| Save fails with `handshake_failure` | The appliance does not trust the endpoint's certificate, or the endpoint needs TLS settings the appliance lacks. See [Certificates](#certificates). |
| Save fails with `The API Endpoint must be an http:// or https:// URL` | The scheme is missing. |
| The chat says **"An error occurred while processing your request"** or shows nothing | The model answered nothing. With a reasoning model before 0.1.0 this was the 1000-token budget; now the answer says *cut off at the output token limit* instead. Otherwise look for `Usage:` and `WARN` in the log. |
| A reasoning model's answers are cut off | Raise **Default Max Output Tokens** or leave it empty; the floor of 8192 applies only when the field is empty. |
| An agent answers **"0"**, "none" or "No servers were found" although the objects exist | The model filled optional tool parameters with empty values. **Drop Empty Tool Arguments** is on by default; the log shows `Dropped empty arguments from ...`. If it is unticked, tick it or use another model. |
| **Only List These Models** cannot be emptied | Morpheus 9.0.1 puts the old value back into an emptied field. Enter `*`. |
| Switching from a stored credential to *Local Credentials* is not saved | Morpheus 9.0.1 keeps the stored credential on edit. Change the key inside that credential under *Infrastructure > Trust > Credentials*, or create a new integration and point the agents at it. |
| The log says `Only List These Models '...' matches none of N models and is ignored` | A typo in the allow list. The list stays complete until it is fixed. |
| An agent fails after the model list was narrowed | Its model is disabled because it is no longer listed. The log names it. List it again, or give the agent another model. |
| The model tab has no search | Not available to plugins. Use the allow list. Reported to HPE: [docs/hpe/hpe-feature-request-llm-model-search.md](../../docs/hpe/hpe-feature-request-llm-model-search.md). |
| An agent calls `get_result_excerpt` over and over | The built-in MCP tools return a large result truncated, as a preview with an artifact id, and a model can page through it piece by piece. Ask a narrower question. |
| An answer looks invented | Check for `Tool calls` in the log. Smaller models sometimes answer from the example values in the MCP tool descriptions instead of calling a tool. |
| A new agent is missing from the chat's agent picker | The picker loads its list with the page. Reload the page. |
| The Polish dialog title reads *Edytuj {0} Integrację* | Morpheus' own Polish text; it affects every integration. |

The problems of Morpheus' own integration form are described for HPE in
[docs/hpe/hpe-bug-report-integration-edit.md](../../docs/hpe/hpe-bug-report-integration-edit.md).

## Version compatibility

| | |
|---|---|
| Minimum appliance | 9.0.0 (`Morpheus-Min-Appliance-Version`) |
| Tested on | HPE Morpheus Enterprise 9.0.2 (plugin API 1.4.1, Java 25) |
| Built against | `morpheus-plugin-api` 1.4.2; the HTTP client tests also run against 1.4.1 |

## Build from source

Requires JDK 17 (**not 21** — Groovy 3.0.9) and the bundled Gradle wrapper. Run it in `llm/openai`.

```bash
./gradlew clean test shadowJar
# -> build/libs/morpheus-openai-plugin-<version>-all.jar
./gradlew test --tests '*ApiServiceSpec*' -PmorpheusPluginApiVersion=1.4.1
```

## Relationship to other plugins

| Use case | Plugin |
|---|---|
| Claude, with prompt caching and Anthropic's web search — direct or through OpenRouter | [Anthropic Claude plugin](../anthropic/README.md) |
| OpenRouter's catalog with its filters, regional routing and the cost footer | [OpenRouter plugin](../openrouter/README.md) |
| api.openai.com, LiteLLM, vLLM, Ollama, any other OpenAI-compatible API, over HTTP or HTTPS | this plugin |
| Ollama, vLLM, LM Studio or llama.cpp over plain HTTP, without a key or proxy | HPE's Local LLM plugin works too |

## Attribution

Derived from this repository's [OpenRouter plugin](../openrouter), itself derived from the Apache 2.0
licensed [HewlettPackard/morpheus-copilot-plugin](https://github.com/HewlettPackard/morpheus-copilot-plugin)
(structure, Gradle setup, session-scoped HTTP client pattern). See [NOTICE](NOTICE).

### Trademarks

"OpenAI", "GPT" and "ChatGPT" are trademarks of OpenAI; "OpenRouter" is a trademark of its owner;
"Anthropic" and "Claude" are trademarks of Anthropic PBC; "HPE", "Hewlett Packard Enterprise" and
"Morpheus" are trademarks of Hewlett Packard Enterprise. Other model and vendor names are trademarks
of their respective owners. They are used here solely to identify the services this plugin
integrates with. The icon in `src/assets/images/` is original artwork, not a brand asset of any of
them.

## License

[Apache License 2.0](LICENSE)
