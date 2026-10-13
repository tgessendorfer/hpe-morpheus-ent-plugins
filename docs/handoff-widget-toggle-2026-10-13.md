# Handoff: FinOps Insights 1.2.0, widget switch, latest log entries and log history page (2026-10-13)

Branch `feature/finops-insights-1.2.0-widget-toggle`, on top of `main` at `98dfd6c`: the switch
(`ebc0440433297377f35b484960e83ef3e44e85bb`), this note's first version (`67d587607e64fcd1ce4452e98777e87457ddae9d`)
and, after them, the latest log entries widget with its history page (last section of this note). Not pushed,
not tagged, no jar uploaded to any appliance, `main` untouched, no digest recorded.

Plugin: `finops/finops-insights` (`morpheus-finops-insights-plugin`, code and jar name unchanged), 1.1.1 to **1.2.0**
(1.2.0 is unreleased, so the log widget is part of it).

**Sections:** the switch for the three FinOps widgets (written first, below; the fourth checkbox `hideLogs` and the fourth
widget are in the second part), then *Latest log entries and log history page*.

## The request

An on/off switch for the dashboard widgets the plugin contributes (FinOps at a glance, Six-month trend, This month by
cloud and tenant). Morpheus 9.1 has no per-user dashboard customisation to build on, and the plugin API has no
enable/disable hook for a dashboard item type: `enabled`, `optionTypes` and `canExecute` of a `DashboardItemType` are
not persisted by the appliance's item type sync.

Chosen: **plugin settings with one inverted checkbox per widget**, global for the appliance. Considered and left out:
the role permission *Operations > Costing > Budgets* (exists, but hides all three widgets together; stays the
per-role answer), a new plugin permission per widget (needs role grants and has an untested null-permission case), a
client-side toggle (per browser only, the query still runs) and disabling the plugin (removes the dashboard from
*Dashboards to Display*; never use it as a switch).

## What changed

| File (under `finops/finops-insights/`) | Change |
|---|---|
| `src/main/groovy/.../FinopsInsightsPlugin.groovy` | `setSettings(settingsOptionTypes())`: three `CHECKBOX` option types `finops-insights-hide-kpi`, `-hide-trend`, `-hide-split`, fieldNames `hideKpi`, `hideTrend`, `hideSplit`, `fieldContext: 'config'`, no `defaultValue`, no `fieldCode` |
| `src/main/groovy/.../AbstractFinopsItemProvider.groovy` | `getSettingKey()` per widget, `pluginConfig()` (reads `morpheus.getSettings(plugin)` on every render), `flagOn()`, `parseConfig()`, `isHidden()`, `renderHidden()`; `renderDashboardItem` returns the hidden template first and skips the invoice query |
| `src/main/groovy/.../Finops{Kpi,Trend,Split}ItemProvider.groovy` | one line each: the setting key |
| `src/main/resources/renderer/hbs/finops-hidden-widget.hbs` | new: `.widget-container:has(.fi-off) { display: none; }` and one muted line as the fallback |
| `src/main/resources/i18n/messages*.properties` | `finops-insights.hint.hidden` in English and German |
| `src/test/groovy/.../FinopsWidgetToggleSpec.groovy` | new, 41 test iterations (flag parsing, JSON parsing, per-widget decision, unreadable settings, no query when hidden, template renders) |
| `src/test/groovy/.../FinopsInsightsPluginSpec.groovy` | settings and template assertions; the dashboard layout test is repaired (see below) |
| `gradle.properties`, `RELEASE-NOTES.md`, `README.md` | version 1.2.0, release notes section, README paragraph *Switch a widget off* and a version table row |
| `gradlew` | exec bit restored in the index (`100644` to `100755`); content unchanged |

Behaviour: all three widgets stay shown by default. A ticked box hides that widget for every user of every tenant on
the next dashboard load, with no plugin reload. A missing, unticked or unreadable setting keeps it shown.

## Checks against the real code

The first sketch was untested. What was checked, and where the implementation differs from it:

