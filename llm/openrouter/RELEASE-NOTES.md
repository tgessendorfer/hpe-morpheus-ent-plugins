# OpenRouter plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `openrouter-v<version>`, where the
shaded `-all.jar` is attached.

---

## 0.2.5

**Fix release from a code review: a restored question stays in its own conversation, tool calls
keep their required arguments and lose the ones the token limit cut off, and an empty model
catalog no longer empties the integration.** Built against plugin API 1.4.2. No new setting.

- **A restored question stays with its conversation.** Since 0.2.3 the plugin remembers which
  question each tool call worked on, to put back a question that the Morpheus 9.1 chat memory
  dropped. That map was keyed by the tool call id alone and shared by every integration, tenant and
  user; only numbered ids were refused. A backend that names its tool calls in a fixed pattern
  (`tool_0_list_servers`) could have handed one conversation the question of another. Each entry is
  now bound to **the integration and the system messages the conversation starts with**, which
  Morpheus' chat memory never evicts, and a question is restored only within that scope. When a
  known tool call belongs to another scope, the log says *Not restoring a dropped question: its tool
  calls were remembered for another integration or system prompt*.
- **Two tool loops that share a tool call id get no question back.** Morpheus hands the plugin no
  user, so two users of the same agent on the same integration share one scope, and a backend that
  names its tool calls in a fixed pattern (`tool_0_list_servers`) gives two tool loops running at
  the same time the same id. Up to 0.2.4 the question stored last on that id came back in both
  conversations. The rule now:
  - **A different question stored on a tool call id that was written less than 10 minutes before
    is a clash and marks the id ambiguous.** The mark holds short hashes of the questions that
    clashed and the time of the last clash, never a question text.
  - **While an id is marked, a request that carries it gets no question back**, not even through
    another tool call of the same loop. The same goes for a request whose tool calls hold
    different questions. The log says *Not restoring a dropped question: its tool calls belong to
    more than one question, so it could be another conversation's*, without any question text.
  - **A mark ends 10 minutes after the last clash.** A question that is already part of the clash,
    stored again by a loop that keeps running, does not renew it; only another different question
    does. After the mark the id no longer stops a request, but it holds no question until nothing
    has been stored on it for 10 minutes, since either loop may still be running; then the next
    question stored on it takes it over.
  - The same question stored again on an id that is not marked only refreshes its time; the id
    keeps the cost key it was first stored with. An id not written for 10 minutes takes a new
    question, as before.
  - **A loop that has no question left and gets none back marks the tool calls it makes next**,
    whether it was refused or nothing was known of its earlier tool calls (after a plugin reload,
    say). Each id it marks stays marked for 10 minutes from that call, so the loop gets nothing
    back for the rest of its run, and a question another loop stores on those ids later does not
    come back into it.
  - When it cannot tell which question a request's tool calls belong to, the plugin restores
    nothing: the worst case there is the lost question of Morpheus 9.1, never another user's
    question.

  In this plugin the key carries the integration and the system messages the conversation starts
  with, so only loops of the same integration and agent can clash. Tool call ids that look random
  never repeat, so only backends with fixed-pattern ids are affected.

  Two known limits: a backend that repeats its ids from one question to the next also marks a
  user's own follow-up question asked within 10 minutes, which then gets no restore through those
  ids; and since an id not written for 10 minutes takes the next question, a loop whose latest tool
  call is more than 10 minutes old (one tool that runs that long), or whose earlier tool calls the
  plugin no longer knows (after a plugin reload), can still get back the question of another loop
  that stored on the same id in the meantime. One long tool loop on Morpheus 9.1 was measured at
  137 s. The size and age limits of the map (10,000 entries, one hour) are unchanged.
- **The cost footer counts per integration.** The key of a question's running cost now includes
  the integration. Morpheus hands the plugin no user, so two users asking the same first question
  through the same agent at the same moment can still share one tally.
