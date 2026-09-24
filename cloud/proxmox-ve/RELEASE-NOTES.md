# Proxmox VE plugin — release notes

Lab builds of the Proxmox VE cloud plugin. The source is
`cloud/proxmox-ve` in this repository: HPE upstream `main`, then `ThePoshArchitect/morpheus-proxmox-ve-plugin`
commit `5841b29`, then the lab commits. Jars are attached to GitHub Releases
tagged `proxmox-ve-v<version>-lab`.

Build with `./gradlew shadowJar`. Every version below is a
`Plugin-Version` in the jar's manifest, which is the only thing the appliance
reports — it never reports a digest, so a rebuild that keeps its number is
indistinguishable from the build before it. That is why each change here gets a
new number, and why the digests are recorded.

---

## 0.1.28

**Proxmox networks can be edited, and edits survive the next refresh.**

- **`networkServer: Cannot be blank` is gone for existing clouds.** 0.1.24
  registers a network server when a cloud is added, but a cloud created before
  that has an empty `cloud.networkServer`, and its networks were saved without
  one, so every edit in *Infrastructure › Network* failed. The network sync now
  looks the server up by type (`proxmox-ve.network`) and cloud when the cloud
  carries none, uses it for new networks, and attaches it to existing networks
  that lack one.
- **The sync no longer blanks operator settings.** Each refresh wrote
  `dnsPrimary`, `dnsSecondary` and `dhcpServer` back to their defaults on every
  existing network, so a DNS server entered in the UI vanished within minutes.
  Proxmox knows none of these values; they are set only when a network is
  first created and are the operator's afterwards. Name, CIDR, gateway,
  netmask and subnet still follow Proxmox.

## 0.1.27

**HPE's Morpheus OS images are offered for provisioning, with nothing to
upload.** The plugin now declares seven system images through scribe
resources (`src/main/resources/scribe/proxmox-virtual-images.scribe`):
Morpheus Debian 12 and 13, Ubuntu 22.04 and 24.04, Rocky 9 and 10,
AlmaLinux 10, the qcow2 builds HPE publishes for KVM-type clouds with
cloud-init and the agent prerequisites baked in. They appear under
*Library › Virtual Images* with the filter *System*, and in the provisioning
wizard's *Image* list as `<name> (Download on first use)`.

- **First use downloads on the node.** A system image has a `remotePath`
  and no file in Morpheus. On the first provision the node itself fetches the
  qcow2 with `wget` into `/var/lib/vz/template/qemu` (0.8 to 1.5 GB, so the
  node needs internet access; the cloud's API proxy does not apply to it),
  the plugin imports the disk into a new template named after the image
  (`MorpheusDebian1220260203`) and records the template as the image's
  location. Every later provision clones that template. A partial download
  is removed so the next attempt starts over.
- **The image list distinguishes the three kinds:** synced Proxmox templates
  by name, uploaded qcow2 files as `(To Be Uploaded)`, system images as
  `(Download on first use)`; once a system image has its template, it is
  listed by name like a synced template.
- Only images that answered HTTP 200 on 2026-09-21 are declared. AlmaLinux 9,
  Debian 11, Ubuntu 20.04 and Rocky 8 were left out (404, or superseded).
  HPE may move or retire these files; a dead link fails at the first
  provision with the download error in the instance's status.

Verified on HPE Morpheus Enterprise 9.0.2 with Proxmox VE 9.2.20: after the
plugin load the seven images exist (`ScribeService - import resource new`),
the wizard's image list shows them; a provision from *Morpheus Debian 12
20260203* downloaded the image in 62 seconds, created template 100, cloned
VM 102 and reached `running` with its address and the agent 3.3.0 after
**2 minutes 16 seconds**; a second provision from the same image reused the
template and reached `running` after 65 seconds. The test suite passes
(31 tests). 0.1.25 and 0.1.26 were lab iterations of this build, uploaded to
the appliance only. Not verified: the other six images, and what
`VirtualImageLocationSync` makes of a template it did not create itself.

Built by the release workflow from the source at tag `proxmox-ve-v0.1.27-lab`.

sha256 `ea9f79d8415a23b0bfd82090353cc803735b420ef625d0ed60ff94cf4b0cae38`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/proxmox-ve-v0.1.24-lab...proxmox-ve-v0.1.27-lab

---

## 0.1.24

**Datastores follow the resource pool, the network provider registers its
server, and static addresses, resizes and pools are verified.** The last
open items of the upstream TODO list that a single-node lab can verify.

- **Datastore filter by resource pool.** A Proxmox pool can hold storages as
  members. `DatastoreSync` now records that membership on each datastore
  (`assignedZonePools`) and the cloud provider filters the wizard's datastore
  list through `filterDatastores`: with a pool selected that has at least one
  storage member, only its storages are offered; with no pool, or a pool
  without storage members, the list is unchanged. Verified in the wizard:
  `local-lvm` and `usb` without a pool, only `local-lvm` with the pool that
  holds it. `listProxmoxPools` reports a failed pool detail call instead of
  a `null` entry, and the datastore sync skips a failed pool listing.
- **The network provider registers its server.** `initializeProvider`
  created the `NetworkServer` without an account; Morpheus rejected it with
  `NetworkServer.account rejected value [null]`, the cloud had no network
  server, and every network edit (an IP pool, DHCP off, DNS) failed with
  `networkServer: Cannot be blank`. The server now carries the cloud's
  account and id. Morpheus calls `initializeProvider` when a cloud is
  created; the lab cloud predates the fix and still has no network server,
  so its networks cannot be edited until it is re-created. Verified only that
  a cloud save no longer logs the error.
- **Quieter waiting.** The guest agent poll runs every 15 seconds instead of
  10, which halves the `HttpApiClient` warnings Proxmox's 500 answers cause
  before the agent runs. Two option-source lines logged at ERROR
  (`FOUND n VirtualImages...`) are debug lines now.
- **Erratum for 0.1.21:** the Debian 12 cloud image does name its NIC
  `eth0`, not `ens18` as those notes claimed; the `ipconfigN` approach stays,
  because it does not depend on the guest's naming at all.

Verified on HPE Morpheus Enterprise 9.0.2 with Proxmox VE 9.2.20, on top of
0.1.22's checks: a **static address** given per interface (`ipMode: static`,
`192.168.0.240`) reached the guest as `ipconfig0: ip=192.168.0.240/24,
gw=192.168.0.1`, the guest agent and Morpheus both report it; a **resize**
from 1 vCPU / 1 GB / 5 GB to 2 vCPU / 2 GB / 6 GB changed `cores`, `memory`
and the disk on the running VM and kept the NIC's MAC address; provisioning
**into a resource pool** places the VM in it; **`noAgentInstall`** reaches
`running` with its address. The test suite passes (31 tests, two new).
0.1.23 was a lab iteration of this build, uploaded to the appliance only.

