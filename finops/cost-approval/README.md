# Morpheus Cost Threshold Approval Plugin

An `ApprovalProvider` plugin for HPE Morpheus Enterprise 9.0 that adds **Cost Threshold Approval**
as an approval integration type. Attached to an *Approve Provision* policy, it approves requests
whose monthly price is at or below a threshold and rejects everything else at once, with the
reason in the request (Morpheus 9.0.2 offers no manual decision for such requests; see *Known limits*).

> **Independent community project.** Not an official HPE product, and neither endorsed by nor
> affiliated with Hewlett Packard Enterprise. See [NOTICE](NOTICE).

## What it does

| Case | Result |
|---|---|
| Monthly price at or below the threshold, same currency | references `approved` in the response |
| Monthly price above the threshold | `rejected`, reason in the request name |
| Request currency differs from the threshold currency, or references in mixed currencies | `rejected`; prices in different currencies are never added |
| Request carries no price at all, or one of its references has none (since 1.2.3) | `rejected` |
| The threshold or threshold currency that applies is set but cannot be read (since 1.2.4) | `rejected`; a looser level or the default is never used instead |

No request is ever left `requested`: on 9.0.2 nobody could approve or deny it by hand, and it would
stay pending until the instance is deleted.

Prices and thresholds are rounded half-up to cents before they are compared: 50.004 passes a
threshold of 50, 50.005 does not. The request name shown in Morpheus explains the decision:

| Case | Request name (English) |
|---|---|
| Approved | *Approved automatically (12.50 EUR <= 50.00 EUR per month)* |
| Above the threshold | *Above cost threshold of 20.00 EUR/month (requested 32.00 EUR). Please contact your provider for approval.* |
| Currency differs | *Currency differs from the cost threshold (requested 16.00 USD, threshold 20.00 EUR/month). Please contact your provider for approval.* |
| No price | *No monthly price for this request (threshold 20.00 EUR/month). Please contact your provider for approval.* |
| Threshold misconfigured | *Cost threshold misconfigured: the field "Monthly Cost Threshold" of the policy has an invalid value. An administrator must correct it. Please contact your provider for approval.* |

The German texts are in `messages_de.properties`, e.g. *Über der Kostenschwelle von 20,00 EUR/Monat
(angefragt 32,00 EUR). Bitte wenden Sie sich für eine Freigabe an Ihren Provider.* External request
ids start with `ca-`.

**Language of the request name** (since 1.2.0): the Morpheus language setting of the user who
asked (*User Settings*, the same setting the Morpheus UI follows), not the browser language. A user
without a setting gets the browser language of the web request, and English when there is none.
When the requesting user cannot be determined, the texts stay English as in 1.1.x. Texts exist in
English and German; any other language gets the English text, with numbers formatted for that
language. The text is written once, when the request is decided, and is not translated again for
other viewers.

## Who sees it

Approval integrations and policies are administered where your role allows it (usually the master
tenant). The plugin itself shows no pages; it only answers Morpheus' approval calls.

## Options

Integration (*Administration > Integrations > New > Cost Threshold Approval*):

| Option code | Field | Meaning |
|---|---|---|
| `cost-threshold-approval-threshold` | Monthly Cost Threshold | Default `100`. Requests up to this monthly price are approved automatically |
| `cost-threshold-approval-currency` | Threshold Currency | ISO 4217 code. Empty: the currency of the request |

Policy (per *Approve Provision* policy that uses the integration):

| Option code | Field | Meaning |
|---|---|---|
| `cost-threshold-approval-policy-threshold` | Monthly Cost Threshold | Overrides the integration's threshold |
| `cost-threshold-approval-policy-currency` | Threshold Currency | Overrides the integration's currency |

Order of precedence: policy, then options passed with the call, then integration, then the default.
The first level that sets a value (anything but empty) decides, for the threshold and for the
currency each; the levels below it are not read. A policy that sets only its threshold therefore
takes the currency from the call options or the integration, and an unreadable currency there
rejects the request (see below).

**Which requests are checked** (since 1.2.2): provisioning of an instance, app or cluster (or a
server added to a cluster) and reconfigure of an instance, server or app. Every other request type
(delete, expiration or shutdown extension, workflow run, cluster delete or remove) adds no monthly
cost and is approved without a check, with *Approved automatically: the cost threshold applies to
provisioning, app and reconfigure requests only* and the type in the request name. A request whose
type cannot be read is checked.

**Threshold format:** a plain non-negative number such as `50` or `50.00`. A single decimal comma
with one or two digits after it is accepted too (`50,00` is 50.00). Forms that mix comma and dot
or look like a thousands separator (`1.000,50`, `1,000`) are not guessed, and neither is a value
with more than 12 integer digits, more than 32 decimal places or more than 32 characters
(`1e999999999`, `1000000000000`). **Threshold currency format:** three letters (`EUR`, `eur`);
`euro` or `E1R` cannot be read.

