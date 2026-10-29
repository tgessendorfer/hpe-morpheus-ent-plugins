# FinOps Insights Dashboard plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`finops-insights-v<version>`, where the shaded `-all.jar` is attached.

---

## 1.2.3

**The FinOps widgets are never shown without their permission, and a failed connection release no
longer breaks a widget.** Built against plugin API 1.4.2, minimum appliance 9.0.2. Fix release from a
code review; nothing changes on an appliance where the permissions load and the database answers.

### What changes

- **FinOps widgets without the Budgets permission are left out.** A dashboard item type without a
  permission is shown to every user. Up to 1.2.2 only the latest log entries widget was left out when
  its *Logs* permission could not be loaded; if *Operations > Costing > Budgets*
  (`operations-budgets`) could not be loaded when the plugin started, the KPI strip, the trend and
  the split were shown to every user, and a master tenant user without that permission saw the
  provider's revenue, cost and margin. Since 1.2.3 **every plugin widget whose permission is missing
  is left out of the layout** and an error is logged. Rows that end up empty take no row number, so
  the rows below move up without a gap. Reload the plugin once the permission is there.
- **A failed release of the database connection is logged and goes no further.** The invoice load
  and the log source load handed the read-only report connection back in a `finally` block without a
  guard: when that release threw, the exception replaced the result, and the widget failed instead of
  showing its figures or its *loadFailed* hint. The release is now wrapped and logs a warning (the
  same guard Budget Burn has had since 1.1.1).
- **A month with a negative total draws no bar.** A month whose credits are larger than its charges
  gave the trend chart a negative bar height, which is not valid SVG. The bar height is clamped to
  zero; the tooltip still shows the real figure.