- ***Drop Empty Tool Arguments* keeps required arguments.** It removed `""`, `0` and `false`
  without looking at the tool's definition, so a required `offset: 0` of `get_result_excerpt` went
  too. It now reads the parameters schema the request carries with its tools: **a parameter listed
  as required is kept whatever its value, and so is `false` for a parameter that defaults to
  `true`.** Help text (English, German, Polish) and README say so.
- **A tool call the output token limit cut off is no longer passed on.** With `finish_reason:
  length` the plugin still made the turn a tool-call turn, so half-written JSON arguments reached
  Morpheus. Calls whose arguments are not a complete JSON object are now dropped and logged; the
  complete ones still run. **Empty arguments count as incomplete**: a call cut off before its first
  argument character would otherwise have run as `{}`. In a turn that was not cut off, a tool call
  without arguments still runs with `{}`. With none left, the answer carries the truncation note,
  like a text answer that was cut off.
- **An empty catalog no longer removes every model.** A model list that came back empty, or with
  no entry that passes the filter (after a field rename, say), was synced as "nothing listed": every
  model was removed, and the ones agents use were disabled. The sync now keeps the stored models and
  logs *Skipping OpenRouter model sync ... no usable model in the catalog*.
- **A stream that ends early is an error.** A stream that closed without `[DONE]` and without a
  finish reason was taken for the whole answer, partial tool arguments included. It now fails as
  *OpenRouter stream closed before the answer was complete*: asked again while no text has reached
  the chat, otherwise the error follows the text that did.
- **The U+FFFD note no longer changes a question's cost key halfway.** Only the conversation up to
  the question is checked for replacement characters, so a tool result that carries one does not
  add the note in a later round of the same question.
- **The API key is trimmed** before it goes into the `Authorization` header. A key pasted with a
  space or line break around it failed with 401.

### Behaviour changes from 0.2.4

- A dropped question is restored only for a request from the same integration that starts with the
  same system messages as the request whose tool calls worked on it.
- A dropped question is not restored while a tool call of the request is marked (a different
  question was stored on it less than 10 minutes after it was last written; the mark ends 10 minutes
  after the last clash), or when its tool calls hold different questions; 0.2.4 restored the
  question stored last. An id that was marked brings no question back until nothing has been stored
  on it for 10 minutes, and a loop that gets no question back marks the tool calls it makes next.
- Required arguments, and `false` where the tool defaults to `true`, reach the tool even when empty.
- Incomplete tool calls, empty arguments included, in an answer cut off by the token limit are not
  run.
- A catalog without a usable model leaves the integration's models as they are.
- A stream cut off without `[DONE]` or a finish reason ends as an error answer (with *Show
  OpenRouter Errors in Chat* unticked: as an error) instead of as a partial answer.

### Verified

