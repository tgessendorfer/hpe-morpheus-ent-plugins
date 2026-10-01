# Socket Usage Report

A Morpheus Enterprise report that shows where the socket count of the license comes from:
sockets of hypervisor hosts, plus a share for VMs in clouds without a discovered host,
broken down per tenant and per cloud and tenant.

| | |
|---|---|
| Plugin code | `morpheus-socket-report-plugin` |
| Report type (provider code) | `socket-usage-report`, category *Inventory*, name *Socket Usage* |
| Jar | `morpheus-socket-report-plugin-<version>-all.jar` |
| Release tag | `socket-report-v<version>` |

## What it shows

- **Totals:** sockets in total, host sockets (with the number of hosts) and VM sockets (with
  the number of VMs without a host and the ratio used).
- **Per tenant:** hosts, host sockets, VMs without a host, VM sockets and sockets. The master
  tenant comes first, then the other tenants by name.
- **Per cloud and tenant:** cloud, cloud type, tenant, hosts, host sockets, hosts counted with
  the default socket value, VMs on hosts, VMs without a host, VM sockets and sockets.

Numbers follow the viewer's language (`1,234.567` in English, `1.234,567` in German); without
a request locale the report uses English. Labels come in English and German.

**Which language counts:** the plugin uses the locale of the web request, i.e. the browser's
language (`Accept-Language`), not the language chosen in the Morpheus UI. Measured on 9.0.2: with
Morpheus set to German and the browser to English, the Morpheus menus are German but the plugin
content and numbers are English, and vice versa. Set the browser language to the language the
viewers use in Morpheus.

## Counting rule

Measured against `GET /api/license` (`currentUsage`) on Morpheus 9.0.2. It may change with
other versions.

- A server is a **hypervisor host** when its server type has `vm_hypervisor` set or its node
  type ends in `Metal`. A host counts its `max_sockets`; a host without a value counts the
  option *Sockets per Host Without Value* (default 2).
- A server in a cloud **without any discovered host** counts as a VM: VMs divided by the option
  *VMs per Socket* (default 15).
- VMs on discovered hosts add nothing.

Values are shown with three decimals, rounded half up. Per tenant and in total, the VM count is
summed first and divided once, so a subtotal is not always the sum of the rounded rows.
**Administration > License stays authoritative**; the report explains it, it does not replace
it.

## Who sees it

The report is **master tenant only** (`masterOnly`), because its query reads the servers of
every tenant.

## Options

| Option | Code | Field | Default |
|---|---|---|---|
| VMs per Socket | `socket-usage-report-vms-per-socket` | `vmsPerSocket` | 15 |
| Sockets per Host Without Value | `socket-usage-report-default-host-sockets` | `defaultHostSockets` | 2 |

An empty field uses the default. Zero, negative or non-numeric values are rejected.

## Compatibility

| Plugin version | Plugin API | Min. appliance | Tested appliance | Internal tables read |
|---|---|---|---|---|
| 1.1.0 | 1.4.2 | 9.0.2 | 9.0.2 | `compute_server`, `compute_server_type`, `compute_zone`, `compute_zone_type`, `account` |

**The report queries internal database tables. It is tested on 9.0.2 only and may break on an
upgrade.** If a query fails, the report ends in status *failed* and the appliance log names the
cause, instead of showing wrong numbers.

## Known limits

- The counting rule and both defaults are measured on 9.0.2, not documented by HPE.
- The report counts the servers in the database at the time it runs. It has no history.
- No currency or cost figures: sockets only.

## Build

```
./gradlew clean test shadowJar
```

The jar is `build/libs/morpheus-socket-report-plugin-<version>-all.jar`. JDK 11 or newer;
the build targets Java 11.

## Install

Administration > Integrations > Plugins > Add, then upload the `-all.jar`. Run the report
from Operations > Reports, type *Socket Usage*.

## License

Apache License 2.0, see `LICENSE` and `NOTICE`.
