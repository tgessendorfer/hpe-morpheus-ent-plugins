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
  their id, so two groups with the same name in one tenant get a row each. An invoice line that
  carries a group id but no group name (server invoices on 9.0.2 do) gets the name from the group
  table (`compute_site`) and counts towards that group. Lines without a group id, or whose group
  no longer exists, are shown as one row per tenant and currency, *Servers without a group*.

On the report page (since 1.2.2) a currency whose total cost, list price and invoice amount are
all zero is left out of the cards and the three tables, as long as another currency has an amount;
the CSV export keeps every row. The *Tenants* card then counts only the tenants the tables list
(since 1.3.0). Numbers are right-aligned and never broken inside.

**Deactivated tenants (since 1.3.0).** A tenant whose account is deactivated in Morpheus
(`account.active` = 0) is listed with its invoices like any other tenant; its rows in *Per tenant*
and *Per tenant and group* carry a *Deactivated* tag next to the tenant name. Its amounts are in
every total and the *Tenants* card counts it, as before. Report results stored by an earlier
version carry no flag and show no tag.

Counted are the invoices of **instances** and of **servers that belong to no instance** (since
1.2.1 a server belongs to an instance when a `container` row links them (`container.server_id` = the invoice's `ref_id`, `container.instance_id` set); Morpheus leaves `instance_id` empty on the server invoice of an instance's own VM). The
summary invoices Morpheus keeps per tenant, group, cloud and user are left out; they would count
the same resources twice.

| Figure | Meaning |
|---|---|
| Cost | `total_cost` – what the resources cost the provider |
| List price | `total_price` – the price including the price-set markup |
| Margin | list price − cost |
| Margin % | margin / list price |
| Invoice amount | list price × (1 + additional markup %) |

Every row of the group table (tenant, group and currency) is rounded to cents (half up) before
anything is added, so the rows shown always add up to the totals. MSP Tenant Overview rounds once
per tenant instead, so its amounts can differ by cents (see
[Comparing with MSP Tenant Overview](#comparing-with-msp-tenant-overview)). For the current month
Morpheus projects the amounts to the end of the month; the heading says so.

## Who sees it

The report is **master tenant only** (`masterOnly = true`): it reads the invoices of all
tenants. Which roles may run it follows the Morpheus report permissions. Without
*Include Master Tenant Resources* only the sub-tenants are listed.

Since 1.3.0 the report also checks this itself before it reads any invoice: the user who ran it
must belong to the master tenant (a report result without a creator: its own account must be the
master tenant). Otherwise, and for a month that is not `YYYY-MM` (possible through the API), the
report ends as *failed* with a warning in the appliance log.

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
`invoice`, `deactivated` (since 1.3.0; `true` for a deactivated tenant). `markupPercent` carries
the markup as entered, up to four decimals; the page shows it with the same precision. Header rows
(per tenant) add `marginPct` and carry `deactivated` as well; footer rows carry the per-currency totals
(`kind = total`) and the report metadata (`kind = meta`: `month`, `current`, `markupPercent`,
`tenants`, `currencies`).

## Language and number format

English is the default; German is included (`i18n/messages_de.properties`). Headings, column
names and messages on the report page are shown in the user's language, and numbers are formatted
in the user's locale (e.g. `1,234.56` or `1.234,56`). The report name and description in the
report list stay English, since plugin API 1.4.2 stores them when the plugin registers.

**Which language counts** (since 1.2.0):

1. the user's own Morpheus language setting (*User Settings*, stored as `user.locale`, e.g.
   `en-US`), read with one parameterised query over the read-only report connection;
2. if that setting is empty or not a known language, the browser's language (`Accept-Language`
   of the web request);
3. without a web request, English.

Texts come in English or German; any other language gets English texts, while numbers still use
that locale's format. An `en-US` setting therefore gives an English page even when the browser
sends German. Up to 1.1.2 the plugin used the browser's language only, so a Morpheus UI set to
English could show the report in German.

The report page gets no viewing user from the plugin API, so the setting of the **user who ran
the report** (the report result's creator) is used. Another user who opens the same result sees
it in the creator's language. If the setting cannot be read, or the report result has no
creator, the plugin writes one debug line to the appliance log and falls back to the browser's
language; the page is never broken by it.

Validation messages of the report form (a wrong month or markup) follow the browser's language,
since that call carries no user. The option labels and help texts of the form are looked up by
Morpheus itself through their i18n codes, not by the plugin.

## Comparing with MSP Tenant Overview

[MSP Tenant Overview](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/finops/msp-tenant-overview)
reads the same invoices (monthly invoices of instances and of servers that belong to no
instance), the same columns (`total_cost`, `total_price`) and resolves currencies by the same
rule. Its amounts can still differ from this report's, for these reasons:

- **Cents.** This report rounds each row of the group table (tenant, group and currency) to cents
  and adds up the rounded rows, so the rows add up to the tenant and currency totals. MSP Tenant
  Overview adds up all of a tenant's invoices per currency and month and rounds once. A tenant's
  amount can therefore differ by up to half a cent per group row, and a currency total by the sum
  of those: three groups of 0.004 EUR each are 0.00 EUR here (each row rounds to 0.00) and
  0.01 EUR there (0.012 rounds to 0.01). Margins and margin % come from the rounded amounts and
  move with them. Both rules stay as they are; aligning them would change the amounts of one of
  the two pages by cents.
- **Which tenants count.** MSP Tenant Overview lists the active sub-tenants only
  (`account.master_account = 0` and `active = 1`), with or without invoices. This report lists
  every tenant that has invoices in the month, deactivated tenants included (tagged *Deactivated*
  since 1.3.0), and the master tenant when *Include Master Tenant Resources* is ticked. The amounts
  of deactivated tenants are in this report's totals but not in MSP Tenant Overview's; since 1.3.0
  that page names them in one line per currency under its totals (their number, and their amounts
  in the columns of its totals: revenue, cost and margin of the current month, revenue and margin
  of the previous month). MSP Tenant Overview never shows the master
  tenant's own amounts. The two *Tenants* cards therefore count different tenants.
- **List price, not invoice amount.** MSP Tenant Overview's *Revenue* is the list price. Compare
  it with *List Price* here; *Invoice Amount* adds the additional markup and equals the list price
  only at 0 %.
- **Stored result, live page.** A report result keeps the amounts of the moment it ran; MSP Tenant
  Overview reads the invoices at every page view. Every Morpheus costing run updates the invoices,
  so compare a new report run with a freshly loaded page.
- **Current month.** Both show the current month as Morpheus' projection to the end of the month
  (`total_price`), not the month to date, and the projection moves with every costing run. An
  empty month here is the month the report ran in; MSP Tenant Overview shows the month of the page
  view and the month before it (both in the appliance JVM time zone), and no other month.
- **Tenants without any amount, zero rows.** MSP Tenant Overview lists a tenant without any amount
  (no invoices, or only zero amounts) with one zero row in the master tenant's currency, else USD,
  and leaves out a tenant's currency whose amounts in both months are zero. This report lists a
  tenant only with its invoices, in their own currency, and hides a currency only when its totals
  are zero. Only zero rows differ.
- **Counts measure different things.** *Resources* here counts invoices; MSP Tenant Overview's
  *Instances* and *Servers* count the tenant's instances and servers, priced or not, and its
  *Groups* every group of the tenant.

## Known limits

- **Current month in the appliance JVM time zone.** An empty month means the month the appliance
  JVM is in; around midnight at a month end that can differ from the viewer's month.
- Amounts are as current as the last Morpheus costing run.
- Two groups with the same name in one tenant are two rows with the same name; the CSV export
  carries no group id to tell them apart, only their order.
- A group name comes from the invoice line when it carries one, else from the group table by
  group id. If the lines of one group id carry different names (a group renamed during the month),
  the row shows the alphabetically last of them. Invoices of a group that has since been deleted
  and that carry no name count as *Servers without a group*.
- **Language of the report page:** the language setting of the user who ran the report, not of
  the user who views it (see *Language and number format*). Validation messages follow the
  browser's language.
- The tenant currency is not used for the currency rule; an invoice line without a currency
  falls back to the master tenant's currency.
- **Docker hosts and Kubernetes nodes.** A server invoice is not counted when a `container` row
  links that server to an instance (`container.server_id` = the invoice's `ref_id`,
  `container.instance_id` set); that keeps an instance's own VM from being counted twice. A Docker
  host or Kubernetes node on which Morpheus-managed container instances run has such rows as well,
  so its own server invoice is not counted either; the instance invoices of its container
  instances are. The rule assumes that those instance invoices carry the host's cost. The report
  does not check this: a host cost that is not priced into its container instances is missing
  from the figures. The `container` table is read as it is when the report runs, not as it was in
  the invoiced month.
- **Reads internal database tables** (see the matrix below) through the read-only report
  connection. They are not a public API: the plugin is tested on Morpheus 9.0.2 and 9.1.0 only
  and may break on an upgrade. A failed query marks the report result as failed and writes a
  one-line error to the appliance log (the stack trace at debug level).

## Compatibility

| Plugin version | Plugin API | Min. appliance | Tested on | Internal tables read |
|---|---|---|---|---|
| 1.3.0 | 1.4.2 | 9.0.2 | 9.1.0 (1.3.0-rc.1, master tenant) | `account_invoice`, `account` (incl. `active`), `compute_site`, `user`, `container` |
| 1.2.2 | 1.4.2 | 9.0.2 | 9.0.2 (1.2.2-rc.2, master tenant) | `account_invoice`, `account`, `compute_site`, `user`, `container` |
| 1.2.1 | 1.4.2 | 9.0.2 | 9.0.2 (1.2.1-rc.1, master tenant) | `account_invoice`, `account`, `compute_site`, `user`, `container` |
| 1.2.0 | 1.4.2 | 9.0.2 | 9.0.2 (1.2.0, master tenant) | `account_invoice`, `account`, `compute_site`, `user` |
| 1.1.2 | 1.4.2 | 9.0.2 | 9.0.2 (1.1.0, 1.1.2) | `account_invoice`, `account` |
| 1.1.1 | 1.4.2 | 9.0.2 | 9.0.2 (1.1.0; 1.1.1 run: split no-group rows, fixed in 1.1.2) | `account_invoice`, `account` |
| 1.1.0 | 1.4.2 | 9.0.2 | 9.0.2 | `account_invoice`, `account` |

Queries internal tables, tested on 9.0.2 and 9.1.0 only, may break on upgrade.

## Install

Download `morpheus-tenant-chargeback-plugin-<version>-all.jar` from the
[release tenant-chargeback-v1.3.0](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/tenant-chargeback-v1.3.0) (tag
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
