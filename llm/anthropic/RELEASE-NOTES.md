# Anthropic Claude plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `anthropic-v<version>`, where the
shaded `-all.jar` is attached. Notes for 1.4.1 and earlier exist only there.

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

### Not yet verified

- The release jar on an appliance. Its content equals 1.6.2, so no change is expected.
- A build with Gradle 10, which is not released yet.

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
