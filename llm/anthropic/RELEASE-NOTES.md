# Anthropic Claude plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `anthropic-v<version>`, where the
shaded `-all.jar` is attached. Notes for 1.4.1 and earlier exist only there.

---

## 1.6.6

**Fix release from a code review: the chat retry works, keys in stored credentials are found on
update and refresh, models that leave the catalog are removed, the per-question memory is kept
per integration and agent, where a tool call id that two questions claim at once restores
nothing, and the usage probe goes to `api.anthropic.com` only.** Built against plugin API 1.4.2.
No new setting.

- **The chat retry never ran.** `generateResponse` was meant to send a request up to three times
  after a broken connection, but it waited for an exception, and the API service reports every
  failed call as a result instead of throwing. The retry is now decided on that result: a call that
  got **no HTTP answer** (connection reset, refused, *failed to respond*, connect timeout) or an
  **HTTP 529 (overloaded)** is sent again, up to three attempts with 1.5 s and 3 s between them, each
  logged as *Anthropic API retry n/2 after ...*. A read timeout is not repeated (the request may still
  be running), and neither is any other HTTP error. Streaming is not retried, as before.
- **A key kept in a stored credential is loaded.** An update through `PUT /api/integrations` and the
  scheduled refresh hand the provider no stored credential, only the request's own keys or none, so
  an integration whose key sits under *Credentials* failed with *An Anthropic API key is required*
  (seen on Morpheus 9.0.2 with `POST /api/integrations/<id>/refresh`). The plugin now loads the stored
  credential whenever no key was handed over, as the OpenRouter and OpenAI-compatible plugins do. A
  chat request with no key at all is no longer sent: it fails with *An Anthropic API key is required*
  and a log line, instead of a 401 from the API.
- **Models that leave the catalog are removed.** They used to be disabled, but Morpheus still offers
  disabled models in the agent form, where picking one fails with *The AI model is no longer
  available*. A model an agent still points at cannot be removed (the foreign key from the agent has
  no cascade); that one is disabled as before and logged. A catalog answer without a model list, or
  without a single Claude model, now leaves the stored models alone instead of emptying the
  integration.
- **A failed pause_turn continuation no longer sends the paused turn twice.** When a web search turn
  paused, its continuation failed, and the turn had no answer text yet, the request for the final
  answer carried the paused turn twice, with duplicate `server_tool_use` ids. The API rejected it, so
  that recovery request was billed and failed. The paused turn now goes back once.
- **A tool call cut off at `max_tokens` is dropped.** A `tool_use` block at the end of a turn that hit
  `max_tokens` is incomplete; handed to Morpheus it would run an MCP tool with part of its arguments.
  Complete tool calls before it stay. With nothing left to run and no text, the existing request for
  a final answer without tools follows.
- **The per-question memory is scoped to the integration and the agent.** The question restore
  (1.6.4), the thinking blocks of a tool loop (1.6.5) and the per-question cost total were shared by
  every integration, tenant and user on the appliance. Their entries are now kept under the
  integration id and a hash of the system prompt Morpheus sends (the agent's prompt), and only used
  within that scope. Before, a backend with predictable tool call ids could hand one conversation the
  question or thinking of another, and the same first question asked through two integrations shared
  one cost total. **The per-question cost total (the *Cost* footer through OpenRouter) now adds up
  only the requests of the same integration and system prompt.** When a request has lost its
  question and its tool calls are known only under another integration or system prompt, the plugin
  logs *... the question is not restored*. The eviction above 10,000 entries now runs under a lock,
  so concurrent requests drop no more than the excess.
- **Two tool loops that share a tool call id get no question back.** Morpheus hands the plugin no
  user, so two users of the same agent on the same integration share one scope, and a backend that
  names its tool calls in a fixed pattern (`tool_0_list_servers`) gives two tool loops running at
  the same time the same id. Up to 1.6.5 the question stored last on that id came back in both
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

  In this plugin the map is keyed by the tool call id alone and each question is stored with its
  integration and system prompt, so the same question stored for an id from another integration or
  system prompt counts as a different question and marks the id too, and a marked id stops a
  request from any integration or system prompt. Anthropic's own `toolu_...` ids differ on every
  call, so through `api.anthropic.com` this should not trigger; it guards gateways that reuse ids.

  Two known limits: a backend that repeats its ids from one question to the next also marks a
  user's own follow-up question asked within 10 minutes, which then gets no restore through those
  ids; and since an id not written for 10 minutes takes the next question, a loop whose latest tool
  call is more than 10 minutes old (one tool that runs that long), or whose earlier tool calls the
  plugin no longer knows (after a plugin reload), can still get back the question of another loop
  that stored on the same id in the meantime. One long tool loop on Morpheus 9.1 was measured at
  137 s. The size and age limits of the map (10,000 entries, one hour) are unchanged.
- **The thinking blocks of a tool loop follow the same rule.** Like the question, they were stored
  with a plain overwrite, so with a backend that reuses tool call ids one loop could send the model
  the thinking of another loop's turn. **Different thinking blocks (or the same ones from another
  integration or system prompt) stored for a tool call id within 10 minutes now mark the id, and a
  tool call id that is marked, for its thinking or for its question, gets no thinking blocks
  back**; an id that was marked keeps no blocks. The request then goes out without extended
  thinking, as when the blocks are unknown, and the log says *A tool loop has a tool call id that
  another loop used at the same time; its thinking blocks are not put back*. The question mark
  counts too because a turn without thinking stores no blocks: when it shares an id with another
  loop's turn that thought, only the question map sees the clash. A loop that gets no question back
  marks its next tool calls, so the rest of that tool loop runs without extended thinking; the next
  question thinks again.