- Unit tests (Spock), 27 more test cases than in 0.2.4: a remembered question is not restored for
  another integration or another system prompt, and is for its own conversation, also with the
  plugin's U+FFFD note among the system messages; two tool loops that store different questions on
  one tool call id at once restore neither, not even through an id only one of them used, and the id
  holds no question text; both loops storing their own questions again refresh the id without
  renewing the mark, while a third question renews it; 10 minutes after the last clash the loop gets
  its question back through its own id, while the shared id, still stored on, gives no question back
  until nothing has been stored on it for 10 minutes and then takes the next question; once the mark
  has ended, a loop that stores its question on the shared id again hands the other loop nothing,
  and a question that was not part of the clash marks the id again; the same question again on an id
  that is not marked only refreshes it, keeps its cost key and is still restored; a different
  question on an id not written for 10 minutes, whether it held an old question or an ended mark,
  replaces it and is restored; a request whose tool calls hold different questions, or a marked one,
  restores nothing, and a restored request counts on its tool calls' cost key only while they name
  one question; a loop refused its question marks its next tool calls from the time of its own call,
  and the other loop's question stored on them later neither lifts nor renews that mark; a loop that
  lost its question while nothing was known of its tool calls marks them too, so another loop's
  question stored on them does not come back into it; the cost key differs per integration and stays
  the same through a tool round; a required `offset: 0` and a `false` over a `true` default are kept
  while other empty arguments go; an incomplete tool call under `finish_reason: length` is dropped
  and the complete one runs, and with none left the answer is the truncation note; under
  `finish_reason: length` a call with empty or missing arguments, or arguments that are no JSON
  object, is dropped too, while the same calls in a turn that was not cut off still run; a U+FFFD in
  a tool result of the running question adds no note and keeps the cost key; the key is trimmed; a
  catalog without a usable model leaves the stored models alone; a stream without `[DONE]` and
  finish reason fails, one with a finish reason but no `[DONE]` completes; against a local stream
  endpoint, a stream that closes early before any text is asked again once and the second answer is
  used, and one that closes after its first words is not asked again and ends with the error under
  those words. The restore tests now send the agent's system prompt with the first request, as
  Morpheus does.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --no-daemon`, 178 tests,
  0 failures.
- On HPE Morpheus Enterprise 9.1.0-1 with a release candidate built from this source
  (`0.2.5-rc.1`), uploaded in place of 0.2.4: status `loaded`. **The model sync ran with this
  build**, in Morpheus' own scheduled refresh of the OpenRouter integration (right after the
  upload, when a refresh was also started by hand and answered HTTP 200, then about every
  11 minutes): each run listed the same
  262 models as 0.2.4's last runs before the upload, with nothing added, updated or removed, and
  the integration stayed `ok`. The appliance log showed no warning or error of the plugin.

### Not yet verified

- On an appliance: the release jar loads in place of 0.2.4 and its integration stays `ok` (the
  release candidate above was a separate build).
- On an appliance: a chat whose question the Morpheus 9.1 chat memory drops still gets it back
  (*Restored the user's question ...* in the log). That relies on Morpheus keeping the system
  message the same through a tool loop.
- That Morpheus sends the `required` lists of its MCP tools in the tool definitions it hands to the
  plugin.
- Whether the tool definitions Morpheus hands to the plugin mark any boolean parameter
  `default: true` at all, and, where one does, that keeping a model's `false` for it does not bring
  back the empty `list_servers` answer that *Drop Empty Tool Arguments* was made for.
- Two users chatting with the same agent at the same time, each with a tool loop: with random tool
  call ids each still gets their own dropped question back; with a backend whose tool call ids
  repeat, the log shows *Not restoring a dropped question: its tool calls belong to more than one
  question ...* instead of one user getting the other's question.
- **A chat with this version**: the test had no agent on an OpenRouter integration, so nothing
  of the chat path above ran on the appliance.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openrouter-v0.2.4...openrouter-v0.2.5

---

## 0.2.4

**Fix release: *Default Max Output Tokens* now applies in the Morpheus chat.** Built against plugin
API 1.4.2. No new setting; plugin code unchanged.

- **What went wrong.** Morpheus asks for 1000 output tokens on every chat request. Up to 0.2.3 the
  plugin sent the caller's value whenever there was one, so the integration's *Default Max Output
  Tokens* was never used in chat. Reasoning models could spend all 1000 tokens thinking and answer
  only *Answer cut off at the output token limit.*
- **What the plugin does now.** The configured value is a floor: `max_tokens` is the larger of the
  caller's value and the configured one. A caller asking for more keeps its value. Without a
  configured value the request goes out as the caller asked, or without `max_tokens`, as before.
  This is how the Anthropic plugin has handled it since 1.6.1.
- **Help text and README** say so, in English, German and Polish.

### Behaviour changes from 0.2.3

- An integration with *Default Max Output Tokens* set (for example 4096) now sends that value on
  every chat request instead of Morpheus' 1000. Answers can be longer and cost more output tokens.
- An integration without the setting behaves as before.

### Verified

- Unit tests (Spock): configured 4096 with a request of 1000 sends 4096; a request of 8192 keeps
  8192; no or zero request sends 4096; without a configured value the request's 1000 is sent, and no
  value at all leaves `max_tokens` out. The old test that expected 1000 over a configured 4096 was
  the bug and is replaced.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --warning-mode all`, 151 tests,
  0 failures.

