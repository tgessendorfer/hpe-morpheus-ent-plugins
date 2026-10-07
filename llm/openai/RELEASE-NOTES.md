# OpenAI-compatible plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `openai-v<version>`, where the
shaded `-all.jar` is attached.

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
