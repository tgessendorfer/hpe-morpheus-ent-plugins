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

### Verified

- 46 Spock unit tests: option parsing, ratio division and rounding, host defaults, aggregation,
  locale formatting, codes, manifest, message bundles.
- Live on Morpheus 9.0.2 with the 1.1.0 release candidates: default options and `vmsPerSocket=10`,
  93 numbers each, equal to a reference implementation run side by side; the socket total equals
  `GET /api/license`. Report and options in English and German.
- Not yet uploaded: this release jar itself. The release candidates were replaced in place by
  later builds with the same plugin id, so 1.1.0 is expected to install over them the same way.

### Not yet verified

- Any appliance version other than 9.0.2. The counting rule is measured, not documented by HPE.

Plugin API 1.4.2, minimum appliance 9.0.2.

Built by the release workflow from the source at tag `socket-report-v1.1.0`.

sha256 `62d1761e8dd7f66dff001c271e601c516c16b2cc37e716f65dc78257d25ba0cb`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/socket-report-v1.1.0