| Sketch claim | Result |
|---|---|
| `MorpheusContext.getSettings(Plugin)` returns the stored settings as JSON | **Confirmed.** Plugin API 1.4.2 (`javap`): `Single<String> getSettings(Plugin)`. On the appliance core 9.1.0 it answers the stored plugin config, and an **empty string**, not an error, when nothing is stored; `parseConfig` handles that. No caching, so a save is seen by the next render |
| Keys of that JSON are the option types' fieldNames | **Confirmed from the vendor guide** (`getting_started/guides/instance_tabs.md`, *Retrieving Plugin Settings*: `settingsJson.settingFieldName`). Not yet seen with a real saved value |
| `fieldContext: 'config'` | Correct, and the default of `OptionType` (constructor, `javap -c`). Kept explicit for clarity |
| `OptionType.InputType.CHECKBOX` | Exists; `InputType` is a class with constants, not an enum |
| `Plugin.setSettings(List<OptionType>)`, list initialised | Confirmed (`Plugin` constructor creates the list) |
| `defaultValue: 'off'` | **Dropped.** An unticked box needs no default, and the guard treats a missing key as shown. (`tenant-chargeback` uses `'off'`, the LLM plugins omit it; both exist in this repository) |
| `displayOrder` 1 to 3 | 0 to 2, as elsewhere in the repository |
| Checkbox label *Hide revenue by cloud and tenant* | **Corrected** to *Hide This Month by Cloud and Tenant*: the title on the dashboard comes from the message `split.title`, not from the item type title |
| Hidden-widget hint key `finops-insights.hidden` | `finops-insights.hint.hidden`, in the existing `hint.*` group, shown together with the widget title |
| CSS `:has(> .fi-off)` | `.widget-container:has(.fi-off)` (descendant), which also works when the host adds a wrapper. The class `widget-container` is the one the appliance wraps dashboard items in; **not yet seen on a live page** |
| Tests in `FinopsInsightsPluginSpec` only | The behaviour tests are in the new `FinopsWidgetToggleSpec` |

## How it was verified

- JDK 17, Gradle 9.8.0 wrapper, build in a local copy of the module (the checkout is on a network share),
  `clean test shadowJar --no-daemon`: **75 tests, 0 failures** (19 calculation, 15 plugin, 41 toggle).
- The jar `build/libs/morpheus-finops-insights-plugin-1.2.0-all.jar` (108,210 bytes, sha256
  `bb934117e449d0a29941f8aedb55af9483870565c236a1eeac179a006af7d436` for that local build, **not a release digest**)
  carries `Plugin-Version: 1.2.0`, the unchanged `Morpheus-Code`, the new template and both bundles.
- Nothing ran against an appliance: no upload, no API call.

## Found on the way

- **The dashboard layout test had failed since 1.1.0** (`main` at 1.1.1: 32 tests, 1 failed). It still expected the
  three-widget dashboard of 1.0.4. Repaired in this branch (14 items, FinOps widgets in group rows 3 and 4).
- **`finops/finops-insights/gradlew` was committed as `100644`.** A lost exec bit has broken a release before
  (`./gradlew: Permission denied`). Fixed in the index.
- **No CI workflow and no release trigger for this plugin.** `.github/workflows` has no `build-finops-insights.yml`,
  and `release.yml` lists neither the tag prefix `finops-insights-v*` nor a `case` branch for it, so a pushed tag
  would not start a release. Not changed here; it needs a decision before the first tag.
- **No `finops-insights-v*` tag exists on `origin`.** 1.0.4, 1.1.0 and 1.1.1 were never tagged, and the root
  `README.md` table still says 1.0.4 with a link to a tag that does not exist. The 1.2.0 release notes therefore end
  with a `commits/` link, as the 1.0.4 section does, not a `compare/` link.
