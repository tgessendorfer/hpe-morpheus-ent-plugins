# Tenant Chargeback plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`tenant-chargeback-v<version>`, where the shaded `-all.jar` is attached.

---

## 1.1.2

**Fix release: servers without a group are one row per tenant and currency again.** Plugin API
1.4.2, minimum appliance 9.0.2, no new options.

### Changes

- **Nameless group lines merged again.** On Morpheus 9.0.2 the invoices of servers without an
  instance can carry a group id but an empty group name. 1.1.1 keyed the group rows by group id,
  so every such id became a row of its own, and all of them read *Servers without a group*: one
  tenant showed two such rows in the same currency, another three. Lines without a group name now
  merge into one *Servers without a group* row per tenant and currency, whatever their group id.
  Lines with a group name stay keyed by group id, so two groups with the same name in one tenant
  still get a row each (the 1.1.1 fix).
- The query is unchanged: it sums per tenant, group id and currency and takes the group name it
  finds. Group names are not looked up from the group table.
- **Limit:** when other invoices of the same group id, tenant and currency carry the group's name,
  the nameless server invoices of that id are summed into that group's row, not into *Servers
  without a group*. 1.1.1 does the same; 1.1.0, which grouped by name, showed them without a
  group. The totals are not affected. In the invoices that showed the split rows with 1.1.1 no
  group id had both, since each of those ids came out as a row without a name.

### Behaviour changes from 1.1.1

- Several *Servers without a group* rows of one tenant and currency become one row with the summed
  resources and amounts. For lines without a group name this restores the single row 1.1.0
  showed. The per-tenant and per-currency totals are unchanged.

### Verified

- Unit tests (Spock), 121 in total, 0 failures. New tests cover the data shape seen on the
  appliance: nameless lines with different group ids in two tenants, a nameless line with a null
  group id, nameless lines in several currencies (including one without a currency that falls back
  to the master tenant's), and two groups with the same name but different ids next to nameless
  lines. The three new tests fail against the 1.1.1 sources.
- Local build with JDK 17 and Gradle 9.8.0: one jar,
  `morpheus-tenant-chargeback-plugin-1.1.2-all.jar`, no deprecation warnings.

- On Morpheus 9.0.2 (build 9.0.2-2): the release jar replaced 1.1.1 in place (same plugin id),
  status `loaded`, valid and enabled. Reports for 2026-09 (10 % markup, provider resources) and
  2026-10 (no options) against the same runs with 1.1.1: the split rows are one row again (two USD
  rows of 34 and 3 resources became one of 37; three of 1, 1 and 3 became one of 5); tenant and
  total rows are identical, and every tenant and currency adds up to the same resources and
  invoice amount.

### Not yet verified

- The report as a sub-tenant user is not applicable (master tenant only); the page in English with
  this jar was not opened.

Built by the release workflow from the source at tag `tenant-chargeback-v1.1.2`.

sha256 `d74d9ec3a0d1b6c2a49a18db6af296f04a0cfa229e2a2bae23d4c5634a040875`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/tenant-chargeback-v1.1.1...tenant-chargeback-v1.1.2

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
- On Morpheus 9.0.2 (build 9.0.2-2): the release jar was uploaded over the previous build and
  replaced it in place (same plugin id), status `loaded`, valid and enabled; no plugin error in the
  log.

- Live on Morpheus 9.0.2: the 2026-09 figures with 10 % markup equal the 1.1.0 acceptance
  (cost 4.96, list price 6.16, margin 1.20, 19.5 %, invoice 6.78 EUR); a markup of 7.1234 % gives
  6.60; markups of -5, 1e3, 7.12345, 1000.01 and +5 and the month 2026-13 are rejected.

### Known issue, fixed in 1.1.2

- **Server invoices carry a group id but no group name**, so on the appliance one tenant showed two
  or three *Servers without a group* rows per currency instead of one. Totals per tenant and
  currency were correct. See 1.1.2.

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
