# Instance Showback plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `instance-showback-v<version>`, where the
shaded `-all.jar` is attached.

---

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
