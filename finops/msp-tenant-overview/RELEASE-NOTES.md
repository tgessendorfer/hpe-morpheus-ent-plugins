# MSP Tenant Overview plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`msp-tenant-overview-v<version>`, where the shaded `-all.jar` is attached.

---

## 1.2.0

**The page now follows the viewer's Morpheus language setting instead of the browser language.**
Built against plugin API 1.4.2, minimum appliance 9.0.2; plugin code, provider code and options are
unchanged.

### What changed

- **Language from the user setting.** On Morpheus 9.0.2 a user whose Morpheus language was English
  saw this page in German, because the browser sent `Accept-Language: de-DE`. Morpheus takes its UI
  language from the user's own setting (`user.locale`, e.g. `en-US`, also shown by
  `GET /api/user-settings`), while the page took the request locale, which is the browser's. Plugin
  API 1.4.2 has no locale on `User`, so the provider now reads the setting itself with
  `SELECT locale FROM user WHERE id = ?` (parameterised, on the read-only report connection) and
  uses it for texts and number formats.
- **Fallback chain.** An empty, missing or unparsable setting falls back to the browser language,
  and without a web request to English. A failed lookup logs one debug line and never breaks the
  page.
- **Texts resolved by the provider.** The template's `i18n` helper looks up messages in the
  browser's locale, so the template now gets every label from the provider, read from the plugin's
  own English and German bundles. Any language other than German gets English texts; number formats
  follow the full locale as before.

### Behaviour changes from 1.1.1

- **English Morpheus setting, German browser:** the page is English (`1,234.56`, *Revenue*), where
  1.1.1 showed German (`1.234,56`, *Umsatz*).
- **German Morpheus setting, English browser:** the page is German, where 1.1.1 showed English.
- **No setting stored:** unchanged, the browser language as in 1.1.1.
- **Error messages** (*Only the provider (master) tenant can open this page.*) follow the same rule.

### Verified

- `./gradlew clean test shadowJar --warning-mode all` with JDK 17 and Gradle 9.8.0: 66 tests, 0
  failures, no deprecation warnings, one jar `morpheus-msp-tenant-overview-plugin-1.2.0-all.jar`.
- New Spock tests: an `en-US` setting with a German browser gives English, a `de` setting with an
  English browser gives German, an empty, blank or unparsable setting falls back to the browser,
  no request gives English, a failing lookup is quiet; the lookup runs the parameterised query with
  the user id as its only parameter; `loadData` formats amounts and labels in the setting's
  language, and the rendered template shows the labels in loops, the footer and the empty list.
  Ignoring the setting makes 8 of these tests fail.
- On Morpheus 9.0.2 (build 9.0.2-2) with a release candidate built from this source, as a master
  tenant user whose Morpheus setting is `en-US` while the browser sends German: the page renders in
  English with the same figures as 1.1.1.
- The release jar replaced the release candidate on the same Morpheus 9.0.2 appliance in place
  (same plugin id), status `loaded`, valid and enabled. Its source equals the candidate's apart
  from the version; the pages were not opened again with it.

### Not yet verified

- A user with a German setting, and that a sub-tenant user still sees no menu entry.

Built by the release workflow from the source at tag `msp-tenant-overview-v1.2.0`.

sha256 `6087b7c48dd3b47ba5c00dc10195d95317f984be049b61a74dbfbf53ba9131f1`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/msp-tenant-overview-v1.1.1...msp-tenant-overview-v1.2.0

## 1.1.1

**Fix release: tenants without revenue no longer show `(- %)` next to their margin.** Built against
plugin API 1.4.2, minimum appliance 9.0.2; plugin code, provider code and options are unchanged.

### What changed

- **No more `- %`.** The percentage helper returns `-` when a month has no revenue, and the template
  appended ` %` to it in three places: the margin tile of the current month (`(USD, - %)`), and the
  previous month's margin in every tenant row and in every total row (`0.00 (- %)`). Every tenant
  without invoices showed it. The provider now hands the template a separate label with the unit
  (`40.0 %`), which is empty when there is no percentage, and the template leaves the percentage
  out in that case.
- **Build update, no change to the jar's contents.** Gradle wrapper 9.8.0 with a pinned
  distribution checksum (`distributionSha256Sum`), Shadow plugin 9.6.1 (`com.gradleup.shadow`)
  instead of 6.0.0, Java 11 bytecode set through the `java { }` block, and no `mavenLocal()`
  repository any more, so a build resolves only from Maven Central and the Gradle plugin portal.
  The jar keeps the same manifest attributes and the same class layout.

