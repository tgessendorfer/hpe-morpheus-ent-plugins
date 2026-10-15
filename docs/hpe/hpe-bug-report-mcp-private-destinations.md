# Morpheus 9.1.0: the upgrade silently blocks every external MCP server on a private address, and the only switch is an undocumented global config key

## Summary

Since 9.1.0, `McpToolCatalogService.assertServerDestinationAllowed` checks the destination of every
HTTP/SSE MCP server before discovery and before every tool call. A server whose host resolves to an
RFC1918, IPv6 ULA or CGNAT address is refused unless the Grails config key
`morpheus.mcp.allowPrivateDestinations` is `true`. The key is not documented, defaults to `false`,
and can only be set for the whole appliance.

The result: **an MCP server on the customer's own LAN that worked on 9.0.2 stops working after the
upgrade to 9.1.0, without any status change in the UI.** Every tool call fails in the log:

```
WARN  c.m.m.McpToolCatalogService - Blocked MCP connection to unsafe destination for server '<name>' … Destination resolves to a private address
… MCP server '<name>' has a service URL that is not permitted
Caused by: com.morpheus.util.SsrfBlockedException
```

The only text about it in the 9.1.0 release notes is the line "Hardened MCP destination validation".

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.1.0-1 (build 9.1.0.1), single node, upgraded from 9.0.2 |
| Component | `com.morpheus.mcp.McpToolCatalogService` (`morpheus-core-9.1.0.jar`), `com.morpheus.util.SsrfGuard` (`common-9.1.0.jar`) |
| MCP server | Streamable HTTP server on a LAN host (RFC1918 address), working on 9.0.2 |

## Steps to reproduce

1. On 9.0.2, add an external MCP server (Tools > AI Services > MCP Servers) whose service URL points
   at a host with a private address, for example `http://10.0.0.10:8081/mcp`. Assign it to an agent
   and call one of its tools from the AI chat: it works.
2. Upgrade the appliance to 9.1.0.
3. Ask the same agent a question that needs the tool.

**Expected:** the server keeps working, or the upgrade, the server list or the chat states clearly
that private destinations are now refused and how to allow them.

**Actual:**

- Every tool call is refused with `SsrfBlockedException` (log only). The chat answer either fails or
  falls back to other tools, for example web search.
- The server still shows status OK, and agents keep offering its **cached** tools, so the model
  calls them again and again (18 refused calls from one chat question in our test).
- Saving the server with a private URL succeeds: save-time validation checks syntax only, while
  every later call rejects the URL.

## Behaviour of the guard (read from the 9.1.0 bytecode, confirmed in the log)

- Runs in `createClient` (discovery) and before every `executeTool`. STDIO servers are skipped.
- Reads `grailsApplication.config.getProperty('morpheus.mcp.allowPrivateDestinations', Boolean, false)`
  on every call and passes it to `SsrfGuard.assertSafeDestination(String, boolean)`.
- Resolves all A/AAAA records of the host and checks each address:
  - always refused: loopback, link-local (including 169.254.169.254), any-local, multicast, the
    cloud metadata addresses 100.100.100.200, 192.0.0.192 and fd00:ec2::254, and reserved ranges;
  - refused only while the flag is `false`: RFC1918, IPv6 ULA `fc00::/7` and CGNAT `100.64.0.0/10`.
- The resolved address is not pinned for the connection, so a DNS rebinding window remains.
  Redirects are not followed.

## Workaround (verified)

Set the key as a JVM system property and restart the UI service:

```
# /opt/morpheus/lib/tomcat/bin/setenv.sh  (owner morpheus-app, mode 0640)
CATALINA_OPTS="$CATALINA_OPTS -Dmorpheus.mcp.allowPrivateDestinations=true"
```

`catalina.sh` sources this file. After `morpheus-ctl restart morpheus-ui` the JVM carries the flag and
tool calls to the LAN server succeed. Loopback, link-local and metadata addresses stay refused.

Whether this file survives `morpheus-ctl reconfigure` or a package upgrade is not verified. It is not
owned by any package and no chef template writes it.

## Requests

1. **Document the key** and a supported way to set it (for example in `morpheus.rb`), or state that it
   is not supported.
2. **Allow it per server, not globally.** Today the switch opens private destinations for every MCP
   server that any user with `services-ai: full` in any tenant can create, including the
   appliance's own nginx on its LAN address. A per-server flag, or an admin-managed CIDR or host
   allowlist, would let an operator allow one known LAN server without opening the whole subnet.
3. **Warn on upgrade.** List existing MCP servers with private destinations in the upgrade
   preflight or in the release notes, so the break is not silent.
4. **Validate on save and show the state.** Reject (or flag) a private URL when the server is saved,
   mark a blocked server as failed in the server list, and stop offering cached tools of a server
   whose destination is refused.

## Related observations on 9.1.0 (separate, for HPE's security review)

- **STDIO servers bypass the guard and have no command allowlist.** A user with `services-ai: full`
  can create a STDIO server that runs any command on the appliance as the Morpheus service user.
  `McpServersController` checks the licence feature `mcpIntegrations` and the permission
  `services-ai: full`; there is no master-tenant check and no STDIO-specific gate. The 9.1.0 release
  notes mention "command allowlist enforcement", but no allowlist exists in the MCP code (string
  scan of the 9.1.0 jars); the phrase may refer to `hvmcli`.
- **Custom headers are returned unmasked.** The MCP server dialog's *Custom Headers* (JSON, with a
  `{{credential}}` placeholder) come back in plain text from the UI controller JSON to anyone with
  `services-ai` read access, while the credential itself appears only as id and name. Secrets put
  directly into a header are therefore readable.
- **The credential is also sent as `Authorization: Bearer`.** With a credential and a custom header
  that uses `{{credential}}`, the server receives the value twice, once in each header.
- **LLM integrations have no destination guard.** Plugin `HttpApiClient` calls to LLM endpoints on
  private addresses are not checked, so the hardening applies to MCP servers only.
