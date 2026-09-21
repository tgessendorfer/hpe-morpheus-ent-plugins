# Proxmox as a Morpheus cloud

Morpheus cannot manage a Proxmox VE hypervisor out of the box. This is what it
took to close that gap in a lab, what breaks on the way, and why no published
build fitted it. The result is this fork.

Investigated 2026-08-11 against Morpheus 9.0.1 and Proxmox VE 9.2.10.

## There is no built-in Proxmox type

Verified on this appliance — `GET /api/zone-types` returns **23** types and none
of them is Proxmox:

```
alibaba     azurestack    fusion        macstadium   opentelekom  powervc    upcloud
amazon      digitalocean  googlecloud   nutanix      openstack    scvmm      vcd
azure       esxi          huawei        hyperv       oraclecloud  standard   vmware
vmwareCloudAws            xenserver
```

All 23 are enabled; the list above is the complete set.

**MVM is not the answer.** It is HPE's own KVM-based hypervisor (VM Essentials),
not a Proxmox client, and it is not among the enabled types above either.
Proxmox needs a plugin.

## The plugin

[`HewlettPackard/morpheus-proxmox-ve-plugin`](https://github.com/HewlettPackard/morpheus-proxmox-ve-plugin)
— Apache-2.0, Groovy, last pushed 2026-04-16.

**Read the header before planning around it.** Despite living in the HPE
organisation, its own README opens: *"This is a COMMUNITY maintained plugin
WITHOUT official Morpheus support."* There is no support contract behind it and
no HPE release process in front of it.

There are **no GitHub releases**, so there is no jar to download — it is built
from source with the bundled wrapper and the result uploaded to the appliance.

What it does once installed: syncs Proxmox hosts, VMs, networks, datastores,
resource pools and templates; provisions VMs; and turns a qcow2 uploaded through
Morpheus into a Proxmox template for later provisioning.

The other search result, `martezr/morpheus-proxmox-plugin`, was last pushed
2023-07-12. Dead — ignore it.

## Proxmox VE 9 breaks it

This lab runs PVE **9.2.10**. The plugin is titled *"Proxmox VE 8"*, and
upstream issue #42 *Proxmox VE 9 Support* has been open since 2026-04-23 with a
single unanswered "any update?" comment from 2026-07-31.

The only PVE 9 work that exists anywhere is one commit on a fork —
[`ThePoshArchitect/morpheus-proxmox-ve-plugin@5841b29`](https://github.com/ThePoshArchitect/morpheus-proxmox-ve-plugin/commit/5841b29)
"Working with Version 9", 2026-07-31, one commit ahead of upstream `main` and
never opened as a pull request. Its diff is the clearest available statement of
what actually breaks:

- **`qm importdisk` was renamed `qm disk import`.** Template creation fails
  outright — this is the headline break.
- **The imported disk ID was scraped from stdout** with
  `/imported disk ['"]([^'"]+)['"]/`. That output changed; the fix reads the VM
  config's `unusedN` keys over the API instead.
- **`qm set <vm> --ide2 <datastore>:cloudinit` fails when a cloud-init volume
  already exists.** The fix looks for `vm-<id>-cloudinit` in storage content
  and reattaches it with `,media=cdrom`.
- It also adds a version guard: majors 8 and 9 pass, anything else is rejected
  at cloud-validation time with a clear message rather than failing later.

## No single branch fits Morpheus 9.0.1 *and* PVE 9

| Branch | Plugin API | Runtime | Appliance target | PVE 9 fixes |
|---|---|---|---|---|
| `main` | 1.2.13 | Java 17, Groovy 3 | 8.0.0+ (README) | no |
| `api-1.4.x` | `1.4.0-SNAPSHOT` | Java 25, Groovy 4 | `morphApplianceMinVersion=9.0.0` | no |
| fork `5841b29` | 1.2.13 | Java 17, Groovy 3 | 8.0.0+ | **yes** |

`api-1.4.x` is the branch aimed at a 9.0 appliance and it carries none of the
PVE 9 work. It also pins a snapshot that predates the real thing: plugin-core
1.4.0 shipped 2026-05-28 and 1.4.2 on 2026-07-30.

**Try the fork first.** `morphApplianceMinVersion` is a floor rather than a
ceiling and the Morpheus plugin API is normally backward compatible, so a
1.2.13 build has a fair chance of loading on 9.0.1 — one build and one upload
settles it. Only if the appliance rejects it is the merge worth doing: take
`api-1.4.x`, cherry-pick `5841b29`, and move `1.4.0-SNAPSHOT` to the released
1.4.2.

## Building

Gradle 8.3 is pinned by the wrapper, which caps at JDK 20 — so **Java 17**, not
a newer one, for the 1.2.13 branches. The `api-1.4.x` branch wants Java 25.

```bash
git clone https://github.com/ThePoshArchitect/morpheus-proxmox-ve-plugin.git
cd morpheus-proxmox-ve-plugin
./gradlew clean build          # jar lands in build/libs/*-all.jar
```

Building on Apple Silicon is fine — the output is a jar, and the x86_64
constraint that governs the appliance itself does not apply to bytecode.

**A fresh JDK does not trust what the Mac trusts.** Behind a TLS-intercepting
proxy, macOS can trust the proxy's root while the JVM, which keeps its own
truststore, does not. `curl`, `git` and `brew` then work and the network looks
clean, but the first thing the wrapper does — fetch `gradle-8.3-bin.zip` — dies
with `PKIX path building failed`.

A redirect can hide which host failed: `services.gradle.org` sends the download
on to GitHub. Check the certificate chain of the host that actually failed, not
the one in the URL.

Import the proxy root into a *copy* of the JDK truststore rather than editing
the installed one, and point both the wrapper and the build JVM at it:

```bash
security find-certificate -a -c "<proxy root CA name>" -p \
  /Library/Keychains/System.keychain > proxy-root.pem
cp "$JAVA_HOME/lib/security/cacerts" truststore.jks && chmod u+w truststore.jks
keytool -importcert -noprompt -alias proxy-root -file proxy-root.pem \
  -keystore truststore.jks -storepass changeit

export JAVA_OPTS="-Djavax.net.ssl.trustStore=$PWD/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
export GRADLE_OPTS="$JAVA_OPTS"   # JAVA_OPTS covers the wrapper, GRADLE_OPTS the build
```

Upload the jar under **Administration › Integrations › Plugins**, then add the
cloud under **Infrastructure › Clouds › Add**.

### Built here, 2026-08-11

The fork builds clean on this Mac: `BUILD SUCCESSFUL in 1m 55s`, 7 Spock tests
passed with no failures — including the fork's own *"finds an orphaned
cloud-init volume for provisioning retry"*, which covers the PVE 9 cloud-init
fix. CodeNarc reports style violations; they do not fail the build.

    proxmox-ve-0.1.0-pve9-5841b29-all.jar   3.6 MB
    sha256 8f55b393709f464aec7e31fb2cc6c0aaa7ab2802691e8fb03189181b11683f9c

**The manifest settles the version worry.** The built jar declares:

```
Plugin-Class:  com.morpheusdata.proxmox.ve.ProxmoxVePlugin
Morpheus-Code: proxmox-ve
Morpheus-Min-Appliance-Version: 6.3.0
```

A floor of **6.3.0**, not the 8.0.0 the README talks about — so a 9.0.1
appliance is comfortably above it and there is no manifest-level reason for the
upload to be refused.

### Installed, 2026-08-11 — it works on 9.0.1

Uploaded with `POST /api/plugins/upload` (multipart, field `file`; the same
thing the Plugins page does). `HTTP 200`, and the appliance answered:

```json
{"id":21,"name":"Proxmox VE","code":"proxmox-ve","version":"0.1.0",
 "enabled":true,"valid":true,"status":"loaded","statusMessage":""}
```

Six providers registered — CLOUD, PROVISION, NETWORK, DATASET and two OPTION
sources. The cloud type is live:

```
id 27 · Proxmox VE · code proxmox-ve.cloud · enabled · provision
hasDatastores, hasNetworks, hasResourcePools, hasCloudInit, hasContainers
serverType proxmox-ve-node (vmHypervisor)
```

Appliance health after the load: cpu ok, memory ok, elastic ok, database
healthy. So **the 1.2.13 branch loads on 9.0.1** — the `api-1.4.x` merge is not
needed to get a working plugin, and the earlier worry about
`morphApplianceMinVersion` was unfounded. Only the *Proxmox* version mattered.

## The cloud Summary panel cannot show the version — three attempts

Worth recording so nobody spends another evening on it. The version belongs, to
any reasonable eye, on the cloud's Summary panel. On 9.0.1 it cannot go there.

| Attempt | Result |
|---|---|
| `OptionType.displayValueOnDetails` | field registers on the zone type, panel unchanged |
| Standalone `UI_EXTENSION` provider | registers, never called |
| `CloudProvider.getCloudSummaryProvider()` override | live after a full restart, never called |

Every part of the machinery is present, which is what makes it convincing:

```
CloudSummaryProvider        morpheus-plugin-api-1.4.1.jar   (the appliance's own)
CloudPluginComputeService   morpheus-core-9.0.1.jar         (references summaryHtml,
                                                             renderCloudSummary and
                                                             "plugins/zoneSummary")
plugins/_zoneSummary.gsp    ${raw(zoneSummary?.summaryHtml)}
```

and the cloud page even renders the slot — empty:

```html
</dl></div>
<!-- zone summary -->
<!-- alrams --> <!-- guidance --> <!-- costing -->
```

The decisive measurement is a log line added to the provider's own entry point.
After a full `morpheus-ui` restart with 0.1.13 loaded and the page fetched in a
browser: **`renderCloudSummary` called 0 times, no exceptions.** Not swallowed,
not failing — never invoked.

`ProxmoxVeCloudSummaryProvider` is kept, inert. It is correct against the
published interface, costs nothing, logs nothing, and would work unchanged if a
later release wires that path. It does not work on 9.0.1.

**The version is visible on the host detail page** as `Platform: linux 9.2.10`,
and has been since 0.1.5. That is the answer, and it was available before any of
this was attempted.

## Configuring the cloud

**`PROXMOX API URL` is a full base URL, not a host.** The plugin passes the
field through untouched — `apiUrl: cloud.serviceUrl` — and appends
`/api2/json/access/ticket`. Type `proxmox.example.com` and the client is handed
`/proxmox.example.com/api2/json/access/ticket`, which is not a URL at all:

```
ERROR c.m.c.u.HttpApiClient - Error occurred processing the response for
      /proxmox.example.com/api2/json/access/ticket : null{}
org.apache.http.client.ClientProtocolException
  at ProxmoxApiComputeUtil.getApiV2Token(ProxmoxApiComputeUtil.groovy:1469)
  at ProxmoxVeCloudProvider.validate(ProxmoxVeCloudProvider.groovy:411)
```

The form then reports *"Unable to validate cloud connection using provided
credentials and URL"* — which reads as an authentication problem and is not
one. Enter `https://proxmox.example.com:8006`.

**`USER NAME` needs a realm.** Proxmox authenticates `user@realm`; on this host
`pveum user list` shows `root@pam` and one personal `@pam` account, with realms
`pam` and `pve`. Use **`root@pam`**.

**The SSH fields are not used during validation.** `validate()` only calls
`getApiV2Token`. So a validation failure is never an SSH problem, however much
the message invites that reading — SSH is exercised later, when images are
uploaded and cloud-init snippets are written. On this host both
`permitrootlogin` and `passwordauthentication` are `yes`, so the *Initial Host*
credentials will work when their turn comes.

**The self-signed Proxmox certificate is not a problem either** — the plugin
sets `ignoreSSL: true` on the token request.

Working values for this lab:

| Field | Value |
|---|---|
| Proxmox API URL | `https://proxmox.example.com:8006` |
| User name | `root@pam` |
| Password | the PVE root password |
| Initial host username | `root` |
| Initial host password | the same root password (SSH) |

Provisioning through the cloud once it syncs writes to the thin pool — check its
real usage first (see *Two prerequisites that are easy to miss* below).

## Local changes on top of the fork — 0.1.1

Built from the fork plus the changes below, because the stock form gives an
admin no way to know what those fields want. Installed and verified on the
appliance. The code is on `main` in this repository, and jars are attached to
its GitHub Releases.

**Every field now carries help text and a placeholder.** In particular the two
SSH fields, relabelled from the meaningless *Initial Host …* to **Node SSH
Username / Password**, now say what they are for: a root-level Linux account on
the nodes, used only after the cloud is added, for image import and cloud-init.

**The Proxmox version is recorded.** `refresh()` reads `/api2/json/version` and
writes it to `Cloud.serviceVersion`; `HostSync` puts the same value on each
node's `ComputeServer.platformVersion`, fetched once per sync and cached.
Verified after a refresh — the cloud reads `serviceVersion: "9.2.10"` and the
`proxmox` host `platformVersion: "9.2.10"`, where both were previously null.
Neither is fatal on failure: a cluster that will not report its version still
syncs.

**The plugin description is filled in** — it was the generated
`TODO: Fill in the Plugin Description`.

### 0.1.2 — getting the version onto the detail page

Writing `Cloud.serviceVersion` is not enough to *see* it. The cloud Summary
panel renders a fixed set of core fields — Appliance URL, Datacenter ID,
Network Mode, Time Zone, Agent Install Mode, Local Firewall, Domain, Guidance,
Security Server — and a version is not among them, so 0.1.1 stored the value
where nothing displayed it.

0.1.2 adds a read-only OptionType over the existing domain field:
`fieldName: 'serviceVersion'`, `displayValueOnDetails: true`, with
`showOnCreate`/`showOnEdit` false so it never appears as an input. Registered
and confirmed on the appliance (the appliance normalises `fieldContext` from
`domain` to `zone`).

**That inference was wrong.** `displayValueOnDetails` is not honoured on the
cloud Summary panel — the field registers on the zone type, `serviceVersion`
holds `9.2.10`, and the panel still shows the same nine core fields after a
full `morpheus-ctl restart morpheus-ui`. The warning sign was there before the
attempt: none of the 24 built-in cloud types uses the flag, so there was no
working example to copy.

So the version is **stored but not displayable** in 9.0.1 from a plugin:

| Where | State |
|---|---|
| `GET /api/zones/{id}` → `serviceVersion` | `9.2.10` |
| `GET /api/servers/{id}` → `platformVersion` | `9.2.10` |
| Cloud Summary panel | fixed nine fields, no version |
| Cloud → Hosts tab | columns are Power, OS, Name, Type, Cloud, IP, Compute, Memory, Storage, Status — no version |
| Host detail page | would show it — but returned 403 at the time, see below |

The version and the 403 turned out to be the same problem: the host detail page
is where a node's `platformVersion` belongs, and at 0.1.2 that page was
unreachable. *The 403 on the host page* below fixes the page, and *Where the
version actually shows* puts the version on it.

## LXC containers — discovered since 0.1.9

Containers are Proxmox's second guest type: they share the host kernel rather
than booting their own, are driven with `pct` not `qm`, and live under
`/nodes/{node}/lxc`. The stock plugin asks only for QEMU guests, so a container
was invisible to Morpheus — not filtered out of a list, never fetched.

`LxcSync` adds read-only discovery. A container arrives with its IP (parsed from
the `netN` config string, which handles `dhcp`, `manual`, IPv6-only and a
missing `net0`), cores, memory, storage, power state and a link to its
hypervisor.

**Discovery only, deliberately.** The registered type sets `controlPower`,
`selectable`, `creatable` and `externalDelete` all false. Start and stop would
be routed to `/nodes/{node}/qemu/{vmid}/status/...` and fail — probed on this
host, that path answers *"Configuration file 'nodes/proxmox/qemu-server/101.conf'
does not exist"*. A button that always fails is worse than no button.

**The deletion trap, avoided.** `HostSync` scopes itself by a category string
while `VMSync` scopes by `computeServerTypeCode`. A container sync copied from
the former, writing its own category, would select every record it does not own
— deleting all the QEMU VMs on its first run. `LxcSync` filters by type code,
re-checks the type before removing anything, and returns early when the API
listing *fails* rather than treating a failure as "zero containers, delete
everything" — a bug `VMSync` still has.

### Every VM showed "Unknown" for its OS

`VMSync` hard-coded `osType: 'unknown'` and `serverOs: OsType(code: 'unknown')`,
so every synced guest carried a grey question mark where an OS icon belongs —
while Proxmox had the answer all along in each guest's config (`ostype: l26`
for the appliance). 0.1.10 maps it: `l24`/`l26` to linux, `win*`/`w2k*`/`wxp`
to windows, `solaris` to solaris, anything unrecognised stays `unknown` rather
than being guessed at.

**Fixing it on create was not enough, three times over.** `parentServer`, the
OS mapping and the container label each worked only for records that did not
yet exist — and every record here already existed, so `addMissing…` never ran
again and nothing changed. Each needed the same treatment: set on create *and*
repair on update, guarded so an operator's own value is never overwritten. The
OS only upgrades away from `unknown`; the label is added only when absent.

### Marking a container in the cloud's VMs grid

Containers carry an `lxc` label. It is not visible by default, and that is a
column setting rather than a missing feature: the grid defines a `server-labels`
column (`view_column` 310, template `/templates/table/labels`, a chip per label)
with `default_column = 0`, so it is absent from the default twelve. **Gear menu →
Columns → Labels** shows it, and the chip is click-to-filter — the front end
binds `.table-label-action` to the Labels box.

Do not expect the TYPE column to distinguish them: it renders
`<morph:serverTypeImage ... title="…"/>`, one icon for every server type, with
the type name only in the hover title. Two earlier attempts at a marker were
wrong for exactly this reason — reasoning from the data model instead of reading
the view.

## Guests: one VM was correct, the container was not synced

Before 0.1.9 the cloud reported 1 hypervisor and 1 VM, which was right —
`qm list` on the node returns exactly one QEMU guest, `9000 morpheus-appliance`.
What was missing is `pct list`: **LXC container 101 `plex` was not synced at
all**, because the plugin synced QEMU guests only. Its TODO had "Add provision
container host" unticked, so this was a known gap rather than a fault. 0.1.9
closed it for discovery — see *LXC containers — discovered since 0.1.9* above.

## The 403 on the host page: three theories, all wrong

Clicking the `proxmox` host returns 403; clicking `morpheus-appliance` in the
VMs tab opens normally. That isolates it to the `proxmox-ve-node` server type
the plugin registers, and rules out the account, the cloud and the session.

Falsified, each with evidence, so nobody repeats them:

- **Not a missing role permission.** Searching "host" in Administration ›
  Roles › System Admin › Features returns nothing, and the API agrees: of 161
  feature permissions not one is host-scoped. There is nothing to grant, and
  the account is Super User on the master tenant.
- **Not group scoping.** Both the host and the VM have `group: null`; only the
  host 403s. The cloud is in group `OnPrem Clouds` either way.
- **Not `managed: true` on a hypervisor type.** It looked like the outlier
  until all 29 hypervisor types were checked: **12 are managed**, including
  Morpheus's own `mvm`, `mvmHost` and `morpheusKvmLinux`.

A fourth theory died too: **`nodeType` is not it.** Every built-in hypervisor
uses `morpheus-node` where the plugin invented `proxmox-node`, which looked
compelling; changing it registered correctly and the 403 persisted.

### It was never a permissions problem

Nothing is logged at INFO, but `logback.xml` is `scan="true" scanPeriod="5
seconds"`, so `com.morpheus` and `org.springframework.security` can be raised
to DEBUG on a **running** appliance and reverted the same way — no restart, no
downtime. Do that rather than assuming the log has nothing to say. The API
cannot reproduce the failure: a bearer token fetching `/infrastructure/servers/1`
gets `302` to the login form, so load the page in a browser session.

The captured request settles it. Spring Security *allowed* it:

```
o.s.s.w.a.i.FilterSecurityInterceptor - Authorized filter invocation
o.s.s.w.FilterChainProxy              - Secured GET /infrastructure/servers/1
c.m.PermissionService                 - Zone perm full
```

and then the render blew up:

```
org.grails.taglib.GrailsTagException:
  [views/admin/servers/show.gsp:152] Error executing tag <g:render>:
  [views/admin/servers/_dashboard.gsp:7] Ambiguous method overloading for
  method java.lang.Long
Cannot resolve which method to invoke for [null] due to overlapping
prototypes between: [class java.lang.Character] [class java.lang.Number]
```

**Morpheus renders an unhandled view exception as its 403 "You do not have
permissions to access this page" page.** The message is not merely unhelpful,
it points at the wrong subsystem entirely — which is how four plausible
permission theories got built on top of it.

### The null is `reserved_memory`, and the plugin cannot set it

`coresPerSocket` was null on the host and 0 on the VM, which looked like the
answer. It was not — 0.1.4 set it and the page still 403s. Read the template
instead of diffing records:

```gsp
admin/servers/_dashboard.gsp:6   <g:if test="${server.computeServerType?.vmHypervisor || ...}">
admin/servers/_dashboard.gsp:7     <g:render template="capacityInfo"/>
admin/servers/_capacityInfo.gsp:7  ${(((server.capacityInfo?.maxMemory ?: 0l) - server.reservedMemory) * server.provisionPercent) ?: 0l}
```

Line 6 is why the VM works: `capacityInfo` renders **only for hypervisor
types**, so the VM never reaches the failing expression. And line 7 subtracts
`server.reservedMemory` with no `?:` guard — while line 11 of the same file
writes `server.reservedMemory ?: 0d`. The guard exists eleven lines further
down; it is missing here.

The API does not serialise these fields, so its nulls prove nothing. The
database does:

```
id  name                reserved_memory  provision_percent  cores_per_socket
1   proxmox             NULL             1                  0
2   morpheus-appliance  NULL             1                  0
```

`reserved_memory` is a nullable `double` with **no default**, and it is NULL on
both. `Long.minus(null)` cannot be dispatched — Groovy sees `minus(Character)`
and `minus(Number)` and refuses — so the tag throws and Morpheus serves its 403
page.

**The plugin could not fix this at 1.2.13** — `ComputeServer` there exposes no
`reservedMemory` property at all, only `provisionSiteId`, `provision` and
`preProvisioned`. A sync could populate every field it was given and still leave
the column NULL.

**Plugin-api 1.4.2 adds it**, and that is the way out:

```
1.2.13   (none)
1.2.14   (none)
1.4.2    protected java.lang.Double reservedMemory;   public void setReservedMemory(Double)
         protected java.lang.Double provisionPercent; public void setProvisionPercent(Double)
```

Moving to it cost less than the upstream `api-1.4.x` branch implies. That branch
also jumps to Java 25 and Groovy 4, which reads like a requirement and is not:
both API jars are **class-file major 55, Java 11**. Bumping only
`morpheusApiVersion` to 1.4.2 compiled clean on the existing Java 17 / Groovy 3
/ Gradle 8.3 toolchain, with no source changes, and the resulting jar **loads on
Morpheus 9.0.1** — `status: loaded`, valid, six providers. So the appliance's
plugin runtime accepts 1.4.x even though its own release predates it.

0.1.6 therefore sets `reservedMemory: 0.0d` and `provisionPercent: 1.0d` when
creating a host, and repairs an existing one on update with
`existingItem.reservedMemory ?: 0.0d`. A node discovered from now on renders its
detail page without any database intervention.

So it is two upstream defects meeting: a GSP expression that omits a null guard
its own neighbour has, and a plugin API that does not expose the column the
expression needs. Any plugin registering a `vmHypervisor` compute server type
will hit it.

### Confirmed, and the workaround

One row of SQL, scoped by a join so it cannot touch VMs and skips rows that
already have a value:

```sql
update compute_server cs
  join compute_server_type cst on cst.id = cs.compute_server_type_id
  set cs.reserved_memory = 0
  where cst.vm_hypervisor = 1 and cs.reserved_memory is null;
```

Applied 2026-08-11, one row (`id 1`, `proxmox`). The access log settles it
without trusting the page's appearance:

```
13:46:25  GET /infrastructure/servers/1  403   5509    <- before
14:03:34  GET /infrastructure/servers/1  200  52372    <- after
```

This is a database write on a live appliance, so it belongs to whoever owns the
appliance rather than to a script here.

**The repair sticks.** At 1.2.13 `HostSync` updated a fixed set of fields that
did not include `reserved_memory`, so a cloud refresh could not put it back to
NULL — verified after several refreshes, the row still read `0` and the host
page still rendered. The fix did not carry to **new** records then, because
plugin-api 1.2.13 exposes no `reservedMemory` property for the sync to set.

That is no longer so. Since 0.1.6 `HostSync` sets `reserved_memory` to 0 when it
creates a host, and on update fills a NULL while keeping any value already
there (see above). The SQL is only needed on an appliance still running a build
older than 0.1.6.

### Where the version actually shows: `platform`, not `platformVersion`

Fixing the 403 was necessary but not sufficient. `_generalInfo.gsp` prints

```gsp
<g:if test="${server.platform}">
  ... ${server.platform} ${server?.platformVersion}
</g:if>
```

so the whole line is behind a guard on **`platform`**, which the plugin never
set — leaving the version stored and still invisible. `osType` feeds a
different line ("Operating System"), which is why the page showed `linux`
while the version stayed hidden.

**0.1.5** sets `platform: 'linux'` alongside `platformVersion`, on create and
on update. The host page then reads `linux 9.2.10`.

The cloud Summary panel still does not show the version and cannot be made to
— see `displayValueOnDetails` above. The host page is the only place in the UI
that renders it.

Three things worth knowing if you edit this further:

- **`OptionType.helpText` persists as `helpBlock`.** Set `helpText` in Groovy;
  read it back as `helpBlock` over the API. `helpText` is not serialised at all,
  so checking for it reports the field as empty when it is populated.
- **`plugin_instance.description` is a bounded column.** A description of ~330
  characters failed the upload outright with
  `DataIntegrityViolationException ... update plugin_instance set ... description=?`
  and `HTTP 400`. Keep it near 100 characters. The failure names the column, but
  only if you read past "Failed to register plugin".
- **Dropping `fieldCode` is what lets a custom `fieldLabel` win.** The stock
  fields carry i18n codes like `gomorpheus.optiontype.HostUserName`, which
  override the label set beside them.

## Two prerequisites that are easy to miss

**It authenticates to nodes with an SSH username and password, not keys.** The
README is explicit that keys are a future item, and that the same credentials
are assumed on every node. Those are the *Initial Host Username* / *Initial Host
Password* fields on the cloud form, and they are used for real work — uploading
qcow2 images and writing cloud-init snippets to `/var/lib/vz/snippets`, not just
for validation. A host that only accepts key auth will fail here.

**Anything provisioned lands in the Proxmox thin pool.** On the lab host the
volume group had 16 GB free and the NVMe was fully partitioned, and a pool that
fills corrupts *every* guest on it rather than only the one that wrote last.
Read the real usage with `lvs -o lv_name,data_percent,metadata_percent` before
provisioning anything through Morpheus. `pvesm status` reports unwritten space
and will not tell you.

## Image preparation

Images must be built for cloud-init and the Morpheus agent the same way a VMware
template is — the plugin's README points at Morpheus's own VMware template
guide for this. Upload under **Library › Virtual Images › Add › QCOW2**, and
**uncheck both "VM Tools Installed" and "VirtIO Drivers Loaded"**, then switch
the filter to *User* to see the upload.

The first time an image is provisioned it is copied to the Proxmox node and a
template is created from it; later provisions reuse that template. So the first
build of any image is much slower than the rest.

Windows images need cloudbase-init rather than cloud-init, plus virtio drivers
installed before Proxmox will recognise the disk — see
[`prepare_windows_image.md`](../prepare_windows_image.md).

## Provisioning — verified 2026-09-21 on Morpheus 9.0.2 with PVE 9.2.20

Up to 0.1.16 no instance had been provisioned through this cloud. The first
attempts ended in `Provisioning failed: com.jcraft.jsch.JSchException: session
is down`, and fixing that uncovered three more defects in turn. 0.1.21 is the
first build that provisions end to end: `POST /api/instances` → `running` with
an address, Morpheus agent checked in, console working, no manual step.

**What the node must offer.** Root over SSH: the plugin runs `qm`, `pvesm` and
writes snippets under `/var/lib/vz/snippets` with the host's SSH credentials
and no sudo. The cloud form's *Node SSH Username/Password* is what to set;
since 0.1.21 every sync copies it to the host record (up to 0.1.16 the host
record was written once, on creation, and kept whatever it held). The storage
`local` needs the `snippets` content type; the plugin adds it, and there is no
API upload for snippets in PVE 9.2, so SSH stays a requirement. A failed login
now fails within seconds, before the clone, with
`SSH to Proxmox node '<node>' as <user>@<host> failed: ...`.

**What the template must offer.** Cloud-init, `agent: 1` with the guest agent
installed, and a `virtio` NIC. Debian's cloud kernel (`6.1.0-*-cloud-amd64`)
ships no e1000e driver: 0.1.16 rewrote every NIC as `model=e1000e` after the
clone, the guest booted, its agent answered, and it listed `lo` as its only
interface. The NIC now keeps the template's model and MAC address.

**How the VM is configured.** Morpheus's cloud-init user-data (users, hostname,
agent install) goes to the node as `<vmid>-cloud-init-user-data.yml` and is
attached with `cicustom user=local:snippets/...`. The network is not taken
from Morpheus's network snippet, which names the interface `eth0` while the
guest has `ens18`; the plugin sets Proxmox's `ipconfigN` per interface
(`ip=dhcp`, or address, prefix and gateway from the network) and Proxmox
generates a network-config that matches the NIC by MAC address. The VM's
created users are the *Cloud-Init User* from the provisioning settings and,
with `createUser`, the requesting Morpheus user's Linux account; the console
logs in with the latter when its profile holds a Linux password.

