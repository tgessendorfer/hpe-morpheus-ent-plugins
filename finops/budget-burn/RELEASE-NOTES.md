# Budget Burn plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`budget-burn-v<version>`, where the shaded `-all.jar` is attached.

---

## 1.2.1

**Spend, burn rate and forecast no longer count each priced VM twice.**
Built against plugin API 1.4.2, minimum appliance 9.0.2; plugin code, provider code and options are
unchanged.

### What changed

- **Each priced VM counted once.** An instance's VM has two monthly invoices in Morpheus 9.0.2:
  the instance invoice and a server invoice. Both carry the same price and cost, and the server
  invoice has no `instance_id`, so the old filter `ref_type = 'ComputeServer' AND instance_id IS NULL`
  let it through and the VM counted twice. A server invoice now counts only when no `container`
  row links its server to an instance:
  `NOT EXISTS (SELECT 1 FROM container ct WHERE ct.server_id = <invoice>.ref_id AND ct.instance_id IS NOT NULL)`.
  Server invoices of discovered or unmanaged VMs (no instance) still count.
- **Internal tables read:** `container` in addition to the tables of 1.2.0.

### Behaviour changes from 1.2.0

- **Spend to date, burn rate and forecast drop to about half** for budgets whose scope holds
  instances that 1.2.0 counted twice; budgets and statuses are re-evaluated on these amounts.

### Not covered

- **Deleted instances (not verified).** If Morpheus removes the `container` row when an instance
  and its server are deleted, a server invoice of that VM left over in the month counts again next
  to the instance invoice. In the lab the server invoices of two deleted Contoso instances stayed
  out, so the row seems to survive the deletion; the amounts involved are what accrued until then.

### Verified

- Unit tests (Spock) for the new filter.
- Lab appliance (Morpheus 9.0.2, master tenant), 1.2.1-rc.1 built from the same source: tenant budgets Contoso forecast 29.48 EUR, Fabrikam 13.62 EUR (1.2.0: 56.71 and 26.72 EUR), the same amounts as MSP Tenant Overview and Tenant Chargeback. The new filter runs against the `container` table without error.
- The release jar loads on the lab appliance (9.0.2) in place of 1.2.1-rc.1.

Built by the release workflow from the source at tag `budget-burn-v1.2.1`.

sha256 `b065b5fa3c932bbf5367b74065183e1279a7af16ba2dc1212ac8c956cda66112`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/budget-burn-v1.2.0...budget-burn-v1.2.1

## 1.2.0

**The page now speaks the viewer's Morpheus language: texts and number formats follow the
user's own language setting in Morpheus, not the browser.** Plugin API 1.4.2, minimum appliance
9.0.2; plugin code, provider code and the page layout are unchanged.

### Why

On Morpheus 9.0.2 a user with Morpheus set to English and a browser sending German saw the
Morpheus menus in English but the Budget Burn page in German, with German number formats.
Morpheus takes the UI language from the user's own setting (*Locale* in the user settings, for
example `en-US`, stored in the user record), while the plugin used the locale of the web request,
which is the browser's `Accept-Language`. Plugin API 1.4.2 offers no language on its `User`
model, so the plugin now reads the setting itself.

### Changes

- **Language from the user's Morpheus setting.** The page reads the viewer's setting with one
  parameterised query (`SELECT locale FROM user WHERE id = ?`) over the read-only report
  connection it already uses, and accepts `en-US`, `de`, `de_DE` and the like. **Behaviour
  change:** a user set to English sees the page in English even when the browser asks for
  German, and a user set to German sees it in German with an English browser. Headings, column
  names, scope and status texts, the error message and every amount and percentage
  (`1,234.56` / `1.234,56`) follow that setting.
- **Fallback.** When the setting is empty, names no valid language or cannot be read, the page
  uses the browser language as before, and English when there is no web request. A failed read
  is logged at debug level only and never breaks the page. The setting is read in a query of its
  own, so a change to the user record can at worst bring back the browser language, not an
  error page.
- **Texts in English and German only, as before.** Any other language setting gets the English
  texts, with numbers formatted in that language.
- **The page texts come with the page data.** The template no longer uses Morpheus' `i18n`
  helper, which always uses the browser language; the provider resolves the texts from the
  plugin's own bundles (`i18n/messages.properties`, `i18n/messages_de.properties`) for the
  viewer's language and passes them to the template. The bundles and their keys are unchanged.
  The page's root element carries a `lang` attribute (`en` or `de`).
- The month key of the invoice query is built from year and month directly instead of through
  `Date.format`; the value (`yyyyMM`) is the same.

### Verified

- New unit tests (Spock): parsing of the setting (`en-US`, `en_US`, `de`, `de_DE`; blank, empty,
  `null` and invalid values give no locale); an `en-US` setting with a German browser gives
  English, a `de` setting gives German, a missing, blank or invalid setting and a failing query
  give the browser language, no web request gives English; the language query is the
  parameterised `SELECT locale FROM user WHERE id = ?`; the page data of a budget is in English
  (`1,234.50`) for `en-US` and in German (`1.234,50`) for `de-DE` with a German browser; the error
  message follows the setting; the template rendered with the plugin API's Handlebars renderer is
  in the viewer's language, including the currency mismatch branches and the empty list; the
  template uses no `i18n` helper.
- Local build with JDK 17 and Gradle 9.8.0: 121 tests, 0 failures, no deprecation warnings,
  one jar `morpheus-budget-burn-plugin-1.2.0-all.jar`.
- On Morpheus 9.0.2 (build 9.0.2-2) with a release candidate built from this source, as a master
  tenant user whose Morpheus setting is `en-US` while the browser sends German: the page renders in
  English with the same figures as 1.1.1 (number format with a decimal point).
- The release jar replaced the release candidate on the same Morpheus 9.0.2 appliance in place
  (same plugin id), status `loaded`, valid and enabled. Its source equals the candidate's apart
  from the version; the check with the `de` setting below ran with this jar.
- With the Morpheus setting switched to `de` for the same user while the request asked for English
  (`Accept-Language: en-US`): the page renders in German with German number formats (decimal
  comma).
- As a sub-tenant admin (impersonated from the master tenant, no language setting, German
  browser): the page lists only that tenant's own budget, in German.

### Not yet verified

- Nothing beyond the limits in the README.

Built by the release workflow from the source at tag `budget-burn-v1.2.0`.

sha256 `e6ec2abef31706af1e5fcbb43797c9a7e807352521dfac36d179b973d239aed8`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/budget-burn-v1.1.1...budget-burn-v1.2.0

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
- On Morpheus 9.0.2 (build 9.0.2-2): the release jar was uploaded over the previous build and
  replaced it in place (same plugin id), status `loaded`, valid and enabled; no plugin error in the
  log.

- Live on Morpheus 9.0.2 as master tenant user, in German and English: every budget renders; the
  forecast and the year to date equal the tenant's invoices (for example 16.33 forecast and 22.49
  year to date against a year-to-date budget of exactly 5,000.00), and a budget with spend in
  another currency shows it separately without percentages.

### Not yet verified

- A budget of 0 on the appliance (none exists there; covered by unit tests) and the page as a
  sub-tenant user with this jar.

Built by the release workflow from the source at tag `budget-burn-v1.1.1`.

sha256 `74ae571f3bd8bce2fe7648b8dd1a7dd48d1eb3f00bcf77e86a0d5b590db90bbc`

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
