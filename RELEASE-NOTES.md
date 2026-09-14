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

**A conversation with mangled history no longer fails on every turn.** Morpheus can replay a
stored conversation with unpaired UTF-16 surrogates in it, and `api.anthropic.com` rejects any such
request with `400 The request body is not valid JSON: str is not valid UTF-8: surrogates not
allowed` — so once it happened, every later turn of that conversation failed too. Seen after
switching an agent mid-conversation from OpenRouter, which tolerated the same history, to the
Anthropic API. The provider now repairs the request before sending it: a run of low surrogates that
decodes as UTF-8 bytes is restored, any other unpaired surrogate is dropped, and each repair is
logged as `Repaired N unpaired surrogate characters in the request (first: U+....)`.

**Lost umlauts no longer spread.** Morpheus replays stored chat history with non-ASCII characters
replaced by U+FFFD — the plugin receives `L�uft` for an answer it returned as `Läuft`. Those
characters cannot be restored, but a model reading its own earlier `L�uft` writes the next answer
that way too. When replayed history contains U+FFFD, the provider now logs where
(`Replayed conversation contains N U+FFFD replacement characters`, with message index, role and an
excerpt) and adds a short system note, after the cache breakpoint, telling the model not to copy
them. Verified on a live appliance: with the damaged history still in the conversation, the next
answer came back with every `ä` intact.

**Tool calls are logged.** Morpheus shows no trace of tool use in the chat, so an answer built from
MCP data and one the model made up look the same. Every response that calls tools now logs
`Anthropic tool calls: <names>` to the appliance log.

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

Integrations against `api.anthropic.com` behave as before.

The README also gains troubleshooting entries for what testing turned up in Morpheus itself: the
chat widget loads its agent list with the page, so an agent created afterwards shows
*No agents found* until the page is reloaded; and smaller models such as Haiku tend to answer from
earlier turns or from examples in the MCP tool descriptions instead of calling the tool, which the
new tool-call log makes visible.

**Full Changelog**: https://github.com/tgessendorfer/morpheus-anthropic-plugin/compare/v1.4.1...v1.5.0
