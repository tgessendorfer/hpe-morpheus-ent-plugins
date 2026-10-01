# Cost Threshold Approval plugin — release notes

One section per version, newest first. The same text is the body of the matching
[GitHub release](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `cost-approval-v<version>`, where the
shaded `-all.jar` is attached.

---

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

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/commits/cost-approval-v1.1.0
