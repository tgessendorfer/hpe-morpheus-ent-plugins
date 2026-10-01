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

### Not yet verified

- Live acceptance on Morpheus 9.0.2 of this build (`1.1.0-rc.1`): same figures as the previous
  internal build for the same month, UI in English and German, CSV export.
- That the `{{i18n}}` helper of the report renderer and the option `fieldCode` labels resolve the
  plugin's own message bundles on the appliance.

### Limits

- The current month follows the appliance JVM time zone.
- Reads internal database tables (`account_invoice`, `account`); tested on 9.0.2 only and may
  break on an upgrade.