### Behaviour changes from 1.1.0

- **Margin tile without revenue:** `Margin 2026-10 (USD)` instead of `Margin 2026-10 (USD, - %)`.
- **Previous month's margin without revenue** (tenant rows and totals): `0.00` instead of
  `0.00 (- %)`.
- **Unchanged:** the *Margin %* column still shows `-` without revenue, and every percentage with
  revenue reads as before (`40.0 %`).

### Verified

- `./gradlew clean test shadowJar --warning-mode all` with JDK 17 and Gradle 9.8.0: 46 tests, 0
  failures, no deprecation warnings, one jar `morpheus-msp-tenant-overview-plugin-1.1.1-all.jar`.
- Compared with a local 1.1.0 build: the same jar entries and the same manifest attributes apart
  from `Plugin-Version`; class files are still Java 11 bytecode (major version 55).
- New Spock tests: the percentage label for positive, negative, zero and missing revenue in English
  and German; a tenant without invoices gets no percentage label in either month; and the template,
  rendered with Handlebars, shows no `- %` and no empty brackets for such a tenant while keeping
  `(EUR, 40.0 %)` and `5.00 (10.0 %)` for a tenant with revenue. The template test fails against
  the 1.1.0 template.
- On Morpheus 9.0.2 (build 9.0.2-2): the release jar was uploaded over the previous build and
  replaced it in place (same plugin id), status `loaded`, valid and enabled; no plugin error in the
  log.

- Live on Morpheus 9.0.2 as master tenant user, in German and English: revenue, cost and margin
  equal the Tenant Chargeback report for both months, and a currency without revenue shows
  *Margin 2026-10 (USD)* and *0.00* instead of *(- %)*.

### Not yet verified

- That a sub-tenant user sees no menu entry with this jar (unchanged code path from 1.1.0).

Built by the release workflow from the source at tag `msp-tenant-overview-v1.1.1`.

sha256 `07cd4a696d8a8b8efc76b3c0b696a52c6766862af6d09c861b0c7c950600dc25`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/msp-tenant-overview-v1.1.0...msp-tenant-overview-v1.1.1

## 1.1.0

**First public release: one Analytics page for the provider with resources, revenue, cost and
margin of every sub-tenant, per currency.** Built against plugin API 1.4.2, minimum appliance
9.0.2.

### What it does

- **Analytics page *MSP Tenant Overview*** (provider code `msp-tenant-overview-analytics`, plugin
  code `morpheus-msp-tenant-overview-plugin`), category *Cost*, in the master tenant only
  (`masterTenantOnly` plus a check of the viewer's account).
- **Per sub-tenant:** users, groups, instances, vCPU, memory, servers, and revenue, cost, margin and
  margin % for the current month (forecast) and the previous month, from Morpheus' monthly
  invoices of instances and of servers without an instance.
- **Per currency, never mixed:** one line per tenant and currency; totals per currency. An invoice
  without a currency counts in the master account's currency, else in `USD`.
- **Rounded once:** amounts are summed per tenant, currency and month, then rounded to cents; margins
  and totals use the rounded amounts.
- **English and German:** all headings, columns, notes and error messages; numbers in the viewer's
  locale (English when there is none).
- **Clear error instead of a stack trace** when the internal tables cannot be read.

### Verified

- `./gradlew clean test shadowJar` with JDK 17: Spock tests for plugin code = manifest code, repo
  link, the one provider, identical message keys in English and German, every template key present,
  currency fallback, grouping per currency, rounding, percentages and number formats.
- Live on Morpheus 9.0.2 with the 1.1.0 release candidates: every figure equal to a reference
  implementation run side by side; sub-tenant users see neither the menu entry nor the content;
  English and German, including an apostrophe in a German text that MessageFormat used to drop.
- Not yet uploaded: this release jar itself. The release candidates were replaced in place by
  later builds with the same plugin id, so 1.1.0 is expected to install over them the same way.

### Compatibility

Plugin API 1.4.2, minimum appliance 9.0.2, tested on 9.0.2 only. Reads the internal tables `user`,
`account`, `compute_site`, `instance`, `compute_server` and `account_invoice`; may break on an
appliance upgrade.

Built by the release workflow from the source at tag `msp-tenant-overview-v1.1.0`.

sha256 `f8def473b5024ac4362cdc56c35c33a183320d6265e70456bcc7f02f862d1c8d`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/msp-tenant-overview-v1.1.0