- **Extended thinking with a forced tool choice.** The API accepts only `tool_choice` `auto` and
  `none` while thinking is on; `any` (Morpheus' `required`) and a named tool now go out as `auto`
  instead of failing with 400.
- **Streaming keeps citations and web search input.** The stream accumulator now collects
  `citations_delta`, so a streamed answer gets its source list, and parses the streamed input of a
  `server_tool_use` block, so a paused streamed turn goes back with its search query.
- **Usage of resumed turns adds up nested counts.** `server_tool_use` (web search and fetch requests)
  and `cache_creation` were taken from the first segment only; the *Anthropic turn finished* log line
  under-reported them.
- **Unloading the plugin closes its pooled HTTP clients**, so an upgrade leaves no connection pool
  behind.
- **The usage probe is sent to `api.anthropic.com` only.** Every refresh sent a billed
  `max_tokens: 1` request to read the `anthropic-ratelimit-*` headers, through OpenRouter and other
  gateways too, where no such headers come back (OpenRouter) or where they would describe the
  gateway's own Anthropic account rather than this integration. The probe now runs only when the
  host of the **API Endpoint** is `api.anthropic.com` (any letter case, port or path; an empty
  endpoint means `api.anthropic.com`). For any other host the refresh sends nothing, empties the
  integration's usage fields and logs *Anthropic usage probe skipped for integration N:
  openrouter.ai is not api.anthropic.com, ...* at DEBUG, or at INFO with *... the usage values of an earlier probe
  were cleared* when it empties values that an earlier probe left behind (from when the
  integration pointed at `api.anthropic.com`, or from a gateway that passed the headers on).
  Morpheus stores those fields without a time stamp, so an old value would keep reading as current.
- **The usage values are saved and the save is waited for.** The probe handed the integration to
  Morpheus' data service without subscribing to the RxJava `Single` that came back, so whether its
  values reached the database depended on the appliance's implementation of that service. It now
  blocks on the save, as the model sync does, and logs a failed save as a warning
  (*Could not save the Anthropic usage values of integration N: ...*).

### Behaviour changes from 1.6.5

- A model no longer listed by the API is removed, not disabled; one an agent still uses stays
  disabled as before.
- A chat request that got no HTTP answer, or a 529, is sent up to twice more.
- A dropped question and a tool loop's thinking blocks only come back within the same integration
  and system prompt, and the per-question cost total only adds up requests of that scope.
- A dropped question is not restored while a tool call of the request is marked (a different
  question was stored on it less than 10 minutes after it was last written; the mark ends 10 minutes
  after the last clash), or when its tool calls hold different questions; 1.6.5 restored the
  question stored last. An id that was marked brings no question back until nothing has been stored
  on it for 10 minutes, and a loop that gets no question back marks the tool calls it makes next.
- A tool loop's thinking blocks follow the same rule: different blocks, or the same blocks from
  another integration or system prompt, stored for one tool call id within 10 minutes mark it. A
  tool loop that gets no question back runs without extended thinking until it ends.
- With thinking on, a forced tool choice becomes `auto`.
- A tool call cut off at `max_tokens` no longer reaches Morpheus.
- A chat request without any API key is not sent.
- **No usage probe through OpenRouter or any other gateway**: only an API Endpoint on
  `api.anthropic.com` gets it. A gateway integration's usage fields are emptied at the next refresh
  and stay empty.
- **The usage values are written with a blocking save.** A refresh on `api.anthropic.com` writes
  the integration whenever a usage value changed, which is nearly every refresh because the remaining
  budget moves; a gateway integration is written once, when the values of an earlier probe are
  emptied. If the appliance's data service saves only once its result is subscribed to, 1.6.5 never
  stored these values and 1.6.6 is the first version that does.

### Verified

- Unit tests (Spock), 78 more test cases than in 1.6.5: a connection failure and a 529 are retried,
  an HTTP error, a read timeout and a fourth attempt are not; a request without a key is not sent,
  streaming or not; the stored credential is loaded once and kept, a handed-over key needs no
  lookup, Local Credentials and a failing lookup fall back to the local field, and saving finds the
  stored key; a failed continuation sends the paused turn once; a cut-off tool call is dropped and
  the complete one before it stays; a forced tool choice becomes `auto` with thinking on and stays
  without; nested usage adds up without changing the first segment; a lost question comes back only
  in its own integration and system prompt, thinking blocks only in their scope, and two
  integrations keep two cost totals; two tool loops that store different questions on one tool call
  id at once restore neither, not even through an id only one of them used, and the id holds no
  question text; both loops storing their own questions again refresh the id without renewing the
  mark, while a third question renews it; 10 minutes after the last clash the loop gets its question
  back through its own id, while the shared id, still stored on, gives no question back until
  nothing has been stored on it for 10 minutes and then takes the next question; once the mark has
  ended, a loop that stores its question on the shared id again hands the other loop nothing, and a
  question that was not part of the clash marks the id again; the same question again on an id that
  is not marked only refreshes it, keeps its cost key and is still restored; a different question on
  an id not written for 10 minutes, whether it held an old question or an ended mark, replaces it
  and is restored; a request whose tool calls hold different questions, or a marked one, restores
  nothing, and a restored request counts on its tool calls' cost key only while they name one
  question; a loop refused its question marks its next tool calls from the time of its own call, and
  the other loop's question stored on them later neither lifts nor renews that mark; a loop that
  lost its question while nothing was known of its tool calls marks them too, so another loop's
  question stored on them does not come back into it; the same question stored for the id from
  another integration or system prompt marks it too; thinking blocks stored twice under one id come
  back only when both stores hold the same blocks in the same scope, and a loop whose id another
  question claimed gets no thinking back; eviction drops exactly the expired and the oldest entries;
  unlisted models are removed, a model an agent uses is disabled, a bulk removal that fails on one
  model in use is retried model by model so the free model still goes, a catalog without a list or
  without a Claude model removes nothing; the stream accumulator keeps a `server_tool_use` input and
  the citations of the answer, and each streamed block carries exactly the fields of a non-streaming
  one; `shutdown()` closes every pooled client; the model list request carries `limit=1000` as a
  query string (a local HTTP server checks the request line instead of the former test that checked
  nothing); a refresh sends the usage probe for an empty API Endpoint and for `api.anthropic.com` in
  any letter case and with a port or path, and none for OpenRouter or another gateway host; a host
  that only begins or ends with `api.anthropic.com`, carries it in the path or as a user name, or
  does not parse counts as a gateway; a refresh through a gateway empties the usage fields an
  earlier probe filled and saves once, and saves nothing when they were empty; the save is
  subscribed to (a lazy `Single` runs), and a failing save is logged as a warning without breaking
  the refresh; the skip is logged once, at DEBUG when nothing was stored and at INFO with *were
  cleared* when values were emptied, and a probe against `api.anthropic.com` logs no skip line.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --no-daemon`, 210 tests,
  0 failures.
- On HPE Morpheus Enterprise 9.1.0-1 with a release candidate built from this source
  (`1.6.6-rc.1`), uploaded in place of 1.6.5 (status `loaded`):
  - **Refresh:** `POST /api/integrations/<id>/refresh` answered HTTP 200 and left the integration
    `ok` both for an integration on `api.anthropic.com` and for one through OpenRouter whose key
    sits in a stored credential (read back over the API); with 1.6.5, just before the upload,
    the OpenRouter one answered 400 *An Anthropic API key is required*. **No usage probe
    error**: no *Could not save the Anthropic usage values* warning, and no INFO skip line for
    the OpenRouter integration, which had no usage values stored. Its model sync removed three
    Claude models that OpenRouter no longer lists.
  - **Chat with web search and three follow-up questions**, in one new conversation with an
    agent on the `api.anthropic.com` integration: the first answer searched the web and carried
    the plugin's **Sources** list (two links); for *open the first link* the model fetched the
    first link of that replayed list; the two follow-ups without a search had no **Sources**
    heading. **The model wrote no Sources heading of its own**: the page showed two, both the
    plugin's. Input tokens per request (read from the prompt cache / written to it / uncached):
    29,727 / 37,851 / 193 for the first question and 89,181 / 7,941 / 1,089 for *open the first
    link*; the two follow-ups without a fetch read 29,727 from the prompt cache with 704 and 864
    uncached input tokens. **The replayed history, source lists included, cost about 0.7 to 0.9k
    uncached input tokens per such follow-up.**

### Not yet verified

- On an appliance: the release jar loads in place of 1.6.5 and its integrations stay `ok` (the
  release candidate above was a separate build).
- On an appliance: a long tool loop on Morpheus 9.1 still gets its question back with the scoped
  memory (this needs the system prompt to stay the same during the loop; the log line above says
  when it does not); a model removed from the catalog disappears from the agent form, and one an
  agent uses stays disabled; a retry after a real connection failure or 529; the streaming path,
  which Morpheus has not called in any test so far; the pool shutdown, which only matters when
  Morpheus passes a client scope key.
- Two users chatting with the same agent at the same time, each with a tool loop: each gets only
  their own question back, and with Anthropic's own tool call ids neither *Not restoring a dropped
  question ...* nor *... another loop used at the same time ...* appears, with extended thinking on
  as well.
- The *Cost* footer through OpenRouter for a question with a tool loop: it still sums every request
  of the question now that its total is kept per integration and system prompt.
- The usage probe on an appliance: **where Morpheus shows the usage fields** (they are not in
  `GET /api/integrations`), and so whether a refresh of an integration on `api.anthropic.com`
  fills them with the blocking save; a refresh that also removed or disabled a model leaves the
  model list and every agent's model as the model sync left them (the integration that is saved
  afterwards is the one Morpheus handed to the refresh, with the model list from before that
  sync); a refresh through OpenRouter sends no `max_tokens: 1` request (none in OpenRouter's
  activity list) and leaves the usage fields empty; an integration switched from
  `api.anthropic.com` to OpenRouter loses its old values at the next refresh, read back in the UI
  or over the API and not only from the log line (the plugin empties the values Morpheus hands
  it with the integration; if those arrive empty, the stored ones stay).

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/anthropic-v1.6.5...anthropic-v1.6.6

## 1.6.5

**Fix release: extended thinking works with MCP tool loops.** Built against plugin API 1.4.2. No new
setting; plugin code unchanged.

- **What went wrong.** With *Enable Extended Thinking* on, the Messages API requires the assistant
  turn that issued the tool calls of a running loop to come back starting with its thinking blocks,
  signature included. Morpheus rebuilds that turn from its own stored `tool_calls` only, so from the
  second round of every tool loop the request lacked them and the API would answer 400 (*a final
  `assistant` message must start with a thinking block*), shown in chat as *The AI model is no
  longer available*.
- **What the plugin does now.** It keeps the thinking and redacted thinking blocks of each turn that
  calls tools, unchanged, under each tool call id (one hour; above 10,000 entries the oldest are
  dropped; only ids that look random are used), and puts them back in front of the rebuilt turn.
  Without the interleaved-thinking beta the model thinks once, at the start of its turn, so only
  the first tool-call message of a loop carries thinking; later rounds keep thinking on as long as
  that first message's blocks are known. When they are not (another appliance node, a plugin
  reload, or a turn older than an hour), the request goes out without extended thinking instead of
  failing, and logs *thinking blocks of the running tool loop not known, sending this request
  without extended thinking*.
- **Streaming keeps the signature.** The stream accumulator now collects `signature_delta`, so a
  thinking block from a streamed answer can go back to the API. A thinking block without a
  signature is never remembered.
- With thinking off nothing changes: no blocks are kept in requests, none are sent.

### Behaviour changes from 1.6.4

- With thinking on, tool loops now continue after the first tool call.
- A tool-loop request whose thinking blocks are lost runs without thinking, once.

### Verified

- Unit tests (Spock): a response that thought and called a tool gets its thinking block (with
  signature) and redacted block (with data) back in front of the rebuilt turn, in API order; a
  loop with a second tool round whose reply has no thinking keeps thinking on; unknown blocks send
  the request without `thinking`; thinking off never sends blocks; a new question after a finished
  loop keeps thinking; numbered tool call ids and blocks without a signature are not remembered;
  the stream accumulator keeps `signature_delta`.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --warning-mode all`, 132 tests,
  0 failures.

### Not yet verified

- A tool loop with thinking on against api.anthropic.com. The lab integrations run with thinking off.

On Morpheus 9.1.0 (build 9.1.0-1): the release jar replaced the previous version in place (same plugin id), status `loaded`; afterwards integrations 2 to 8 refreshed `ok`; no plugin warning in the log.

Built by the release workflow from the source at tag `anthropic-v1.6.5`.

sha256 `8a455680a8ebdbb7ce0a2e3f55347a6131b4ad0f668adbb900fa933c2841f680`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/anthropic-v1.6.4...anthropic-v1.6.5

## 1.6.4

**The model no longer loses the user's question in a long tool loop on Morpheus 9.1.** Built
against plugin API 1.4.2. Fix release; no new setting.

- **What went wrong.** Morpheus 9.1 keeps the chat memory in a token window, 32,000 tokens unless
  `morpheus.ai.chat.maxMemoryTokens` is set, and evicts the oldest message first. MCP results over
  8,000 characters come back truncated, and the agent pages through them with `get_result_excerpt`.
  A question that needs many of those pages fills the window inside one question: the question
  itself drops out, and the model answers *"It looks like your message came through empty"*.
  Measured on 9.1.0 with two broad questions: 4:25 min and 8:07 min, up to 55 excerpt calls in one
  turn.
- **What the plugin does now.** It maps every tool call id the model issues to the question of
  that request (kept for one hour; above 10,000 entries the oldest are dropped). Only ids that look
  random are used: a backend that numbers its tool calls (`call_0`, `functions.list_servers:0`)
  could otherwise hand one conversation the question of another, since the map is shared by every
  integration and user. When a request reaches the plugin
  without any user question but with tool calls it knows, the plugin puts the question back at the
  start of the conversation, with a one-line note that the chat application had dropped it, and
  logs *Restored the user's question, which the Morpheus chat memory had dropped during a tool
  loop*. The question text is not logged. The cost footer keeps counting on the question's first requests,
  so it covers the whole question.
- **Behaviour change from 1.6.3:** only requests that have lost their question are changed. Long
  tool loops still cost what they cost; raising `morpheus.ai.chat.maxMemoryTokens` on the
  appliance shortens them, and narrow questions avoid them.

### Verified

- 124 unit tests (JDK 17, Gradle 9.8.0), eight of them new: a request without its question gets it
  back from a known tool call, the restored question carries on to the next tool calls without the
  note piling up, requests that still have their question or whose tool calls are unknown or older
  than an hour are left alone, only tool calls of a request with a question are remembered, numbered tool call ids are never
  trusted, a restored question keeps its cost key, a full map drops only its oldest entries, and a
  question asked through `generateResponse` comes back in a later request that lost it.
- The cause, read from `morpheus-core-9.1.0.jar`: `ToolAwareChatMemory` wraps langchain4j's
  `TokenWindowChatMemory`; `AiChatService.DEFAULT_MAX_MEMORY_TOKENS` is 32,000;
  `McpToolResultStoreService.INLINE_CHAR_LIMIT` is 8,000.
- On Morpheus 9.1.0-1 with a release candidate of this code (1.6.4-rc.1), an agent on
  `claude-sonnet-5` with the built-in MCP server: the cross-cloud server table that ended in *"your
  message came through empty"* after 4:25 min with 1.6.3 came back correct after 60 s (10 turns,
  27 `list_servers`, 4 `get_result_excerpt`). The log showed the restore once. Integrations of this
  plugin stayed `ok` after the upload.
