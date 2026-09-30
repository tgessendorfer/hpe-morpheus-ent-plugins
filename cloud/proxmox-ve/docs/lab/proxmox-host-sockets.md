# Proxmox sockets for licensing: the cloud type was public

The appliance reported licence consumption as a fraction of a socket for guests
running on a single-socket on-premises Proxmox host. **The cause is the cloud
type's classification, fixed in plugin 0.1.29.** The investigation that led
there is kept below, including the wrong turns, because each was arrived at
carefully and each was wrong in a way worth not repeating.

## The cause (found 2026-09-30, Morpheus 9.0.2)

The licence figures are computed in `ApplianceStatsService` in
`WEB-INF/lib/morpheus-core-<version>.jar` (searching the class files of that jar
for `hypervisorSocketCount` finds it; the earlier search here missed it). What
`getSocketStats` does:

- **`hypervisorSocketCount`** sums `maxSockets ?: 2` over compute servers in
  clouds whose type has `cloud = 'private'`, with no parent server, a server
  type with `vmHypervisor`, `containerHypervisor` or `bareMetalHost`, not
  `guestVm`, not an agent type `controller`, not a node type `kube-master`,
  de-duplicated by `uniqueId`.
- **`publicVirtualMachineCount`** counts every compute server in clouds whose
  type has `cloud = 'public'` (distinct `external_id`, plus those without one),
  whatever its server type or power state. 15 of them make a socket.
- **`privateVirtualMachineCount`** counts the same way in private clouds
  **without** such a hypervisor host; guests of a counted host are not counted
  again.
- **`hosts`** counts only compute types `docker-host`, `kube-worker`,
  `kvm-host` and `kvm-docker-host`. It has nothing to do with hypervisor
  sockets.
- **`mvmSockets`** (the *HVM Sockets* bar on the licence page) sums
  `max_sockets` over compute type `mvm-host` only.

`GET /api/zone-types?code=proxmox-ve.cloud` answered `"cloud": "public"`. The
plugin never overrode `CloudProvider.getCloudClassification()`, whose default is
`PUBLIC`, and `CloudProviderPluginManagerService.syncProvider` writes that value
into the cloud type on every plugin load. So the node counted for nothing, and
all 12 servers of the lab cloud, the node itself and two powered-off guests
included, counted as 12 / 15 = 0.8 sockets.

0.1.29 returns `PRIVATE`. Measured after uploading it: the cloud type reads
`private`, `hypervisorSocketCount` rose from 6 to 8 (the node, at the default
of 2), `publicVirtualMachineCount` fell from 12 to 0, and `sockets` went from
8.13 to 9.33, exactly as the code predicts. What remains is `maxSockets`: the Plugin API has no
such field on `ComputeServer`, so a node counts as Morpheus's default of 2
sockets, whatever the hardware.

## Where the UI shows it

*Administration › Settings › License* shows a usage bar only for what the
installed licence key limits. From the page template
(`admin/settings/_license.gsp`, 9.0.2):

```
showSockets    = license.maxSockets > 0
showHosts      = license.maxHosts > 0
showMvmSockets = !showSockets
```

So the socket bar and the hypervisor list (`_socketDetails.gsp`) appear only
with a socket-based key. A key without `maxSockets`, like the lab's, shows the
*HVM Sockets* bar instead, which counts `mvm-host` servers and never a Proxmox
node. The counts are computed either way; `GET /api/license` → `currentUsage`
has them (`hypervisorSocketCount`, `sockets`, `mvmSockets`).

## Verified in the UI (2026-09-30, 9.0.2, plugin 0.1.29)

With a socket-limited key stacked on the lab's key, the licence page shows the
*Sockets* bar: 9.333 used. *View Details* opens *Socket Usage* with *Host
Sockets: 8*, *Public Cloud VM Sockets: 0*, and a host list in which the Proxmox
node appears as type *Proxmox VE Node* with *Default (2)* sockets.

Two things on that dialog are Morpheus's, not the plugin's:

- **The host list shows *Default (2)* for every host**, including two HPE VM
  hosts whose `maxSockets` is 1 in `GET /api/servers`. The summary counts them
  as 1: 2 + 2 + 2 + 1 + 1 = 8. Only the rows are wrong.
- **The dialog has no section for private-cloud VMs without a hypervisor host**,
  so its figures (8 + 0) do not add up to the bar (9.333); the difference is
  20 such VMs at 15 per socket.

Stacking keys combined `maxSockets` but left `maxMvmSockets` empty although the
new key carries one; with a socket limit the page hides the HVM bar anyway.

## What the appliance reported (9.0.1, before 0.1.29)

From `GET /api/license` → `currentUsage`, on a lab appliance at 9.0.1 backed by
one Proxmox host with one physical socket:

```
sockets                          0.2667
hosts                            0
hypervisorSocketCount            0
privateVirtualMachineCount       0
privateVirtualMachineSocketCount 0
publicVirtualMachineCount        4        -> 4 / 15 = 0.2667
publicVirtualMachineSocketCount  0.2667
```

Four on-premises VMs on a one-socket host report as 0.2667 sockets rather than
1. The arithmetic is the appliance's own and it is correct; the classification
is what is wrong. The direction matters: this **under**-reports against the
socket model, so a customer part-way through integrating a hypervisor sees a
reassuringly small number that is not what they will be billed against.

