# Installing a lab build

Lab jars are attached to this repository's
[GitHub Releases](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases) tagged `proxmox-ve-v<version>-lab`,
each with a `SHA256SUMS` file. Verify before uploading:

```bash
shasum -a 256 -c SHA256SUMS
```

## Upload

In the console: **Administration › Integrations › Plugins › Upload File**.

Or with the API, which is what that page calls:

```bash
curl -k -X POST "https://<appliance>/api/plugins/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@proxmox-ve-0.1.15-lab-all.jar"
```

The part is named `file`. A wrong name is answered
`{"errors":{"plugin":"required as multipart file data"}}`. That `plugin` is the
property the controller binds to, not the field it reads, so do not take the
error for documentation.

Read the plugin list back afterwards rather than trusting the `200`. A jar can
be accepted and still fail to load, and `status` is the field that says so:

```json
{"code":"proxmox-ve","version":"0.1.15","enabled":true,"valid":true,"status":"loaded"}
```

Then add the cloud under **Infrastructure › Clouds › Add**. The field values
that work, and the ones that look right and fail, are in
[`proxmox-cloud.md`](proxmox-cloud.md#configuring-the-cloud).

## What the appliance calls it

The filename is not the identity. Every Morpheus plugin declares itself in
`META-INF/MANIFEST.MF`, and that is what gets registered:

```
Morpheus-Code: proxmox-ve
Plugin-Version: 0.1.15
Morpheus-Name: Proxmox VE
Plugin-Class: com.morpheusdata.proxmox.ve.ProxmoxVePlugin
```

So every jar here registers as `proxmox-ve`: the same plugin at different
versions, not different plugins. Uploading a newer one upgrades it.

## A version does not identify a build

`0.1.1` once meant two different jars on the lab network, one rebuilt over the
other within the hour with the same `Plugin-Version`. The appliance reports a
code and a version and never a digest, so only the digest identifies a build.
That is why every published build gets its own version.

To see which bytes an appliance actually runs:

```bash
# on the appliance
sudo mysql morpheus -N -B -e "select code, absolute_path from plugin_instance" \
  | while IFS=$'\t' read -r c p; do echo "$c $(sha256sum "$p" | cut -c1-16)"; done
```

Compare the result with `SHA256SUMS` from the release. There is no unique index
on `plugin_instance.code`, so after an upgrade check that the old row was really
replaced.
