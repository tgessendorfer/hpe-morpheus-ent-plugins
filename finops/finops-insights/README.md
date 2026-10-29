# Morpheus FinOps Insights Dashboard Plugin

A `DashboardProvider` plugin for HPE Morpheus Enterprise 9.0 that adds a dashboard **FinOps
Insights** to Operations > Dashboard: the month's revenue, cost and margin at a glance, a six-month
trend and the split by cloud and tenant, all read from the monthly invoices, and the latest log
entries of the tenant's servers with a log history page.

> **Independent community project.** Not an official HPE product, and neither endorsed by nor
> affiliated with Hewlett Packard Enterprise. See [Trademarks](#trademarks).

> **Reads internal tables.** The widgets query Morpheus' internal database through the read-only
> report connection of the plugin API. They are tested on Morpheus 9.0.2 and 9.1.0 only and may
> break on an appliance upgrade. See [Version compatibility](#version-compatibility).

## What it shows

| Widget | Content |
|---|---|
| FinOps at a glance | Revenue of the current month (Morpheus' month-end forecast), purchase cost, margin and its share of revenue, change against the previous month, active tenants, instances |
| Six-month trend | Revenue and cost per month as a bar chart; the current month is the forecast |
| This month by cloud and tenant | Top five clouds and top five tenants by revenue with amount, share and bar; the rest as *other* |
| Latest log entries | The newest log lines of the last seven days of the viewer's servers (up to ten groups of the 40 newest entries, repeated lines folded), each linked to its instance, and a link to the [log history page](#latest-log-entries-and-log-history) |

- **Scope follows the viewer.** A user of the master tenant sees the invoices of all active
  sub-tenants (`account.master_account = 0`, `active = 1`): the provider view with revenue, cost
  and margin. A user of a sub-tenant sees only that tenant's spend: no cost, no margin, no tenant
  split.
- **Deactivated sub-tenants (since 1.3.0)** (`active = 0`) are left out of every figure, as
  before. In the provider view, when they have invoices with an amount in the current month, one
  line under the KPI cards says how many there are and gives their revenue and cost per currency,
  the widgets' main currency first: *Not shown: 2 deactivated tenants with invoices in 2026-10:
  revenue 123.45 EUR, cost 100.00 EUR*. It uses the same invoices and currency rule as the cards.
  A tenant counts when its own revenue or cost in a currency, rounded to cents, is not zero (as on
  MSP Tenant Overview, so a tenant below one cent counts on neither page); the amounts of the
  tenants that count are summed per currency and rounded once, as the cards are, and a currency
  without an amount is left out. The trend and the split leave deactivated tenants out without a
  note. When the KPI strip renders, the widget first asks whether a deactivated sub-tenant exists
  at all (one small query on `account`); only then are their invoices read, in one grouped query.
  If either query fails, the widget shows its figures without the line and the appliance log gets
  one warning.
- **Invoices counted:** monthly invoices (`period_interval = 'month'`) of instances, plus those of
  servers that belong to no instance (no `container` row links them to one), so a VM is never
  counted twice. Same rule as the MSP Tenant Overview plugin.
- **One currency.** Amounts are summed per currency and never across currencies. The widgets show
  the currency with the most money and say which other currencies they leave out.
- **Months:** the current month and the five before it, in the appliance's time zone.
- **Languages:** English and German, chosen by the user's Morpheus language setting, else the
  browser language.

## Enable the dashboard

1. Upload the jar under Administration > Integrations > Plugins.
2. Administration > Settings > Appliance > **Dashboards to Display**: select *Operations Overview*
   and remove *Default Home Dashboard*, then save. Since 1.1.0 the plugin dashboard already contains
   the standard home widgets (environment, system status, instances, jobs, backups, tasks,
   favourites, alarms, activity, log history) in a fixed order with the FinOps widgets in the middle;
   keeping the default home dashboard enabled shows the standard widgets twice.
3. Operations > Dashboard renders every enabled dashboard, one below the other.

Users see the widgets when their role grants **Operations > Costing > Budgets** (`operations-budgets`,
read or full); since 1.1.1 the invoice permission is not required. If the plugin cannot load that
permission when it starts, the three FinOps widgets are left out of the dashboard instead of being
shown to everyone (since 1.3.0); reload the plugin once the permission is there.

## Latest log entries and log history

Since 1.2.0 the dashboard's log row holds **Latest log entries** next to the standard *Log History*
widget. The standard widget only looks at today and at a very small default scope (a few objects), so
it is often empty on a lab appliance. The plugin widget lists the newest entries of the viewer's own
servers instead, and while it is shown it hides the standard widget with a CSS rule
(`.widget-container:has(#log-count-widget)`; a browser without `:has()` shows both).

- **Which servers:** servers with the agent installed, logging not switched off and not removed. A user
  of the master tenant gets the servers of all tenants, a sub-tenant user only its own. At most 50,
  the ones with the newest activity first. The ids come from the database for the signed-in user on
  the server and from nothing the request carries: the browser is only ever handed the viewer's own
  servers, and a server id in a request parameter is never used.
- **Scope is the tenant, not the role's limits.** The list does not follow a role's group, cloud or
  site restrictions: a role with the *Logs* permission sees the log lines of every server of its
  tenant (the master tenant: of all tenants). Grant *Logs* read or full only to roles that may see
  all of them.
- **How the entries are fetched:** the widget script asks `GET /api/logs` with the browser session
  (`servers=<id>` repeated, newest first, last seven days, 40 entries, up to ten groups shown). If the
  API does not accept the session, it asks the UI's own log route once per server (at most ten) and
  merges. When neither answers, the widget says so and the standard widget is shown again.
- **Permission:** *Logs* with level **read** or **full**. The level *user* (a user who sees only his own
  instances) does not get the widget or the page, because the list covers the whole tenant. If the
  plugin cannot load the *Logs* permission when it starts, the widget is left out of the dashboard
  (the standard widget stays) instead of being shown to everyone.
- **Repeated lines** of one server with the same level and text are folded into one line with a count.
- **Each line links** to its instance, opened on the *Logs* tab (`/provisioning/instances/<id>#!monitoring-logs-tab`),
  or to the server page (`/infrastructure/servers/<id>#!monitoring-logs`) when the server belongs to
  no instance or to several (a container host), and when the instance belongs to another tenant than
  the viewer: Morpheus opens an instance page only in the instance's own tenant, so a provider user
  would land on the instance list, while the server page opens (since 1.2.1). The tab is selected by the appliance's own tab script
  from the URL fragment; when the instance has no Logs tab the instance page simply opens on its
  first tab.
- **Texts are plain text.** Log lines and server names are written by tenants, so the script sets
  `textContent` only, strips terminal escape sequences and control characters, builds links from digits
  only and takes the level class from a whitelist.

**Log history page** (`/plugin/finops-insights/logs`, linked from the widget): the newest first, time
range 1 hour, 24 hours, 7 days or 30 days, level filter (INFO, WARN, ERROR, DEBUG), source filter (the
viewer's servers by name), a text filter on the loaded lines, *Load more* in steps of 100, an optional
fold of repeated lines, and per line time (in the browser's time zone), level, source with its
instance link, and message. It needs the same *Logs* level. The URL accepts `window`, `level`, `source`,
`q` and `collapse` for the first view; every value is cut down to a whitelist, and a `source` that is
not one of the viewer's own servers is ignored.

- **Expect no Morpheus navigation around the page.** A plugin route (`/plugin/...`, signed-in session
  required) is served as the HTML the plugin returns, outside the Morpheus layout. The page therefore
  has its own header with links back to the dashboard and to the standard log viewer. A second
  dashboard in *Dashboards to Display* was rejected because the appliance renders every enabled
  dashboard below the other on Operations > Dashboard, so it could not be a page of its own, and an
  instance tab only shows one instance.
- **Retention.** The appliance keeps log entries for the period set under Administration > Settings >
  Logs (7 days by default), so the 30 day range returns at most that.
- **The standard log viewer** (`/monitoring/logs`) takes no query parameters and opens with its own
  default scope, so its content differs from this widget's.

## Switch a widget off

Since 1.2.0 the plugin has four settings (Administration > Integrations > Plugins, edit
*FinOps Insights Dashboard*), one checkbox per widget:

| Setting | Hides |
|---|---|
| Hide FinOps at a Glance | FinOps at a glance |
| Hide Six-Month Trend | Six-month trend |
| Hide This Month by Cloud and Tenant | This month by cloud and tenant |
| Hide Latest Log Entries | Latest log entries and its history page; the standard Log History widget is shown again |

- The setting is **global**: it applies to every user and every tenant. For a choice per role take
  *Operations > Costing > Budgets* away from the role, which hides all three widgets together.
- It takes effect on the **next dashboard load**; the plugin does not need a reload. A hidden widget
  also skips its invoice query.
- An unticked box, or a setting the plugin cannot read, **shows** the widget.
- A hidden widget's box disappears from the grid (CSS `:has()`); a browser without `:has()` shows one
  muted line instead. The other widget of a row keeps its width.
- The settings are stored in the plugin's `config` (`hideKpi`, `hideTrend`, `hideSplit`, `hideLogs`,
  value `on`), so the REST API can set them as well: `PUT /api/plugins/<plugin id>` with
  `{"plugin": {"config": {"hideTrend": "on"}}}`.

## How it renders

The three invoice widgets are complete HTML when it leaves the server: Handlebars templates with their
own scoped CSS and an inline SVG chart, no JavaScript, no CDN, nothing the content security policy
has to allow. Their registered script is a no-op: on 9.0.2 Morpheus resolves a script per dashboard
and widget through the plugin's asset manifest and answers `{"success":false}` for the whole
dashboard without one. The latest log entries widget and the history page are the exception: the
server renders the frame and the viewer's sources, and the plugin's own script
(`finops-insights-logs.js`, ES5, no library) fetches and draws the entries, because no plugin
service returns log lines. Two more findings from 9.0.2 shaped the code: the dashboard
controller hands the widgets an empty option map, so the viewer is read from the request's Spring
Security context; and a dashboard with `defaultDashboard = false` is not rendered.

## Build

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./gradlew clean test shadowJar     # build/libs/morpheus-finops-insights-plugin-<version>-all.jar
node src/test/js/finops-insights-logs.test.js    # the browser script alone (also run by `test`)
```

Plugin API 1.4.2, Gradle 9.8 wrapper, Groovy 3, JDK 17. The unit tests cover the calculations,
the chart geometry, the message bundles, the plugin identity, the widget settings, the line about
deactivated sub-tenants, the log sources
(SQL and parameters for the master tenant and for a sub-tenant) and the history page. The browser
script is tested with plain node (no packages) against a small DOM stub: hostile messages, folding,
time parsing, filters and paging, running the script twice. The Gradle task `jsTest` runs it and is
skipped where node is not installed.

## Known limits

- **Docker hosts and Kubernetes nodes.** A server invoice is not counted when a `container` row
  links that server to an instance (`container.server_id` = the invoice's `ref_id`,
  `container.instance_id` set); that keeps an instance's own VM from being counted twice. A Docker
  host or Kubernetes node on which Morpheus-managed container instances run has such rows as well,
  so its own server invoice is not counted either; the instance invoices of its container
  instances are. The rule assumes that those instance invoices carry the host's cost. The widgets
  do not check this: a host cost that is not priced into its container instances is missing from
  the figures. The `container` table is read as it is when the dashboard is loaded, not as it was
  in the invoiced month.
- **The line about deactivated sub-tenants covers the current month only**, the month of the KPI
  cards; the earlier months of the trend have no such note.

## Version compatibility

| Plugin | Morpheus | Plugin API | Notes |
|---|---|---|---|
| 1.0.x | 9.0.2 | 1.4.2 | Reads `user`, `account`, `account_invoice`, `compute_zone`, `container`, `instance` |
| 1.2.x | 9.0.2 | 1.4.2 | Adds the widget settings, the latest log entries widget and the history page; also reads `compute_server` for the log sources; verified on 9.1.0 |
| 1.3.x | 9.0.2 | 1.4.2 | Adds the line about deactivated sub-tenants (`account.active` = 0) with invoices under the KPI cards in the provider view; no new tables; release candidate tested on 9.1.0 (the new line not yet seen on an appliance) |

## Trademarks

HPE and Morpheus are trademarks of Hewlett Packard Enterprise Development LP. They are used here
only to name the product this plugin runs on.

## License

Apache License 2.0, see [LICENSE](LICENSE).