**What a run looks like.** Clone (seconds on the thin pool), snippet and
`qm set` over SSH, start, then the plugin polls the guest agent for an IPv4
address (up to 10 minutes; the lab guest answered after 13 seconds) and stores
it on the server, then Morpheus waits for the agent, which checked in about
30 seconds later. `config.proxmoxNode` may be left out when the cloud has one
active node. Deleting the instance destroys the VM and removes the server
record and the snippets, in about 8 seconds.

**Failure modes and their log lines** (`/var/log/morpheus/morpheus-ui/current`):

- `RpcService - Unable to log in via username/password. Host: … User: …` right
  before `session is down`: the host record's SSH account; the exception names
  neither.
- `QEMU guest agent is not running` for minutes: the guest has no network or no
  agent; with 0.1.16's e1000e NIC the agent came up after seven minutes and
  reported only `lo`.
- `Please select a network` on a request that worked before: a sync deleted
  the cloud's networks. One transient `[B.getAt()` failure of the network
  listing made `NetworkSync` remove `vmbr0`; the re-sync created it with a new
  id. Fixed in 0.1.21 for every listing, but a network id from before that
  sync is gone.
- `Cannot get property 'name' on null object` when deleting a failed instance:
  the server never got a parent node (fixed).

Not verified: a static address from a Morpheus IP pool, a Windows guest, a
multi-node cluster, a resize.