- **The German note shows mojibake**: the bundle `messages_de.properties` is UTF-8, `Properties.load(InputStream)`
  reads ISO-8859-1, so *andere Währung* renders as `andere WÃ¤hrung` (measured by calling `message()` in a test).
  Present since 1.0.4, not touched here. One-line fix: load through an `InputStreamReader` with UTF-8.

## What the maintainer has to do

1. **Build the jar from this branch** (CI or the local build above) and upload it to the lab appliance as an upgrade
   of 1.1.1. Read the plugin list back: version `1.2.0`, status `loaded`, `valid`.
2. **Read the plugin**: `GET /api/plugins/<id>` shows three `optionTypes` with the codes above, `config` empty.
3. **Open the settings dialog**: Administration > Integrations > Plugins, edit *FinOps Insights Dashboard*. Check that
   the three checkboxes render with label and help text on a **9.1.0 appliance** (the dialog showed only name,
   description, code and providers for plugins without settings, so this is the main unknown). Tick one and save.
4. **Toggle and observe**: reload Operations > Dashboard (no plugin reload). Tick *Hide Six-Month Trend*: that widget
   is gone and the split widget keeps its half; untick: it is back. Repeat for the other two, and once as a
   sub-tenant user (the setting is global). With the browser's developer tools, confirm that `.widget-container`
   wraps the hidden widget's markup and that the box really disappears; if the class differs, only the selector in
   `finops-hidden-widget.hbs` needs to change.
5. **Fallback when the dialog does not render or save the fields**:
   `PUT /api/plugins/<id>` with `{"plugin":{"config":{"hideTrend":"on"}}}`, then reload the dashboard. Read the
   stored value back with `GET /api/plugins/<id>`. Decide whether the settings are documented for the dialog or for
   the API only, and say so in the README paragraph and the release notes.
6. **Check the stored shape**: the keys must be `hideKpi`, `hideTrend`, `hideSplit`. The values `on`, `"true"`,
   `"yes"`, `"1"` and the JSON boolean `true` are all read as ticked.
7. **Release**: add `finops-insights-v*` to `release.yml` (trigger and `case`) and a build workflow; fill the
   *Verified* and *Not verified* sections of the 1.2.0 release notes with what steps 1 to 6 showed; update the
   version links in the plugin README and the root README table once the tag exists; push the tag alone; add the
   digests of the release jar afterwards (CI jars are not reproducible, so the local sha256 above will differ).
   The repository is public: keep hostnames, addresses and plugin ids out of the notes.
8. **Merge decision**: the branch is two commits on top of `main`, so a fast-forward merge works. Merge in a clone on
   local disk (`git merge` on the share can fail with `fatal: stash failed`).

## Open risks

- **The settings dialog is untested.** If 9.1.0 does not render option types of a plugin, only the API route works.
- **`widget-container` is untested** on a live page. Worst case the user sees one muted line instead of an empty box.
- **The settings are global.** A choice per role or tenant needs a plugin-defined permission per widget (untested:
  the role permission page and `update-permission` with an unknown code, and a `null` permission on the item type).
- **Setting labels are English only.** `fieldCode` would localise them, but it replaces the `fieldLabel` with an
  i18n lookup, and that lookup is unverified for the plugin settings dialog.
- **Upgrade behaviour:** whether a saved `config` survives a plugin upgrade and a later upload of another version is
  not checked. Do it once in step 4 (save a tick, upload the same jar again, read `config`).
- **Appliance 9.0.2 (plugin API 1.4.1):** `getSettings` is in the API since 5.4.1 according to the vendor guide;
  not run on 9.0.2.
- **Cost:** three small reads of the plugin's config per dashboard load; no query when the widget is hidden.

---

# Latest log entries widget and log history page (same branch, same 1.2.0)

## The request

Show the latest ten log entries on the dashboard with a link to a full log history page. The standard *Log History*
widget looks only at today and at a very small default scope (a handful of objects), so it is empty most of the time.
Scope added the same day: the history page is served by this plugin, and every line links to its instance details.

## What changed

