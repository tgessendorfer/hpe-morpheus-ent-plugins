# Instance Showback plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `instance-showback-v<version>`, where the
shaded `-all.jar` is attached.

---

## 1.2.0

**The Costs tab now speaks the language set in the viewer's Morpheus user settings,** the same
setting the Morpheus UI uses, for its texts and its number formats. Up to 1.1.1 it followed the
browser language, so on Morpheus 9.0.2 a user with the Morpheus UI in English and a German browser
saw the Morpheus pages in English and the tab in German. Minor release; plugin code, provider code,
plugin API 1.4.2 and minimum appliance 9.0.2 unchanged.

### What changed

- **Why:** Morpheus takes its UI language from the user's own setting (the `locale` column of its
  internal `user` table, for example `en-US`; `GET /api/user-settings` shows it as `locale`). The
  plugin API's web request carries the browser's `Accept-Language` instead, and plugin API 1.4.2
  has no locale on `User`. In 1.1.x, texts and number formats both came from the web request.
- **New rule for the content language:** the viewing user's Morpheus setting, else the browser
  language, else English. The setting is read with the parameterised query
  `SELECT locale FROM user WHERE id = ?` over the read-only database connection the tab already
  uses; `en_US` style values are accepted. An empty value, an unknown language or a failed lookup
  falls back to the browser language with at most one debug line in the log; the tab still renders.
- **Which user:** `renderTemplate()` gets no user in plugin API 1.4.2. Morpheus 9.0.2 calls the
  tab's `show()` (which gets the user) and then `renderTemplate()` in the same request
  (`InstancesController.show`), so the plugin keeps the user id from `show()` for that one render.
  Without a preceding `show()` on the request thread there is no user in context and the browser
  language counts, as before.
- **Messages from the plugin's own bundles.** The template no longer uses Morpheus' `i18n` helper,
  which always takes the browser language. The texts are resolved in the content language and passed
  to the template. Texts exist in English and German as before; any other setting gets English texts
  and that locale's number format.

### Behaviour changes from 1.1.1

- A user whose Morpheus language differs from the browser language now sees the tab in the Morpheus
  language: English setting with a German browser gives English texts and `1,234.56`, German setting
  with an English browser gives German texts and `1.234,56`.
- With an empty or unknown Morpheus setting, or when the setting cannot be read, nothing changes:
  browser language, else English.
- The error message shown when the cost data cannot be loaded follows the same rule once the
  database connection is open; when the connection itself fails, it is in the browser language.
- The tab title stays *Costs* in every language (`getName()` has no request context).
- The plugin now also reads the internal `user` table (`id`, `locale`).

### Verified

- 79 unit tests with plugin API 1.4.2, 0 failures (36 new, each row of a data table counted): the
  language rule (`en-US` setting with a German browser gives English, `de` setting with an English
  browser gives German, empty, blank, unknown and malformed settings give the browser language,
  no setting and no request give English), the query text and that the user id is passed as a
  parameter, the quiet fallback when the user row is missing or the query fails, no query without a
  user, the hand-over of the user id from `show()` to one render, English texts for languages
  without a bundle, equal keys in both bundles, the template rendered with Handlebars in the
  resolved language and number format, and `renderTemplate()` itself with a stand-in database: it
  reads the setting of the user from `show()` (`en-US` setting with a German browser gives
  *Month to date* and `1,234.57`), falls back to the browser language for an empty setting or
  without a preceding `show()`, and releases the connection. Removing the user lookup from
  `renderTemplate()`, dropping the user id handed over from `show()`, or not storing it in `show()`
  each makes tests fail.
- Local build: JDK 17, Gradle 9.8.0, no deprecation warnings, one jar
  `morpheus-instance-showback-plugin-1.2.0-all.jar`; manifest attributes unchanged apart from
  `Plugin-Version`.
- The call order `show()` then `renderTemplate()` in one request was read from the 9.0.2-2
  appliance's `InstancesController` bytecode, not observed at runtime.
- On Morpheus 9.0.2 (build 9.0.2-2) with a release candidate built from this source, as a master
  tenant user whose Morpheus setting is `en-US` while the browser sends German: the *Costs* tab
  renders in English with the same figures as 1.1.1 and the last cost run in UTC.
- The release jar replaced the release candidate on the same Morpheus 9.0.2 appliance in place
  (same plugin id), status `loaded`, valid and enabled. Its source equals the candidate's apart
  from the version; the check with the `de` setting below ran with this jar.
- With the Morpheus setting switched to `de` for the same user while the request asked for English
  (`Accept-Language: en-US`): the tab renders in German with German number formats (decimal
  comma).
- As a sub-tenant admin without a language setting (impersonated from the master tenant, German
  browser): the *Costs* tab of one of the tenant's instances shows its forecast (16,33 EUR) in
  German, the browser language being the fallback.

### Not yet verified

- Nothing beyond the limits in the README.

Built by the release workflow from the source at tag `instance-showback-v1.2.0`.

sha256 `b8aa2e188d478db55893d3b20058bba8cd64ed37cbd8f2a3799e6976b75f3d50`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/instance-showback-v1.1.1...instance-showback-v1.2.0

## 1.1.1

**The footer time of the last cost run is now really UTC.** The footer has always said *UTC*, but
1.1.0 printed the database value with `Timestamp.toString()`, which uses the time zone of the
appliance JVM. Patch release; no new options, plugin code and provider code unchanged.

### What changed

