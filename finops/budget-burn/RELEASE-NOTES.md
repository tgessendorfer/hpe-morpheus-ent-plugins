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
- Live on Morpheus 9.0.2 with the 1.1.0 release candidates: every figure equal to a reference
  implementation run side by side, as master and as sub-tenant user (a sub-tenant sees only its
  own budgets), page in English and German.
- Not yet uploaded: this release jar itself. The release candidates were replaced in place by
  later builds with the same plugin id, so 1.1.0 is expected to install over them the same way.

### Limits

- Only budgets with `period = year`; the current month follows the appliance JVM time zone.
- Reads internal database tables; tested on 9.0.2 only and may break on an upgrade.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/budget-burn-v1.1.0
