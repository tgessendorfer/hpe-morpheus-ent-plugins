# Socket Usage Report plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `socket-report-v<version>`, where the
shaded `-all.jar` is attached.

---

## 1.2.0

**The report now shows in the language chosen in the Morpheus user settings, not in the
browser language.** Measured on Morpheus 9.0.2: with the Morpheus UI set to English and the
browser sending German, the menus were English but the report was German, texts and numbers
alike. Morpheus takes its UI language from the user's own setting (`GET /api/user-settings`,
`"locale": "en-US"`), while the plugin API only offers the locale of the web request, which is
the browser's `Accept-Language`, and the API's user model carries no language.

### What changed

- **The report reads the language setting of the user** with one parameterised query over the
  report's read-only database connection (`SELECT locale FROM user WHERE id = ?`), and uses it
  for **texts and number formats**. A setting such as `en-US` gives English texts and
  `1,234.567` even when the browser sends German; `de` gives German texts and `1.234,567`.
- **Order of precedence:** the user setting, then the browser language, then English. An empty
  or unparsable setting, or a lookup that fails, falls back to the browser language with at
  most one debug line in the appliance log; the report always renders.
- **Which user:** plugin API 1.4.2 hands a report's rendering no viewing user, so the report
  uses the user who ran it (the report result's creator). Without one, the browser language
  applies as in 1.1.1.
- **Texts come from the plugin's own English and German bundles**, passed to the template
  with the data, because the template helper of the plugin API always uses the request
  locale. Any other language gets English texts with its own number format, as before.
- The create dialog is unchanged: Morpheus translates the option labels itself, and the
  validation messages still follow the browser language, since validation gets no user.

### Behaviour changes from 1.1.1

- A user whose Morpheus language differs from the browser language now sees the report in the
  Morpheus language. 1.1.1 showed the browser language.
- A viewer other than the user who ran the report sees the report in the runner's language
  when the runner has a setting. 1.1.1 showed every viewer their own browser language.
- The report reads one more internal table, `user` (column `locale`), each time it is shown.

### Verified

- 115 Spock unit tests, 0 failures (79 in 1.1.1). New: parsing of the setting (`en-US`, `de`,
  `de_DE`, blank, `garbage`, over-long text), the precedence (an `en-US` setting with a German
  browser gives English, a `de` setting gives German, a blank, missing, unparsable or unreadable
  setting gives the browser language, no request gives English), that the lookup is a single
  parameterised query, and the real template rendered in English and German from the resolved
  locale with no `{{i18n}}` helper left. Two tests run the lookup through the plugin API's own
  `withDbConnection` against a stubbed read-only connection: the user id is bound as the
  statement's only parameter, the connection is released once, an `en-US` setting renders the
  report in English despite a German browser, and a failing query falls back to the browser
  language and still releases the connection.
- Local build: JDK 17, Gradle 9.8.0, `clean test shadowJar` with `--warning-mode all` and no
  deprecation warnings; one `morpheus-socket-report-plugin-1.2.0-all.jar` with the same manifest
  attributes as 1.1.1 apart from the version.
- On Morpheus 9.0.2 (build 9.0.2-2) with a release candidate built from this source, as a master
  tenant user whose Morpheus setting is `en-US` while the browser sends German: a report result
  renders in English with 11.600 sockets in total, equal to `GET /api/license`.
- The release jar replaced the release candidate on the same Morpheus 9.0.2 appliance in place
  (same plugin id), status `loaded`, valid and enabled. Its source equals the candidate's apart
  from the version; the pages were not opened again with it.

### Not yet verified

- A user with a German setting, and a result opened by a user other than its creator.

Built by the release workflow from the source at tag `socket-report-v1.2.0`.

sha256 `ec7733bd38ee125f0d9773d3b2f998a3b03bcd1e7f50d4a81098177076ecb029`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/socket-report-v1.1.1...socket-report-v1.2.0

## 1.1.1

**Fix: the two report options are now bounded, so an extreme value can no longer hang the
report.** Before, any positive number passed validation; an option such as `1e2000000000` was
accepted and the report thread then tried to write it out in full, with two billion digits.

### What changed

- **VMs per Socket** and **Sockets per Host Without Value** accept a number greater than 0 and
  **at most 1,000,000, with at most 3 decimal places, in plain notation**. Exponent notation
  (`1e2`, `1E+2000000000`) is rejected before the value is parsed, and the check that picks the
  message reads the text in linear time, so it stays cheap for any input, including a pasted
  text of several hundred thousand characters. Such values get their own message in English and German (*Enter a number up to
  1,000,000 with at most 3 decimal places, without exponent notation, or leave the field empty
  for the default.*); zero, negative and non-numeric input keep the 1.1.0 message.
- **The report no longer trusts stored options.** When a report run reads an option outside
  these bounds, for example from a report created with 1.1.0 or through a path that skips
  validation, it uses the default (15 or 2) and logs a warning that names the option, instead
  of hanging. The routine that echoes the options in the report footer refuses an unbounded
  value instead of expanding it.
- **Build update, no change to the jar's contents:** Gradle 9.8.0 (the wrapper now checks the
  distribution's SHA-256), Shadow 9.6.1 (`com.gradleup.shadow`), and no `mavenLocal()`
  repository, so a build resolves dependencies only from Maven Central and the Gradle plugin
  portal. The jar keeps its name, manifest and Java 11 bytecode.

### Behaviour changes from 1.1.0

- Values above 1,000,000, with more than 3 decimal places or in exponent notation are now
  rejected when a report is created. 1.1.0 accepted them.
- A report run with such a stored value now uses the default for that option and finishes,
  where 1.1.0 used the value as given (and hung on an extreme exponent).

### Verified

- 79 Spock unit tests, 0 failures (46 in 1.1.0). New: the bounds and the 1,000,000 and 0.001
  edges, exponent notation including `1e2000000000` and `1E+2000000000` under a 10-second
  timeout, option texts of 30,000 to 100,000 characters under a 5-second timeout, the new validation message, the bundle keys, and a report run with out-of-range
  stored options that finishes as *ready* with the defaults in the footer. The new tests fail
  against the 1.1.0 parsing.
- Local build: JDK 17, Gradle 9.8.0, `clean test shadowJar` with `--warning-mode all` and no
  deprecation warnings; one `morpheus-socket-report-plugin-1.1.1-all.jar` with the same manifest
  attributes as 1.1.0 (apart from the version) and class-file major version 55.
- On Morpheus 9.0.2 (build 9.0.2-2): the release jar was uploaded over the previous build and
  replaced it in place (same plugin id), status `loaded`, valid and enabled; no plugin error in the
  log.
- Live on Morpheus 9.0.2: the report with default options counts 11.600 sockets, equal to
  `GET /api/license`; with 10 VMs per socket 12.400. An option of `1e2000000000`, `1000001`,
  `1.2345`, `0`, `-3` or `abc` is rejected in under 0.1 s with the English or German message.

### Not yet verified

- The fallback for a stored 1.1.0 report with an out-of-range option (unit tests only).
- Any appliance version other than 9.0.2.

Plugin API 1.4.2, minimum appliance 9.0.2.

Built by the release workflow from the source at tag `socket-report-v1.1.1`.

sha256 `1d88cf0531196a6a87f513eeb51342a24628ffbb0d7aa63c6912abf2a40dfe79`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/socket-report-v1.1.0...socket-report-v1.1.1

## 1.1.0

**First public release: a report that shows where the socket count of the license comes
from, per tenant and per cloud and tenant.** Plugin code `morpheus-socket-report-plugin`,
report type `socket-usage-report` (*Socket Usage*, category *Inventory*), master tenant only.

### What it does

- Counts hypervisor hosts with their sockets and servers in clouds without a discovered host as
  a share of a socket, the way the license page counted on Morpheus 9.0.2.
- Totals, a per-tenant table (master tenant first) and a per cloud and tenant table, including
  how many hosts were counted with the default socket value.
- Two options: **VMs per Socket** (default 15) and **Sockets per Host Without Value**
  (default 2). Empty uses the default; zero, negative or non-numeric input is rejected.
- English and German labels; numbers in the viewer's locale, English without one.
- A failed query of the internal tables ends the report as *failed* with a clear log entry.

### Verified

- 46 Spock unit tests: option parsing, ratio division and rounding, host defaults, aggregation,
  locale formatting, codes, manifest, message bundles.
- Live on Morpheus 9.0.2 with the 1.1.0 release candidates: default options and `vmsPerSocket=10`,
  93 numbers each, equal to a reference implementation run side by side; the socket total equals
  `GET /api/license`. Report and options in English and German.
- Not yet uploaded: this release jar itself. The release candidates were replaced in place by
  later builds with the same plugin id, so 1.1.0 is expected to install over them the same way.

### Not yet verified

- Any appliance version other than 9.0.2. The counting rule is measured, not documented by HPE.

Plugin API 1.4.2, minimum appliance 9.0.2.

Built by the release workflow from the source at tag `socket-report-v1.1.0`.

sha256 `62d1761e8dd7f66dff001c271e601c516c16b2cc37e716f65dc78257d25ba0cb`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/socket-report-v1.1.0
