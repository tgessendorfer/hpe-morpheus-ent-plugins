# Tenant Chargeback plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`tenant-chargeback-v<version>`, where the shaded `-all.jar` is attached.

---

## 1.1.0

**First public release: a monthly chargeback report per tenant and group from the Morpheus
invoices.** Plugin API 1.4.2, minimum appliance 9.0.2.

### What it does

- **Report *Tenant Chargeback*** (provider code `tenant-chargeback-report`, plugin code
  `morpheus-tenant-chargeback-plugin`, category *Cost*, master tenant only): cost, list price,
  margin, margin % and invoice amount per currency, per tenant, and per tenant and group.
- **Options:** month (`YYYY-MM`, empty = current month, months 00 and 13 are rejected), an
  additional markup in percent on the list price, and whether to include the master tenant's own
  resources.
- **No double counting:** instance invoices plus invoices of servers without an instance; the
  summary invoices per tenant, group, cloud and user are left out.
- **Cent-exact:** every line is rounded to cents before it is added, so the shown rows add up.
- **Currencies kept apart:** line currency, else master tenant currency, else USD. Totals are per
  currency, never converted or added up.
- **CSV-friendly results:** the stored values are plain numbers (two decimals, dot), the page
  formats them in the viewer's locale.
- **English and German** for options, page and messages.

### Verified

- Unit tests (Spock): month validation and period key, option parsing, rounding to cents,
  margin and markup per line, totals equal the sum of the shown lines, currency resolution and
  grouping, master tenant filter, locale formats, option codes, and that every option label,
  help text and template key exists in the English and the German bundle; plugin code equals
  the manifest `Morpheus-Code`.
- Live on Morpheus 9.0.2 with the 1.1.0 release candidates, one master and two sub-tenants: four
  runs over two months, with and without options, every figure equal to a reference implementation
  run side by side (68 to 104 numbers per run, 0 differences). Page, option labels and messages in
  English and German. CSV from the API and from the UI byte-identical, UTF-8 without BOM, every
  CSV number also on the page.
- Not yet uploaded: this release jar itself. The release candidates were replaced in place by
  later builds with the same plugin id, so 1.1.0 is expected to install over them the same way.

### Limits

- The current month follows the appliance JVM time zone.
- Reads internal database tables (`account_invoice`, `account`); tested on 9.0.2 only and may
  break on an upgrade.

Built by the release workflow from the source at tag `tenant-chargeback-v1.1.0`.

sha256 `38cdaac17bf58a7993a8980f342f5264ca2f54fa5dd77e2c9b74499fe05bce38`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/tenant-chargeback-v1.1.0