| File (under `finops/finops-insights/`) | Change |
|---|---|
| `src/main/groovy/.../FinopsLogsItemProvider.groovy` | new item type `finops-insights-logs` (half width, permission *Logs* read or full, own script `finops-insights-logs.js`, setting key `hideLogs`) |
| `src/main/groovy/.../FinopsLogsController.groovy` | new `PluginController`: route `/finops-insights/logs` (served at `/plugin/finops-insights/logs`), method `logsPage(ViewModel)` |
| `src/main/groovy/.../FinopsLogsSupport.groovy` | paths, limits, the JSON handed to the script, the request parameter whitelist |
| `src/main/groovy/.../FinopsInsightsData.groovy` | `logSources(opts, locale)`: the viewer's servers (SQL, cap 50) through the `newSql` test seam; the viewer now comes from the security context first, the options only without one (also for the invoice widgets) |
| `src/main/groovy/.../AbstractFinopsItemProvider.groovy` | `getPermissionCode()`, `getAccessTypes()`, `getScriptPath()` overridable; the invoice path moved to `renderContent` |
| `src/main/groovy/.../FinopsInsightsPlugin.groovy`, `FinopsInsightsDashboardProvider.groovy` | registers the widget and the controller; fourth setting `hideLogs`; log row `[finops-insights-logs, dashboard-item-log-count]` (15 items); the log widget is left out when its permission could not be loaded |
| `src/assets/javascripts/finops-insights-logs.js` | the browser script for widget and page (ES5, no library) |
| `src/main/resources/renderer/hbs/finops-logs-widget.hbs`, `finops-logs-page.hbs` | the two templates |
| `src/main/resources/i18n/messages*.properties` | `finops-insights.logs.*` in English and German (equal key sets) |
| `src/test/groovy/.../FinopsLogSourcesSpec`, `FinopsLogsItemProviderSpec`, `FinopsLogsControllerSpec`; `FinopsInsightsPluginSpec` updated | new Spock iterations (see *How it was verified*) |
| `src/test/js/finops-insights-logs.test.js`, `build.gradle` (`jsTest`) | browser script tests with plain node |
| `README.md`, `RELEASE-NOTES.md` (`gradle.properties` unchanged, 1.2.0) | documentation |

## Design in short

- **No plugin service returns log entries** (checked in plugin API 1.4.2 and 1.5.0), so the browser asks the platform's
  `GET /api/logs` with the signed-in session, `servers=<id>` repeated (a comma list matches nothing), newest first.
  The server only decides *which* servers: one SQL query for the viewer (`compute_server` with the agent installed,
  logging not switched off, not removed; `container.instance_id` for the link when there is exactly one), master
  tenant without an account filter, a sub-tenant with its own account id, newest activity first, at most 50.
- **The ids come from that query and from nothing the request carries.** A test hands the query hostile option keys
  and checks that none of it reaches the SQL, and that the security context decides who the viewer is.
- **Texts are tenant data.** The script writes `textContent` only, strips terminal escape sequences and control
  characters, builds links from digits, takes the level class from a whitelist; server names reach the page as
  HTML-escaped JSON in a data attribute.
- **Swap with the standard widget.** While the plugin widget is shown (sources resolved) it emits
  `.widget-container:has(#log-count-widget) { display: none; }` in its own `<style data-fi-swap>`; the script removes
  that rule again when no log route answers, so the standard widget is back. With *Hide Latest Log Entries* ticked
  the widget renders the existing hidden template and the standard widget stays.
- **Scope is the tenant** (master: all tenants), not the role's group, cloud or site limits.

### How the history page is served: mechanism choice

Expected behaviour of the appliance's plugin route handling, taken from a read-only look at the appliance and to be
confirmed with a running plugin:

