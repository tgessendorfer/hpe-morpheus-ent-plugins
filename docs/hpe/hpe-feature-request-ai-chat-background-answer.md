# Morpheus 9.1.0: a chat answer is lost when the user leaves the page

## Summary

When a user opens another page while the AI chat is still working on a question, the answer never
appears. The server still finishes the whole question, with every model call and tool call, but
the answer is not delivered to the browser. Back on any page, the chat offers *Continue* without
the answer.

Whether the model still knows the lost answer depends on the conversation:

- If the lost answer was the first one of a conversation, the model does not know it afterwards.
- If the conversation already had an answer, the server session keeps the lost answer. In the
  same browser tab, within the widget's 30-minute limit, the user can get it back by asking for it
  again.

With a slow model, such as a self-hosted LLM, the window for this is long: users browse on while
they wait and do not see the answer, and in a new conversation the model time is spent for
nothing. A slow model step also meets a 120-second proxy timeout on the chat stream.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.1.0 (build 9.1.0-1) |
| Agent | an AI agent on a Claude model, built-in MCP server |
| LLM integration | community Anthropic provider plugin; it logs every tool call at INFO, and every model turn with its stop reason when web search or web fetch is enabled, as in this test |
| Chat | the chat widget on the standard UI pages |

## Steps to reproduce

1. Open the chat on the dashboard and start a new conversation.
2. Ask a question that needs a few tool calls, for example: *For every cloud, count the instances
   and the servers, then give me one table with cloud name, type, status, instance count and server
   count.*
3. While the typing indicator is shown, open another page, for example *Provisioning > Instances*.
4. Open the chat on the new page.

## Expected

The chat shows that the question is still being answered and shows the answer when it is ready,
or keeps the answer so that it can be shown on the next page.

## Actual

- The new page shows *Previous conversation (1 messages)* with *Continue* and *New Chat*. There is
  no typing indicator and no answer.
- The provider plugin's log shows the request running on after the page change: five model turns
  with 30 tool calls in total. The last three turns (24 tool calls and the final answer) finished
  after the new page had loaded, and the final answer came 44 seconds after the question was sent.
  The audit log records `askMurph#send` as created. Nothing is logged at the default log level
  about the closed connection.
- After *Continue*, the question *Do you have an answer from you to my previous question in this
  conversation? Reply only yes or no, and do not call any tools.* was answered *No*.
- Second run, in a conversation that already had one answer: the same question (with a test label
  in front), the same page change, the answer again not shown. About ten seconds after the server
  had finished, the model confirmed after *Continue* that it had answered. Asked to show its answer
  to that question again (naming the question by its label), unchanged and without tools, it
  returned the full table in about 6 seconds without any tool call.

## Cause (from the 9.1.0 widget script and byte code)

- The widget sends the question with `POST /askMurph/send` and reads the answer from the response
  as a server-sent event stream (events `tool`, `confirmation_required`, `response`, `error`).
- The widget keeps the conversation in the browser's `sessionStorage` (`ask-murph-conversation`).
  It stores the question at once, but the answer only when the `response` event arrives. The
  session's lease token also arrives only with a `response` event. A page change ends the stream,
  so the answer is not stored.
- `AskMurphController.send` runs the whole tool loop on the request thread. It does not stop when
  the client is gone; a failed write of a tool event is caught and logged at DEBUG only. The answer
  goes into the server-side chat session only.
- `AiChatService` keeps that session in memory (`sessionCache`), keyed by the lease token, for one
  hour after the last activity (`SESSION_TTL_MS`). When a question carries a known lease token, the
  server reuses the session and its memory. In a new conversation the browser has no token yet,
  so the next question starts a new session, rebuilt from the browser's history, which lacks the
  answer.
- The widget sets no timeout on the stream and has no reconnect or polling. On a page load it
  drops the stored conversation and lease token when the last activity is older than 30 minutes.

## Timeouts and limits on the path (read from 9.1.0)

| Where | Limit | Applies to |
|---|---|---|
| Chat widget | none | the whole stream |
| Bundled nginx, `location /` (`proxy_read_timeout`, install default 120) | 120 s | time between two pieces of data from the application on `/askMurph/send` |
| HPE Local LLM plugin 1.0.0 | 120 s read, 30 s connect | each model call (one step of the tool loop) |
| Chat MCP client (`toolExecutionTimeout`) | 60 s | each tool call of the chat, built-in or external |
| Approval card | 60 s | an unanswered *Allow / Deny* card |
| langchain4j tool loop (`maxSequentialToolsInvocations`, default not changed by Morpheus) | 100 rounds | sequential tool rounds of one question |
| Chat session (`SESSION_TTL_MS`) | 1 hour after the last activity | the server-side chat memory |
| Widget, stored conversation | 30 minutes after the last activity | the conversation and lease token in `sessionStorage`, dropped on the next page load |

No time limit for a whole question was found; on the question's path, the only wait with a time
limit in `AiChatService` is the approval card (core also retries a model call at most twice after
a connection failure). The stream sends no keep-alive events, so a model call that stays silent
for more than 120 seconds is cut by nginx, whatever the provider plugin's own timeout. The browser
then shows an error while the server goes on with the question (not measured).

## Workarounds

- Stay on the page until the answer arrives. Browsing in a second browser tab should also work,
  because the stream stays open in the first tab (not tested).
- If the answer was lost in a conversation that already had an earlier answer: wait until the
  question would have finished, then, in the same browser tab and within 30 minutes, open the chat,
  click *Continue* and ask the model to show its answer to that question again, naming the
  question, without calling any tools (worked in our test; asking while the lost question was still
  running was not tested).
- Ask narrow questions that need one or two tools, so each answer is quick.
- With a slow model, keep each model step under 120 seconds. A provider plugin with a longer read
  timeout does not help the browser as long as nginx cuts the stream after 120 seconds.

## Suggested improvements

1. **Deliver the answer after a page change:** the answer is already in the server-side session.
   Send the lease token as the first event of the stream, and let the widget fetch a pending or
   finished answer by that token on the next page.
2. **Show a running question after a page change:** a status such as *Still working on your last
   question* instead of only *Continue / New Chat*.
3. **Or stop the work when the client is gone:** if the answer cannot be delivered, stop the tool
   loop and the model calls, and log it at INFO or WARN (today a failed tool-event write is logged
   at DEBUG only).
4. **Send keep-alive events** on the stream while a model call runs, or give `/askMurph/send` its
   own nginx location with a longer read timeout, so a slow model is not cut by the proxy.
5. **Document the existing nginx setting** `nginx['proxy_read_timeout']` (`morpheus.rb`, default
   120 s, applies to every UI request under `location /`), and make the Local LLM plugin's model
   call timeout and the approval card timeout configurable.