- The release jar on the same appliance: loaded, its integrations refreshed `ok`. The same question
  came back correct after 137 s (17 turns, 25 `list_servers`, 30 `get_result_excerpt`), and the log
  showed the restore once. How long such a question takes varies with the number of excerpt calls
  the model chooses.

### Not yet verified

- With plugin API 1.4.1 (Morpheus 9.0.2), whose chat memory was not measured.

Built by the release workflow from the source at tag `anthropic-v1.6.4`.

sha256 `69f9d031fd648cd7feda4f423e6fcd740670612858c4e3ce2c9d1e6306524ae2`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/anthropic-v1.6.3...anthropic-v1.6.4

---

## 1.6.3

**Build update only: the build no longer uses a Gradle API that Gradle 10 removes.** No change in
behaviour, settings or plugin code, and the jar has the same content as 1.6.2.

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
  116 test cases, 0 failures; the HTTP client tests also pass against plugin API 1.4.1.
- The jar has the same entries as the 1.6.2 release jar, with the same content. Two files differ
  only in ways every build produces: the build timestamp comment in `assets/manifest.properties`,
  and the line order of `i18n/i18n.manifest` (a local build on macOS against the release workflow
  on Linux).
- The release jar on the lab appliance (HPE Morpheus Enterprise 9.0.2): loaded, its Anthropic integration `ok`
  after the upload, no error or warning in the log.

