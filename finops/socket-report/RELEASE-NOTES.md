# Socket Usage Report plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `socket-report-v<version>`, where the
shaded `-all.jar` is attached.

---

## 1.1.0

**First public release: a report that shows where the socket count of the license comes
from, per tenant and per cloud and tenant.** Plugin code `morpheus-socket-report-plugin`,
report type `socket-usage-report` (*Socket Usage*, category *Inventory*), master tenant only.

### What it does

- Counts hypervisor hosts with their sockets and servers in clouds without a discovered host as
  a share of a socket, the way the license page counted on Morpheus 9.0.2.
- Totals, a per-tenant table (master tenant first) and a per cloud and tenant table, including
  how many hosts were counted with the default socket value.
- Two options: **VMs per Socket** (default 15) and **Sockets per Host Without Value**
  (default 2). Empty uses the default; zero, negative or non-numeric input is rejected.
- English and German labels; numbers in the viewer's locale, English without one.
- A failed query of the internal tables ends the report as *failed* with a clear log entry.

### Changes from the earlier private build

- New plugin code, report type code and option codes; the earlier build cannot be upgraded in
  place and is removed separately.
- The default of 2 sockets for a host without a value is now an option.
- Subtotals and the total divide the summed VM count once, instead of adding up per-row shares.
- Two tenants with the same name are kept apart.

### Verified

- 45 Spock unit tests: option parsing, ratio division and rounding, host defaults, aggregation,
  locale formatting, codes, manifest, message bundles.

### Not yet verified

- Install and run on a live 9.0.2 appliance with this build, including the German UI and the
  comparison with `GET /api/license`.
- Any appliance version other than 9.0.2.

Plugin API 1.4.2, minimum appliance 9.0.2.
