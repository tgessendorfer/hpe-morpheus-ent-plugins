# Morpheus 9.0.2: external MCP tools fail over REST /api/mcp with a ClassCastException (GStringImpl to String)

## Summary

Morpheus' MCP endpoint `POST /api/mcp` can load the tools of an external MCP server
(`X-Mcp-External-Server-Ids`), but **calling** one of them over that endpoint with an API bearer
token fails every time:

```
java.lang.ClassCastException: class org.codehaus.groovy.runtime.GStringImpl cannot be cast to class java.lang.String
```

The same external tools work from the AI chat on the same appliance. Over `/api/mcp`, listing,
searching and loading the external tools work; only the call fails.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.0.2 |
| Plugin API | `morpheus-plugin-api` 1.4.1, as shipped with 9.0.2 |
| External MCP server | a streamable HTTP MCP server, registered under *Tools > AI Services*, no credential, no custom headers |
| Client | `curl` with an API access token (`Authorization: Bearer <token>`) |

## Steps to reproduce

1. Register an external MCP server (protocol streamable HTTP) with at least one tool. Note its id.
2. `POST /api/mcp` with `Authorization: Bearer <api token>`, `X-Mcp-External-Server-Ids: <id>`:
   `initialize`, then `notifications/initialized` with the returned `Mcp-Session-Id`.
3. `tools/call` `search_external_tools` with `{}`: the server's tools are listed. Works.
4. `tools/call` `load_external_tools` with their names: `{"loadedTools":[...]}`. Works.
5. `tools/call` `external__<id>__<tool>` with valid arguments.

**Expected:** the tool's result, as in the AI chat.

**Actual:** a tool result with `isError: true`:

```json
{"jsonrpc":"2.0","id":40,"result":{"content":[{"type":"text","text":"{\"error\":\"java.lang.ClassCastException: class org.codehaus.groovy.runtime.GStringImpl cannot be cast to class java.lang.String (org.codehaus.groovy.runtime.GStringImpl is in unnamed module of loader org.apache.catalina.loader.ParallelWebappClassLoader @<hash>; java.lang.String is in module java.base of loader 'bootstrap')\"}"}],"isError":true}}
```

The appliance log has the stack trace, with these Morpheus frames:

```
at com.morpheus.mcp.McpToolCatalogService.createClient(McpToolCatalogService.groovy:403)
...
at com.morpheus.mcp.McpService.handleToolsCall(McpService.groovy:832)
```

Both external tools tried fail the same way, one called with `{}` and one with arguments. Calling the same
tools from the AI chat a few minutes earlier logged `McpService - Executing external tool...` and no
error.

## Cause, as far as the bytecode shows

Read from `morpheus-core-9.0.2.jar` (`javap -c -l`); this is an interpretation, not a debugger
session.

`McpToolCatalogService.createClient(McpServer server, String bearerToken)` builds the HTTP headers
for the external server. With no credential on the server it forwards the caller's token:

```groovy
// lines ~360-366, reconstructed
headers['Authorization'] = bearerToken.startsWith('Execution') ? bearerToken : "Bearer ${bearerToken}"
```

- The chat passes a token that starts with `Execution`, so the header value is a `String`.
- A REST client's API token does not, so the value is a Groovy `GString` (`GStringImpl`).

Lines 392-396 then pass `headers` to `StreamableHttpMcpTransport.builder().customHeaders(headers)`,
and lines 400-403 build the `DefaultMcpClient`. The langchain4j transport declares the headers as
`Map<String, String>`, so a `GStringImpl` value fails as soon as it is read as a `String`. That
would explain why the exception is raised at line 403 and only on the REST path.

The `customHeaders` closure of the same method already calls `toString()` on its values, so the
problem is limited to the forwarded bearer token.

## Impact

- External MCP tools cannot be used by any MCP client of `/api/mcp` other than the built-in chat:
  external agents, automation, or tests of an MCP server through Morpheus.
- The tools are listed and loaded without error, so the failure is only visible at call time.

## Suggested fix

- Convert the header value to a `String`:
  `"Bearer ${bearerToken}".toString()`, or declare the map as `Map<String, String>` and build it with
  `String` concatenation.
- Add a test that calls an external tool through `/api/mcp` with an API token, not only from the
  chat.

## Workaround

Give the external MCP server its own credential or a custom `Authorization` header in its
configuration. `createClient` then takes the credential branch instead of forwarding the caller's
token (not tested). Otherwise call the external server directly, outside Morpheus.
