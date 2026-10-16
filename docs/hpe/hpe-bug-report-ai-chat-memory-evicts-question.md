# Morpheus 9.1.0: the AI chat forgets the user's question during a long tool loop

## Summary

A chat question that needs many MCP tool calls ends with an answer such as *"It looks like your
message came through empty — I don't see an actual question or request in your last turn"*. The
model never sees the question again after a few dozen tool messages, because the chat memory is a
token window that evicts the oldest message first, and within one question the oldest message is
the question itself.

On 9.1.0 the effect is triggered easily, because large MCP results come back truncated and the
model pages through them with `get_result_excerpt`. Each page fills the window further.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.1.0 |
| Agent | an AI agent on a Claude model (Anthropic Messages API), built-in MCP server plus one external MCP server |
| Memory settings | defaults (`morpheus.ai.chat.maxMemoryTokens` and `morpheus.ai.chat.compactionThresholdPercent` not set) |

## Steps to reproduce

1. Open the chat as an administrator of an appliance with a few dozen servers in two public clouds.
2. In a new conversation ask: *List the servers in the clouds Amazon AWS and Microsoft Azure as one
   table with name, OS, vCPU, RAM, power state and tag costcenter.*

## Expected

A table of the servers, as on 9.0.2 (measured there: about 90 seconds).

## Actual

After 4 min 25 s: *"It looks like your message came through empty ..."*. The provider log shows
about 15 model turns with more than 100 `get_result_excerpt` calls (up to 55 in one turn); the
last request carried about 60,000 input tokens. A second broad question (latest release and its
changes, through an external documentation MCP server) took 8 min 7 s, 41 turns, and ended the
same way.

## Cause (from the 9.1.0 byte code)

- `AiChatService` builds the memory as `ToolAwareChatMemory`, which delegates to langchain4j's
  `TokenWindowChatMemory`. The budget is the model context window minus output tokens,
  `TOOL_TOKEN_RESERVE` (30,000) and `SAFETY_MARGIN` (5,000), capped by `DEFAULT_MAX_MEMORY_TOKENS`
  (32,000) or the config key `morpheus.ai.chat.maxMemoryTokens`.
- `TokenWindowChatMemory` evicts the oldest messages once the estimated tokens exceed the budget.
  It keeps only the system message. Inside a single question, the oldest non-system message is the
  user's question.
- `McpToolResultStoreService` stores MCP results over `INLINE_CHAR_LIMIT` (8,000 characters) as
  artifacts and returns a preview; the model reads the rest with `get_result_excerpt`.
- `AiChatMemoryCompactionService` (threshold `morpheus.ai.chat.compactionThresholdPercent`, 50)
  summarises the memory between questions, not inside one.

With a 32,000-token window and excerpts of up to 8,000 characters, a dozen pages are enough to
push the question out.

## Workarounds

- Raise `morpheus.ai.chat.maxMemoryTokens` (it is read through the application config, so a JVM
  system property works), at the price of larger requests per turn.
- Ask narrow questions that need one or two tools.
- An LLM provider plugin can remember the question per tool call id and put it back when a request
  arrives without one (implemented in a community Anthropic plugin).

## Suggested fix

- Pin the current turn's user message in the chat memory, the same way the system message is
  kept, and evict tool messages first.
- Or run the compaction inside a tool loop when the window is nearly full, keeping the question
  and the latest tool results.
- Log at INFO when the memory evicts messages during a question, with the counts.

## Addendum: more AI chat problems seen on 9.1.0 while building automation from the chat

### A workflow cannot be created from the chat

- Asked to create an operational workflow from an existing task, the agent calls `create_workflow`, the chat shows
  the approval card *Create workflow*, the user clicks **Allow** - and the core then answers *LLM hallucinated tool
  name 'create_workflow'*. Afterwards `use_tasks_tools` is rejected the same way, and the conversation ends in *An
  error occurred*. Reproduced twice, with two different LLM integrations.
- The same tool works over the MCP endpoint (`POST /api/mcp`): `use_tasks_tools`, then `create_workflow` creates the
  workflow. The chat loses the loaded tool category between the approval and the call.

### The `create_task` schema does not mention the script

- The MCP input schema of `create_task` describes `task` as *"Task config with name, taskType:{code}, taskOptions
  (type-specific config), executeTarget, retryable, retryCount, retryDelaySeconds, allowCustomConfig"*. The REST API
  expects the script of a script task in `task.file` (`{sourceType: local, content: ...}`), which the schema does not
  name.
- Result in the chat: a shell script task is created without a script, and the model keeps calling `create_task`
  again (five approvals in five minutes). Naming `task.file` in the prompt makes `update_task` set the script at once.
- Suggested fix: describe `file` in the `create_task` and `update_task` schemas, with the shape used by `POST
  /api/tasks`.

### A task cannot be created from the chat either, and the reason is not logged

- Measured a day later on the same appliance (9.1.0, agent on a Claude model, built-in MCP server). Prompt: *Create a
  shell script task named web-health-check that runs on the target instance with sudo. Use create_task and set
  task.file to {sourceType: local, content: "..."}. Do not create a workflow and do not run anything. Reply with the
  task id.*
- The chat shows the approval card *Create task* after about 6 seconds. After **Allow** the audit log records
  `morpheus_api:tasks#save | task Failed to create`, with no error text. The model then reads the tool details and
  calls `create_task` again, which shows a second card.
- The same happens with a two-step wording (first `create_task` without a script, then `update_task` with
  `task.file`): the first step already fails. The day before, a `create_task` without a script had worked.
- The same task through `POST /api/tasks` is created at once: `taskType: {code: script}`,
  `executeTarget: resource`, `taskOptions: {"shell.sudo": "on"}`, `file: {sourceType: local, content: ...}`.
- Neither the arguments of the tool call nor the validation errors of the failed save are logged, so the cause
  cannot be found from the appliance. The model gets no usable error either and simply retries.
- Suggested fix: return the API's validation errors to the model as the tool result and log them; log the
  arguments of write tools at DEBUG.

### An unanswered approval card expires after 60 seconds

- When the second card above was left alone, the chat answered after 60 seconds: *"The request timed out waiting for
  approval. Let me know if you'd like to try again."* The card itself does not show the time limit.
- 60 seconds is short for a user who reads what the AI wants to change before deciding, which is the point of the
  card.
- Suggested fix: show the remaining time on the card, make the timeout configurable, or keep the card open until
  the user decides.
