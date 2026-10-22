# Cost Threshold Approval plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `cost-approval-v<version>`, where the
shaded `-all.jar` is attached.

---

## 1.2.2

**Only provisioning, app and reconfigure requests are checked against the threshold; deletes,
extensions and workflow runs are approved.** Plugin code, provider code, option codes, plugin API
1.4.2 and minimum appliance 9.0.2 are unchanged; no new options.

### Changed

- **What went wrong.** The plugin never read the request type. Attached to an *Approve Delete*,
  *Approve Workflow Execute* or expiration/shutdown policy, it compared the monthly price of the
  instance with the threshold, so deleting or extending an instance above the threshold was
  rejected, and on Morpheus 9.0.2 nobody can override that decision by hand.
- **What the plugin does now.** It reads the request type (the plugin API enum on the request,
  else the type on Morpheus' internal request behind the references) and checks the price only for
  provisioning of an instance, app or cluster, a server added to a cluster, and reconfigure of an
  instance, server or app. Every other type is approved at once, with *Approved automatically: the
  cost threshold applies to provisioning, app and reconfigure requests only (<type>)* as the
  request name, with the type as Morpheus shows it (*Instance Delete Approval*) (German: *Automatisch freigegeben: Die Kostenschwelle gilt nur für Bereitstellungs-,
  App- und Rekonfigurationsanfragen*), and one log line naming the type.
- **A request whose type cannot be read is checked**, as before, so nothing is waved through only
  because its type is unknown.

### Behaviour changes from 1.2.1

- Delete, extension, shutdown extension, workflow, cluster delete and cluster remove requests that
  use this integration are approved regardless of price. In 1.2.1 those above the threshold were
  rejected.
- Provisioning, app and reconfigure requests are decided as in 1.2.1.

### Verified

- New unit tests (Spock): instance and app delete, extension and shutdown extension (plugin API
  enum) are approved at 500 EUR against a 10 EUR threshold, with the new texts; instance, app,
  instance reconfigure and server reconfigure are still rejected; the domain strings behind the
  references (*Instance Delete Approval*, *Workflow Approval*, *Cluster Delete Approval* approved;
  *Instance Approval*, *App Reconfigure Approval*, *Cluster Approval* and no type checked) decide
  the same way; both bundles carry the new keys.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --warning-mode all`, 131 tests,
  0 failures.
- **On Morpheus 9.1.0 (build 9.1.0-1)** with release candidates built from this source and a test
  integration with a 10 EUR threshold, both policies scoped to one cloud:
  - *Approve Delete*: deleting a running instance priced at 12.50 EUR per month was approved at
    once (`1.2.2-rc.1`); Morpheus hands the type over as the plugin API enum
    `INSTANCE_DELETE_APPROVAL_TYPE`. At the next monitor run (about 3 minutes) the instance was
    deleted. The enum name in the request text led to the readable label in `1.2.2-rc.2`.
  - *Approve Provision* (`1.2.2-rc.2`): a new instance at 12.50 EUR per month was rejected with
    the usual reason and became `denied` at the next monitor run (about 4 minutes).
  - Test policies, instances and integration were deleted afterwards.

### Not yet verified

- App, cluster, reconfigure, extension and workflow requests on an appliance (unit tests only).

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/cost-approval-v1.2.1...cost-approval-v1.2.2

## 1.2.1

**Fix release: a decision is no longer lost when the plugin is reloaded or Morpheus restarts
before the next monitor run.** Plugin code, provider code, option codes, plugin API 1.4.2 and
minimum appliance 9.0.2 are unchanged; no new options.

### Fixed

- **Decisions survive a jar upload, a restart and a node switch.** Morpheus 9.0.2 applies an
  approval decision only in its next `monitorApproval` call (about every 5 minutes), and that call
  receives the integration, never the request. Up to 1.2.0 the plugin kept every decision for that
  call in memory only. Uploading a new jar (every release), restarting `morpheus-ui`, or a monitor
  run on another appliance node in that window left the request `requested` for good, and on
  9.0.2 nobody can approve or deny an item owned by an approval integration by hand.
- **How:** the external request id now carries the decision: `ca-<uuid>-a` for approved,
  `ca-<uuid>-r` for rejected. Each monitor run reads the integration's open requests (references
  still `requesting` or `requested`, external id starting with `ca-`) from the internal tables
  `request` and `request_reference` with one parameterised query over the read-only report
  connection, the newest 500 such requests, and reports their decisions again with the same
  external ids, names and references as the original answer. The in-memory reports stay as the fast path for decisions Morpheus
  has not stored yet; a request found in both is reported once.
- **The external id uses the full random UUID** instead of its first 8 hex digits (32 bits), which
  made a collision likely at around 10,000 requests per integration.
- **Quiet failure.** When the open requests cannot be read, the monitor run reports the in-memory
  decisions as before and writes one warning per plugin load, then debug lines.

### Behaviour changes from 1.2.0

- External request ids change shape: `ca-1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed-r` instead of
  `ca-1b9d6bcd`; reference ids append `-0`, `-1`, ... as before.
- A decision can be reported in more than one monitor run, until Morpheus has taken it over.
  It is always the same decision.
- Each monitor run now queries the internal tables `request` and `request_reference` once per
  integration.
- Requests answered by 1.2.0 or earlier carry no decision in their id; the new monitor run leaves
  them alone, so one stuck before this upgrade stays stuck.

### Verified

- New unit tests (Spock): ids carry the decision and a full UUID; ids without a decision are
  ignored; the query is parameterised, limited to the integration and to open references; stored
  rows are rebuilt per request in order, with the response's ids and names as fallback; a decision
  taken by one provider instance is reported by a new instance with nothing in memory; a request in
  memory and stored is reported once; without a Morpheus context the monitor run still works.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --warning-mode all`, 115 tests,
  0 failures.
- **On Morpheus 9.1.0 (build 9.1.0-1), the reload case end to end**, with release candidates built
  from this source: an *Approve Provision* policy on one cloud with a 10 EUR integration threshold;
  a request for 12.50 EUR per month was rejected by `1.2.1-rc.1`, and `1.2.1-rc.2` was uploaded
  within a second, before any monitor run, so the decision was no longer in memory. Morpheus stored
  the external ids as answered (`ca-<uuid>-r`, item `ca-<uuid>-r-0`) with the item still
  `requested`; the next monitor run, about four minutes later, read it back through the new query
  (no warning in the log), the item became `rejected` and the instance `denied`. Test policy,
  instance and integration were deleted afterwards.

### Not yet verified

- The approved path after a reload (same code path, other suffix) and requests with several
  references.
- On Morpheus 9.0.2.

On Morpheus 9.1.0 (build 9.1.0-1): the release jar replaced the previous version in place (same plugin id), status `loaded`; afterwards no plugin warning in the log.

Built by the release workflow from the source at tag `cost-approval-v1.2.1`.

sha256 `5b4c504145a653f71cc7cfc7c6f9aa812d6bbce92d8abd51fb183bf85554e9eb`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/cost-approval-v1.2.0...cost-approval-v1.2.1

## 1.2.0

**The reason written into a request now follows the requesting user's Morpheus language setting
instead of always being English.** Plugin code, provider code, option codes, package, plugin API
1.4.2 and minimum appliance 9.0.2 are unchanged; no new options.

### Changed

- **Language from the user's setting, not the browser.** On Morpheus 9.0.2 the UI language comes
  from the user's own setting (*User Settings*, stored per user, e.g. `en-US`), while the locale of
  the web request that plugins see is the browser language (`Accept-Language`). A user with an
  English Morpheus UI and a German browser therefore saw German plugin content in the other FinOps
  plugins. Plugin API 1.4.2 offers no user locale, so the setting is now read from the internal
  table `user` (column `locale`) with a parameterised query over the read-only report connection.
- **Who the user is.** The approval call carries no user either. The internal reference objects
  Morpheus 9.0.2 puts into `Request.refs` point back to the internal request, which holds
  `requestByUserId`; the plugin reads it by property name, as it already does for the references.
- **Order:** the requesting user's setting, else the browser language of the web request, else
  English. Messages exist in English and German; another language gets the English text, with
  numbers in that language's format. When the requesting user cannot be determined, the texts stay
  English as before.
- **Quiet failures.** A lookup that fails writes one debug line and falls back; it never changes or
  delays the decision.

### Behaviour changes from 1.1.1

- **A requester whose Morpheus language is German gets the request name and message in German**,
  with German number formats (*Über der Kostenschwelle von 1.000,00 EUR/Monat (angefragt
  1.234,50 EUR). ...*). In 1.1.1 every text was English.
- A requester without a language setting gets the browser language of the web request, if the
  approval runs in one; otherwise English, as before.
- The plugin now also reads the internal table `user` (column `locale`, one row by id) once per
  approval request.

### Verified

- New unit tests (Spock): `en-US` setting with a German browser gives English; `de`, `de-DE`,
  `de_DE` give German; blank, missing and unparsable settings fall back to the browser; no web
  request gives English; the setting is read with `SELECT locale FROM user WHERE id = ?` and the id
  as parameter; the requesting user is found through the internal request behind the references;
  the request name and message are rendered in the resolved language and number format (English
  and German); without a requesting user nothing is looked up and the texts stay English; an
  unreadable setting never breaks the decision.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --warning-mode all`, 103 tests,
  0 failures, no deprecation warnings, one `morpheus-cost-approval-plugin-1.2.0-all.jar`.
- On Morpheus 9.0.2 (build 9.0.2-2) with a release candidate built from this source: an *Approve
  Provision* policy on one cloud with an integration threshold written as `10,00` EUR; a request
  for 12.50 EUR per month by a user whose Morpheus setting is `en-US` was rejected, the instance
  became `denied` at the next monitor run (about 4 minutes), and approval and item show the English
  reason *Above cost threshold of 10.00 EUR/month (requested 12.50 EUR). Please contact your
  provider for approval.*
- The release jar replaced the release candidate on the same Morpheus 9.0.2 appliance in place
  (same plugin id), status `loaded`, valid and enabled. Its source equals the candidate's apart
  from the version; the check with the `de` setting below ran with this jar.
- With the requesting user's Morpheus setting switched to `de`: the same request was rejected with
  the German reason *Über der Kostenschwelle von 10,00 EUR/Monat (angefragt 12,50 EUR). Bitte
  wenden Sie sich für eine Freigabe an Ihren Provider.*

### Not yet verified

- Nothing beyond the limits in the README.

Built by the release workflow from the source at tag `cost-approval-v1.2.0`.

sha256 `956b429752ea32f87bcc531592949674ba4ce1bd372ae0bf1321234dea5b3812`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/cost-approval-v1.1.1...cost-approval-v1.2.0

## 1.1.1

**Fix release: a threshold with a decimal comma is read instead of silently ignored, and a
decision can no longer be lost between two approval calls.** Plugin code, provider code, option
codes, plugin API 1.4.2 and minimum appliance 9.0.2 are unchanged; no new options.

### Fixed

- **Threshold with a decimal comma.** In 1.1.0 a threshold entered as `50,00` did not parse. It
  was skipped without a log line, and the next level applied (call options, integration) or, at the
  end, the default of 100, so a policy meant to allow 50 allowed 100. 1.1.1 reads a single decimal
  comma with one or two digits after it as a decimal point (`50,00` is 50.00). Forms that mix comma
  and dot or look like a thousands separator (`1.000,50`, `1,000`, `1,000.50`) are still not
  accepted, because guessing them could change the threshold by a factor of a thousand.
- **A threshold that does not parse is now logged.** Such a value is still skipped and the next
  level still applies, as in 1.1.0, but each one now writes one warning that names the level
  (policy, call options or integration) and the raw value, e.g. *Cost threshold approval: policy
  threshold '1.000,50' is not a non-negative amount, ignored*. Empty values stay silent.
- **Lost decision under concurrency.** `createApprovalRequest` stored its decision for the next
  `monitorApproval` run with `computeIfAbsent(...).put(...)`, while `monitorApproval` takes the
  integration's pending reports with `remove`. When the monitor run removed the map between those
  two steps, the decision went into a map nobody read any more and the request stayed `requested`.
  The decision is now stored inside `compute`, under the same lock as the `remove`, so it lands
  either in the reports the monitor run takes or in a new map for the next run. Each decision is
  still reported once, unchanged, and only to the integration it is asked about.

### Behaviour changes from 1.1.0

- A threshold such as `50,00` or `10,5` now sets the threshold to that amount. In 1.1.0 it was
  ignored and the next level or the default of 100 applied, so requests between this amount and
  the fallback that 1.1.0 approved are now rejected (and the other way round when the fallback was
  lower).
- A set but unparsable threshold writes a warning to the Morpheus log on every approval request
  that reaches that level.

### Build

- **Gradle 9.8.0** (wrapper with `distributionSha256Sum`), **Shadow 9.6.1** (`com.gradleup.shadow`)
  instead of 6.0.0, Java 11 bytecode set through the `java {}` block, and **no `mavenLocal()`**
  in either repository list, so a build no longer picks up artifacts from the local Maven
  repository. This changes nothing in the jar's contents beyond the fixes above: same manifest
  attributes, Java 11 bytecode.

### Verified

- New unit tests (Spock): decimal comma accepted (`50,00`, `50,5`, `0,99`), ambiguous and malformed
  forms rejected (`1.000,50`, `1,000`, `1,000.50`, `50,`, `,50`, `5,0,0`, `-50,00`); a comma
  threshold used instead of the default; one warning per skipped level naming the level and the
  value, none for empty or valid values; a policy threshold of `10,50` honoured end to end.
- New concurrency tests: a decision whose store is held while a monitor run takes the pending
  reports is reported exactly once; four threads creating 1,000 decisions while a monitor run loops
  report each exactly once. Both fail against the 1.1.0 code, as do the decimal comma and warning
  tests.
- Local build: JDK 17, Gradle 9.8.0, `./gradlew clean test shadowJar --warning-mode all`, 82 tests,
  0 failures, no deprecation warnings, one `morpheus-cost-approval-plugin-1.1.1-all.jar`.
- On Morpheus 9.0.2 (build 9.0.2-2): the release jar was uploaded over the previous build and
  replaced it in place (same plugin id), status `loaded`, valid and enabled; no plugin error in the
  log.

### Not yet verified

- An approval run with this jar on the appliance. The jar loads there (see above); the approval
  flow itself is unchanged from 1.1.0, which was verified live.

Built by the release workflow from the source at tag `cost-approval-v1.1.1`.

sha256 `254e91fbf2f697acc4029e73355aefa0b79b45e1fb1ff01be6aef745ecf117f3`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/cost-approval-v1.1.0...cost-approval-v1.1.1

## 1.1.0

**First public release: approve provisioning requests automatically up to a monthly cost
threshold.** Plugin code `morpheus-cost-approval-plugin`, provider code `cost-threshold-approval`,
plugin API 1.4.2, minimum appliance 9.0.2.

### What it does

- **Approval integration type *Cost Threshold Approval*** with a monthly threshold (default 100)
  and an optional ISO currency; an *Approve Provision* policy can override both.
- **At or below the threshold, same currency:** the references come back `approved`. Above it, in
  another or a mixed currency, or without a price: `rejected` at once, with the reason in the
  request name, e.g. *Above cost threshold of 20.00 EUR/month (requested 32.00 EUR). Please contact
  your provider for approval.* (German text in the i18n bundle).
- **Never adds prices across currencies.** Request currency: its own, else the references' common
  currency, else the tenant's, else the master tenant's, else USD.
- **Cent rounding** (half-up) on both sides before the comparison.
- **Reads the option values Morpheus actually stores** for approval integrations
  (`cm.plugin.<field>`, nested or flat) as well as the plain field name.
- **`monitorApproval` reports each decision (approved or rejected) once, unchanged, and only to the
  integration it is asked about.**
- Form labels and help texts in English and German; request ids start with `ca-`.

### Why it rejects instead of waiting

On 9.0.2 a `requested` item owned by an approval integration cannot be approved, denied or
cancelled by anyone (HTTP 403 *action not available for item*). A request left `requested` would
hang until the instance is deleted, so the plugin answers `rejected`
(`RequestReference.ApprovalStatus.rejected`) with the reason instead.

### Verified

- Unit tests (Spock): option lookup and precedence, currency resolution, price summing, rounding,
  every decision outcome, number format per locale, message bundles, plugin and provider codes,
  plugin description equal to the manifest `Morpheus-Description` and at most 255 characters.
- Live on 9.0.2: the plugin list shows the description that matches this behaviour
  (*... Requests above it, priced in another currency or without a price are rejected at once.*).
  The approval runs below were made with an earlier candidate that differs from this source only
  in that description; they were not repeated.
- On HPE Morpheus Enterprise 9.0.2: the integration type lists its fields as
  `cm.plugin.costThreshold` and `cm.plugin.thresholdCurrency` with field context `config`.
- Live on 9.0.2 with an *Approve Provision* policy scoped to one group: a 16.00 EUR request is
  approved under a 20 EUR threshold and provisions. Under a 10 EUR threshold the item
  becomes `rejected` in the next monitor run (here after about 5 minutes), the instance `denied`,
  and approval and item show *Above cost threshold of 10.00 EUR/month (requested 16.00 EUR).
  Please contact your provider for approval.*; nothing stays pending. Values saved over the API
  appear in the edit form and are the ones the plugin applies.
- `Request.refs` holds Morpheus' internal reference objects, not the plugin model class; they are
  read by property name (a typed closure failed live with `MissingMethodException`).
- Not yet uploaded: this release jar itself. The release candidates were replaced in place by
  later builds with the same plugin id, so 1.1.0 is expected to install over them the same way.

### Known limits

- **The approval is applied only by the next `monitorApproval` run** (about every 5 minutes on
  9.0.2), not from the `createApprovalRequest` response. The pending report is kept in memory: a
  restart in between leaves the request `requested`.
- **No human override.** On 9.0.2 approve and deny are "not available" for an item owned by an
  approval integration (API and UI). The plugin therefore rejects with a reason instead of waiting;
  to let a larger request through, raise the threshold of the integration or policy.
- `PUT /api/integrations/{id}` replaces the whole option map: send threshold and currency together.
- Reads the internal table `account` for the currency fallback; tested on 9.0.2 only.

Built by the release workflow from the source at tag `cost-approval-v1.1.0`.

sha256 `7e17f84908f917211597579a21330a387ac9f339f745aab585d8e14fe9e9b8ae`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/cost-approval-v1.1.0
