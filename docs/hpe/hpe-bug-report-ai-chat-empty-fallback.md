# Morpheus 9.0.2: AI chat shows an empty fallback answer after a meta tool, and loses the question after a provider error

## Summary

Two error paths of the AI chat (*Ask Murph*, `AiChatService`) leave the user with nothing to act on:

1. **Empty provider answer after a meta tool.** When the provider returns an empty answer and the
   last tool result came from `search_external_tools` or `load_external_tools`, the tool-result
   fallback prints only its header:

   > I called `search_external_tools` and gathered the data successfully, but had trouble
   > formatting a final reply. Here is what I found:

   and nothing after it. The answer looks truncated.
2. **Provider error.** When the provider call fails, the chat shows only *An error occurred while
   processing your request. Please try again.*, and the rollback of the chat memory removes the
   user's question as well. The provider's own error message, which says what went wrong, is only in
   the appliance log.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.0.2 |
| Plugin API | `morpheus-plugin-api` 1.4.1, as shipped with 9.0.2 |
| Provider | a plugin `LlmProvider`; the behaviour is in core and does not depend on the provider |
| Agent | an AI agent with at least one external MCP server, so that the meta tools are offered |

## Issue 1: the fallback prints only its header for meta tool results

### Steps to reproduce

1. Configure an AI agent with an external MCP server.
2. Ask a question that makes the model call `search_external_tools` (or `load_external_tools`) and
   then end its turn without a text block. This happens, for example, when the model spends its
   output on server-side tools or reasoning, or reaches its output limit before writing text.

**Expected:** a fallback that shows what the tool returned, or the generic message
*I gathered the requested data but had trouble formatting a final reply...*

**Actual:** only the header quoted above. The log shows:

```
WARN  c.m.AiChatService - AI assistant returned an empty response; using tool-result fallback (length=134) for leaseToken=<uuid>
```

The header is exactly 134 characters long for the tool name `search_external_tools` (132 for
`load_external_tools`, 129 for `get_tool_details`), so nothing was appended to it.

### Cause

`AiChatService.buildFallbackResponseFromToolCalls(session, preCallMessageCount)`, read from the
bytecode of `morpheus-core-9.0.2.jar`:

1. It takes the last tool-result message after `preCallMessageCount`.
2. If the text does not parse as JSON, it returns *"I was able to call `<tool>` and gather data, but
   had trouble formatting a reply. Here is the raw result:"* followed by up to 2000 characters. That
   path works.
3. If the text parses, it starts with the header above and, for a Map, appends only these keys when
   present: `summary` (collection, item count, total, hint), `items` (first 50), or else
   `preview` (up to 2000 characters as JSON).

The meta tools do not return those keys. `search_external_tools` returns
`{"results":[...],"total":n}`, and `load_external_tools` returns `{"loadedTools":[...]}`. The method
therefore returns the header alone. Because it is not blank, the generic
`gomorpheus.askMurph.error.emptyResponse` message is not used either.

The meta tools (`search_external_tools`, `load_external_tools`, `get_tool_details`,
`get_result_excerpt`, `delegate_to_specialist`) are dispatched directly in
`McpService.handleToolsCall` and never pass through `McpToolResultStoreService.compactResult`, which
is what produces the `summary`/`items`/`preview` shape of normal tool results.

## Issue 2: a provider error drops the question and hides the reason

### Steps to reproduce

1. Ask a question in the AI chat that makes the provider return an error, for example a context
   overflow (`400 prompt is too long`) or an invalid model.

**Expected:** the question stays in the conversation, and the chat says why the request failed, or
at least which kind of error it was (context too long, rate limit, authentication, model not found).

**Actual:** *An error occurred while processing your request. Please try again.* The log shows the
reason and the rollback:

```
WARN  c.m.c.u.HttpApiClient - path: /v1/messages error: 400 - {"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 1065647 tokens > 1000000 maximum"},"request_id":"<id>"}
ERROR c.m.AiChatService - Error in AI chat service: LLM provider error: API returned 400: prompt is too long: 1065647 tokens > 1000000 maximum
WARN  c.m.AiChatService - Rolling back chat memory from 15 to 10 messages after error
```

The 5 removed messages are the user's question and two pairs of tool call and tool result.

### Cause

In the `catch` block of `AiChatService.sendChatMessage`:

- `resolveUserFacingError` returns *The AI model is no longer available...* if the error looks
  model-related and otherwise the fixed text above. The exception message is not passed on.
- `preCallMessageCount` is taken from the chat memory before `chat()` is called, after which the
  user message is added. The rollback keeps only the first `preCallMessageCount` messages, so the
  question is removed together with the partial tool calls.
- `MorpheusLlmChatModel.callWithRetry` retries connection errors only, so a 4xx is not retried
  (which is correct), but nothing tells the user that retrying the same question will fail again.

## Impact

- After issue 1 the user sees what looks like a cut-off answer and cannot tell that the model did
  not answer at all.
- After issue 2 the question is gone from the conversation. Asking again starts from the same state
  and usually fails the same way, and only an administrator with access to the appliance log can
  see why.

## Suggested fix

- **Fallback**
  - When none of `summary`, `items` and `preview` is present, append the raw JSON (up to 2000
    characters), as the non-JSON path already does, or return the generic
    `gomorpheus.askMurph.error.emptyResponse` message instead of a bare header.
  - Optionally, give the meta tools their own short rendering (for `search_external_tools`: the
    names of the tools found; for `load_external_tools`: the names loaded).
- **Rollback**
  - Keep the user's question when rolling back, and remove only the partial assistant and tool
    messages. Alternatively, store the question together with the error answer.
- **Error text**
  - Show a short, safe form of the provider error: at least the HTTP status and the provider's
    error message, or a classified text (context too long, rate limited, authentication failed,
    model not available).

## Workaround

None in the chat. Read `/var/log/morpheus/morpheus-ui/current` for the provider error. A provider
plugin can avoid issue 1 by never returning an empty answer, for example by returning a short note
that the model ended without text.
