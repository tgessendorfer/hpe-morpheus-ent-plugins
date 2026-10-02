# Morpheus MSP Tenant Overview Plugin

An `AnalyticsProvider` plugin for HPE Morpheus Enterprise 9.0 that adds an **MSP Tenant Overview**
page to Analytics (category *Cost*). It gives a service provider one table with every active
sub-tenant: resources, revenue, cost and margin for the current month (forecast) and the previous
month, per currency.

> **Independent community project.** Not an official HPE product, and neither endorsed by nor
> affiliated with Hewlett Packard Enterprise. See [Trademarks](#trademarks).

> **Reads internal tables.** The page queries Morpheus' internal database through the read-only
> report connection of the plugin API. It is tested on Morpheus 9.0.2 only and may break on an
> appliance upgrade. See [Version compatibility](#version-compatibility).

## What it shows

One row per active sub-tenant (`account.master_account = 0`, `active = 1`), ordered by name:

| Column | Source |
|---|---|
| Users | enabled users of the tenant |
| Groups | groups (`compute_site`) of the tenant |
| Instances, vCPU, Memory GB | count, `SUM(max_cores)`, `SUM(max_memory)` of the tenant's instances |
| Servers | `compute_server` rows owned by the tenant |
| Revenue | `account_invoice.total_price`: the price from the tenant's price set, markup included |
| Cost | `account_invoice.total_cost`: the purchase cost |
| Margin, Margin % | revenue - cost, and margin as a share of revenue (`-` in the column when revenue is 0; the percentage next to the tiles and the previous month's margin is then left out) |

- **Invoices counted:** monthly invoices (`period_interval = 'month'`) of instances, plus those of
  servers that belong to no instance, so a VM is never counted twice.
- **Months:** the current month, which Morpheus projects to month end (forecast), and the previous
  month.
- **Currencies are never added up.** A tenant with invoices in two currencies gets one line per
  currency; the totals in the table footer and the tiles above it are per currency, too.
- **Currency of an invoice:** its own currency; if it has none, the master account's currency; if
  that is empty too, `USD`. A tenant without invoices is listed with zeros in that fallback currency.
- **Rounding:** each tenant's amount per currency and month is summed, then rounded to cents
  (half up). Margins and totals are computed from these rounded amounts.
- **Numbers and language** follow the viewer's browser language (`Accept-Language`), not the
  language chosen in the Morpheus UI: `1,234.56` in English, `1.234,56` in German. Without a
  request locale the page uses English. Amounts always carry their ISO currency code.

## Who sees it

- **Provider (master) tenant only.** The provider sets `masterTenantOnly`, so Morpheus hides the
  page in sub-tenants. `loadData` also checks that the viewer's account is the master account and
  otherwise returns *Only the provider (master) tenant can open this page.*
- Inside the master tenant, any user who can open Analytics sees all sub-tenants. Restrict it with
  the role's Analytics permissions if needed.

## Options

None. The page has no settings; prices, markups and currencies come from the tenants' price sets
and invoices in Morpheus.

## Install

1. Download `morpheus-msp-tenant-overview-plugin-<version>-all.jar` from the
   [release msp-tenant-overview-v1.1.1](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/msp-tenant-overview-v1.1.1) (tag
   `msp-tenant-overview-v<version>`) and check it against `SHA256SUMS`.
2. *Administration > Integrations > Plugins > Add*, upload the jar.
3. Open *Operations > Analytics* in the master tenant and pick **MSP Tenant Overview**.

## Known limits

- **Current month in the JVM time zone.** "Current month" is taken from the appliance JVM's
  clock and time zone, not the viewer's. Around midnight on the first of a month the page can
  still show the old month.
- **Memory** is `instance.max_memory`, in bytes, shown as GB (1024³ bytes) with one decimal.
- **Only what Morpheus invoices.** Tenants without price sets show zero revenue; resources outside
  Morpheus' costing do not appear.
- **Provider name** (*MSP Tenant Overview*) in the Analytics menu is not translated; the page
  content is.
- **Internal tables** (`user`, `account`, `compute_site`, `instance`, `compute_server`,
  `account_invoice`) may change with a Morpheus upgrade. A failing query shows *Tenant data could
  not be loaded* and logs the cause instead of a stack trace on the page.

## Version compatibility

| Plugin version | Plugin API | Min appliance | Tested on | Internal tables read |
|---|---|---|---|---|
| 1.1.1 | 1.4.2 | 9.0.2 (`Morpheus-Min-Appliance-Version`) | HPE Morpheus Enterprise 9.0.2 (1.1.0; 1.1.1 loads, not run) | `user`, `account`, `compute_site`, `instance`, `compute_server`, `account_invoice` |
| 1.1.0 | 1.4.2 | 9.0.2 (`Morpheus-Min-Appliance-Version`) | HPE Morpheus Enterprise 9.0.2 | `user`, `account`, `compute_site`, `instance`, `compute_server`, `account_invoice` |

Queries internal tables, tested on 9.0.2 only, may break on upgrade.

## Identifiers

| | |
|---|---|
| Plugin code (`Morpheus-Code`) | `morpheus-msp-tenant-overview-plugin` |
| Provider code | `msp-tenant-overview-analytics` |
| Release tag prefix | `msp-tenant-overview-v` |

The codes are what Morpheus stores. They do not change between versions.

## Build from source

Requires JDK 17 (Groovy 3.0.9) and the bundled Gradle wrapper (Gradle 9.8.0, checksum-pinned). Run it in `finops/msp-tenant-overview`.

```bash
./gradlew clean test shadowJar
# -> build/libs/morpheus-msp-tenant-overview-plugin-<version>-all.jar
```

The Spock tests cover the plugin identity (code, repo link, provider, message bundles), the
currency fallback, grouping per currency, rounding, percentages, locale-aware number formats and
the rendered template (no `- %` for tenants without revenue).

## Translations

English is the default (`src/main/resources/i18n/messages.properties`), German is in
`messages_de.properties`. Keys start with the provider code.

## Trademarks

"HPE", "Hewlett Packard Enterprise" and "Morpheus" are trademarks of Hewlett Packard Enterprise
Development LP. They are used here solely to identify the platform this plugin extends.

## License

[Apache License 2.0](LICENSE)
