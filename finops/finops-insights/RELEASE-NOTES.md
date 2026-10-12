# FinOps Insights Dashboard plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged
`finops-insights-v<version>`, where the shaded `-all.jar` is attached.

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
