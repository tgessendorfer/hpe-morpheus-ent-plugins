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

- **Monthly budget** – the share of the budget for the current month: monthly budgets as
  entered, quarterly budgets / 3, yearly budgets / 12 (two decimals, half up).
- **Spend to date** – `running_price` of the monthly invoices in the budget scope, and its
  share of the monthly budget.
- **Burn rate / day** – spend to date divided by the elapsed days of the month.
- **Forecast** – `total_price` of the same invoices (Morpheus' projection for the whole month),
  with a bar showing forecast vs. budget.
- **Status** – *On track* below 80 %, *Warning* from 80 % to 100 %, *Over budget* above 100 %
  of the monthly budget (forecast based).
- **Year to date** – closed months (`total_price`) plus the current month's forecast, against
  the budget for January to the current month.

Spend means the price the tenant pays (including any tenant markup), not the provider's cost.
Invoices counted are monthly invoices of instances and of servers that belong to no instance,
so nothing is counted twice. The budget scope maps to the invoices as follows: tenant →
`account_id`, group → `site_id`, cloud → `zone_id`, user → `user_id`, otherwise the budget
owner's tenant.

## Who sees what

- Users of the **master tenant** see all budgets of the master tenant and of every sub-tenant,
  with an extra *Owner* column.
- Users of a **sub-tenant** see only the budgets owned by their own tenant.

The page appears in the Morpheus analytics section (category *Cost*); which roles can open it follows the Morpheus analytics permissions.

## Currencies

A budget is in the currency of its owner tenant; if that tenant has none, the master tenant's
currency; otherwise `USD`. Spend is summed per invoice currency (an invoice without a currency
counts in the budget currency). Amounts in different currencies are **never converted or added
up**: spend in another currency is listed next to the budget amount and the row is marked
*Currency mismatch*, without burn rate and percentages for the affected period. Every amount
carries its ISO currency code.

## Language and number format

English is the default; German is included (`i18n/messages_de.properties`). Headings, column
names, scope and status texts follow the viewer's language, and numbers are formatted in the
viewer's locale (e.g. `1,234.56` or `1.234,56`). The menu entry and the provider description stay
English, since plugin API 1.4.2 does not localize them.

**Which language counts:** the plugin uses the locale of the web request, i.e. the browser's
language (`Accept-Language`), not the language chosen in the Morpheus UI. Measured on 9.0.2: with
Morpheus set to German and the browser to English, the Morpheus menus are German but the plugin
content and numbers are English, and vice versa. Set the browser language to the language the
viewers use in Morpheus.

## Options

None. The page has no settings; budgets are managed in Morpheus under
*Operations → Budgets*.

## Known limits

- **Only yearly budgets** (`period = year`) for the current calendar year are shown.
- **Current month in the appliance JVM time zone.** Around midnight at a month end the page may
  already show (or not yet show) the next month compared with the viewer's time zone.
- The **burn rate** is a plain average over the elapsed days; it does not weight weekdays.
- Spend is read from Morpheus invoices; it is as current as the last invoice / costing run.
- **Reads internal database tables** (see the matrix below) through the read-only report
  connection. They are not a public API: the plugin is tested on Morpheus 9.0.2 only and may
  break on an upgrade. A failed query shows a short error on the page; details go to the
  appliance log.

## Compatibility

| Plugin version | Plugin API | Min. appliance | Tested on | Internal tables read |
|---|---|---|---|---|
| 1.1.0 | 1.4.2 | 9.0.2 | 9.0.2 | `user`, `account`, `account_budget`, `account_budget_period`, `account_invoice` |

Queries internal tables, tested on 9.0.2 only, may break on upgrade.

## Install

Download `morpheus-budget-burn-plugin-<version>-all.jar` from the
[releases](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) (tag
`budget-burn-v<version>`), then upload it under *Administration → Integrations → Plugins →
Add*. Updating to a newer version with the same plugin code replaces the plugin in place.

## Build

JDK 17 (the jar targets Java 11):

```
./gradlew clean test shadowJar
```

The jar is `build/libs/morpheus-budget-burn-plugin-<version>-all.jar`. The Morpheus plugin API,
Groovy, SLF4J and RxJava are provided by the appliance and are not bundled.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
