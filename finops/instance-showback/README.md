# Morpheus Instance Showback Plugin

An instance tab plugin for HPE Morpheus Enterprise 9.0 that adds a **Costs** tab to every
instance detail page. Whoever can open the instance sees what it has cost this month so far,
the forecast to month end, the split into compute, storage and licenses, and the three months
before, all taken from the invoices Morpheus already calculates.

> **Independent community project.** Not an official HPE product, and neither endorsed by nor
> affiliated with Hewlett Packard Enterprise. See [NOTICE](NOTICE).

## What it shows

- **Month to date** (`running_price`) per currency, with the day of the month (for example *day 15/31*).
- **Forecast for month end** (`total_price`), the projection Morpheus stores on the invoice.
- **Plan** of the instance from the current invoice.
- **Split, month to date:** compute (CPU + memory), storage and licenses. Actual prices are used
  where Morpheus has them, otherwise the plan prices.
- **History:** the current month and the three months before, one row per month and currency,
  with a bar scaled to the highest month **of the same currency**.
- **Footer:** time of the last cost run, converted to UTC whatever the JVM time zone, and a note
  when the instance has costs in more than one currency.

Prices are the invoice prices, so they include any tenant markup from the price set.

### Currencies

Amounts in different currencies are **never added up, converted or compared**. Every sum, every row
and every bar scale is per currency. The currency of an amount is resolved by one rule, shared by
all FinOps plugins in this repository:

1. the currency of the invoice row,
2. else the currency of the master tenant,
3. else `USD`.

Rows that end up in the same month and currency (for example one without a currency and one in the
master currency) are merged.

### Number format and language

The tab content (texts and number formats) follows the **language set in the viewer's own Morpheus
user settings**, the same setting the Morpheus UI uses (since 1.2.0):

1. the viewing user's Morpheus language setting (for example `en-US` or `de`),
2. else the browser language of the web request (`Accept-Language`), when the setting is empty or
   not a known language, or cannot be read,
3. else English.

Numbers are formatted in that locale (`1,234.56` in English, `1.234,56` in German). Every amount is
shown with its ISO currency code. Texts exist in English (default) and German, in the plugin's
`i18n/messages*.properties`; any other language setting gets English texts with the numbers of
that locale. A user set to English sees English even when the browser asks for German, and the
other way round.

Up to 1.1.1 the plugin used the browser language only, so plugin content and Morpheus UI could be
in different languages (measured on 9.0.2: Morpheus UI in English, plugin content in German).

## Who sees it

- `show()` returns `true` for every instance. The tab relies on Morpheus' own access check for the
  instance detail page: a user who can open the instance sees its costs, in the master tenant and in
  subtenants alike.
- The tab only queries the invoices of the instance it is shown on.
- There are no options and no permissions of its own.

## Install

1. Download `morpheus-instance-showback-plugin-<version>-all.jar` from the
   [release instance-showback-v1.2.0](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/instance-showback-v1.2.0) (tag
   `instance-showback-v<version>`) and check it against `SHA256SUMS`.
2. *Administration > Integrations > Plugins > Add*, upload the jar.
3. Open any instance: the tab **Costs** appears next to the built-in tabs.

Costs appear once Morpheus has run its cost calculation for the instance. Until then the tab says
that there is no cost data yet.

## Known limits

- **Internal tables.** The plugin reads `account_invoice`, `account` and `user` over the plugin API's
  read-only database connection. These tables are internal to Morpheus, not a public API: the
  plugin is tested on 9.0.2 only and may break on an upgrade. A failed query shows an error
  message in the tab and logs the cause; it does not break the page.
- **Current month = JVM time zone.** Which month counts as current, and the day counter, follow the
  time zone of the appliance JVM, not the viewer's. Morpheus 9.0.2 runs its JVM in UTC. The footer
  time of the last cost run is always shown in UTC (since 1.1.1).
- **Language setting read from an internal table.** The viewer's language comes from the
  `locale` column of Morpheus' internal `user` table (plugin API 1.4.2 has no locale on `User`),
  read with a parameterised query on the same read-only connection. If it cannot be read, the tab
  falls back to the browser language, logs one debug line and still renders.
- **Viewer handed from `show()` to the content.** `renderTemplate()` gets no user in plugin API
  1.4.2. Morpheus 9.0.2 calls `show()` and then `renderTemplate()` for the tab in the same request,
  so the plugin keeps the user id from `show()` for that render. If a Morpheus version renders the
  tab without calling `show()` first on the same request thread, the tab has no user in context and
  falls back to the browser language, as up to 1.1.1.
- **Tab title in English.** `getName()` has no request context in plugin API 1.4.2, so the tab is
  titled *Costs* in every language; its content is translated.
- **No conversion.** Mixed-currency instances show one row per currency; there is no total.
- **Forecast is Morpheus' own.** The forecast is the invoice's `total_price`; the plugin does not
  extrapolate on its own.

## Version compatibility

Queries internal tables, tested on 9.0.2 only, may break on upgrade.

| Plugin version | Plugin API | Min. appliance | Tested appliance | Internal tables read |
|---|---|---|---|---|
| 1.2.0 | `morpheus-plugin-api` 1.4.2 | 9.0.2 (`Morpheus-Min-Appliance-Version`) | 9.0.2 (1.2.0 release candidate, master tenant) | `account_invoice` (`ref_type`, `ref_id`, `period`, `period_interval`, `currency`, `*_price`, `plan_name`, `last_cost_date`), `account` (`currency`, `master_account`), `user` (`id`, `locale`) |
| 1.1.1 | `morpheus-plugin-api` 1.4.2 | 9.0.2 (`Morpheus-Min-Appliance-Version`) | 9.0.2 (1.1.0; 1.1.1 as master) | `account_invoice` (`ref_type`, `ref_id`, `period`, `period_interval`, `currency`, `*_price`, `plan_name`, `last_cost_date`), `account` (`currency`, `master_account`) |
| 1.1.0 | `morpheus-plugin-api` 1.4.2 | 9.0.2 (`Morpheus-Min-Appliance-Version`) | HPE Morpheus Enterprise 9.0.2 | `account_invoice` (`ref_type`, `ref_id`, `period`, `period_interval`, `currency`, `*_price`, `plan_name`, `last_cost_date`), `account` (`currency`, `master_account`) |

## Identifiers

| | |
|---|---|
| Plugin code (`Morpheus-Code`) | `morpheus-instance-showback-plugin` |
| Provider code (instance tab) | `instance-showback-tab` |
| Release tag prefix | `instance-showback-v` |
| Java package | `com.morpheusdata.instanceshowback` |

## Build from source

Requires JDK 17 (Groovy 3.0.9) and the bundled Gradle wrapper (Gradle 9.8.0, checked against its
SHA-256 checksum). Run it in `finops/instance-showback`.

```bash
./gradlew clean test shadowJar
# -> build/libs/morpheus-instance-showback-plugin-<version>-all.jar
```

The unit tests cover the currency rule, grouping per month and currency, rounding, locale-aware
number formatting, bar scaling, the month list across year boundaries, the UTC footer time under a
non-UTC JVM time zone, the choice of the content language (Morpheus setting before browser
language before English, with the parameterised lookup and its quiet fallback), the rendered
template in that language, the plugin identity
(code, repository URL, provider) and the completeness of the message bundles.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
