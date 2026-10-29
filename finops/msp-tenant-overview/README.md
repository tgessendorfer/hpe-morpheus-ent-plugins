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
| Margin, Margin % | revenue - cost, and margin as a share of revenue, in a smaller line under the margin (left out when revenue is 0) |

- **Invoices counted:** monthly invoices (`period_interval = 'month'`) of instances, plus those of
  servers that belong to no instance, so a VM is never counted twice. Since 1.2.1 a server belongs to an instance when a `container` row links them (`container.server_id` = the invoice's `ref_id`, `container.instance_id` set); Morpheus leaves `instance_id` empty on the server invoice of an instance's own VM.
- **Zero currencies are hidden (since 1.2.1).** A currency whose revenue and cost are 0.00 in both
  months gets no tenant row and no total; a tenant with nothing but zero amounts keeps one zero row
  in the master currency. Since 1.2.3 that zero row adds no zero total (and no zero cards) next to a
  currency with an amount: whenever no other tenant has an amount in that currency, for example
  USD (or CHF) as the master currency over tenants invoiced in EUR, or USD as the fallback when the
  master account has no currency.
- **Months:** the current month, which Morpheus projects to month end (forecast), and the previous
  month, each under its own column group heading.
- **Layout (since 1.2.2):** the page brings its own scoped CSS, shows the five summary cards in
  one row and fits the 857 px that Morpheus leaves an analytics page in its 1150 px container:
  numbers right-aligned and never broken, secondary values in a smaller line under the amount, a
  horizontal scroll bar instead of wrapping on narrow screens. It sets no text color, so it
  follows the page in light and dark mode.
- **Currencies are never added up.** A tenant with invoices in two currencies gets one line per
  currency; the totals in the table footer and the tiles above it are per currency, too.
- **Currency of an invoice:** its own currency; if it has none, the master account's currency; if
  that is empty too, `USD`. A tenant without any amount (no invoices, or only zero amounts) is
  listed with zeros in that fallback currency.
- **Rounding:** each tenant's amount per currency and month is summed, then rounded to cents
  (half up). Margins and totals are computed from these rounded amounts. Tenant Chargeback rounds
  per group instead, so its amounts can differ by cents (see
  [Comparing with Tenant Chargeback](#comparing-with-tenant-chargeback)).
- **Numbers and language** follow the viewer's own language setting in Morpheus (user settings,
  stored as `user.locale`, e.g. `en-US`), the same setting the Morpheus UI uses: `1,234.56` in
  English, `1.234,56` in German. If that setting is empty or unreadable, the page falls back to the
  browser language (`Accept-Language`), and without a request to English. Texts exist in English and
  German; any other language gets English texts with that language's number format. Amounts always
  carry their ISO currency code.

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
   [release msp-tenant-overview-v1.2.2](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/msp-tenant-overview-v1.2.2) (tag
   `msp-tenant-overview-v<version>`) and check it against `SHA256SUMS`.
2. *Administration > Integrations > Plugins > Add*, upload the jar.
3. Open *Operations > Analytics* in the master tenant and pick **MSP Tenant Overview**.

## Comparing with Tenant Chargeback

[Tenant Chargeback](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/finops/tenant-chargeback)
reads the same invoices (monthly invoices of instances and of servers that belong to no
instance), the same columns (`total_price`, `total_cost`) and resolves currencies by the same
rule. Its report can still show other amounts than this page, for these reasons:

- **Cents.** This page adds up all of a tenant's invoices per currency and month and rounds once.
  Tenant Chargeback rounds each row of its group table (tenant, group and currency) to cents and
  adds up the rounded rows, so its rows add up to its totals. A tenant's amount can therefore
  differ by up to half a cent per group row, and a currency total by the sum of those: three
  groups of 0.004 EUR each are 0.01 EUR here (0.012 rounds to 0.01) and 0.00 EUR there (each row
  rounds to 0.00). Margins and margin % come from the rounded amounts and move with them. Both
  rules stay as they are; aligning them would change the amounts of one of the two pages by
  cents.
- **Which tenants count.** This page lists the active sub-tenants only
  (`account.master_account = 0` and `active = 1`), with or without invoices. Tenant Chargeback
  lists every tenant that has invoices in the month, inactive tenants included, and the master
  tenant when its option *Include Master Tenant Resources* is ticked. Their amounts are in its
  totals but not in this page's, and the two *Tenants* cards count different tenants.
- **Revenue is the list price.** Compare *Revenue* with Tenant Chargeback's *List Price*. Its
  *Invoice Amount* adds an optional additional markup and equals the list price only at 0 %.
- **Live page, stored result.** This page reads the invoices at every page view; a Tenant
  Chargeback report result keeps the amounts of the moment it ran. Every Morpheus costing run
  updates the invoices, so compare a freshly loaded page with a new report run.
- **Current month.** Both show the current month as Morpheus' projection to the end of the month
  (`total_price`), not the month to date, and the projection moves with every costing run. This
  page shows the month of the page view and the month before it; a Tenant Chargeback report with
  an empty month covers the month it ran in (both in the appliance JVM time zone). Other months
  are in Tenant Chargeback only.
- **Tenants without any amount, zero rows.** This page lists a tenant without any amount (no
  invoices, or only zero amounts) with one zero row in the master account's currency, else USD,
  and leaves out a tenant's currency whose amounts in both months are zero. Tenant Chargeback
  lists a tenant only with its invoices, in their own currency, and hides a currency only when its
  totals are zero. Only zero rows differ.
- **Counts measure different things.** *Instances* and *Servers* here count the tenant's instances
  and servers, priced or not, and *Groups* every group of the tenant; Tenant Chargeback's
  *Resources* counts invoices.

## Known limits

- **Current month in the JVM time zone.** "Current month" is taken from the appliance JVM's
  clock and time zone, not the viewer's. Around midnight on the first of a month the page can
  still show the old month.
- **Memory** is `instance.max_memory`, in bytes, shown as GB (1024³ bytes) with one decimal.
- **Only what Morpheus invoices.** Tenants without price sets show zero revenue; resources outside
  Morpheus' costing do not appear.
- **Provider name** (*MSP Tenant Overview*) in the Analytics menu is not translated; the page
  content is.
- **Language setting read from an internal table.** Plugin API 1.4.2 does not expose the user's
  language, so the page reads `user.locale` itself (one parameterised query on the read-only
  connection). If that fails, the page still loads, in the browser language. Since 1.2.0 the page
  texts no longer use the template's `i18n` helper, which follows the browser.
- **Internal tables** (`user`, `account`, `compute_site`, `instance`, `compute_server`,
  `account_invoice`) may change with a Morpheus upgrade. A failing query shows *Tenant data could
  not be loaded* and logs the cause instead of a stack trace on the page.

## Version compatibility

| Plugin version | Plugin API | Min appliance | Tested on | Internal tables read |
|---|---|---|---|---|
| 1.2.2 | 1.4.2 | 9.0.2 (`Morpheus-Min-Appliance-Version`) | HPE Morpheus Enterprise 9.0.2 (1.2.2-rc.2, master tenant) | `user` (incl. `locale`), `account`, `compute_site`, `instance`, `compute_server`, `container`, `account_invoice` |
| 1.2.1 | 1.4.2 | 9.0.2 (`Morpheus-Min-Appliance-Version`) | HPE Morpheus Enterprise 9.0.2 (1.2.1-rc.1, master tenant) | `user` (incl. `locale`), `account`, `compute_site`, `instance`, `compute_server`, `container`, `account_invoice` |
| 1.2.0 | 1.4.2 | 9.0.2 (`Morpheus-Min-Appliance-Version`) | HPE Morpheus Enterprise 9.0.2 (1.2.0, master tenant) | `user` (incl. `locale`), `account`, `compute_site`, `instance`, `compute_server`, `account_invoice` |
| 1.1.1 | 1.4.2 | 9.0.2 (`Morpheus-Min-Appliance-Version`) | HPE Morpheus Enterprise 9.0.2 (1.1.0; 1.1.1 as master) | `user`, `account`, `compute_site`, `instance`, `compute_server`, `account_invoice` |
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
currency fallback, grouping per currency, rounding, percentages, locale-aware number formats, the
choice of language (user setting over browser, with its fallbacks) and the rendered template (no
`- %` for tenants without revenue).

## Translations

English is the default (`src/main/resources/i18n/messages.properties`), German is in
`messages_de.properties`. Keys start with the provider code.

## Trademarks

"HPE", "Hewlett Packard Enterprise" and "Morpheus" are trademarks of Hewlett Packard Enterprise
Development LP. They are used here solely to identify the platform this plugin extends.

## License

[Apache License 2.0](LICENSE)
