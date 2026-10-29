# Budget Burn

An analytics page for HPE Morpheus Enterprise that compares every yearly Morpheus budget with
what has actually been spent: monthly budget, spend to date, daily burn rate, month-end
forecast, status, and the year to date.

| | |
|---|---|
| Plugin code | `morpheus-budget-burn-plugin` |
| Provider | Analytics provider `budget-burn-analytics`, category *Cost*, name *Budget Burn* |
| Jar | `morpheus-budget-burn-plugin-<version>-all.jar` |
| Release tags | `budget-burn-v<version>` |

## What it shows

One row per budget with `period = year` for the current calendar year:

- **Budget** – the budget's name, and under it in a smaller line its scope (*Tenant: …*,
  *Group: …*, …), in the master tenant's view also its owner.
- **Monthly budget** – the share of the budget for the current month: monthly budgets as
  entered, quarterly budgets / 3, yearly budgets / 12 (two decimals, half up), with the budget
  currency. All other amounts of the row are in that currency.
- **Spend to date** – `running_price` of the monthly invoices in the budget scope, and its
  share of the monthly budget.
- **Burn rate / day** – spend to date divided by the elapsed days of the month.
- **Forecast** – `total_price` of the same invoices (Morpheus' projection for the whole month),
  with a bar showing forecast vs. budget.
- **Status** – *On track* below 80 %, *Warning* from 80 % to 100 %, *Over budget* above 100 %
  of the monthly budget (forecast based). A monthly budget of 0 is *Over budget* as soon as
  there is a forecast, and *On track* without one; its percentages show `-` instead of a number,
  and the bar is full when there is spend. A budget that is switched off shows *Disabled* (see
  below).
- **Year to date** – closed months (`total_price`) plus the current month's forecast, against
  the budget for January to the current month. Quarterly and yearly shares are rounded once for
  the whole period, so in December a yearly budget shows exactly its amount.

Spend means the price the tenant pays (including any tenant markup), not the provider's cost.
Invoices counted are monthly invoices of instances and of servers that belong to no instance,
so nothing is counted twice (since 1.2.1 a server belongs to an instance when a `container` row links them (`container.server_id` = the invoice's `ref_id`, `container.instance_id` set); Morpheus leaves `instance_id` empty on the server invoice of an instance's own VM). The budget scope maps to the invoices as follows: tenant →
`account_id`, group → `site_id`, cloud → `zone_id`, user → `user_id`, otherwise the budget
owner's tenant.

**Whose invoices count.** A budget counts only the invoices of the tenant that owns it. A
budget owned by the master tenant also counts the invoices of its sub-tenants within its scope
(for example every tenant's spend on a cloud). A sub-tenant's budget never counts another
tenant's invoices, even when its cloud is shared by the master tenant or its group or user also
carries invoices of the master tenant.

**Switched-off budgets (since 1.2.3).** A budget whose `enabled` flag is off (`enabled: false`
in the REST API and the CLI; the budget form of Morpheus 9.1.0 has no such field) is still
listed, with all its amounts, but at the end of the table and with the status *Disabled*
instead of *On track*, *Warning*, *Over budget* or *Currency mismatch*. *Disabled* has a look
of its own: a slate blue status in italics (neither an alarm color nor the grey of a currency
mismatch), a striped bar and dimmed amounts. The status the budget would have when switched on
stays visible as a small line under it, for example *would be Over budget*. A disabled budget
never raises an alarm: its status is never *Currency mismatch*, and it does not bring up the
currency mismatch note under the table. Spend in another currency is still shown in its row as
for an enabled budget: the foreign amounts under *Spend to date* and *Forecast*, no burn rate,
*No comparison: costs in a currency other than the budget currency* in place of the bar (so no
striped bar), *would be Currency mismatch* as the small status line when this month has such
spend, and in *Year to date* the foreign amounts followed by *Currency mismatch* when the year
so far has such spend. A budget without a value for the flag counts as enabled.

## Who sees what

- Users of the **master tenant** see all budgets of the master tenant and of every sub-tenant,
  with the owner under each budget name.
- Users of a **sub-tenant** see only the budgets owned by their own tenant.

The page appears in the Morpheus analytics section (category *Cost*); which roles can open it follows the Morpheus analytics permissions.

## Currencies

A budget is in the currency of its owner tenant; if that tenant has none, the master tenant's
currency; otherwise `USD`. Spend is summed per invoice currency (an invoice without a currency
counts in the budget currency). Only a three-letter ISO code counts as a currency; any other
value is treated as no currency. Amounts in different currencies are **never converted or added
up**: spend in another currency is listed next to the budget amount and the row is marked
*Currency mismatch*, without burn rate and percentages for the affected period. Every amount
carries its ISO currency code.

## Language and number format

English is the default; German is included (`i18n/messages_de.properties`). Headings, column
names, scope and status texts follow the viewer's language, and numbers are formatted in the
viewer's locale (e.g. `1,234.56` or `1.234,56`). The menu entry and the provider description stay
English, since plugin API 1.4.2 does not localize them.

**Which language counts (since 1.2.0):** the viewer's own language setting in Morpheus (the
user's *Locale*, e.g. `en-US`), which is also the language of the Morpheus UI. So a user set to
English sees the page in English even when the browser asks for German, and vice versa. Only
when that setting is empty or not a valid language, or cannot be read, does the plugin use the
locale of the web request (the browser's `Accept-Language`), and English without a request.
Texts exist in English and German; any other language gets English texts with numbers in that
language's format.

Up to 1.1.1 the plugin used the browser's language only, so with Morpheus set to English and the
browser to German the menus were English but the plugin content and numbers German.

## Options

None. The page has no settings; budgets are managed in Morpheus under
*Operations → Budgets*.

## Known limits

- **Only yearly budgets** (`period = year`) for the current calendar year are shown.
- **Current month in the appliance JVM time zone.** Around midnight at a month end the page may
  already show (or not yet show) the next month compared with the viewer's time zone.
- The **burn rate** is a plain average over the elapsed days; it does not weight weekdays.
- Spend is read from Morpheus invoices; it is as current as the last invoice / costing run.
- **Not the figure of Morpheus' own budget page.** This page counts the price, markup included
  (`running_price` to date, `total_price` for the forecast and the closed months), of the instance
  invoices and of the server invoices without an instance in the budget's scope. Morpheus' own
  budget page shows the cost: per the
  [Morpheus 9.1.0 documentation on budgets](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008772en_us&docLocale=en_US&page=GUID-33E060CF-9C8E-4AA3-971B-7C5E06954157.html)
  (*Budget Monitoring*), its actual amount for a month is the total cost of the invoice with the
  same scope (for a cloud budget, the cloud's own invoice; the projected cost for the current
  month). The two figures can therefore differ. The cloud's own invoice
  (`ref_type = 'ComputeZone'`) is not added here: it carries no price, only actual cost (on
  Morpheus 9.1.0 its `total_price` is 0 and only the `actual_*` cost columns are filled).
- **Docker hosts and Kubernetes nodes.** A server invoice is not counted when a `container` row
  links that server to an instance (`container.server_id` = the invoice's `ref_id`,
  `container.instance_id` set); that keeps an instance's own VM from being counted twice. A Docker
  host or Kubernetes node on which Morpheus-managed container instances run has such rows as well,
  so its own server invoice is not counted either; the instance invoices of its container
  instances are. The rule assumes that those instance invoices carry the host's cost. The page
  does not check this: a host cost that is not priced into its container instances is missing
  from the spend. The `container` table is read as it is when the page is loaded, not as it was
  in the invoiced month.
- **Language from the user record.** The language setting is read from the internal `user`
  table (column `locale`); if that read fails, the page falls back to the browser language
  instead of failing.
- **Reads internal database tables** (see the matrix below) through the read-only report
  connection. They are not a public API: the plugin is tested on Morpheus 9.0.2 only and may
  break on an upgrade. A failed query shows a short error on the page; details go to the
  appliance log.

## Compatibility

| Plugin version | Plugin API | Min. appliance | Tested on | Internal tables read |
|---|---|---|---|---|
| 1.2.2 | 1.4.2 | 9.0.2 | 9.0.2 (1.2.2-rc.2, master tenant) | `user`, `account`, `account_budget`, `account_budget_period`, `account_invoice`, `container` |
| 1.2.1 | 1.4.2 | 9.0.2 | 9.0.2 (1.2.1-rc.1, master tenant) | `user`, `account`, `account_budget`, `account_budget_period`, `account_invoice`, `container` |
| 1.2.0 | 1.4.2 | 9.0.2 | 9.0.2 (1.2.0, master tenant) | `user`, `account`, `account_budget`, `account_budget_period`, `account_invoice` |
| 1.1.1 | 1.4.2 | 9.0.2 | 9.0.2 (1.1.0; 1.1.1 as master) | `user`, `account`, `account_budget`, `account_budget_period`, `account_invoice` |
| 1.1.0 | 1.4.2 | 9.0.2 | 9.0.2 | `user`, `account`, `account_budget`, `account_budget_period`, `account_invoice` |

Queries internal tables, tested on 9.0.2 only, may break on upgrade.

## Install

Download `morpheus-budget-burn-plugin-<version>-all.jar` from the
[release budget-burn-v1.2.2](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/budget-burn-v1.2.2) (tag
`budget-burn-v<version>`), then upload it under *Administration → Integrations → Plugins →
Add*. Updating to a newer version with the same plugin code replaces the plugin in place.

## Build

JDK 17 (the jar targets Java 11); the wrapper fetches Gradle 9.8.0 and checks its SHA-256:

```
./gradlew clean test shadowJar
```

The jar is `build/libs/morpheus-budget-burn-plugin-<version>-all.jar`. The Morpheus plugin API,
Groovy, SLF4J and RxJava are provided by the appliance and are not bundled.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