### Not yet verified

- A build with Gradle 10, which is not released yet.

Built by the release workflow from the source at tag `anthropic-v1.6.3`.

sha256 `26e847ba677648e72a2a3d7ec71c6d7608d1965e3e439de6a8640efd21181038`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/anthropic-v1.6.2...anthropic-v1.6.3

## 1.6.2

**Build update only: the plugin now builds with Gradle 9.8.0 instead of the end-of-life Gradle
7.5.1.** No change in behaviour, settings or plugin code.

- **Gradle 9.8.0**, with the wrapper checking the distribution's SHA-256 checksum, and the Gradle
  wrapper scripts regenerated for it.
- **Shadow 9.6.1** (`com.gradleup.shadow`) replaces Shadow 6.0.0, which does not run on Gradle 9.
  The jar keeps its name, `morpheus-anthropic-plugin-<version>-all.jar`.
- **No `mavenLocal()`** in the build: dependencies come only from Maven Central, the Gradle plugin
  portal and the asset-pipeline repositories, so a local Maven cache cannot change what is built.
- Java 11 bytecode as before, set through a `java {}` block.

### Verified

- Local build with JDK 17 and Gradle 9.8.0: 11 tests, 0 failures; the HTTP client tests also
  pass against plugin API 1.4.1.
- The jar has the same entries as the 1.6.1 jar, and its manifest differs only in `Plugin-Version`.
- On Morpheus 9.0.2 (build 9.0.2-2): the release jar was uploaded over the previous build and
  replaced it in place (same plugin id), status `loaded`, valid and enabled; no plugin error in the
  log.
