# Morpheus FinOps Insights Dashboard Plugin

A `DashboardProvider` plugin for HPE Morpheus Enterprise 9.0 that adds a dashboard **FinOps
Insights** to Operations > Dashboard: the month's revenue, cost and margin at a glance, a six-month
trend and the split by cloud and tenant, all read from the monthly invoices.

> **Independent community project.** Not an official HPE product, and neither endorsed by nor
> affiliated with Hewlett Packard Enterprise. See [Trademarks](#trademarks).

> **Reads internal tables.** The widgets query Morpheus' internal database through the read-only
> report connection of the plugin API. They are tested on Morpheus 9.0.2 only and may break on an
> appliance upgrade. See [Version compatibility](#version-compatibility).

## What it shows

| Widget | Content |
|---|---|
| FinOps at a glance | Revenue of the current month (Morpheus' month-end forecast), purchase cost, margin and its share of revenue, change against the previous month, active tenants, instances |
| Six-month trend | Revenue and cost per month as a bar chart; the current month is the forecast |
| This month by cloud and tenant | Top five clouds and top five tenants by revenue with amount, share and bar; the rest as *other* |

- **Scope follows the viewer.** A user of the master tenant sees the invoices of all active
  sub-tenants (`account.master_account = 0`, `active = 1`): the provider view with revenue, cost
  and margin. A user of a sub-tenant sees only that tenant's spend: no cost, no margin, no tenant
  split.
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
2. Administration > Settings > Appliance > **Dashboards to Display**: add *FinOps Insights* (keep
   *Default Home Dashboard* in the list if the standard widgets should stay) and save.
3. Operations > Dashboard renders every enabled dashboard, one below the other.

Users see the widgets when their role grants **Operations > Costing** (`operations-invoices`, read
or full).

## How it renders

Every widget is complete HTML when it leaves the server: Handlebars templates with their own scoped
CSS and an inline SVG chart, no JavaScript, no CDN, nothing the content security policy has to
allow. The plugin still registers a script, a no-op: on 9.0.2 Morpheus resolves a script per
dashboard and widget through the plugin's asset manifest and answers `{"success":false}` for the
whole dashboard without one. Two more findings from 9.0.2 shaped the code: the dashboard
controller hands the widgets an empty option map, so the viewer is read from the request's Spring
Security context; and a dashboard with `defaultDashboard = false` is not rendered.

## Build

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./gradlew clean test shadowJar     # build/libs/morpheus-finops-insights-plugin-<version>-all.jar
```

Plugin API 1.4.2, Gradle 9.8 wrapper, Groovy 3, JDK 17. The unit tests cover the calculations,
the chart geometry, the message bundles and the plugin identity.

## Version compatibility

| Plugin | Morpheus | Plugin API | Notes |
|---|---|---|---|
| 1.0.x | 9.0.2 | 1.4.2 | Reads `user`, `account`, `account_invoice`, `compute_zone`, `container`, `instance` |

## Trademarks

HPE and Morpheus are trademarks of Hewlett Packard Enterprise Development LP. They are used here
only to name the product this plugin runs on.

## License

Apache License 2.0, see [LICENSE](LICENSE).
