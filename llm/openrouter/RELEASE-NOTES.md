# OpenRouter plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `openrouter-v<version>`, where the
shaded `-all.jar` is attached.

---

## 0.1.2

**The plugin list links to the plugin's new home.** The plugin moved, with its full history, into
[tgessendorfer/hpe-morpheus-ent-plugins](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/llm/openrouter),
next to the Anthropic Claude and Proxmox VE plugins, and the old `morpheus-openrouter-plugin`
repository was deleted. The website link in the plugin list and `Morpheus-Repo` in the jar's
manifest still named the old repository; both now lead to `llm/openrouter` in the new one.

Nothing else changed from 0.1.1. The plugin code stays `morpheus-openrouter-plugin`, so the jar
upgrades an installed 0.1.1. Releases are now tagged `openrouter-v<version>`; the tags of the earlier
releases were renamed the same way and point at the same commits.

Upgrading: upload the new jar under *Administration > Integrations > Plugins*. No configuration
changes are required.

Verified: the test suite passes with plugin API 1.4.2, the HTTP client tests with 1.4.1, and the
jar's manifest names the new repository. Not verified on an appliance.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openrouter-v0.1.1...openrouter-v0.1.2

---

## 0.1.1

**OpenRouter's error messages reach the chat, and rate limits are waited out.** Morpheus replaces
every provider error with a generic text — *The AI model is no longer available*, *An error
occurred while processing your request* — and on 2026-09-15 a question on a new OpenRouter account
failed that way after 22 requests, with the real cause, a `429` for new accounts, only in the log.

### What changed

- **Errors as answers.** A question that fails for good is answered by the plugin with OpenRouter's
  message under a heading with the HTTP status, and what to do about it, for 400, 401, 402, 403,
  404, 408, 429 and 5xx — in the language of the question (English, German, Polish). With the cost
  footer on, the answer ends with what the question cost until it failed. Text a stream had already
  shown stays above the error.
- **New option *Show OpenRouter Errors in Chat*, on by default — also for existing integrations.**
  This is a behaviour change: Morpheus now records a failed question as answered, so the
  conversation is kept instead of discarded. Untick it for the 0.1.0 behaviour.
- **Error answers are left out of the history** replayed to the model, like the cost footer.
- **Rate limits and overloaded providers are retried.** A `429` or `503` is waited out twice, 10 and
  then 20 seconds, or as long as a stream's `Retry-After` asks, up to 30 seconds each. A stream that
  has already shown text is not retried. Connection failures are retried as before, and now for
  streams too. `callJsonApi` hands no headers of an error response to the plugin, so a non-streaming
  request always uses the plugin's own waits.
- **Streams report the status of an error event**, so a `429` inside a started stream is told apart
  from other failures.
- **Short questions get the right language.** Greetings and question words such as *Hallo, wer bist
  du?*, *who*, *how many* or *kim jesteś* count now; with 0.1.1-rc.1 that German question got an
  English error answer.

### Verified

- On HPE Morpheus Enterprise 9.0.1 with 0.1.1-rc.1: a real `429` for new accounts, provoked with a
  burst of `max_tokens: 1` requests to the same model, was waited out for 10 and then 20 seconds and
  then shown in the chat as the error answer. Morpheus kept the conversation: the next question in
  it was answered normally, with `input=8204` tokens against `8200` for a fresh question, so the
  error answer was not replayed to the model. Normal answers with the cost footer before and after.
- 127 Spock tests with plugin API 1.4.2, and the HTTP client tests with 1.4.1: retry waits, error
  answers in three languages, the cost until the failure, the option's default, streams with and
  without text before the error, error answers removed from replayed history, and the language of
  short questions.

### Not verified

- A live `402`, `503` or other 5xx through the plugin; the tests use OpenRouter's documented and
  recorded messages.
- The streaming path on the appliance: Morpheus 9.0.1 sent every chat in these tests as a
  non-streaming request.
- The language fix on the appliance.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openrouter-v0.1.0...openrouter-v0.1.1

---

## 0.1.0

First release. **OpenRouter as an AI integration type for HPE Morpheus Enterprise 9.0**, so
Morpheus AI agents can use the models of every vendor OpenRouter serves through its
OpenAI-compatible API. Claude stays with the separate Anthropic Claude plugin, which keeps prompt
caching of the MCP tool catalog.

### What it does

- **Chat and streaming with MCP tool calls.** Morpheus and OpenRouter both speak the OpenAI format,
  so messages and tool definitions pass through. Sampling parameters are forwarded unchanged;
  OpenRouter drops the ones a model does not support.
