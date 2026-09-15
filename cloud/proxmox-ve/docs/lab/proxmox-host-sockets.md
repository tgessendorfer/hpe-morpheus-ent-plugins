# Proxmox sockets are not counted, and the reason is not what this file said

The appliance reports licence consumption as a fraction of a socket for guests
running on a single-socket on-premises Proxmox host. This records what was
measured, what has been fixed, and the one thing still open.

**Two claims in the previous version of this document were wrong.** They are
corrected below rather than deleted, because both were arrived at carefully and
both were wrong in a way worth not repeating.

## What the appliance reports

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

## The finding: the plugin cannot fix this

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
`hosts` at `0`. `hosts: 0` is the gate — the controller's `hypervisors`
collection is empty — and nothing in the host record explains why. The figures
are computed rather than stored: `appliance_license` has no host or socket
columns, only `date_created` and `last_updated`. The computing code is not
greppable as a plain string in `WEB-INF/classes` or the shipped jars.

## What to ask HPE

The question is now specific enough to be worth asking, and does not need this
lab to reproduce:

> A Proxmox VE host is registered as a ComputeServer of type `proxmox-ve-node`,
> whose ComputeServerType has `vm_hypervisor = 1`, with `server_type =
> hypervisor` and `power_state = on`. `currentUsage.hosts` is still 0, so its
> guests are counted as public-cloud workloads at 15:1. What makes a
> ComputeServer count as a hypervisor host for licensing — and since
> `_socketDetails.gsp` reads `server.maxSockets`, how is a cloud plugin expected
> to populate it, given the Plugin API exposes only `coresPerSocket` on
> ComputeServer?

## What no longer blocks this

**The Gradle build works.** It resolves only from Maven Central and
plugins.gradle.org. Behind a TLS-intercepting proxy, see *Building* in
[`proxmox-cloud.md`](proxmox-cloud.md#building).

**The build is reproducible from scratch**: the source is on `main` in this
repository, so `./gradlew shadowJar` is the whole build. The base is
ThePoshArchitect's fork commit `5841b29`, not HPE upstream `main` alone, because
upstream lacks the Proxmox VE 9 fixes that commit carries.