| Mechanism | Finding | Verdict |
|---|---|---|
| **(a) `PluginController` route** | Every path below `/plugin/` goes to the plugin dispatcher. It compares `Route.url` with `"/" + <path after /plugin/>` and only checks that the route's permission **code is present** in the user's permission map. An `HTMLResponse` is the response body; a `JsonResponse` is rendered as JSON; anything else is a 404. The path requires a signed-in session and no further permission, so a sub-tenant user with only *Logs* reaches it. | **chosen**: the only mechanism that gives an addressable page of the plugin's own |
| (b) second plugin dashboard | The dashboard page renders **every** dashboard of *Dashboards to Display* one below the other; a single dashboard cannot be requested as a page. It would add a long list to the home page. | rejected |
| (c) instance tab provider | Per instance only, and the instance page already has a native *Logs* tab. | rejected; every line links to that tab instead |

- **No Morpheus navigation is expected** on the route page: the HTML is returned outside the Morpheus layout. The page
  therefore has its own header with links back to the dashboard and to the standard log viewer. **Not seen in a browser.**
- **Access level.** The dispatcher's check is "code present", so the controller itself requires the Logs level
  **read** or **full** from the user's permission map and answers 403 otherwise (also for *user* and *none*).
- **Row links.** Instance: `/provisioning/instances/<id>#!monitoring-logs-tab`; server without exactly one instance:
  `/infrastructure/servers/<id>#!monitoring-logs`. The appliance's tab script selects a tab from `#!<tab id>` (the
  convention it uses itself, for example for `#!backups-tab`); the Logs pill ids are `monitoring-logs-tab` on the
  instance page (only with containers, the `logging` licence feature and the Logs permission) and `monitoring-logs` on
  the server page. When the tab does not exist the page opens on its first tab. **Not clicked in a browser.**

## Corrections to the first design sketch

| Sketch | What the implementation does |
|---|---|
| Item type permission *Logs* with read, user and full | **read and full only.** The level *user* limits a viewer to his own instances; the list covers the whole tenant |
| Header link to `/monitoring/logs` | The header links to the plugin's history page. The standard viewer takes no parameters and opens on its own default scope, so its content differs from this widget; the page links to it as *Standard log viewer* |
| `SELECT ... cs.status<>'removed' ...` | NULL-safe (`status IS NULL OR status <> 'removed'`), tie-break `cs.id DESC`; the instance only when the server carries exactly one |
| `if(window.__fiLogs) return` | A second execution rescans for roots that are not drawn yet (the dashboard inserts the item and loads its script again after a reload), draws only those, and is otherwise a no-op |
| Fallback through `/logs/logs.json` | Implemented: one POST per server (at most 10), merged newest first, no paging; sends the CSRF header from the page's meta tags when present. The UI route reads `serverId`, not a server array |
| `FinopsLogsItemProvider` only | Plus a controller and a support class; the base class gets a `renderContent` hook so the log widget does not run the invoice query |
| Standard widget hidden in every state | Only when the sources resolved, and the script removes the rule again when the entries cannot be fetched |
| Viewer from the request options first | The security context first; the options only without a context |
| (not in the sketch) page route URL | `Route.url` is `/finops-insights/logs` with the leading slash |

## How it was verified

- JDK 17, Gradle 9.8.0 wrapper, build in a local copy of the module (the checkout is on a network share), `clean test
  shadowJar --no-daemon`: see the counts in the release notes' *Verified* section (Spock iterations and node tests,
  0 failures); the node tests also ran against the minified script from the jar.
- Not run against an appliance: no upload, no API call, no browser. The route handling, the permission map and the tab
  fragment were looked up, not exercised.

## What the maintainer has to test after the upload

1. **Plugin list:** version 1.2.0, `loaded`, `valid`; the plugin's `optionTypes` list four settings.
2. **Master tenant dashboard:** the log row shows *Latest log entries* and not the standard widget; open the browser
   network tab: one `GET /api/logs?servers=...` with the viewer's servers (the count equals the servers with an agent
   across tenants, at most 50); lines appear newest first; repeated lines show a count.