- **A model list an agent can use.** Only models with tool calling and text-only output are listed —
  no `:batch` variants, routers or `~...-latest` aliases. Anthropic models and `:free` variants are
  listed only when ticked. On 2026-09-15 that was 244 of 445 models.
- **Only List These Models**, an allow list of model ids with `*` as wildcard, keeps the list short.
  It defaults to `*`, because Morpheus 9.0.1 does not save the field once it is emptied. An allow
  list that matches no model is ignored with a warning instead of emptying the integration.
- **Models that drop out are removed**, since Morpheus offers disabled models in the agent form. A
  model an agent still uses stays disabled and is logged. Each refresh logs one line with the
  listed, added, updated, removed and disabled counts.
- **The key is checked on save** with `GET /api/v1/key`, because OpenRouter's model list answers any
  key. A key in the wrong format gets a hint, since OpenRouter's own message for it is
  `Missing Authentication header`.
- **In-region routing** through `eu.openrouter.ai` and `us.openrouter.ai`. Saving against a regional
  domain checks, at no cost, that the account has the Business or Enterprise plan it needs, and
  otherwise says so in the form.
- **Reasoning Effort**: *Model default* sends nothing; low, medium or high is sent as
  `reasoning.effort`. Reasoning is never shown in the answer.
- **Cost footer**, optional: the cost of the whole question, summed over its requests, in the
  language of the answer. Footers are stripped from replayed history.
- **Network proxy** per integration, with a checkbox as the switch, since Morpheus 9.0.1 offers no
  empty proxy entry.
- **Form in English, German and Polish.**
- **Works with integrations changed through the REST API.** `PUT /api/integrations/<id>` hands a
  provider the request's own keys instead of the stored credential; the plugin loads the
  credential itself.
- **Request bodies go out as ASCII-only JSON**, and TLS keeps SNI (`ignoreSSL: false`) — the two
  plugin API 1.4.1 pitfalls that broke the Anthropic plugin and HPE's Local LLM plugin.

### Verified on HPE Morpheus Enterprise 9.0.1

- The plugin loads and registers its LLM and option-source providers; the form renders in English,
  German and Polish, and *Reasoning Effort* offers its four entries.
- Saving with a valid key from the credential store; a key in the wrong format and an unknown key
  are rejected in the form; `eu.openrouter.ai` without the Business plan is rejected with the plan
  hint.
- Model sync: 244 models; the Anthropic and free-variant checkboxes; the allow list (36 and 10
  models); removal of models that drop out; a model used by an agent left disabled and listed
  again later; no model saved again when nothing changed; an allow list of `""` and one with a
  typo leave the list complete.
- An agent on `openai/gpt-5.4-nano` with the built-in Morpheus MCP server: parallel tool calls,
  streaming, reasoning effort *Low*, OpenAI's automatic prompt caching (`cached_tokens`), the cost
  footer in German and switched off.
- An agent on `google/gemini-3.5-flash`: tool calls and a correct answer with the cost footer.
- Changing the integration through `PUT /api/integrations/<id>`.

### Not verified

- Chats with models of vendors other than OpenAI and Google inside Morpheus.
- Routing through a network proxy.
- In-region routing with an account that has the plan, and `us.openrouter.ai` beyond its model list.
- `:free` variants in a chat, an expiring model's name on the appliance, and a `402` for missing
  credits.
- A new integration with *Local Credentials* and a valid key; the stored-credential path was used
  throughout.

### Known Morpheus limitations

Reported to HPE in `docs/`:

- On edit, switching from a stored credential to *Local Credentials* is not applied, and an emptied
  text field keeps its old value.
- The integration's model tab has no search, and disabled models are shown and counted like enabled
  ones.
- The MCP tool `list_instances` fails with a server error when called with `agentInstalled`
  together with `serverId` or `hostId`.

### Known model behaviour

- **`openai/gpt-5.4-nano` fills every optional MCP tool parameter with an empty value**, and the
  built-in Morpheus MCP tools treat `""` and `0` as filters, so the agent answers "0" for servers,
  clouds or groups that exist. The plugin forwards tool arguments unchanged; use another model for
  the agent. Other models were not checked for this.
- **`google/gemini-3.5-flash` pages through large MCP results.** The built-in MCP tools return a
  large result as a truncated preview; asked for a server list, Gemini called `get_result_excerpt`
  19 times, 22 requests in 40 seconds, until OpenRouter's limit for new accounts (20 requests per
  minute per model) answered `429`. Morpheus shows that as *An error occurred while processing your
  request* and discards the conversation. Narrower questions work.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/openrouter-v0.1.0
