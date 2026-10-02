# Tenant Chargeback plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`tenant-chargeback-v<version>`, where the shaded `-all.jar` is attached.

---

## 1.1.1

**Fix release: groups are told apart by id, and the additional markup is checked and shown with
the precision it is calculated with.** Plugin API 1.4.2, minimum appliance 9.0.2, no new options.

### Changes

- **Groups keyed by id, not by name.** 1.1.0 grouped the invoice lines by the group name, so two
  groups with the same name in one tenant were merged into one row. The query now groups by the
  group id and keeps the name for display; such groups get a row each. Servers without a group
  are still one row, *Servers without a group*. The CSV columns are unchanged, so two groups with
  the same name appear as two rows with the same name.
- **Additional markup validated.** 1.1.0 accepted any number, including negative values and
  exponent notation such as `1e3`. The markup must now be a plain number from 0 to 1000 with up
  to four decimals (a decimal comma is still accepted, `7,5` = 7.5). Anything else is rejected
  with a message in English or German; a report run that bypasses the form with such a value is
  marked as failed instead of producing figures.
- **Markup shown at full precision.** The invoice amount is calculated with up to four decimals
  of a percent, but 1.1.0 showed the markup rounded to two (7.1234 % appeared as 7.12 %). The page
  now shows up to four decimals, the same value the calculation uses.
- **Build update, no change to the jar's contents.** Gradle 9.8.0 (the wrapper now checks the
  distribution's SHA-256), Shadow 9.6.1 instead of 6.0.0, and the build no longer reads the local
  Maven repository, so it uses only published dependencies. The jar keeps its name, manifest
  attributes and Java 11 bytecode.

### Behaviour changes from 1.1.0

- A tenant with two groups of the same name now shows two rows instead of one combined row; the
  per-tenant and per-currency totals are unchanged.
- Markup values below 0, above 1000, with more than four decimals, with a sign or in exponent
  notation are rejected; before, they were applied. Saved report runs with such a value fail.
- The markup in the headline shows up to four decimals instead of two.

### Verified

- Unit tests (Spock), 118 in total, 0 failures: markup values accepted and rejected (sign,
  exponent, range, decimals), form validation and the English and German error message, markup
  shown with four decimals in both locales, two groups with the same name kept apart by id, one
  line per group id, one line for servers without a group, and the query grouping by group id. The
  new tests fail against the 1.1.0 sources.
- Local build with JDK 17 and Gradle 9.8.0: one jar,
  `morpheus-tenant-chargeback-plugin-1.1.1-all.jar`, manifest as in 1.1.0 apart from the version,
  class files for Java 11, no deprecation warnings.

### Not yet verified

- Live on the appliance: 1.1.1 has not been uploaded or run on Morpheus 9.0.2 yet, including the
  query on the group id against the internal `account_invoice` table.

Built by the release workflow from the source at tag `tenant-chargeback-v1.1.1`.

sha256 `d68eddb794eb2ad7aae521634923b60a7c4722754a8ecc9d6c830e7062ef23e2`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/tenant-chargeback-v1.1.0...tenant-chargeback-v1.1.1

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
