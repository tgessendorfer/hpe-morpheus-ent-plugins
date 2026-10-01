# Morpheus 9.0.2: AI chat and AI tasks request only 1000 output tokens, so reasoning and tool-planning models end with no answer

## Summary

Morpheus sends `maxOutputTokens: 1000` to the LLM provider on every AI chat and AI task request.
The value cannot be changed per agent, per task or per model. Models that spend output tokens on
reasoning, or on planning several tool calls, reach that limit before they write any text. The
result is:

- in the **AI chat**, the generic message *I gathered the requested data but had trouble formatting
  a final reply...* or a tool-result fallback;
- in an **AI task**, that same chat message as the task's answer, which the workflow then
  processes as if the task had succeeded.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.0.2 |
| Plugin API | `morpheus-plugin-api` 1.4.1, as shipped with 9.0.2 |
| Providers | plugin `LlmProvider`s for the Anthropic Messages API and for OpenAI-compatible APIs |
| Feature | AI chat (*Ask Murph*) and the *AI Task* task type in a workflow |

## Where the 1000 comes from

`com.morpheus.ai.MorpheusLlmChatModel.toLlmChatRequest(ChatRequest)` in `morpheus-core-9.0.2.jar`
(read from the bytecode):

```groovy
request.temperature     = chatRequest.temperature()     ?: DEFAULT_TEMPERATURE   // 0.7
request.maxOutputTokens = chatRequest.maxOutputTokens() ?: DEFAULT_MAX_TOKENS    // 1000
```

A provider plugin receives `LlmChatRequest.maxOutputTokens == 1000` on every chat request
(measured in an OpenAI-compatible provider plugin). An Anthropic provider plugin that sends the
request's value as `max_tokens` stopped an AI task at exactly 1000 output tokens. The `LlmModel` record has its own `maxOutputTokens`, which
`AiChatService.resolveMemoryTokenBudget` uses for the memory budget (default 4096), but it is not
used for the request.

## Steps to reproduce

### AI task

1. Create an AI agent whose model spends output tokens before its answer (reasoning, extended
   thinking, or several planned tool calls), with the built-in MCP tools.
2. Create a workflow with an *AI Task* whose prompt asks for an analysis that needs a few tool calls
   and a structured answer, for example a triage summary of collected diagnostics.
3. Run the workflow.

**Expected:** the task result contains the model's answer.

**Actual:** the provider answers with exactly 1000 output tokens and no text block. The task's
answer is the generic chat message, not an error. Log (measured, Anthropic provider):

```
INFO  c.m.t.AiTaskService - Sending Ai Task prompt to Agent: <id>, user: <user>
INFO  c.m.a.AnthropicProvider - Anthropic prompt cache: read=0 created=29711 uncached_input=1345 output=1000
WARN  c.m.AiChatService - AI assistant returned an empty response and no tool-result fallback was available; surfacing generic message for leaseToken=<uuid>
```

The same task, assigned to another agent on a different integration and model, returned a full
answer.

### AI chat

1. Create an agent on an OpenAI reasoning model, for example `gpt-5-mini`, through an
   OpenAI-compatible provider.
2. Ask a question in the AI chat that needs a tool call.

**Actual** (measured):

```
INFO  c.m.o.OpenAiProvider - Usage: model=openai/gpt-5-mini provider=OpenAI input=13847 cached=0 output=960 reasoning=960 ...
WARN  c.m.AiChatService - AI assistant returned an empty response and no tool-result fallback was available; surfacing generic message for leaseToken=<uuid>
```

All 960 output tokens went to reasoning; no text was returned.

## Related observation: an AI task result of `null`

In another AI task run, the agent completed a normal run of three provider calls (tool call, tool
result, final answer of 725 output tokens), but the task's DataSet result was logged as:

```
INFO  c.m.t.TaskService - Morpheus DataSet Results: [List instance status:null]
```

The cause was not investigated; it may be the same empty-answer path, or the mapping of the answer
into the task result.

## Impact

- AI tasks fail silently: the workflow continues with a canned message (or a `null` result)
  instead of an answer, and only the appliance log shows why.
- Current reasoning models (OpenAI o-series and GPT-5, models with extended thinking, most open
  reasoning models) are unreliable for AI tasks and in the chat, although their providers allow
  far larger outputs.
- Each provider plugin has to override the value with its own floor to stay usable, so behaviour
  differs between providers for the same agent.

## Suggested fix

- **Make the output limit configurable** per AI agent, and optionally per *AI Task*, with a sensible
  default of at least 4096 tokens. Use the `LlmModel`'s `maxOutputTokens` when it is set.
- **Raise the default** in `MorpheusLlmChatModel` from 1000 to at least the model's own limit or
  4096, consistent with `resolveMemoryTokenBudget`.
- **Report a cut-off answer.** When a provider signals that the output limit was reached (`length`,
  `max_tokens`), say so in the chat or fail the AI task with that reason, instead of an empty result.
- **Fail an AI task on an empty answer** rather than passing a canned message or a `null` result
  to the next task.

## Workaround

- Use a model without reasoning for AI tasks.
- Provider plugins can raise `maxOutputTokens` themselves when the model reasons or thinks; our
  OpenAI-compatible plugin raises it to 8192 for reasoning models.