- **A cloud or tenant with a negative total draws no bar in the split widget either.** Its bar width
  was negative, which the browser drops as invalid CSS, so the row could show a full bar (read from
  the widget's template and CSS, not seen in a browser); the width is now clamped to 0-100 % by the
  same rule. The amount and the share still show the real figures: the share of such a row is a
  negative percentage, as in 1.2.2.
- **Behaviour change from 1.2.2:** only when the Budgets permission cannot be loaded at plugin start:
  the three FinOps widgets are missing from the dashboard instead of being shown to everyone.

### Verified

- 141 unit tests and 36 browser script tests (JDK 17, Gradle 9.8.0). New tests: the layout without
  the Budgets permission (no FinOps widget, no row gap, the log row unchanged), a failing connection
  release after a successful load, an unknown user and a failed query (the result stays), no release
  for a connection that was never opened, the chart with a negative month and the split with a
  negative cloud.

### Not yet verified

- The release jar on an appliance: `loaded`, the dashboard unchanged for a role with *Budgets* read
  or full, and the widgets missing for a role without it.
- The layout without the Budgets permission was only built in a unit test; the appliance was not
  made to fail that lookup.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/finops-insights-v1.2.2...finops-insights-v1.2.3

---

## 1.2.2

**The dashboard has a neutral name: *Operations Overview*.** Built against plugin API 1.4.2, minimum
appliance 9.0.2. Fix release; nothing else changes.

### What changes

- **Dashboard name and title.** Since 1.1.0 the dashboard was named after one provider's brand
  (*Nordwind Operations*), on every appliance. 1.2.2 names it **Operations Overview**, in the list
  under Administration > Settings > Appliance > *Dashboards to Display* and as the title on
  Operations > Dashboard.
- **Behaviour change from 1.2.1:** only the name. The dashboard code stays
  `finops-insights-dashboard`, so an appliance that has the dashboard selected keeps it selected
  after the upgrade, and the widgets, settings and permissions are unchanged.

### Verified

- 133 unit tests and 36 browser script tests (JDK 17, Gradle 9.8.0); the new test checks the name,
  the title and that the dashboard code is unchanged.
- The release jar on Morpheus 9.1.0-1 in place of 1.2.1: `loaded`, valid; Administration > Settings >
  Appliance shows the selected dashboard as *Operations Overview*, and the setting
  (`dashboardsToDisplay`: `finops-insights-dashboard`) stayed as it was.

### Not verified

- The dashboard title on a page that shows more than one dashboard.

Built by the release workflow from the source at tag `finops-insights-v1.2.2`.

sha256 `12f839dea616531eeb4ba565ce393ff770f89a7efd948ac58367a4c9ad942c67`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/finops-insights-v1.2.1...finops-insights-v1.2.2

---

## 1.2.1

**A provider user's log lines of a sub-tenant server now open the server page instead of the
instance list.** Built against plugin API 1.4.2, minimum appliance 9.0.2. Fix release; nothing else
changes.

### What changes

- **Links of the *Latest log entries* widget and the log history page.** In 1.2.0 a line linked to
  its instance whenever its server carried exactly one instance. Morpheus opens an instance page only
  in the instance's own tenant: a master tenant user who followed a line of a sub-tenant's server
  landed on the instance list (measured on 9.1.0). Since 1.2.1 a line links to the instance only when
  the instance belongs to the viewer's tenant; every other line links to the server page on its
  *Logs* tab (`/infrastructure/servers/<id>#!monitoring-logs`), which a provider user can open.
- **One more column in the log source query:** the tenant of the server's instance
  (`instance.account_id`). Which servers are listed is unchanged.
- **Behaviour change from 1.2.0:** a provider user's lines of sub-tenant servers link to the server
  page; sub-tenant users and the provider's own instances are unchanged.

### Verified

- 132 unit tests and 36 browser script tests (JDK 17, Gradle 9.8.0); the log source test now covers
  an instance of the viewer's own tenant, one of another tenant and a server without an instance.
- **On Morpheus 9.1.0 with 1.2.0** (what led to this release, and what 1.2.0 left open):
  - As master tenant administrator, the instance link of a sub-tenant's server led to the instance
    list; the server page of the same server opened with its *Logs* view.
  - As a sub-tenant administrator (impersonated from the master tenant): the widget lists only that
    tenant's servers (eight, all of that tenant), `/api/logs` answers with the browser session, so
    the fallback route is not needed, and the instance link opens the instance on *Monitoring >
    Logs*.
  - The plugin settings dialog (Administration > Integrations > Plugins, edit) shows the four
    checkboxes with their help texts. It was closed without saving.
- The release jar loads on the same appliance in place of 1.2.0 (`loaded`, valid); the stored
  settings are kept across the upgrade.
- With the release jar, in the master tenant view: lines of sub-tenant servers link to
  `/infrastructure/servers/<id>#!monitoring-logs`, and a click opens the server page with its *Logs*
  view.

### Not verified

- A role without *Logs* or with the level *user*, a tenant without servers, and saving the settings
  through the dialog (they were set through the REST API).

Built by the release workflow from the source at tag `finops-insights-v1.2.1`.

sha256 `354bf7feceaa114140862b8618dbbbd2a2a186ee24b3ab86d816be47114f3d53`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/finops-insights-v1.2.0...finops-insights-v1.2.1

---

## 1.2.0

**Each FinOps widget can be switched off in the plugin settings, and the dashboard gets a latest log
entries widget with a log history page.** Built against plugin API 1.4.2, minimum appliance 9.0.2.
Nothing changes for the three FinOps widgets until a box is ticked; the log row of the dashboard now
shows the new widget in place of the standard *Log History*.

### What changes

- **Four plugin settings, one checkbox per widget:** *Hide FinOps at a Glance*, *Hide Six-Month
  Trend*, *Hide This Month by Cloud and Tenant* and *Hide Latest Log Entries*. They are declared with
  `Plugin.setSettings`, so Morpheus offers them where it offers every plugin setting (Administration >
  Integrations > Plugins, edit the plugin), and they are stored in the plugin's `config`.
- **The setting is global.** A ticked box hides the widget for every user of every tenant. A choice
  per role stays with the permissions *Operations > Costing > Budgets* (the three FinOps widgets
  together) and *Logs* (the log widget and its page).
- **It takes effect on the next dashboard load.** The widgets read the plugin settings on every
  render; the plugin does not need a reload.
- **A switched-off widget does no work.** It runs no invoice query and renders a template that hides
  its own grid box with the CSS rule `.widget-container:has(.fi-off)`. A browser without `:has()`
  shows one muted line instead, for example *Six-month trend: Switched off in the plugin settings.*
  (English and German). The other widget of a row keeps its width.
- **Shown is the default and the safe state.** The checkboxes are inverted ("Hide ..."), so a setting
  that is missing, unticked or unreadable keeps the widget shown, which is the behaviour of 1.1.1.
- The labels and help texts of the settings are English only.

### Latest log entries and log history

- **New widget *Latest log entries*** (half width, item type code `finops-insights-logs`): the newest
  log lines of the last seven days of the viewer's servers (up to ten groups of the 40 newest entries,
  repeated lines of one server folded into one with a count), level badge, time, server and message,
  each line linked to its instance on the *Logs* tab (`#!monitoring-logs-tab`) or, for a server
  without an instance or with several, to the server page. The header links to the history page.
