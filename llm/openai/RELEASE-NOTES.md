# OpenAI-compatible plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `openai-v<version>`, where the
shaded `-all.jar` is attached.

---

## 0.1.5

**Fix release from a code review: the output limit, parameters an API rejects, tool arguments and
streams.** Built against plugin API 1.4.2. No new setting; plugin code unchanged.

- **Default Max Output Tokens is now a floor.** Morpheus asks for 1000 output tokens on every chat
  request, so up to 0.1.4 the integration's value was never used in chat. The plugin now sends the
  larger of the two: a caller asking for more keeps its value, and without a configured value the
  request goes out as the caller asked. OpenAI reasoning models still get at least 8192 when the
  field is empty. This is the rule of OpenRouter 0.2.4 and of the Anthropic plugin since 1.6.1.
- **A rejected `reasoning_effort` no longer fails the question.** With *Reasoning Effort* set,
  every request carried `reasoning_effort`, and api.openai.com answers it for `gpt-4.1` with
  `400 Unsupported parameter`; so does LiteLLM without `drop_params`. **A `400` that names the
  parameter is now answered by asking again without it**, and that model at that endpoint is not
  sent it again until the plugin is reloaded (logged once). Models that take it still get it.
- **Streams ask for their usage.** A streamed request now sends
  `stream_options: {"include_usage": true}`; api.openai.com and vLLM report no usage in a stream
  otherwise, so a streamed answer had no usage footer and the log no usage line. An API that rejects
  `stream_options` with a `400` naming it is asked again without it, the same way. The stream reader
  also keeps `reasoning_content` (LiteLLM, DeepSeek) out of the answer and turns a streamed
  `refusal` into the answer, as it already did for a response that was not streamed.
- **Drop Empty Tool Arguments reads the tool definition.** **An argument the tool marks as
  required is kept**: `get_result_excerpt` with `offset: 0` lost its offset. **So is `false` where
  the tool's default is `true`**, which leaving it out turned into `true`. Every other empty
  argument is dropped as before, and a call to a tool that is not in the request is handled as
  before.
- **A tool call the output limit cut off is no longer run.** A turn that ended on `length` was
  reported as `tool_calls`, and a call whose arguments stopped in the middle of the JSON went to
  Morpheus. Such calls are now dropped with a warning in the log; the complete calls of the turn
  still run, and with none left the turn is an answer that says it was cut off.
- **The question map and the cost tally are kept apart per integration.** The map that brings back
  a question the chat memory dropped (0.1.3) is shared by every integration and user. Its keys now
  also carry the integration and a hash of the agent's system messages, so a tool call id that two
  conversations share can only bring back a question of the same integration and agent prompt.
  The cost key of a question carries the integration too, and no longer changes when the plugin's
  U+FFFD note joins a later round of the same question.
- **An empty catalog no longer removes every model.** An endpoint that answered `/models` with an
  empty list, or with entries the filters leave nothing of, made the sync remove every model and
  disable those that agents use. **The stored models now stay**, with a warning in the log, until
  the endpoint lists a chat model again.
- **"Can I see my VMs?" is English.** The Polish markers include the one-letter word `i`, so short
  English questions with "I" scored as Polish, and an error answer or truncation note came back in
  Polish. `i` now counts for English too, and a tie stays English.
- **Help texts** (English, German, Polish) **and README** describe the new behaviour.

### Behaviour changes from 0.1.4

- An integration with *Default Max Output Tokens* set (for example 4096) now sends that value on
  every chat request instead of Morpheus' 1000. Answers can be longer and cost more output tokens.
  A reasoning model whose caller asks for more than the configured value now gets the caller's
  value; 0.1.4 lowered it to the configured one. Without the setting nothing changes.
- With *Reasoning Effort* set, a model whose API rejects the parameter answers without it instead
  of failing with `400`.
- Streamed requests carry `stream_options`.
- Required empty arguments, and `false` over a default of `true`, reach the tool as the model wrote
  them.
- A tool call cut off by the output limit is not run.
- An endpoint that lists no chat model keeps the integration's stored models.

### Verified

