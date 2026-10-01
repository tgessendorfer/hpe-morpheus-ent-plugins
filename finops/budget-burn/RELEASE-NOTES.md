# Budget Burn plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`budget-burn-v<version>`, where the shaded `-all.jar` is attached.

---

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
- **Tenant aware:** master tenant users see all budgets, sub-tenant users only their own.
- **Currencies kept apart:** budget currency = owner tenant currency, else master tenant
  currency, else USD. Spend in another currency is listed separately, never converted or added
  up, and the row is marked *Currency mismatch*.
- **English and German**, numbers in the viewer's locale.

### Verified

- Unit tests (Spock) for the budget split per month, currency grouping, foreign amounts,
  rounding, burn rate, status thresholds, scope mapping, locale formats and the message keys;
  plugin code equals the manifest `Morpheus-Code`.

### Not yet verified

- Live acceptance on Morpheus 9.0.2 of this build (`1.1.0-rc.1`): same figures as the previous
  internal build for the same month and tenant, as master and as sub-tenant user, UI in English
  and German.
- That the `{{i18n}}` helper of the analytics renderer resolves the plugin's own message
  bundles on the appliance.

### Limits

- Only budgets with `period = year`; the current month follows the appliance JVM time zone.
- Reads internal database tables; tested on 9.0.2 only and may break on an upgrade.
