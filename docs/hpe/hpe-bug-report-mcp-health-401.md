# Morpheus 9.0.2: the built-in MCP client's health check fails with 401 exactly 60 minutes after it connected

## Summary

For each AI chat or AI task session, Morpheus starts an MCP client (`morpheus-local`) against its own
endpoint `/api/mcp`, with a langchain4j health check. **Exactly 60 minutes after that client's
`initialize`, the health check starts failing with HTTP 401**, and it keeps trying to reconnect
every 30 seconds, failing each time, until the client is discarded:

```
WARN  d.l.m.c.DefaultMcpClient - MCP server health check (client key: <uuid>) failed. Attempting to reconnect...
java.lang.RuntimeException: java.util.concurrent.ExecutionException: java.lang.RuntimeException: Unexpected status code: 401
	at dev.langchain4j.mcp.client.DefaultMcpClient.checkHealth(DefaultMcpClient.java:433)
	at dev.langchain4j.mcp.client.DefaultMcpClient.lambda$startAutoHealthCheck$0(DefaultMcpClient.java:521)
	...
Caused by: java.lang.RuntimeException: Unexpected status code: 401
	at dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport.lambda$execute$0(StreamableHttpMcpTransport.java:212)
WARN  d.l.m.c.DefaultMcpClient - mcp server reconnection failed
```

Each attempt writes two warnings with stack traces, about 130 log lines in total.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.0.2, single node |
| Plugin API | `morpheus-plugin-api` 1.4.1, as shipped with 9.0.2 |
| Component | built-in MCP client `morpheus-local v1.0` (protocol 2025-11-25) of the AI chat, langchain4j `DefaultMcpClient` with `StreamableHttpMcpTransport` |

## Steps to reproduce

1. Open the AI chat and send one message to an agent, or run an AI task.
   The log shows `McpService - MCP initialize from client: morpheus-local v1.0 (protocol 2025-11-25)`.
2. Leave the session idle and watch `/var/log/morpheus/morpheus-ui/current`.

**Expected:** the health check keeps succeeding while the client exists, or the client is closed
when its session ends.

**Actual:** 60 minutes after the initialize, to within a second, the first `health check ... failed`
appears, followed by `mcp server reconnection failed`. Further attempts follow every 30 seconds.

## Evidence

From 12 days of appliance logs (measured):

- **42 failed health checks** for 17 different client keys, on six days. Every one was
  `Unexpected status code: 401`; no other status appeared.
- **Each series starts exactly 60 minutes after an `MCP initialize from client: morpheus-local`**.
  Example: initialize at `hh:16:28.705`, first failure one hour later at `hh+1:16:28.948`. All 17
  series match an initialize 60 minutes earlier.
- **Retries every 30 seconds:** for example `14:20:08.090`, then `14:20:38.081`. One client failed 9
  times between `15:43:15` and `15:47:15`, another 10 times in four and a half minutes.
- Each series ends after 1 to 10 attempts, presumably when the client is closed. Sessions whose
  client was closed within the hour show no failures.
- No chat or AI task error was found in connection with these failures. The next chat message
  creates a new client.

## Interpretation

The client authenticates to `/api/mcp` with a token or session that expires after one hour. The
health check does not renew it, so after expiry the endpoint answers 401 and reconnecting with the
same token cannot succeed. It is not clear from the log whether a chat that stays open for more than
an hour would hit the same 401 on a tool call.

## Impact

- Log noise: two warnings with full stack traces every 30 seconds, for every chat or AI task session
  that is idle for an hour. This hides real errors when reading the log.
- Possibly failed tool calls in conversations or AI tasks that run longer than an hour (not tested).

## Suggested fix

- Refresh the client's token before it expires, or create a new one on 401 before reconnecting.
- Close the MCP client (and stop its health check) when the chat session or AI task ends or becomes
  idle, instead of letting it run into the expiry.
- Treat a 401 as final: stop retrying and log one line without a stack trace.

## Workaround

None needed for function. To quiet the log, raise the level of `dev.langchain4j.mcp.client` to
`ERROR` in `/opt/morpheus/conf/logback.xml` (not tested).