### Not yet verified

- A chat on the appliance with this jar.

On Morpheus 9.1.0 (build 9.1.0-1): the release jar replaced the previous version in place (same plugin id), status `loaded`; afterwards integrations 2 to 8 refreshed `ok`; no plugin warning in the log.

Built by the release workflow from the source at tag `openrouter-v0.2.4`.

sha256 `2caaacd746ceb8e1e4653392a8d6e4e19f4839d3a29b995c2979e44fc3beb08a`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openrouter-v0.2.3...openrouter-v0.2.4

## 0.2.3

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
- **Behaviour change from 0.2.2:** only requests that have lost their question are changed.

### Verified

- 146 unit tests (JDK 17, Gradle 9.8.0), six of them new: a request without its question gets it
  back after the system messages; requests that still have their question, or whose tool calls are
  unknown or older than an hour, are left alone; numbered tool call ids are never trusted; a restored
  question keeps its cost key and carries on to the next tool calls; a full map drops only its
  oldest entries; and a question asked through `generateResponse` comes back in a later request
  that lost it.
- The cause was read from `morpheus-core-9.1.0.jar` and measured with an Anthropic agent; see the
  Anthropic 1.6.4 notes.

- On Morpheus 9.1.0-1 a release candidate of this code (0.2.3-rc.1) loaded and its integrations
  refreshed `ok`.
- The release jar on the same appliance: loaded, its integrations refreshed `ok`.

### Not yet verified

- A chat on an appliance with a model behind this plugin.

Built by the release workflow from the source at tag `openrouter-v0.2.3`.

sha256 `1d112b7ea15eaa09cd19acfbf875a0fc3861571281f2e24d369660ec06d9dda6`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openrouter-v0.2.2...openrouter-v0.2.3

---

## 0.2.2

**Build update only: the build no longer uses a Gradle API that Gradle 10 removes.** No change in
behaviour, settings or plugin code, and the jar has the same content as 0.2.1.

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
  140 test cases, 0 failures; the HTTP client tests also pass against plugin API 1.4.1.
- The jar has the same entries as the 0.2.1 release jar, with the same content. Two files differ
  only in ways every build produces: the build timestamp comment in `assets/manifest.properties`,
  and the line order of `i18n/i18n.manifest` (a local build on macOS against the release workflow
  on Linux).
- The release jar on the lab appliance (HPE Morpheus Enterprise 9.0.2): loaded, its three integrations `ok`
  after the upload, no error or warning in the log.

### Not yet verified

- A build with Gradle 10, which is not released yet.

Built by the release workflow from the source at tag `openrouter-v0.2.2`.

sha256 `5977bc1e6bae205b81bf341e01cbe92556cafca1997f4ce6102224e662cc933c`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openrouter-v0.2.1...openrouter-v0.2.2

## 0.2.1

**Build update only: the plugin now builds with Gradle 9.8.0 instead of the end-of-life Gradle
7.5.1.** No change in behaviour, settings or plugin code.

- **Gradle 9.8.0**, with the wrapper checking the distribution's SHA-256 checksum, and the Gradle
  wrapper scripts regenerated for it.
- **Shadow 9.6.1** (`com.gradleup.shadow`) replaces Shadow 6.0.0, which does not run on Gradle 9.
  The jar keeps its name, `morpheus-openrouter-plugin-<version>-all.jar`.
- **No `mavenLocal()`** in the build: dependencies come only from Maven Central, the Gradle plugin
  portal and the asset-pipeline repositories, so a local Maven cache cannot change what is built.
- Java 11 bytecode as before, set through a `java {}` block.