- After the upload the plugin's integrations on the appliance stayed `ok`; a refresh of one of them
  succeeded.

### Not yet verified

- A chat with this jar on the appliance. The code is the same as 1.6.1.
- The asset-pipeline Gradle plugin 4.4.0 still uses an API that Gradle 9.8 reports as deprecated
  (`Task.project` at execution time). It works on Gradle 9 and has to change before Gradle 10;
  asset-pipeline 5.0.9 does not build this plugin.

Built by the release workflow from the source at tag `anthropic-v1.6.2`.

sha256 `f55353ccf5dff3b7865e8ed874e024763958cf06b1aa73da7e374e05839d30c3`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/anthropic-v1.6.1...anthropic-v1.6.2

## 1.6.1

**Answers are no longer cut off at 1,000 output tokens.** Morpheus asks for `maxOutputTokens: 1000`
on every chat request, and the plugin passed that on, so *Default Max Output Tokens* (8,192 unless
set) was never used in the chat. With web search and Claude Sonnet 5, which writes thinking blocks
even with *Extended Thinking* off, two of three test questions on Morpheus 9.0.2 ended with
`stop_reason=max_tokens` after 462 and 2,048 characters of answer text. The integration's value is
now a floor: a smaller request is raised to it, a larger one is kept. **Behaviour change:** chat
answers can be up to 8,192 output tokens by default instead of 1,000; set *Default Max Output
Tokens* lower to cap cost.

