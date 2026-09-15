# Proxmox VE plugin — release notes

Lab builds of the Proxmox VE cloud plugin. The source is `main` in this
repository: HPE upstream `main`, then `ThePoshArchitect/morpheus-proxmox-ve-plugin`
commit `5841b29`, then the lab commits. Jars are attached to GitHub Releases.

Build with `./gradlew shadowJar`. Every version below is a
`Plugin-Version` in the jar's manifest, which is the only thing the appliance
reports — it never reports a digest, so a rebuild that keeps its number is
indistinguishable from the build before it. That is why each change here gets a
new number, and why the digests are recorded.

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
