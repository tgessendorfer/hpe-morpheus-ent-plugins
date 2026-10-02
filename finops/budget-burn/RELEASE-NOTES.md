# Budget Burn plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`budget-burn-v<version>`, where the shaded `-all.jar` is attached.

---

## 1.1.1

**Fix release: a budget of 0, the year-to-date budget and a failed connection release are
handled correctly.** Plugin API 1.4.2, minimum appliance 9.0.2; plugin code, provider code and
the page layout are unchanged.

### Changes

- **A monthly budget of 0 with spend is *Over budget*.** 1.1.0 showed such a budget as *On
  track 0.0 %*, because the percentage of a budget of 0 was taken as 0. Now any forecast spend
  against a budget of 0 is *Over budget* with a full bar, and a budget of 0 without spend stays
  *On track*. **Behaviour change:** the percentages of a budget of 0 (spend to date, forecast,
  year to date) show `-` instead of `0.0 %`.
- **The year-to-date budget no longer drifts by cents.** 1.1.0 rounded each month's share of a
  quarterly or yearly budget to cents before adding them up, so a yearly budget of 1,000.00
  showed 999.96 in December. The shares are now added up unrounded and rounded once: a yearly
  budget gives exactly its amount for the full year and a quarterly budget exactly its amount per
  quarter. The monthly budget column is still the current month's share rounded to cents.
  **Behaviour change:** year-to-date budgets and percentages can differ from 1.1.0 by a few cents
  or a tenth of a percent.
- **A failed release of the database connection no longer replaces the page result.** An error
  while handing the read-only report connection back is now logged as a warning, and the page
  shows its data (or its own error message) as before.
- **Build:** Gradle 9.8.0 (wrapper with `distributionSha256Sum`), Shadow plugin 9.6.1
  (`com.gradleup.shadow`), Java 11 target set through the `java` block, and no `mavenLocal()`
  in the repositories, so a build no longer picks up artifacts from the local Maven cache. The
  build update itself changes nothing in the jar: same manifest attributes, classes still Java 11.

### Verified

- New unit tests (Spock): status, percentages and bar of a budget of 0 with and without spend;
  year-to-date budget of a yearly budget (exactly 1,000.00 in December) and a quarterly budget
  (exactly its amount per quarter); a failing connection release that leaves the page result in
  place; the budget query still returns `owner_master` for the spend rule. The year-to-date and
  connection-release tests fail against the 1.1.0 code.
- Local build with JDK 17 and Gradle 9.8.0: 91 tests, 0 failures, no deprecation warnings,
  one jar `morpheus-budget-burn-plugin-1.1.1-all.jar`.

### Not yet verified

- Live on the appliance: 1.1.1 has not been uploaded to Morpheus 9.0.2 yet, so the page with a
  budget of 0 and the new year-to-date figures have not been checked there.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/budget-burn-v1.1.0...budget-burn-v1.1.1

## 1.1.0

**First public release: an analytics page that compares every yearly Morpheus budget with
the spend in its scope.** Plugin API 1.4.2, minimum appliance 9.0.2.

### What it does

- **Analytics page *Budget Burn*** (provider code `budget-burn-analytics`, plugin code
  `morpheus-budget-burn-plugin`, category *Cost*): monthly budget, spend to date, daily burn
  rate, month-end forecast, status and year to date per budget.
- **Status** from the forecast: *On track* below 80 %, *Warning* from 80 % to 100 %, *Over
  budget* above 100 % of the monthly budget.
- **Budget scopes** tenant, group, cloud and user, mapped to the matching monthly invoices.
  Quarterly and yearly budget amounts are spread evenly over their months.
- **Whose invoices count:** a budget counts only its owner tenant's invoices; a master tenant
  budget also counts its sub-tenants' invoices in its scope. A sub-tenant's cloud, group or user
  budget on a shared cloud never includes another tenant's spend.
- **Tenant aware:** master tenant users see all budgets, sub-tenant users only their own.
- **Currencies kept apart:** budget currency = owner tenant currency, else master tenant
  currency, else USD. Spend in another currency is listed separately, never converted or added
  up, and the row is marked *Currency mismatch*.
- **English and German**, numbers in the viewer's locale.

### Verified

- Unit tests (Spock) for the budget split per month, currency grouping, foreign amounts,
  rounding, burn rate, status thresholds, scope mapping with the owner rule, locale formats and
  the message keys; plugin code equals the manifest `Morpheus-Code`.
- Live on Morpheus 9.0.2 with the 1.1.0 release candidates: every figure equal to a reference
  implementation run side by side, as master and as sub-tenant user (a sub-tenant sees only its
  own budgets), page in English and German.
- Live on Morpheus 9.0.2 with a release candidate of the final source: three sub-tenant budgets
  (cloud, group and user scope) whose cloud, group or user also carried master tenant invoices
  showed the master tenant's spend before the owner rule (forecast 37.51 and 36.83 instead of
  21.33 EUR) and only the sub-tenant's own spend with it (21.33 EUR, equal to its invoices);
  all other budgets, as master and as sub-tenant, showed the same figures before and after.
  This check ran with the page in German; the English page was not rechecked after the fix.
- Not yet uploaded: this release jar itself. The release candidates were replaced in place by
  later builds with the same plugin id, so 1.1.0 is expected to install over them the same way.

### Limits

- Only budgets with `period = year`; the current month follows the appliance JVM time zone.
- Reads internal database tables; tested on 9.0.2 only and may break on an upgrade.

Built by the release workflow from the source at tag `budget-burn-v1.1.0`.

sha256 `118ae9067bbbb9c166890e769861c6121a6a15828aa5b54aa77c439f6cc9cf43`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/budget-burn-v1.1.0