**A value that cannot be read is a misconfiguration** (since 1.2.4). When the first level that
sets the threshold or the currency holds such a value, the request is rejected with *Cost
threshold misconfigured* and the field and level in the request name (see the table above):
using a looser level or the default instead could approve what the stricter level was set to
stop. Each such value
writes one warning to the Morpheus log that names the level and the raw value (cut after 40
characters), for example
`Cost threshold approval: policy threshold '1.000,50' is not a non-negative amount of at most 12 integer digits, request rejected (threshold misconfigured)`
or
`Cost threshold approval: policy threshold currency 'euro' is not a three-letter ISO 4217 code, request rejected (threshold misconfigured)`.
Up to 1.2.3 such a value was skipped and the next level applied. Requests that are not checked
(delete, extension, workflow) are approved as before; they never read the threshold.

**Currency of a request:** `Request.currency`, else the one currency all references share, else
the currency of the integration's tenant, else of the master tenant, else `USD`.

Morpheus 9.0.2 lists the integration fields as `cm.plugin.costThreshold` and
`cm.plugin.thresholdCurrency`. The plugin reads the value nested under `cm.plugin`, as the flat
dotted key, and under the plain field name, so integrations created in the UI and over the API both
work.

Form labels and help texts are in English and German (`src/main/resources/i18n`); Morpheus renders
them itself. Texts written into a request follow the requesting user's language setting (see above).

## Known limits

- **Decision arrives with the monitor run.** Morpheus 9.0.2 applies the decision (approval and
  rejection) only when it next calls `monitorApproval` (about every 5 minutes), not from the
  `createApprovalRequest` response. `monitorApproval` receives only the integration, never the
  request (plugin API 1.4.2). Since 1.2.1 the external request id carries the decision
  (`ca-<uuid>-a` approved, `ca-<uuid>-r` rejected), and each monitor run reads the integration's
  open requests from the internal tables `request` and `request_reference` and reports their
  decisions again, so a jar upload or restart in between no longer leaves the request
  `requested`. Requests answered by 1.2.0 or earlier carry no decision in their id and are not
  picked up.
- **No human override.** On 9.0.2, approve and deny are "not available" for an approval item owned
  by an approval integration, over the API and in *Operations > Approvals*. That is why the plugin
  rejects instead of waiting: the requester sees the reason and asks the provider, who can raise the
  threshold (integration or policy) or provision outside this policy.
- `PUT /api/integrations/{id}` replaces the whole option map: send threshold and currency together.
- The tenant and master tenant currency fallback reads the internal table `account` through the
  read-only report connection.
- **Requesting user and language.** The approval call of plugin API 1.4.2 carries no user. The
  plugin finds the requesting user through the internal request behind `Request.refs`
  (`requestByUserId`, Morpheus 9.0.2) and reads the language setting from the internal table `user`
  (column `locale`) through the read-only report connection. If either is not available, the texts
  are English (no user) or follow the browser (no setting); a failed lookup writes one debug line
  and never affects the decision.

## Compatibility

| Plugin version | Plugin API | Min appliance | Tested appliance | Internal DB tables read |
|---|---|---|---|---|
| [1.2.4](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/cost-approval-v1.2.4) | 1.4.2 | 9.0.2 | 9.1.0 (1.2.4-rc.1) | `account` (`currency`, `master_account`), only when no currency is known; `user` (`locale`) of the requesting user; `request`, `request_reference` (open decisions) |
| [1.2.3](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/cost-approval-v1.2.3) | 1.4.2 | 9.0.2 | 9.1.0 (1.2.2) | `account` (`currency`, `master_account`), only when no currency is known; `user` (`locale`) of the requesting user; `request`, `request_reference` (open decisions) |
| [1.2.2](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/cost-approval-v1.2.2) | 1.4.2 | 9.0.2 | 9.0.2 (1.2.0, master tenant) | `account` (`currency`, `master_account`), only when no currency is known; `user` (`locale`) of the requesting user |
| [1.1.1](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/cost-approval-v1.1.1) | 1.4.2 | 9.0.2 | 9.0.2 (1.1.0; 1.1.1 loads, not run) | `account` (`currency`, `master_account`), only when no currency is known |
| [1.1.0](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/cost-approval-v1.1.0) | 1.4.2 | 9.0.2 | 9.0.2 | `account` (`currency`, `master_account`), only when no currency is known |

Queries internal tables, tested on 9.0.2 and 9.1.0 only, may break on upgrade.

## Building

```bash
./gradlew clean test shadowJar
```

JDK 17 (Groovy 3.0.9 does not run on JDK 21). The plugin jar is `build/libs/morpheus-cost-approval-plugin-<version>-all.jar`.
Upload it under *Administration > Integrations > Plugins*.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
