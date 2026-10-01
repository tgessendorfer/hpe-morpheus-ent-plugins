# MSP Tenant Overview plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`msp-tenant-overview-v<version>`, where the shaded `-all.jar` is attached.

---

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

### Not yet verified

- Live on Morpheus 9.0.2: page load as master and as sub-tenant user, German and English UI,
  numbers against the invoices, upgrade from a `1.1.0-rc.N` test build.

### Compatibility

Plugin API 1.4.2, minimum appliance 9.0.2, tested on 9.0.2 only. Reads the internal tables `user`,
`account`, `compute_site`, `instance`, `compute_server` and `account_invoice`; may break on an
appliance upgrade.