## Correction 1: `GET /api/hosts` does not exist

This document previously said "`GET /api/hosts` returns no hosts at all" and
built everything on it. That endpoint is not a Morpheus endpoint:

```
$ curl -H "Authorization: Bearer $TOKEN" https://<appliance>/api/hosts
{"success":false,"msg":"Unable to find api endpoint GET /api/hosts"}
```

An empty result was read out of a 404-shaped reply: a reply that is not an
answer, treated as one. **The endpoint is `/api/servers`.**

## Correction 2: the host *is* registered

`/api/servers` has always listed it:

```
id=1  proxmox  type=proxmox-ve-node  server_type=hypervisor  power_state=on
```

and the type is flagged as a hypervisor:

```sql
select code, vm_hypervisor+0 from compute_server_type where code like 'proxmox%';
proxmox-lxc-container       0
proxmox-qemu-vm             0
proxmox-qemu-vm-unmanaged   0
proxmox-ve-node             1
```

Note the `+0`. `vm_hypervisor` is a `bit(1)`, and `mysql -B` prints true as an
unprintable byte next to a literal `\0` for false — read by eye, the column
gives exactly the wrong answer. Cast it.

So `HostSync` runs, creates the host, and types it correctly. None of the three
possibilities this document previously listed — "not running, failing quietly,
or registering hosts without socket counts" — was the whole story: it was the
third, and the document could not see it because it was asking a URL that does
not exist.

## Fixed in plugin 0.1.14: socket topology

`HostSync` hardcoded `coresPerSocket: 0` with an accurate comment — `/nodes`
really does not report socket topology. But `/nodes/<node>/status` does:

```json
"cpuinfo": { "sockets": 1, "cores": 4, "cpus": 4 }
```

0.1.14 makes that call per node and sets `coresPerSocket = cores / sockets`.
Measured before and after a cloud sync:

| `compute_server` | before | after |
|---|---|---|
| `cores_per_socket` | 0 | **4** |
| `max_cores` | 4 | 4 |

The record now describes the hardware. See
[`RELEASE-NOTES.md`](../../RELEASE-NOTES.md).

## `maxSockets`: what the plugin still cannot set

`coresPerSocket` is not the field the licence counts. Morpheus's own socket view,
`WEB-INF/classes/admin/settings/_socketDetails.gsp`, reads:

```gsp
<g:if test="${server.maxSockets}">
    <g:formatNumber number="${server.maxSockets}" type="number"/>
```

`maxSockets`, and it iterates a `hypervisors` collection the controller builds.
There is a `compute_server.max_sockets` column, and on this node it is `NULL`.

**And the Plugin API 1.4.2 has no setter for it.** Checked with `javap` against
the pinned jar rather than the versionless javadoc site:

```
$ javap ... com.morpheusdata.model.ComputeServer | grep -i socket
  protected java.lang.Long coresPerSocket;
  public java.lang.Long getCoresPerSocket();
  public void setCoresPerSocket(java.lang.Long);
```

`coresPerSocket` and nothing else. Searching every model class in the API jar
finds `maxSockets` in exactly one place — `ApplianceLicenseData`, the licence
entitlement — and `sockets` on `ApplianceLicenseUsage`. Neither is on
`ComputeServer`.

So the field a plugin can write and the field the licence reads are different
fields, which is why three plausible fixes in a row changed nothing.

## Four explanations tested and ruled out

Each was measured on the running appliance, not reasoned about:

| tested | result |
|---|---|
| host not registered | **false** — exists, `proxmox-ve-node`, `vm_hypervisor=1` |
| `coresPerSocket = 0` | **real gap, fixed in 0.1.14** — stored as 4; figures unchanged |
| `managed = 0` | set to 1, sync run, value survived it; figures unchanged |
| `max_sockets = NULL` | set to 1 directly; figures unchanged |

Both database changes were reverted; the appliance is back as found
(`managed=0`, `max_sockets=NULL`, `cores_per_socket=4`).

Through every one of those, `sockets` stayed at exactly `0.2666666667` and
`hosts` at `0`. This document then called `hosts: 0` the gate. **That was the
third wrong claim**: `hosts` counts only Docker, Kubernetes and KVM hosts, and
none of the four changes could matter while the cloud type was public. See
*The cause* above.

## What to ask HPE

How a cloud plugin is expected to report a host's socket count. Licensing reads
`compute_server.max_sockets`, and the Plugin API exposes only `coresPerSocket`
on `ComputeServer`, so every plugin-provided hypervisor host counts as 2
sockets.

## What no longer blocks this

**The Gradle build works.** It resolves only from Maven Central and
plugins.gradle.org. Behind a TLS-intercepting proxy, see *Building* in
[`proxmox-cloud.md`](proxmox-cloud.md#building).

**The build is reproducible from scratch**: the source is on `main` in this
repository, so `./gradlew shadowJar` is the whole build. The base is
ThePoshArchitect's fork commit `5841b29`, not HPE upstream `main` alone, because
upstream lacks the Proxmox VE 9 fixes that commit carries.