- **It replaces the standard *Log History* widget** in the dashboard's log row. The standard widget
  looks only at today and at a very small default scope. While the new widget is shown it hides the
  standard one with `.widget-container:has(#log-count-widget)`; with *Hide Latest Log Entries* ticked
  the new widget renders the hidden box and the standard widget is shown again. The dashboard layout
  has 15 items now (log row: the plugin widget first, the standard widget second).
- **New page `/plugin/finops-insights/logs`**, served by the plugin (a `PluginController` route):
  newest first, time range 1 hour, 24 hours, 7 days or 30 days, level filter, source filter by server
  name, a text filter on the loaded lines, *Load more* in steps of 100, optional fold of repeated
  lines, time in the browser's time zone, links to the instances. The first view can be set with the
  parameters `window`, `level`, `source`, `q` and `collapse`; each value is cut down to a whitelist.
- **Expect no Morpheus navigation around that page.** A plugin route is served as the HTML the plugin
  returns, outside the Morpheus layout, so the page carries its own header with links back to the
  dashboard and to the standard log viewer. See the README for why a second dashboard or an instance
  tab was not used.
- **Which servers.** The ids the browser asks for come from the database for the signed-in user, at
  most 50: the master tenant sees every tenant's servers, a sub-tenant its own. They are never taken
  from a request parameter or from the browser. The scope is the tenant: it does not follow a
  role's group, cloud or site restrictions, so give *Logs* read or full only to roles that may see all
  of the tenant's servers.
- **Permission *Logs*, level read or full.** The level *user* does not get the widget or the page,
  because the list covers the whole tenant, not the viewer's own instances. If the plugin cannot load
  the permission when it starts, the widget is left out of the dashboard (the standard widget stays).
- **Plain text only.** Log lines and server names are tenant data: the browser script sets
  `textContent` only, strips terminal escape sequences and control characters, builds links from
  digits and takes the level class from a whitelist.
- **One script, own file** (`finops-insights-logs.js`, ES5, no library). Running it twice is harmless.
- **New messages** `finops-insights.logs.*` in English and German (the new German texts use `\u`
  escapes, so they display correctly).

### Unchanged

- The three FinOps widgets, their data queries, the `operations-budgets` permission. Plugin code and
  jar name.

### Also fixed

