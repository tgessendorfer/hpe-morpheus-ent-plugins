# Tenant Chargeback

A report for HPE Morpheus Enterprise that turns the invoices Morpheus already calculates into a
monthly chargeback per tenant and group: cost, list price, margin, an optional additional markup
and the invoice amount, totalled per currency and exportable as CSV.

> **Independent community project.** Not an official HPE product, and neither endorsed by nor
> affiliated with Hewlett Packard Enterprise. See [NOTICE](NOTICE).

| | |
|---|---|
| Plugin code | `morpheus-tenant-chargeback-plugin` |
| Provider | Report provider `tenant-chargeback-report`, category *Cost*, name *Tenant Chargeback* |
| Jar | `morpheus-tenant-chargeback-plugin-<version>-all.jar` |
| Release tags | `tenant-chargeback-v<version>` |

## What it shows

For one month (`account_invoice` with `period_interval = 'month'`):

- **Headline:** number of tenants and the invoice amount per currency.
- **Total per currency:** resources, cost, list price, margin, margin % and invoice amount.
- **Per tenant:** the same figures per tenant and currency.
- **Per tenant and group:** one row per tenant, group and currency. Groups are told apart by
  their id, so two groups with the same name in one tenant get a row each. Servers that belong to
  no group are shown as one row, *Servers without a group*.

Counted are the invoices of **instances** and of **servers that belong to no instance**. The
summary invoices Morpheus keeps per tenant, group, cloud and user are left out; they would count
the same resources twice.

| Figure | Meaning |
|---|---|
| Cost | `total_cost` – what the resources cost the provider |
| List price | `total_price` – the price including the price-set markup |
| Margin | list price − cost |
| Margin % | margin / list price |
| Invoice amount | list price × (1 + additional markup %) |

Every invoice line is rounded to cents (half up) before anything is added, so the rows shown
always add up to the totals. For the current month Morpheus projects the amounts to the end of
the month; the heading says so.

## Who sees it

The report is **master tenant only** (`masterOnly = true`): it reads the invoices of all
tenants. Which roles may run it follows the Morpheus report permissions. Without
*Include Master Tenant Resources* only the sub-tenants are listed.

## Options

| Option | Code | Field | Default | Meaning |
|---|---|---|---|---|
| Month (YYYY-MM) | `tenant-chargeback-report-month` | `chargebackMonth` | empty | `YYYY-MM` (or `YYYYMM`), month 01–12. Empty = current month |
| Additional Markup % | `tenant-chargeback-report-markup-percent` | `markupPercent` | `0` | Added to the list price for the invoice amount, e.g. a managed service fee. 0 to 1000, up to four decimals; a decimal comma is accepted, a sign or an exponent is not |
| Include Master Tenant Resources | `tenant-chargeback-report-provider` | `includeProvider` | off | Also list the master tenant's own resources |

## Currencies

The currency of an invoice line is resolved by one rule, shared by all FinOps plugins in this
repository:

1. the currency of the invoice line,
2. else the currency of the master tenant,
3. else `USD`.

Lines that end up in the same tenant, group (by id) and currency are merged. Amounts in different
currencies are **never converted or added up**: every total is per currency, and every amount
carries its ISO currency code.

## CSV export

The report result export contains the plain values, independent of the viewer's language:
amounts with two decimals and a dot, no grouping. Main rows: `tenant`, `group` (empty for servers
without a group), `noGroup`, `resources`, `currency`, `cost`, `price`, `margin`, `markupPercent`,
`invoice`. `markupPercent` carries the markup as entered, up to four decimals; the page shows it
with the same precision. Header rows (per tenant) add `marginPct`; footer rows carry the per-currency totals
(`kind = total`) and the report metadata (`kind = meta`: `month`, `current`, `markupPercent`,
`tenants`, `currencies`).

## Language and number format

English is the default; German is included (`i18n/messages_de.properties`). Option labels and
help texts, headings, column names and messages follow the viewer's language, and numbers on the
page are formatted in the viewer's locale (e.g. `1,234.56` or `1.234,56`). The report name and
description in the report list stay English, since plugin API 1.4.2 stores them when the plugin
registers.

**Which language counts:** the plugin uses the locale of the web request, i.e. the browser's
language (`Accept-Language`), not the language chosen in the Morpheus UI. Measured on 9.0.2: with
Morpheus set to German and the browser to English, the Morpheus menus are German but the plugin
content and numbers are English, and vice versa. Set the browser language to the language the
viewers use in Morpheus.

## Known limits

- **Current month in the appliance JVM time zone.** An empty month means the month the appliance
  JVM is in; around midnight at a month end that can differ from the viewer's month.
- Amounts are as current as the last Morpheus costing run.
- Two groups with the same name in one tenant are two rows with the same name; the CSV export
  carries no group id to tell them apart, only their order.
- The tenant currency is not used for the currency rule; an invoice line without a currency
  falls back to the master tenant's currency.
- **Reads internal database tables** (see the matrix below) through the read-only report
  connection. They are not a public API: the plugin is tested on Morpheus 9.0.2 only and may
  break on an upgrade. A failed query marks the report result as failed and writes a one-line
  error to the appliance log (the stack trace at debug level).

## Compatibility

| Plugin version | Plugin API | Min. appliance | Tested on | Internal tables read |
|---|---|---|---|---|
| 1.1.1 | 1.4.2 | 9.0.2 | 9.0.2 (1.1.0; 1.1.1 loads, not run) | `account_invoice`, `account` |
| 1.1.0 | 1.4.2 | 9.0.2 | 9.0.2 | `account_invoice`, `account` |

Queries internal tables, tested on 9.0.2 only, may break on upgrade.

## Install

Download `morpheus-tenant-chargeback-plugin-<version>-all.jar` from the
[release tenant-chargeback-v1.1.1](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/tenant-chargeback-v1.1.1) (tag
`tenant-chargeback-v<version>`), then upload it under *Administration → Integrations → Plugins →
Add*. Updating to a newer version with the same plugin code replaces the plugin in place and
keeps existing report results. The report then appears under *Operations → Reports* in the
category *Cost*.

## Build

JDK 17 (the jar targets Java 11); the Gradle wrapper (9.8.0) checks the distribution's SHA-256:

```
./gradlew clean test shadowJar
```

The jar is `build/libs/morpheus-tenant-chargeback-plugin-<version>-all.jar`. The Morpheus plugin
API, Groovy, SLF4J and RxJava are provided by the appliance and are not bundled.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
