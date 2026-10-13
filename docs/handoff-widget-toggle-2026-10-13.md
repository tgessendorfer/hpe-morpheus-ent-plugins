# Handoff: FinOps Insights 1.2.0, on/off switch per dashboard widget (2026-10-13)

Branch `feature/finops-insights-1.2.0-widget-toggle`, one implementation commit
`ebc0440433297377f35b484960e83ef3e44e85bb` on top of `main` at `98dfd6c`. Not pushed, not tagged, no jar uploaded
to any appliance, `main` untouched, no digest recorded. This note is the second commit on the branch.

Plugin: `finops/finops-insights` (`morpheus-finops-insights-plugin`, code and jar name unchanged), 1.1.1 to **1.2.0**.

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

The research sketch was untested. What was read or measured, and where the implementation differs from it:

| Sketch claim | Result |
|---|---|
| `MorpheusContext.getSettings(Plugin)` returns the stored settings as JSON | **Confirmed.** Plugin API 1.4.2 (`javap`): `Single<String> getSettings(Plugin)`. Appliance core 9.1.0 (disassembled): `Single.just(PluginInstance.findOrCreateByCode(plugin.code).config ?: "")`. It answers an **empty string**, not an error, when nothing is stored; `parseConfig` handles that. No caching, so a save is seen by the next render |
| Keys of that JSON are the option types' fieldNames | **Confirmed from the vendor guide** (`getting_started/guides/instance_tabs.md`, *Retrieving Plugin Settings*: `settingsJson.settingFieldName`). Not yet seen with a real saved value |
| `fieldContext: 'config'` | Correct, and the default of `OptionType` (constructor, `javap -c`). Kept explicit for clarity |
| `OptionType.InputType.CHECKBOX` | Exists; `InputType` is a class with constants, not an enum |
| `Plugin.setSettings(List<OptionType>)`, list initialised | Confirmed (`Plugin` constructor creates the list) |
| `defaultValue: 'off'` | **Dropped.** An unticked box needs no default, and the guard treats a missing key as shown. (`tenant-chargeback` uses `'off'`, the LLM plugins omit it; both exist in this repository) |
| `displayOrder` 1 to 3 | 0 to 2, as elsewhere in the repository |
| Checkbox label *Hide revenue by cloud and tenant* | **Corrected** to *Hide This Month by Cloud and Tenant*: the title on the dashboard comes from the message `split.title`, not from the item type title |
| Hidden-widget hint key `finops-insights.hidden` | `finops-insights.hint.hidden`, in the existing `hint.*` group, shown together with the widget title |
| CSS `:has(> .fi-off)` | `.widget-container:has(.fi-off)` (descendant), which also works when the host adds a wrapper. The class `widget-container` was **read from the appliance's dashboard controller, not seen on a live page** |
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