**1.6.0 checked on an appliance.** On Morpheus 9.0.2 with Claude Sonnet 5 and web search on, the
question that had failed with `400 prompt is too long` before was answered in full. The new log line
showed the caps at work: 3 `web_search` and 3 `web_fetch` calls, about 243,000 input tokens summed
over the server-side loop, against about 1,065,000 before. A turn without answer text did not occur,
so the final-answer request is still covered by tests only.

Verified: the test suite passes, with a new test for the floor. The release jar loaded on a
Morpheus 9.0.2 appliance; every chat request went out with `max_tokens=8192`, and a question answered
through the built-in MCP tools (`list_clouds`, eight `list_instances` calls) ended with
`stop_reason=end_turn`.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/anthropic-v1.6.0...anthropic-v1.6.1

---

## 1.6.0

Makes web search and fetch safe to leave on for an MCP-backed agent. A chat question on Claude
Sonnet 5 with web search on failed with *An error occurred while processing your request*; asked
again, it came back as a header with nothing under it. The appliance log showed
`400 prompt is too long: 1065647 tokens > 1000000 maximum` for the first, and an empty answer for the
second — while the conversation Morpheus sent was about 37,000 tokens. The most likely cause is
Anthropic's server-side search loop: everything `web_search` and `web_fetch` return is added inside
the same request, and `web_fetch` had no limit on how much of a page or PDF it adds. 1.5.1 logged
neither the stop reason nor the server-side tool use, so this is an interpretation, not a
measurement; 1.6.0 logs both.

**A fetched page is capped at 25,000 tokens.** New option *Web Fetch Max Content Tokens* sends
`max_content_tokens` on `web_fetch`. Empty means 25,000, `0` means no limit. Existing integrations get
the cap without being edited.

**Search and fetch have separate caps, and both default to 3.** *Max Web Searches per Request* now
caps `web_search` only; the new *Max Page Fetches per Request* caps `web_fetch`. **Behaviour change:**
in 1.5.1 one cap of **5** applied to both tools. An integration that left the field empty now gets
**3** searches and **3** fetches; one with its own value keeps it for search, and fetch drops to 3
until *Max Page Fetches per Request* is set. *Restrict to Domains* keeps applying to both tools.

**An answer is never empty.** A turn that ends without text and without a Morpheus tool call — the
model spent it on searches and fetches, or ran into `max_tokens` — is handed back once with
`tool_choice: none`, asking for the final answer from what was gathered. The server-tool blocks go
back verbatim, as in a `pause_turn` continuation; a search call that never got its result is left
out. If the second answer is empty too, or the request fails, the chat gets a short explanation with
the stop reason and the pages the searches found. A `refusal` is explained, not asked again.
Before, the plugin returned `""` and Morpheus showed its own fallback, which for a tool search is a
bare header.

**A context overflow is explained in the chat.** A `400 prompt is too long` comes back as an answer
saying the request did not fit the context window and what to lower, instead of an error. Morpheus
shows every provider error as the same generic sentence and drops the question from the
conversation, so neither the cause nor the fix was visible. **Behaviour change:** for this one error
the request now succeeds, with the explanation as the answer. Every other API error still fails as
before.

**Better logs.**

- Every failed API call logs `WARN Anthropic API error: HTTP <status> <type>: <message>
  (request_id <id>)`, with the request id from the body or the `request-id` header — what Anthropic
  support asks for.
- Every turn that offered or used the web tools, or came back without text, logs `INFO Anthropic turn
  finished:` with the stop reason, the `max_tokens` sent, block types with counts, which server tools
  ran, and the usage including `web_search_requests` and `web_fetch_requests`. This replaces the
  line that was logged only after a `pause_turn`. The `prompt cache` line is unchanged.