- Unit tests (Spock), new or changed: the configured limit as a floor (4096 over a request of
  1000, a request of 8192 kept, the request's 1000 without a configured value) and for a reasoning
  model (a request of 5000 over a configured 2000); a `400` naming `reasoning_effort` answered by a
  second request without it, and the next request of that model sent without it while a reasoning
  model at the same endpoint still gets it; a `400` that names no such parameter sent once only;
  `stream_options` on stream requests only, and a stream whose `400` names it asked again without
  it; a streamed `reasoning_content`, a streamed refusal and a usage chunk without choices; a
  required `offset: 0` and a `false` over a default of `true` kept while other empty arguments go;
  a cut-off tool call dropped with the complete one kept, and with none left the truncation note;
  the same tool call id from another integration or another system prompt never restoring the
  question; a cost key that differs per integration and ignores the U+FFFD note; an empty catalog
  leaving the stored models alone; "Can I see my VMs?" English and "Ile serwerów i instancji?"
  Polish. The question-map tests now send the agent's system message on every request, as Morpheus
  does.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --no-daemon`, 177 tests,
  0 failures.

### Not yet verified

- Nothing of this release on an appliance.
- Whether the built-in Morpheus MCP tools mark only real requirements as `required`. A tool that
  listed every parameter as required would keep its empty arguments: check that
  `Dropped empty arguments from list_servers` still appears with `openai/gpt-5.4-mini` through
  OpenRouter.
- The exact `400` texts of api.openai.com for `reasoning_effort` on `gpt-4.1` and of LiteLLM
  without `drop_params`; the retry needs the parameter's name in the message.
- The usage footer on a streamed answer; Morpheus has called `generateResponse` in every chat test
  so far.
- The restore of a dropped question with the scoped keys. It relies on Morpheus keeping the system
  message unchanged while it evicts the rest, as it does in 9.1.0; the restore itself is still
  unverified on an appliance for this plugin (see 0.1.3).
- A sync against an endpoint that lists no chat model.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openai-v0.1.4...openai-v0.1.5

## 0.1.4

**Fix release: open-weight chat models named `*-Instruct` are listed again.** Built against plugin
API 1.4.2. No new setting; plugin code unchanged.

- **What went wrong.** The catalog filter dropped every model whose id contained `instruct`, meant
  for OpenAI's legacy completion model `gpt-3.5-turbo-instruct`. Most open-weight chat models are
  named that way: `meta-llama/Llama-3.3-70B-Instruct` on vLLM, `llama3.1:8b-instruct-q4_K_M` on
  Ollama, `mistralai/mistral-small-3.2-24b-instruct` on OpenRouter. A vLLM endpoint that serves only
  such a model passed validation, then synced 0 models; on an existing integration the next sync
  removed them.
- **What the plugin does now.** Only `gpt-3.5-turbo-instruct` and its dated variants, with or
  without a vendor prefix such as `openai/`, are dropped by id. Every other filter is unchanged.

### Behaviour changes from 0.1.3

- Models named `*-Instruct` appear in the model list after the next sync. Where the catalog says
  what a model supports (OpenRouter), they still need tool calling and text-only output.

### Verified

- Unit tests (Spock): ids-only catalogs as vLLM, Ollama and LiteLLM list them keep
  `meta-llama/Llama-3.3-70B-Instruct`, `llama3.1:8b-instruct-q4_K_M` and
  `Qwen/Qwen2.5-72B-Instruct`; an OpenRouter entry `mistralai/mistral-small-3.2-24b-instruct` with
  metadata is kept; `gpt-3.5-turbo-instruct`, `gpt-3.5-turbo-instruct-0914` and
  `openai/gpt-3.5-turbo-instruct` are dropped; the api.openai.com catalog test is unchanged.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --warning-mode all`, 163 tests,
  0 failures.

### Not yet verified

- A sync against a vLLM or Ollama endpoint; the lab has none.

On Morpheus 9.1.0 (build 9.1.0-1): the release jar replaced the previous version in place (same plugin id), status `loaded`; afterwards integrations 2 to 8 refreshed `ok`; no plugin warning in the log.

Built by the release workflow from the source at tag `openai-v0.1.4`.

sha256 `08e0bb1368e46f21db18e8fb5bb55ca68be632bdad4edbef389bd43ea2e7fbde`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openai-v0.1.3...openai-v0.1.4

## 0.1.3

**The model no longer loses the user's question in a long tool loop on Morpheus 9.1.** Built
against plugin API 1.4.2. Fix release; no new setting. The same fix as Anthropic 1.6.4.

- **What went wrong.** Morpheus 9.1 keeps the chat memory in a token window, 32,000 tokens unless
  `morpheus.ai.chat.maxMemoryTokens` is set, and evicts the oldest message first. MCP results over
  8,000 characters come back truncated, and the agent pages through them with `get_result_excerpt`.
  A question that needs many of those pages fills the window inside one question: the question
  itself drops out, and the model answers as if the message had been empty.
- **What the plugin does now.** It maps every tool call id the model issues to the question of
  that request (kept for one hour; above 10,000 entries the oldest are dropped). Only ids that look
  random are used: a backend that numbers its tool calls (`call_0`, `functions.list_servers:0`)
  could otherwise hand one conversation the question of another, since the map is shared by every
  integration and user. When a request reaches the plugin
  without any user question but with tool calls it knows, the plugin puts the question back right
  after the system messages, with a one-line note that the chat application had dropped it, and
  logs *Restored the user's question, which the Morpheus chat memory had dropped during a tool
  loop*. The question text is not logged. The cost footer keeps counting on the question's first requests,
  so it covers the whole question.
- **Behaviour change from 0.1.2:** only requests that have lost their question are changed.

### Verified

- 162 unit tests (JDK 17, Gradle 9.8.0), six of them new: a request without its question gets it
  back after the system messages; requests that still have their question, or whose tool calls are
  unknown or older than an hour, are left alone; numbered tool call ids are never trusted; a restored
  question keeps its cost key and carries on to the next tool calls; a full map drops only its
  oldest entries; and a question asked through `generateResponse` comes back in a later request
  that lost it.
- The cause was read from `morpheus-core-9.1.0.jar` and measured with an Anthropic agent; see the
  Anthropic 1.6.4 notes.

- On Morpheus 9.1.0-1 with a release candidate of this code (0.1.3-rc.1), an agent on
  `deepseek-v4.1-flash` through a LiteLLM proxy: the same cross-cloud server table came back correct
  after 30 s. That model needed fewer, smaller turns, so the restore did not have to act.
- The release jar on the same appliance: loaded, its integrations refreshed `ok`.

### Not yet verified

- The restore acting on an appliance with a model behind this plugin.

Built by the release workflow from the source at tag `openai-v0.1.3`.

sha256 `6c06c9969bbbb5bfadf4e7b3405b88882032bc60e265ce3b0215165734fbee36`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openai-v0.1.2...openai-v0.1.3

---

## 0.1.2

**Build update only: the build no longer uses a Gradle API that Gradle 10 removes.** No change in
behaviour, settings or plugin code, and the jar has the same content as 0.1.1.

- **The asset-pipeline Gradle plugin is no longer applied.** Its `assetCompile` task (version
  4.4.0) read its classpath through `Task.project` while it ran, which Gradle 9.8 reports as
  deprecated and Gradle 10 turns into an error. The build now registers its own `assetCompile`
  task, a small subclass of asset-pipeline's `AssetCompile` that receives the same classpath
  (`runtimeClasspath` and `provided`) as a task input at configuration time. It writes to the same
  place and goes into the jar the same way.
- **asset-pipeline stays at 4.4.0.** Newer releases under `com.bertramlabs.plugins` (4.5.x,
  5.0.0-5.0.9) still read the classpath the same way, and 5.0.9 does not build this plugin.
- The `assets {}` extension, the `assets` configuration and the `assetClean` task are gone with
  the plugin; this build used none of them.

### Verified

- Local build with JDK 17 and Gradle 9.8.0 (`--warning-mode all`): no deprecation warning,
  156 test cases, 0 failures; the HTTP client tests also pass against plugin API 1.4.1.
- The jar has the same entries as the 0.1.1 release jar, with the same content. Two files differ
  only in ways every build produces: the build timestamp comment in `assets/manifest.properties`,
  and the line order of `i18n/i18n.manifest` (a local build on macOS against the release workflow
  on Linux).
- The release jar on the lab appliance (HPE Morpheus Enterprise 9.0.2): loaded, its two integrations `ok`
  after the upload, no error or warning in the log.

### Not yet verified

- A build with Gradle 10, which is not released yet.

Built by the release workflow from the source at tag `openai-v0.1.2`.

sha256 `e76be5344ff919318d42dbbe5dd68e65f2cb7ec10a55b9ade9adf0ddb15c4812`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openai-v0.1.1...openai-v0.1.2

## 0.1.1

**Build update only: the plugin now builds with Gradle 9.8.0 instead of the end-of-life Gradle
7.5.1.** No change in behaviour, settings or plugin code.

- **Gradle 9.8.0**, with the wrapper checking the distribution's SHA-256 checksum, and the Gradle
  wrapper scripts regenerated for it.
- **Shadow 9.6.1** (`com.gradleup.shadow`) replaces Shadow 6.0.0, which does not run on Gradle 9.
  The jar keeps its name, `morpheus-openai-plugin-<version>-all.jar`.
- **No `mavenLocal()`** in the build: dependencies come only from Maven Central, the Gradle plugin
  portal and the asset-pipeline repositories, so a local Maven cache cannot change what is built.
- Java 11 bytecode as before, set through a `java {}` block.

### Verified

- Local build with JDK 17 and Gradle 9.8.0: 19 tests, 0 failures; the HTTP client tests also
  pass against plugin API 1.4.1.
- The jar has the same entries as the 0.1.0 jar, and its manifest differs only in `Plugin-Version`.
- On Morpheus 9.0.2 (build 9.0.2-2): the release jar was uploaded over the previous build and
  replaced it in place (same plugin id), status `loaded`, valid and enabled; no plugin error in the
  log.
- After the upload the plugin's integrations on the appliance stayed `ok`; a refresh of one of them
  succeeded.

### Not yet verified

- A chat with this jar on the appliance. The code is the same as 0.1.0.
- The asset-pipeline Gradle plugin 4.4.0 still uses an API that Gradle 9.8 reports as deprecated
  (`Task.project` at execution time). It works on Gradle 9 and has to change before Gradle 10;
  asset-pipeline 5.0.9 does not build this plugin.

Built by the release workflow from the source at tag `openai-v0.1.1`.

sha256 `a37cebb0bc51c1f602b3b54b01a303f27dc27819307ff0bd40fe1b0613f81ccc`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openai-v0.1.0...openai-v0.1.1

## 0.1.0

**First release: any OpenAI-compatible chat API as a Morpheus LLM integration, over HTTP or
HTTPS.** HPE's Local LLM plugin 1.0.0, still shipped with Morpheus 9.0.2, cannot reach
`api.openai.com`, `openrouter.ai` or any other CDN-fronted endpoint: it sends no SNI in the TLS
handshake and fails the save with `handshake_failure`. This plugin keeps certificate checks and SNI
on, and reaches them.

### What it does

- **Integration type *OpenAI-Compatible API*** (provider code `openai-llm`, plugin code
  `morpheus-openai-plugin`), next to HPE's *OpenAI Compatible*. The base URL is taken as entered,
  including its version path; the plugin appends only `/models` and `/chat/completions`.
- **Save checks the key**: `GET /models` with the key, and on `openrouter.ai` also `GET /key`,
  since OpenRouter serves its model list to anyone. A rejected save names the URL and the cause: a
  wrong key, a URL short of `/v1`, a web page instead of a model list, a failed TLS handshake.
- **API key optional**, for endpoints on your own network that take none. *Local Credentials*
  has a key field, which the Local LLM plugin lacks.
- **Chat models only.** api.openai.com lists embeddings, speech, image, moderation and legacy
  completion models under the same `/models`; they are left out by id. OpenRouter-style catalogs
  must list tool calling and text-only output. Model names are the catalog's names or the ids as
  they are, never title-cased. **Only List These Models** narrows the list.
- **OpenAI reasoning models** (o-series, GPT-5, codex) get `max_completion_tokens` instead of
  `max_tokens`, no `temperature` or `top_p`, and **at least 8192 output tokens**: Morpheus asks for
  1000 on every request, and `gpt-5-mini` spent 960 of them reasoning and answered nothing.
- **Cut-off answers say so.** An answer that hits the output limit ends with *Answer cut off at the
  output token limit.* in its own language (English, German, Polish), and shows only that line when
  there was no text at all.
- **Empty tool arguments are dropped** (*Drop Empty Tool Arguments*, on by default). Some models
  fill every optional parameter of a tool call with `""`, `0` or `false`, which Morpheus' built-in
  MCP tools take as filters: `openai/gpt-5.4-mini` through OpenRouter answered *No servers were
  found* that way. The log names what was dropped.
- **From the OpenRouter plugin, unchanged:** MCP tool use in OpenAI format, streaming, the
  network proxy per integration, API errors as chat answers with advice in the language of the
  question, rate limits and overloaded providers waited out twice, the usage footer (tokens, or
  the question's cost where the API reports one), footers and error answers stripped from replayed
  history, ASCII-only request bodies, `reasoning_effort` on request, and form translations in
  English, German and Polish.
- **Every request leaves one log line** with its shape — model, message and tool counts, output
  limit, sampling parameters — never its content, plus the tool calls with their arguments and the
  usage of every response.

### Verified

On HPE Morpheus Enterprise 9.0.2 (plugin API 1.4.1, Java 25) on 2026-09-21, with the release
candidates of this version:

- **api.openai.com:** 47 models with the allow list `gpt-5*, gpt-4.1*`; an agent on `gpt-5.4-mini`
  with the built-in Morpheus MCP server lists all servers, with `max_completion_tokens=8192`, no
  `temperature`, and OpenAI's prompt caching serving the tool catalog from the second request.
- **OpenRouter:** save in 0.16 s where the Local LLM plugin fails with `handshake_failure`; 25
  models with `openai/gpt-5*`, 287 without an allow list; an agent on `openai/gpt-5.4-mini` lists
  all servers once empty tool arguments are dropped; a wrong key is refused on save.
- **LiteLLM over plain HTTP:** 3 models; an agent on `deepseek-v3.2` answers German questions with
  umlauts intact, runs tool rounds and keeps the conversation history.
- 156 unit tests with plugin API 1.4.2, the HTTP client tests also with 1.4.1.

Not verified: streaming on the appliance (Morpheus 9.0.2 calls `generateResponse`), rate limits,
the network proxy against a real proxy, and Ollama or vLLM endpoints.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/openai-v0.1.0