- **The plugin sets its own renderer, so it loads on Morpheus 9.1.0.** A plugin with a controller
  and no renderer of its own gets its class loader added to the appliance's shared template
  loader; on 9.1.0 (plugin API 1.5.0) that loader list has a fixed size, the add throws
  `UnsupportedOperationException`, and the upload answers *Failed to parse plugin file*. The first
  release candidate failed exactly this way. The widgets and the page already rendered with their
  own renderers, so nothing else changes.
- The unit test for the dashboard layout still expected the three-widget dashboard of 1.0.4 and had
  failed since 1.1.0; it now checks the 15-item layout. No effect on the jar.
- **The German note *Nicht gezeigt, andere Währung* no longer shows `WÃ¤hrung`.** The bundle line held
  a raw UTF-8 umlaut, but `Properties.load` reads ISO-8859-1; it is now a `\u00e4` escape like the
  rest of the bundle. Present since 1.0.4.

### Verified

- 132 unit tests (JDK 17, Gradle 9.8.0): the option types, the checkbox values, the settings JSON
  parsing, that only the widget's own box hides it, that every unreadable setting keeps it shown,
  that a hidden widget loads no data, the log sources (SQL and parameters for the master tenant and
  for a sub-tenant, ids never taken from the request options, the cap of 50, the failure states), the
  page route as the dispatcher finds it, the Logs level check, the parameter whitelist, hostile
  server names in both templates, that the security context decides who the viewer is, that the
  widget is left out of the layout without its permission, that the plugin has its own renderer,
  and the English and German message keys.
- 36 browser script tests with node against a DOM stub, also against the minified file of the jar:
  hostile messages and terminal escapes stay text, links from digits only, folding, `+0000` time
  stamps, filters, paging (frozen time bound, repeated entries skipped), overlapping requests, the
  fallback route and the restored standard widget, running the script twice.
- **On Morpheus 9.1.0, as master tenant administrator, with a release candidate of this code**
  (upgrade over 1.1.1):
  - The upload loads the plugin (`loaded`, valid); `GET /api/plugins/<id>` lists the four checkbox
    option types with their field names.
  - The dashboard shows the three FinOps widgets and *Latest log entries* with ten lines, each
    linked to `/provisioning/instances/<id>#!monitoring-logs-tab`; the standard *Log History* box is
    hidden (`display: none`) while the plugin widget is shown.
  - `PUT /api/plugins/<id>` with `{"plugin":{"config":{"hideTrend":"on","hideLogs":"on"}}}` hides
    the six-month trend and the log widget on the next dashboard load, with no plugin reload; the
    standard *Log History* widget is back and the history page answers *switched off in the plugin
    settings*. Setting the values to `""` shows all widgets again. The boxes really disappear, so
    the grid box class `widget-container` is right.
  - `/plugin/finops-insights/logs` is served as a page of its own without Morpheus navigation, as
    expected; its script loads from `/assets/plugin/<plugin name>/finops-insights-logs.js`, the
    filters offer all servers with logging, and the browser console shows no content security
    policy error. The page follows the browser language (German texts seen).
- The release jar loads on the same appliance in place of the release candidate; the stored
  settings are kept across the upgrade and the dashboard shows all four widgets.

### Not verified

- The settings dialog (Administration > Integrations > Plugins, edit) was not opened; the settings
  were set through the REST API.
- A sub-tenant user (whether `/api/logs` accepts that browser session or the fallback route is
  used), a role without *Logs* or with the level *user*, and a tenant without servers.
- That a click on a log line opens the instance on its *Logs* tab.
- Morpheus 9.0.2 with this version.

Built by the release workflow from the source at tag `finops-insights-v1.2.0`.

sha256 `31233409ce89d9280a9f2e24ab877da3bd69220c4b27ba9c90f2f57c4c2bb1a4`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/finops-insights-v1.2.0

---

## 1.1.1

**Widget permission changed from Invoices to Budgets.** Measured on 9.0.2: the dashboard render
(`/operations/dashboard/<code>`) returns only the items whose item type permission the viewer holds.
A tenant FinOps role that must not see provider invoices (`operations-invoices` none) therefore got an
empty dashboard. The three FinOps widgets now require `operations-budgets` (read or full), which such a
role can hold without exposing invoices. No other change.