**The usage probe no longer hits a retired model.** It took the first enabled model with `haiku` in
its id. Through OpenRouter that was `anthropic/claude-3-haiku`, which answered
`404 ... end of its life` on every refresh. It now takes the newest enabled Haiku by version, and on
a 404 for the model tries the next candidate, up to three.

Upgrading: upload the new jar under *Administration > Integrations > Plugins*. No configuration
changes are required; open and save an integration to see the two new fields with their defaults.

Verified: the test suite passes on plugin API 1.4.2, and the API client tests also on 1.4.1. New tests
cover a turn with only server-tool blocks, the final-answer request and its payload, a turn that
stays empty, `max_tokens` without text, `refusal`, `redacted_thinking`, the `prompt is too long`
answer, the error details of a real `400` from a local endpoint, `max_content_tokens` and `max_uses`
in the request body, and the probe model choice. **Not yet verified on an appliance**, and not
against the live API: that the server-side loop stays inside the context window with the cap, and
that `tool_choice: none` after replayed web results yields an answer.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/anthropic-v1.5.1...anthropic-v1.6.0

---

## 1.5.1

**The plugin list links to the plugin's new home.** The plugin moved, with its full history, into
[tgessendorfer/hpe-morpheus-ent-plugins](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/llm/anthropic),
next to the OpenRouter and Proxmox VE plugins, and the old `morpheus-anthropic-plugin` repository
was deleted. The website link in the plugin list and `Morpheus-Repo` in the jar's manifest still
named the old repository; both now lead to `llm/anthropic` in the new one.

Nothing else changed from 1.5.0. The plugin code stays `morpheus-anthropic-plugin`, so the jar
upgrades an installed 1.5.0. Releases are now tagged `anthropic-v<version>`; the tags of the earlier
releases were renamed the same way and point at the same commits.

Upgrading: upload the new jar under *Administration > Integrations > Plugins*. No configuration
changes are required.

Verified on HPE Morpheus Enterprise 9.0.1, upgraded from 1.5.0: the plugin loads, the plugin list
links to the new repository, and both integrations, direct and through OpenRouter, refreshed with
status `ok`. The test suite passes, and the jar's manifest names the new repository.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/anthropic-v1.5.0...anthropic-v1.5.1

---

## 1.5.0

Makes the integration work against **OpenRouter**. Its Anthropic-compatible Messages endpoint
already spoke the same protocol as this plugin; only the model catalog stood in the way.

Pointed at `https://openrouter.ai/api`, an integration saved with status `ok` and **0 models**.
Both causes were in the model sync:

- **Model ids.** OpenRouter lists Claude as `anthropic/claude-sonnet-4.6`, and the sync only kept
  ids starting with `claude`. Both spellings are accepted now. The id is stored exactly as listed,
  since that is the spelling the endpoint expects back. The `:batch` variants are skipped.
- **Paging.** The catalog was requested with `limit=100`. OpenRouter honours that limit across its
  whole multi-vendor catalog of roughly 450 models, and only four Claude entries fell inside the
  first page. The limit is now `1000`, the Anthropic maximum, which returns either catalog in a
  single page.

The capability checks read the dotted version as well, so `anthropic/claude-sonnet-4.6` gets the
same web search tool version, 1M context window and max output tokens as `claude-sonnet-4-6`.

**Each model is listed once.** Asked in Anthropic's format, OpenRouter lists its 1M-context Claude
models as `anthropic/claude-sonnet-4.6[1m]`; in its own format they are
`anthropic/claude-sonnet-4.6`. Two refreshes of one integration saw both, and the sync stored ten
models twice with the first copy disabled. Morpheus still offers disabled models in the agent form,
so half the entries failed with *The AI model is no longer available*. Both spellings now count as
one model: a stored model keeps its code when the listing changes, leftover copies are removed on
the next refresh, and one that an agent still points at stays disabled and is logged. Refreshes of
one integration no longer run concurrently. 1M variants report a 1M context window, and names drop
OpenRouter's `Anthropic: ` prefix — which it applies to most models but not all — in either format.

**The usage footer no longer multiplies.** Found while testing on a live appliance, and not
specific to OpenRouter: final answers are replayed as history on the next question, footer
included. After two answers the model had picked up the pattern and wrote its own `*Tokens: ...*`
line, with invented numbers, above the real one. Footers are now stripped from replayed answers,
so the model never sees one. This also stops re-billing them as input on every later turn.

**Umlauts and every other non-ASCII character reach the API intact.** The `HttpApiClient` in plugin
API 1.4.1 — the version Morpheus 9.0.1 ships — wraps a JSON request body in a `StringEntity` without
a charset, which Apache HttpCore encodes as ISO-8859-1. Every umlaut left the appliance as a single
invalid UTF-8 byte, and every character outside Latin-1 as `?`:

