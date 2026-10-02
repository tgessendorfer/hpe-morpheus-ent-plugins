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

Texts and numbers follow one language (`1,234.567` in English, `1.234,567` in German). Labels
come in English and German; any other language shows English texts with that language's number
format.

**Which language counts (since 1.2.0):** the language chosen in the Morpheus user settings, the
same one the Morpheus menus use. Plugin API 1.4.2 does not tell a report which user views it, so
the report uses the setting of the user who ran it. When that user has no setting, or the
setting cannot be read, the report falls back to the browser language (`Accept-Language`) and,
without a web request, to English. Up to 1.1.1 the report always followed the browser language:
measured on 9.0.2, with Morpheus set to English and the browser to German, the Morpheus menus
were English but the report was German.

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

An empty field uses the default. A value must be greater than 0 and at most 1,000,000, with at
most 3 decimal places, in plain notation; zero, negative, non-numeric, larger values, more
decimal places and exponent notation (`1e2`) are rejected (since 1.1.1). A stored value outside
these bounds, for example from a report created with 1.1.0, falls back to the default when the
report runs, with a warning in the appliance log.

## Compatibility

| Plugin version | Plugin API | Min. appliance | Tested appliance | Internal tables read |
|---|---|---|---|---|
| 1.2.0 | 1.4.2 | 9.0.2 | 9.0.2 (1.2.0 release candidate, master tenant) | `compute_server`, `compute_server_type`, `compute_zone`, `compute_zone_type`, `account`, `user` (language setting) |
| 1.1.1 | 1.4.2 | 9.0.2 | 9.0.2 (1.1.0, 1.1.1) | `compute_server`, `compute_server_type`, `compute_zone`, `compute_zone_type`, `account` |
| 1.1.0 | 1.4.2 | 9.0.2 | 9.0.2 | `compute_server`, `compute_server_type`, `compute_zone`, `compute_zone_type`, `account` |

**The report queries internal database tables. It is tested on 9.0.2 only and may break on an
upgrade.** If a query fails, the report ends in status *failed* and the appliance log names the
cause, instead of showing wrong numbers.

## Known limits

- The counting rule and both defaults are measured on 9.0.2, not documented by HPE.
- The report counts the servers in the database at the time it runs. It has no history.
- No currency or cost figures: sockets only.
- The report shows in the language of the user who ran it, not of the user who views it: Plugin
  API 1.4.2 hands a report's rendering no viewing user. Viewers with another language see the
  runner's language.
- The create dialog is unchanged: Morpheus itself translates the option labels and help texts,
  and the validation messages follow the browser language as before, because validation gets
  no user.

## Build

```
./gradlew clean test shadowJar
```

The jar is `build/libs/morpheus-socket-report-plugin-<version>-all.jar`. JDK 17 (Groovy 3.0.9 does not
run on JDK 21); the build targets Java 11.

## Install

Download `morpheus-socket-report-plugin-1.2.0-all.jar` from the release
[socket-report-v1.2.0](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/socket-report-v1.2.0),
then Administration > Integrations > Plugins > Add and upload the `-all.jar`. Run the report
from Operations > Reports, type *Socket Usage*.

## License

Apache License 2.0, see `LICENSE` and `NOTICE`.