- **Footer time formatted in UTC.** The time of the last cost run (`last_cost_date`) is converted
  explicitly to UTC and shown as `yyyy-MM-dd HH:mm`. A `java.sql.Timestamp` (or any other value
  that carries an instant) is converted from its instant, so a JVM running in another time zone no
  longer shifts the time. Depending on driver and version, JDBC can also return the `DATETIME`
  column as a `java.time.LocalDateTime` without a zone; that value is the stored wall clock, which
  Morpheus writes in UTC, and is shown as is. 1.1.0 printed such a value as `2026-10-15T02:00`.
- **Latest cost run compared as a time, not as text.** When an instance has invoices in more than
  one currency, the footer shows the latest of their cost runs. 1.1.0 compared the values as text,
  which picks the wrong one once the values are of different types or shifted by the JVM time zone.
- **Build update, no change to the jar's contents:** Gradle 9.8.0 (the wrapper now checks the
  distribution against its SHA-256 checksum), Shadow 9.6.1 (`com.gradleup.shadow`) in place of
  Shadow 6.0.0, and no `mavenLocal()` repository, so a build resolves only from Maven Central and
  the Gradle plugin portal. Still Java 11 bytecode, same manifest attributes.

### Behaviour changes from 1.1.0

- On an appliance whose JVM runs in UTC, the footer shows the same time as before. Morpheus 9.0.2
  starts its JVM with `-Duser.timezone=UTC`, so on a standard appliance only the separator of a
  `LocalDateTime` value can change (`2026-10-15 02:00` instead of `2026-10-15T02:00`).
- On a JVM in another time zone, the footer time moves by that zone's offset to the correct UTC
  time.
- A footer value that is not a date-time now shows `-` instead of the first 16 characters of its
  text.

### Verified

- 43 unit tests (13 new, each row of a data table counted), with plugin API 1.4.2. The new tests set a non-UTC default time zone
  (Europe/Berlin, Asia/Tokyo, America/New_York) inside the test and restore it afterwards: a
  `Timestamp` is shown in UTC, a `Timestamp` just before midnight UTC keeps its UTC date, a
  `LocalDateTime` is not shifted, `Instant`, `OffsetDateTime`, `java.util.Date`, text and missing
  values are formatted as expected, and the latest cost run wins, both across currencies and when
  two rows of the same month and currency are merged (in either order). The comparison tests use a
  `Timestamp` and a `LocalDateTime` whose text order is the opposite of their time order.
- Checked against the 1.1.0 code, one change at a time. With the 1.1.0 footer formatting, three
  tests fail: the `Timestamp` footer, the `LocalDateTime` footer and the latest cost run across
  currencies. With the 1.1.0 text comparison across currencies, the cross-currency test fails.
  With the 1.1.0 text comparison in the merge, both merge tests fail. The other new tests call
  helpers that 1.1.0 does not have.
- Local build: JDK 17, Gradle 9.8.0, no deprecation warnings; the jar has the same manifest
  attributes as 1.1.0 apart from `Plugin-Version`, and Java 11 bytecode (class file major 55).
- On Morpheus 9.0.2 (build 9.0.2-2): the release jar was uploaded over the previous build and
  replaced it in place (same plugin id), status `loaded`, valid and enabled; no plugin error in the
  log.

- Live on Morpheus 9.0.2 as master tenant user, page in German: the *Costs* tab shows month to date,
  forecast, split and history equal to the instance's invoices, and the footer prints the last cost
  run as `2026-10-02 08:59 UTC`, the invoice's `last_cost_date` in UTC.

### Not yet verified

- The tab in English and as a sub-tenant user with this jar.

Built by the release workflow from the source at tag `instance-showback-v1.1.1`.

sha256 `21983ae61c1e51e50f96347e11235d736558e90b95e6c3ce89177a59daaafc9b`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/instance-showback-v1.1.0...instance-showback-v1.1.1

## 1.1.0

**First public release: a Costs tab on every instance detail page,** with month-to-date cost, the
forecast to month end, the split into compute, storage and licenses, and the three months before,
per currency. Built against plugin API 1.4.2, minimum appliance 9.0.2.

### What it does

- **Instance tab *Costs*** (provider code `instance-showback-tab`, plugin code
  `morpheus-instance-showback-plugin`), reading the instance's monthly invoices over the read-only
  database connection.
- **Never adds up currencies.** Sums, rows and bar scales are per currency. A row without a currency
  falls back to the master tenant's currency, then to `USD`, and is merged with rows already in that
  currency.
- **Numbers in the viewer's locale** (`1,234.56` / `1.234,56`, English fallback), always with the
  ISO currency code.
- **English and German** texts through `i18n/messages*.properties`; the tab title stays *Costs*.
- **Errors stay in the tab:** a failed query shows a short message and logs the cause.

### Verified

- 30 unit tests with plugin API 1.4.2: currency rule, grouping and merging per month and currency,
  half-up rounding, number format in English and German, bar scaling, month list across year
  boundaries and month ends, plugin code against the manifest, repository URL, registered provider,
  and identical keys in the English and German bundles.
- Live on HPE Morpheus Enterprise 9.0.2 with the 1.1.0 release candidates: every figure equal to a
  reference implementation run side by side, as master and as sub-tenant user, texts in English
  and German.
- Not yet uploaded: this release jar itself. The release candidates were replaced in place by
  later builds with the same plugin id, so 1.1.0 is expected to install over them the same way.

Built by the release workflow from the source at tag `instance-showback-v1.1.0`.

sha256 `d16fb6da0961c421b4c1b8773401c32c8bd112b0829983e9ccc053dcf2d31763`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/instance-showback-v1.1.0