Built by the release workflow from the source at tag `proxmox-ve-v0.1.24-lab`.

sha256 `4f32a19b132e5068c7f3a4bdf349dac64270e73c763c709f7e832ecd7c646f81`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/proxmox-ve-v0.1.22-lab...proxmox-ve-v0.1.24-lab

---

## 0.1.22

**The cloud's API proxy reaches Proxmox, and the upstream TODO list is
worked through.** Nothing in 0.1.21's provisioning path changed.

- **Proxy support.** The cloud's *API Proxy* setting was ignored: no request
  to Proxmox carried it. `getAuthConfig` now hands the proxy to every call,
  and every request goes through one helper that applies it. Verified only
  that a cloud without a proxy behaves as before; no proxy was available in
  the lab.
- **No password hashes in the log.** `runWorkload` and `validateWorkload`
  logged the whole workload, its options and the cloud-init user-data at
  debug level, and `destroyVM` its request headers with the session cookie.
  The user-data holds the password hashes of the created users. Those lines
  are gone; the run is still traceable through the info lines added in
  0.1.21.
- **Upstream TODOs checked in the lab** (see `TODO.md`): the instance wizard
  shows the plugin's validation errors under the network and image fields,
  and lets a request through without a node when the cloud has one; a synced
  resource pool is selectable and the VM is created in it; a provision with
  `noAgentInstall` reaches `running` with its address, and Morpheus's
  `determineSshRoute` sees that address instead of an empty host list. The
  README states the provisioning requirements (root SSH, template with guest
  agent and virtio NIC, network through `ipconfigN`).

Verified on HPE Morpheus Enterprise 9.0.2 with Proxmox VE 9.2.20 with this
build: provisioning into a resource pool and provisioning with
`noAgentInstall`, both reaching `running` with an address within a minute;
instance deletion removed VM, server record and snippets. The test suite
passes (29 tests).

Built by the release workflow from the source at tag `proxmox-ve-v0.1.22-lab`.

sha256 `c88e77033eb9d1604d99fb4da4233df6d407313ac684183a8844cad938b4faac`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/proxmox-ve-v0.1.21-lab...proxmox-ve-v0.1.22-lab

---

## 0.1.21