### Verified

- Local build with JDK 17 and Gradle 9.8.0: 20 tests, 0 failures; the HTTP client tests also
  pass against plugin API 1.4.1.
- The jar has the same entries as the 0.2.0 jar, and its manifest differs only in `Plugin-Version`.
- On Morpheus 9.0.2 (build 9.0.2-2): the release jar was uploaded over the previous build and
  replaced it in place (same plugin id), status `loaded`, valid and enabled; no plugin error in the
  log.
- After the upload the plugin's integrations on the appliance stayed `ok`; a refresh of one of them
  succeeded.

### Not yet verified

- A chat with this jar on the appliance. The code is the same as 0.2.0.
- The asset-pipeline Gradle plugin 4.4.0 still uses an API that Gradle 9.8 reports as deprecated
  (`Task.project` at execution time). It works on Gradle 9 and has to change before Gradle 10;
  asset-pipeline 5.0.9 does not build this plugin.

Built by the release workflow from the source at tag `openrouter-v0.2.1`.

sha256 `81eecde692377e6e369c455149ce67392f3ce31709d4f9b6d74c3cf23f284ae2`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openrouter-v0.2.0...openrouter-v0.2.1

## 0.2.0

**Empty tool arguments are dropped, and cut-off answers say so.** Both came out of building the
[OpenAI-compatible plugin](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/llm/openai)
on 2026-09-21 and apply to OpenRouter models just the same.

### What changed

- **New option *Drop Empty Tool Arguments*, on by default — also for existing integrations.**
  Some models fill every optional parameter of a tool call with `""`, `0` or `false`, and Morpheus'
  built-in MCP tools take those as filters: `openai/gpt-5.4-mini` answered *No servers were found*
  for a lab with 7 servers, `openai/gpt-5.4-nano` did the same in 0.1.0. The plugin now removes
  such top-level arguments (`""`, `0`, `false`, `null`, `[]`, `{}`) from the model's tool calls
  before Morpheus runs the tool, and logs what it dropped. This is a behaviour change from 0.1.2,
  which forwarded tool arguments unchanged; untick the option for that.
- **Cut-off answers are marked.** An answer that hits the output token limit ends with *Answer cut
  off at the output token limit.* in its own language (English, German, Polish), and an answer with
  no text at all — a reasoning model that spent Morpheus' 1000-token budget thinking — becomes
  that line instead of Morpheus' generic *An error occurred* text. The line is stripped before the
  answer is replayed as history, like the cost footer.
- **The log shows more.** Every request leaves one `OpenRouter request:` line with its shape —
  model, message and tool counts, `max_tokens`, `temperature`, reasoning effort — never its
  content, and the `OpenRouter tool calls:` line now carries each call's arguments.

Upgrading: upload the new jar under *Administration > Integrations > Plugins*. No configuration
changes are required; the new option is on until unticked.

### Verified

On HPE Morpheus Enterprise 9.0.2 (plugin API 1.4.1) with `0.2.0-rc.1`, a local build of this
code: the plugin loads, integration 4 refreshes `ok` with 246 models, and on 2026-09-22 an agent on
`openai/gpt-5.4-nano` with the built-in MCP server listed all 7 lab servers. The model had filled
all 18 optional `list_servers` parameters with empty values; the log shows `OpenRouter dropped empty
arguments from list_servers: name, phrase, zoneId, …`, and the question cost 0.008 USD over 4
requests. The cut-off note is covered by unit tests only. 140 tests pass with plugin API 1.4.2, the
HTTP client tests with 1.4.1.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/openrouter-v0.1.2...openrouter-v0.2.0

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

Verified on HPE Morpheus Enterprise 9.0.1, upgraded from 0.1.1: the plugin loads, the plugin list
links to the new repository, and the model sync listed 244 models with status `ok`. The test suite
passes with plugin API 1.4.2, the HTTP client tests with 1.4.1, and the jar's manifest names the new
repository.

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
