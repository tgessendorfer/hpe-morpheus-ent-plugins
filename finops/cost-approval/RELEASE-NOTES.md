# Cost Threshold Approval plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `cost-approval-v<version>`, where the
shaded `-all.jar` is attached.

---

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

### Not yet verified

- Live on the appliance: 1.1.1 has not been uploaded or run against Morpheus 9.0.2 yet. The
  approval flow itself is unchanged from 1.1.0, which was verified live.

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
