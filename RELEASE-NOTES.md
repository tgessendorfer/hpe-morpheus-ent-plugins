# Anthropic Claude plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/morpheus-anthropic-plugin/releases), where the
shaded `-all.jar` is attached. Notes for 1.4.1 and earlier exist only there.

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

**The integration form speaks six languages.** Labels and help texts follow the Default Locale in
User Settings: English, German, Polish, Czech, Hungarian and Romanian. German was checked on a live
appliance, including the menu names Morpheus' own German translation uses. The Polish, Czech,
Hungarian and Romanian texts have not been reviewed by native speakers yet — corrections welcome.
Czech, Hungarian and Romanian, which Morpheus itself does not offer, are added to the Default Locale
list by the plugin; picking one translates this form, and the rest of Morpheus stays English. The
cost line under OpenRouter answers follows the language of the answer in the same six languages,
with the plural forms each of them uses.

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
[Going through OpenRouter](https://github.com/tgessendorfer/morpheus-anthropic-plugin#going-through-openrouter).

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

**Full Changelog**: https://github.com/tgessendorfer/morpheus-anthropic-plugin/compare/v1.4.1...v1.5.0