- `api.anthropic.com` rejected such a request with `400 The request body is not valid JSON: str is
  not valid UTF-8: surrogates not allowed`, shown in the chat as *An error occurred while processing
  your request*. The question stays in the conversation, so every later turn failed as well. A
  question without umlauts went through, which made the failure look random.
- OpenRouter accepted the same bytes and replaced each one with U+FFFD. The model saw `L�uft`,
  repeated it in its answer, and Morpheus stored the answer that way.

The provider now serialises the body itself, as JSON with every non-ASCII character escaped as
`\uXXXX`. Those bytes are the same in any encoding, so the request arrives intact on plugin API
1.4.1 and later versions alike — reproduced, and verified, in the test suite against both.

Conversations damaged before this fix keep their `�` in the stored history. When replayed history
contains U+FFFD, the provider logs where (`Replayed conversation contains N U+FFFD replacement
characters`, with message index, role and an excerpt) and adds a short system note after the cache
breakpoint telling the model not to copy it. Verified on a live appliance: with the damaged history
still in the conversation, the next answer came back with every `ä` intact.

**Tool calls are logged.** Morpheus shows no trace of tool use in the chat, so an answer built from
MCP data and one the model made up look the same. Every response that calls tools now logs
`Anthropic tool calls: <names>` to the appliance log.

**Cost in the chat, through OpenRouter.** With *Append token usage to answers* on, an integration
against OpenRouter ends each final answer with `*Cost: $0.0184 (2 requests)*` instead of the token
line, or `*Kosten: $0.0184 (2 Anfragen)*` when the answer is in German. OpenRouter reports what every request cost, and the plugin adds up all requests of one
question: an agent answer with tool rounds is many billed requests, and the last one alone would
understate it several times over. Morpheus passes no conversation id, so requests are grouped by the
conversation up to the user's latest message. Against `api.anthropic.com`, which reports no cost, the
token line stays. Costs of a paused and resumed turn now add up as decimals instead of being
truncated to zero.

**The integration form speaks German and Polish.** Labels and help texts follow the Default Locale in
User Settings, with English as the fallback. Both were shown on a live appliance, German with the menu
names Morpheus' own German translation uses; the Polish texts have not been reviewed by a native
speaker yet — corrections welcome. The cost line under OpenRouter answers follows the language of
the answer in the same three languages.

**Web search no longer slows MCP agents down.** With web search on, Claude 4.6 and newer used to get
`web_search_20260318` / `web_fetch_20260318`, which run the search inside code execution to filter
results. Once code execution was there, the model also used it to process MCP tool results — measured
with Claude Sonnet 5 and the built-in Morpheus MCP server on a question that never searched the web:
most tool rounds took an extra inference round, reading the cached prefix twice, plus a fresh sandbox
container, and a server inventory question took minutes. No `pause_turn` was involved. Web search now
uses the basic tools, called directly, by default; the new **Filter Search Results with Code
Execution** option brings dynamic filtering back for research-only integrations. **Behaviour change
from 1.4.x:** an integration with web search on loses dynamic filtering until the option is ticked.

Every `pause_turn` continuation is now logged when web search is on
(`Anthropic pause_turn N: resending a turn that paused with ...`, then
`Anthropic turn finished after N pause_turn continuation(s): ...`), with the stop reason, block
sequence, cache read and container of the turn.

Checked against the live OpenRouter endpoint:

- `x-api-key` authentication is accepted.
- Both id spellings are accepted on `/v1/messages`.
- **Prompt caching passes through.** A repeated request read 13,602 tokens from the cache and cost
  about a twelfth of the first one. An agent on the built-in Morpheus MCP server read its tool
  catalog from the cache on every turn.
- OpenRouter sends no `anthropic-ratelimit-*` headers, so the usage fields on the integration stay
  empty.

Not yet verified through OpenRouter: web search and fetch, and the 1M context beta header.

**Worth knowing:** OpenRouter's model list is public and answers `200` to any key. A successful
save therefore proves the appliance reached OpenRouter, not that the key is valid — the first chat
is the real test. See
[Going through OpenRouter](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/llm/anthropic#going-through-openrouter).

Integrations against `api.anthropic.com` behave as before, apart from the encoding fix above.

The plugin list now shows a description, author and website for the plugin — the column was empty,
because Morpheus reads them from the plugin class rather than the manifest. That description, the
integration type's description and the help text under **API Endpoint** now name OpenRouter,
and say that only its Anthropic Claude models are listed; models from other vendors belong to a
separate OpenRouter integration.

The README also gains troubleshooting entries for what testing turned up in Morpheus itself: the
chat widget loads its agent list with the page, so an agent created afterwards shows
*No agents found* until the page is reloaded; and smaller models such as Haiku tend to answer from
earlier turns or from examples in the MCP tool descriptions instead of calling the tool, which the
new tool-call log makes visible.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/anthropic-v1.4.1...anthropic-v1.5.0