3. **Sub-tenant user, same dashboard:** only that tenant's servers in the `servers=` list and only their entries; no
   foreign object ids in the entries (compare `objectId` of the response with the list). Check which path served the
   entries: `/api/logs` with the session cookie (so far only seen for the master tenant) or, when it is refused, the
   per-server POSTs to the UI route. If both fail the widget says so and the standard widget returns; note which
   one it was.
4. **A role without Logs, and a role with the level *user*:** neither the plugin widget nor the page; the standard
   widget is unaffected; opening `/plugin/finops-insights/logs` answers the 403 page (or a plain 404 when the user's
   permission map has no `logs` entry at all: the dispatcher then finds no route for that user).
5. **A tenant without servers (no agent):** the widget shows the hint *No servers with logging are visible to this
   tenant.* and fires no log request.
6. **Switch:** tick *Hide Latest Log Entries*, reload the dashboard: the plugin widget is gone and the standard widget
   is back; the history page answers *Switched off in the plugin settings.* Untick: swapped again. Check the swap
   rule in the developer tools (`.widget-container` around `#log-count-widget` gets `display: none`), and in a browser
   without `:has()` that both widgets show.
7. **History page** (`/plugin/finops-insights/logs`): is there navigation around it (expected: no)? Time range, level,
   source and text filter, *Load more* (100 per step, total shown), fold of repeated lines, times in the browser's time
   zone, the retention note. Does the script load (`/assets/plugin/<plugin name>/finops-insights-logs.js` with the
   page's nonce) and does the browser console stay free of content security policy errors?
8. **Links:** a line of a server that belongs to one instance opens that instance on its *Logs* tab; a line of a
   server without an instance opens the server page on *Logs*. If the tab is not selected, only the fragment needs a
   change (`hrefFor` in the script).
9. **The standard log viewer** (`/monitoring/logs`, linked from the page) shows only its own default scope (a handful
   of objects), so its content **differs from the widget**; this is by design, not a defect of the widget.
10. **Scope:** with a role that has group, cloud or site restrictions and *Logs* read, check that the list shows every
    server of the tenant (documented behaviour of this version).

## Open risks

- **The history page is a bare HTML page** if the appliance does not decorate plugin routes, which is what is
  expected. It works, but it has no navigation.
- **The route URL form** `/plugin/<route url>` was not exercised. If `/plugin/finops-insights/logs` answers 404 for a
  user who holds *Logs*, the mapping may capture only the first path segment: use a one-segment route
  (`/finops-insights-logs`) and change `FinopsLogsController.ROUTE_URL` and `FinopsLogsSupport.PAGE_PATH` (the spec ties
  the two together).
- **The user's permission map:** the level check expects it to carry `logs` with `read`, `full`, `user` or `none`. If
  the platform fills it differently the page answers 403 for everyone (fail closed); step 7 shows it at once.
- **`/api/logs` with the browser session for a sub-tenant user** is untested; the fallback exists for that reason, and
  it assumes the UI route returns the same JSON shape (it does for the standard widget).
- **Column names** `compute_server.status` and `compute_server.last_updated` follow the entity naming; the other
  columns of the query (`agent_installed`, `enable_logs`, `account_id`, `container.instance_id`) are read by existing
  plugins or were queried on a 9.1 appliance.
- **The Logs item type permission** is loaded by code when the plugin starts. If that lookup fails, the widget is
  left out of the dashboard layout (logged as an error), so it is never shown without its permission; the standard
  widget stays. There is no render-time permission check available to the plugin.
- **Scope is the tenant, not the role's limits** (group, cloud, site): documented in the README; narrowing it needs
  the role's resource access and was not attempted.
- **The 50 server cap** hides the rest of a large tenant's servers; the order keeps the ones with recent activity.
- **Load:** the dashboard load and every refresh event run one `/api/logs` call of up to 50 server ids (the widget
  listens to the standard refresh event like the other widgets and unbinds when its box has left the page).
- **Time zone:** the page shows the browser's time zone, the widget uses the Morpheus time service when it is there.
- **Public repository:** keep hostnames, addresses and plugin ids out of release notes written from the lab test.
