# Instance Showback plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `instance-showback-v<version>`, where the
shaded `-all.jar` is attached.

---

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

### Not yet verified

- Live on the appliance: not done yet. 1.1.1 has not been uploaded to HPE Morpheus Enterprise
  9.0.2 so far.

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