---

## 1.1.0

**The dashboard becomes the home dashboard.** Built against plugin API 1.4.2, minimum appliance 9.0.2.

### What changes

- The plugin dashboard (code unchanged: `finops-insights-dashboard`, now named **Nordwind Operations**)
  contains the standard Morpheus home widgets, referenced by their item type codes, interleaved with
  the three FinOps widgets. Row order (owner decision 2026-10-12): Environment + System Status;
  Instance Status + Instances by Cloud; Job Executions + Backups + Task Executions; FinOps at a glance;
  Six-month trend + This month by cloud and tenant; Favorites + Alarms; Activity; Log History.
- Measured on 9.0.2: a `DashboardItem` whose `type` is `new DashboardItemType(code: '<standard code>')`
  is resolved by the appliance to the existing item type on plugin load; the 14 items appear in
  `view_dashboard_item` in the declared order and render with the standard widget scripts, including
  their own permission checks (for example Log History only with the log permission).
- Intended appliance setting: Administration > Settings > Appliance > *Dashboards to Display* = only
  this dashboard (`appliance-settings.dashboardsToDisplay = finops-insights-dashboard`); with the default
  home dashboard still enabled the standard widgets would appear twice.

### Unchanged

- The three widgets and their data queries (1.0.4). Tenants see their own spend; providers see all
  sub-tenants. Give a tenant role the feature permission *Dashboard* = read to show it.

---

## 1.0.4

**First release: a plugin dashboard "FinOps Insights" with three server-rendered widgets.** Versions 1.0.0 to 1.0.3 were lab test builds that never left the lab appliance.
Built against plugin API 1.4.2, minimum appliance 9.0.2.

### What it adds

- **Dashboard `FinOps Insights`** (`DashboardProvider`, code `finops-insights-dashboard`). An
  administrator enables it under Administration > Settings > Appliance > *Dashboards to Display*;
  Morpheus then renders it on Operations > Dashboard below the other enabled dashboards.
- **FinOps at a glance** (full width): revenue of the current month (Morpheus' month-end forecast),
  purchase cost, margin with its share of revenue, the change against the previous month, active
  tenants and instances.
- **Six-month trend**: an inline SVG bar chart of revenue and cost per month.
- **This month by cloud and tenant**: the top five of each with amount, share and a bar; the rest
  folded into *other*.
- **Scope follows the viewer.** A user of the master tenant sees the invoices of all active
  sub-tenants (provider view). A user of a sub-tenant sees only that tenant's spend: no cost, no
  margin, no tenant split.
- **One currency.** Amounts are summed per currency; the widgets show the currency with the most
  money and name any other currency they leave out.
- **No browser code.** Every widget is complete HTML when it leaves the server (own scoped CSS, SVG
  chart, no CDN, nothing for the content security policy to allow). The registered script is a
  no-op, because Morpheus resolves a script per dashboard and widget through the plugin's asset
  manifest and answers `{"success":false}` for the whole dashboard without one.
- **Languages:** English and German, chosen by the user's Morpheus language setting, else the
  browser.

### Verified on Morpheus 9.0.2

- The dashboard appears in *Dashboards to Display* and renders with live invoice figures for the
  master tenant; the three standard dashboards keep working next to it.
- Two findings that shaped the implementation, both measured: the dashboard controller hands the
  widgets an **empty option map** (the viewer is read from the request's Spring Security context
  instead), and a dashboard whose `defaultDashboard` flag is false is **not rendered** (the three
  standard dashboards all carry true).

### Not verified

- The sub-tenant view in a browser (only the SQL scope and the unit tests cover it).
- Appliance versions other than 9.0.2.

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/finops-insights-v1.0.4