**Provisioning checks the node's SSH login before it clones, and a provisioned
VM starts and reports its address.** Lab provisions on Morpheus 9.0.2 ended in
`Provisioning failed: com.jcraft.jsch.JSchException: session is down`, with a
stopped VM left on the node and no cloud-init data in it. The cause was the SSH
login Morpheus uses on the hypervisor host (the host's SSH user and password
under the cloud's *Hosts* tab): the plugin writes the cloud-init snippets and
runs `qm set` over that login *after* the clone, so a login that fails surfaced
late, with an opaque message, and left the VM behind.

- **SSH preflight.** Before the clone, the plugin runs `qm list` on the node
  with the host's SSH credentials. A failed login now fails the provision at
  once with `SSH to Proxmox node '<node>' as <user>@<host> failed: ...`, nothing
  is created on the node, and the message is stored on the server record. The
  SSH user must be root: `qm` and `pvesm` run without sudo.
- **Snippets storage.** `cicustom` needs a storage that offers `snippets`, and
  a fresh Proxmox install has none (`local` carries iso, vztmpl and backup).
  When `local` lacks it, the plugin adds it with `pvesm set`, then creates
  `/var/lib/vz/snippets`.
- **The NIC keeps the template's model; a new NIC is virtio.** After the clone
  the plugin rewrote every NIC as `bridge=<bridge>,model=e1000e`, which also
  gave it a new MAC address. Debian's cloud kernel ships no e1000e driver: the
  lab guest booted, its guest agent answered, and it listed `lo` as its only
  interface. The NIC now keeps what the template gave it (model, MAC address
  and other options; only the bridge follows Morpheus), and a NIC added on
  resize is `virtio` for Linux guests and `e1000e` for Windows, which has no
  virtio driver until one is installed. A resize no longer changes MAC
  addresses either.
- **Cloud-init network through Proxmox.** The plugin no longer passes
  Morpheus's network snippet, which names the interface (`eth0`) and so
  depends on the guest's interface naming. It sets Proxmox's own `ipconfigN`
  per interface instead (`ip=dhcp`, or `ip=<address>/<prefix>,gw=<gateway>`
  for a static address, with `nameserver` and `searchdomain` from the
  network), and Proxmox generates a network-config that matches the NIC by
  MAC address. `cicustom` carries the user-data only. An empty user-data no
  longer throws a `NullPointerException` in the SFTP upload.
- **Host SSH credentials follow the cloud form.** `HostSync` set the node's SSH
  user and password only when it created the host record, never on update. A
  host created with other values, or edited by hand, kept them; the lab host
  held the Morpheus login name as SSH user, which does not exist on the node.
  Every sync now writes the cloud's *Node SSH Username* and *Password* to the
  host when both are set, and logs the change.
- **Start and address.** The start call's result is checked; a failure ends
  the provision with Proxmox's message. When the VM's QEMU guest agent is
  enabled, the plugin waits **up to 10 minutes** for the agent to report an
  IPv4 address, stores it on the server (`internalIp`, `externalIp`,
  `sshHost`) and returns it in the provision response. `getServerDetails`,
  which Morpheus calls while it waits for the network, now returns that
  address too, instead of nothing; it waits up to 60 seconds.
- **Node default.** `config.proxmoxNode` may be omitted when the cloud has
  exactly one active node: validation and provisioning use that node and
  record it on the server. With several active nodes the request still needs
  the node, and `Please select a ProxMox node` stays.
- **Deleting a failed instance no longer throws.** `removeWorkload` read the
  node from the server's parent server, which a provision that failed before
  the clone never set, and deleting such an instance logged
  `NullPointerException: Cannot get property 'name' on null object`. A server
  without a VM id is now nothing to destroy; otherwise the node comes from the
  parent server, the server config or a cluster lookup, and a failed destroy
  call returns Proxmox's message instead of its raw response. After the
  destroy, the VM's cloud-init snippets are removed from the node too: the
  user-data holds the password hashes of the created users, and 0.1.16 left
  it in `/var/lib/vz/snippets` for ever.
- **A failed listing no longer empties the cloud.** During a lab refresh the
  node's network listing failed once, with
  `No signature of method: [B.getAt()` (the HTTP client handed the body over
  unparsed), `listProxmoxNetworks` logged the warning and reported the
  cluster as having no networks, and `NetworkSync` deleted the bridge network
  `vmbr0`. The next provision failed with `Please select a network`, and the
  refresh recreated the network under a new id, which breaks anything that
  referenced the old one. Every listing now reports the failure instead of an
  empty result (networks per node, cluster resources for VMs, templates and
  containers, storages, pools), the network, VM and template syncs skip a
  failed listing with a warning, and an unparsed body is read as JSON.

Behaviour changes from 0.1.16: a provision on a cloud whose host has wrong SSH
credentials fails within seconds and creates no VM, where 0.1.16 left a
stopped VM. A provision of a VM with the guest agent enabled takes up to
10 minutes longer when the guest never reports an address. A NIC keeps the
template's model where 0.1.16 forced `e1000e`, and the cloud form's node SSH
account overwrites the host record on every sync.

The plugin code stays `proxmox-ve`, so the jar upgrades an installed 0.1.16 or
earlier. 0.1.17 to 0.1.20 were lab iterations of this change, uploaded to the
lab appliance only and never released: 0.1.17 lacked the NIC, network and
host credential changes, 0.1.18 the deletion fix, 0.1.19 the snippet removal, 0.1.20 the listing guards.

Verified on HPE Morpheus Enterprise 9.0.2 with Proxmox VE 9.2.20, with this
build and its lab iterations: `POST /api/instances` with the Debian 12 cloud
template (`debian12-morph`, `agent: 1`, cloud-init), the 1 vCPU / 1 GB plan, a
5 GB root volume, the DHCP bridge network and **no `proxmoxNode`** reached
`running` with its address **13 seconds after the start** (35 seconds after
the request), the Morpheus agent 3.3.0 checked in, the server carries the
address as `internalIp`, `externalIp` and `sshHost` and the node in its
config, and port 22 answers from the appliance. On the node the VM has
`net0: virtio=<mac>,bridge=vmbr0`, `ipconfig0: ip=dhcp`, `cicustom: user=...`
and `local` gained `snippets`. Deleting the instance removed the VM, the
server record and the snippets within 8 seconds. With the host record's wrong
SSH user, the same request failed in 7 seconds with the preflight message and
created no VM. With 0.1.17 the same VM booted with an `e1000e` NIC and
reported only `lo`. The listing failure that deleted `vmbr0` happened once
with 0.1.20 and was not reproduced with the guard in place. The test suite
passes (29 tests, twelve new). Not verified: a static address from an IP pool,
a Windows guest, a multi-node cluster, a resize.

Built by the release workflow from the source at tag `proxmox-ve-v0.1.21-lab`.
The lab appliance runs a local build of the same commit, which has another
digest.

sha256 `2ff0aee3180f5936d00cac0e9e00671e13e06d3a5bbf6b417353a57001c6faa3`

**Full Changelog**: https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/compare/proxmox-ve-v0.1.16-lab...proxmox-ve-v0.1.21-lab

---

## 0.1.16

**The plugin list links to the plugin's source.** The plugin moved, with its full
history, from the GitHub fork `tgessendorfer/morpheus-proxmox-ve-plugin` into
`cloud/proxmox-ve` of
[tgessendorfer/hpe-morpheus-ent-plugins](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/cloud/proxmox-ve).
The plugin list showed no website for this plugin; it now links there.
`Morpheus-Repo` in the jar's manifest names that folder too, instead of HPE's
upstream repository, because this build is made from it. Nothing else changed
from 0.1.15.

The plugin code stays `proxmox-ve`, so the jar upgrades an installed 0.1.15 or
earlier. An appliance still on 0.1.14 also gets the 0.1.15 change below.

This is the first lab build made by the release workflow, with JDK 17, from
exactly the source at tag `proxmox-ve-v0.1.16-lab`. Its digest is in the
release's `SHA256SUMS`.

Verified on HPE Morpheus Enterprise 9.0.1 with Proxmox VE 9.2.10, upgraded
from 0.1.14: the plugin loads, the plugin list links to the new repository, and
the cloud synced with status `ok`, with its node, VMs and LXC containers. The
test suite passes, and the jar's manifest and plugin class name the new
repository.

sha256 `1c8e3aadac1b6258712b89ced2c182ff2e6b548b3f88ec91718a45829a3c7cf1`

---

## 0.1.15

**No lab address in the plugin.** The help text under *Proxmox API URL* used a
lab address as its example. It now reads `https://proxmox.example.com:8006`,
matching the placeholder. Nothing else changed from 0.1.14.

Jars for 0.1.14 and earlier are not published, because each of them carries
that address.

sha256 `89b4f1ca5d6936adca0d8fcc51e740d9b352dea0ff5cb38d71f877dae7e8660d`

---

## 0.1.14

**Reads socket topology from the hypervisor.**

`HostSync` hardcoded `coresPerSocket: 0`, with a comment explaining that
`/nodes` does not report socket topology. That was true of `/nodes`, and the
data is one call away: `/nodes/<node>/status` returns

```json
"cpuinfo": { "sockets": 1, "cores": 4, "cpus": 4, "model": "AMD Ryzen 3 3200G" }
```

`listProxmoxHypervisorHosts` now makes that call per node, alongside the
`/network` call it already made, and sets `coresPerSocket = cores / sockets`.
Best effort: a node that does not answer keeps 0, which is the previous
behaviour and the honest answer. The division is guarded — a node reporting 0
sockets must not throw.

Both `HostSync` paths take it. The update path prefers the freshly read value
over what is stored, so a host created by an earlier build — every one of which
stored 0 — is repaired on the next sync instead of keeping its zero for ever.

Measured on the lab appliance (Morpheus 9.0.1, plugin API 1.4.2):

| | before | after |
|---|---|---|
| `compute_server.cores_per_socket` | 0 | **4** |
| `compute_server.max_cores` | 4 | 4 |

The value now matches the hardware: one socket, four cores.

**This did not change the licence figures**, and that is the honest result:

```
hosts                             0   (unchanged)
hypervisorSocketCount             0   (unchanged)
sockets                      0.2667   (unchanged)
publicVirtualMachineCount         4
```

So `coresPerSocket` was a real gap and it was not the gate. See
`docs/lab/proxmox-host-sockets.md` for what is now known and what is still open.

sha256 `4d26f678eecbb44a80b4523299221026c7ad9a0666f8508a5fe337cfe5060574`

### Three findings from this work, none of them code changes

**`GET /api/hosts` does not exist.** It answers
`{"success":false,"msg":"Unable to find api endpoint GET /api/hosts"}`. The
earlier conclusion that "no hypervisor host is registered" was read off that
reply. The endpoint is `/api/servers`, and it has always listed the node.

**The host is registered, and correctly typed.** `compute_server` id 1,
`proxmox`, type `proxmox-ve-node`, `server_type: hypervisor`,
`power_state: on`. And `compute_server_type.vm_hypervisor = 1` for that code —
checked numerically (`vm_hypervisor+0`) rather than read off the rendering,
because it is a `bit(1)` and `mysql -B` prints the true byte as an unprintable
character next to a literal `\0` for false. Reading that column by eye gets it
backwards.

**The licence counts a field this plugin cannot write.**
`admin/settings/_socketDetails.gsp` reads `server.maxSockets`, and Plugin API
1.4.2 exposes only `coresPerSocket` on `ComputeServer` — confirmed with `javap`
against the pinned jar. `maxSockets` appears in the API on
`ApplianceLicenseData` alone, which is the entitlement, not the host.

So 0.1.14 fixes a real gap and cannot fix the licence figure. Also tested and
ruled out, by measurement rather than reasoning: `managed` 0 → 1, and
`max_sockets` NULL → 1 written straight to the database. Neither moved `hosts`
off 0. Both reverted. See `docs/lab/proxmox-host-sockets.md`, which now carries the
question for HPE.

---

## 0.1.13 and earlier

Lab builds carried forward from before these notes existed. `0.1.13` is the
build the appliance ran until `0.1.14`; its code is identical to the tree
reconstructed from the fork plus the lab patch — verified by unpacking both
jars and comparing every class under `com/morpheusdata/proxmox`, which are
byte-identical. Only `assets/manifest.properties` and the archive timestamps
differ, so the differing sha256 is a build stamp and not a code change.

sha256 `d3e07348ca759977ba69ca7227ec7c5ef04e261727d565c8b576333796354802`

Until 2026-09-14 these builds lived in a separate deployment repository, as a
patch plus prebuilt jars. `0.1.5` to `0.1.13` have no source commit of their own
here, because they were built from earlier and incomplete revisions of that
patch. Their jars are not published.

### The patch was incomplete until 0.1.14

`proxmox-ve-0.1.13-lab.patch` was generated with `git diff`, which does not
include untracked files. Two classes the lab build added — `LxcSync` and
`ProxmoxVeCloudSummaryProvider` — were therefore never in it, and the only
copies lived in a temporary directory. A clean rebuild from the patch failed at
compile on classes the patch never mentioned, which reads as a broken patch
rather than a partial one.

Fixed by regenerating with `git add -N` over every untracked file first, and
verified the way the old one never was: clean clone of the base, apply the
patch and nothing else, build. The source now lives in this repository as
commits, so there is no patch left to fall out of step with the tree.

### The base is a fork, not upstream

`HewlettPackard/morpheus-proxmox-ve-plugin` carries neither of those two
classes on any branch or in any commit. The lab patch applies **cleanly** to
upstream and then fails to compile, which looks like a broken patch and is
actually the wrong tree. That is why `main` here carries the fork's commit
`5841b29` rather than starting from upstream alone.
