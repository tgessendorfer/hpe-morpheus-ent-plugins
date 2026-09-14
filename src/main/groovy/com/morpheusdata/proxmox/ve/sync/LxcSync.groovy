package com.morpheusdata.proxmox.ve.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Label
import com.morpheusdata.model.OsType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.proxmox.ve.ProxmoxVePlugin
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import groovy.util.logging.Slf4j

/**
 * Read-only discovery of Proxmox LXC containers.
 *
 * Containers are Proxmox's second guest type: they share the host kernel instead
 * of booting their own, are driven with `pct` rather than `qm`, and live under a
 * different API path. VMSync asks only for QEMU guests, so a container was
 * invisible to Morpheus entirely.
 *
 * Discovery only. Nothing here starts, stops, creates or destroys a container —
 * the plugin has no pct support, and the registered ComputeServerType is
 * unmanaged with controlPower false so the console does not offer actions that
 * would be routed to the QEMU endpoint and fail.
 */
@Slf4j
class LxcSync {

    private Cloud cloud
    private MorpheusContext context
    private ProxmoxVePlugin plugin
    private HttpApiClient apiClient
    private CloudProvider cloudProvider
    private Map authConfig

    // The only type this sync owns. Scoping by TYPE CODE and not by the category
    // string is deliberate and load-bearing: HostSync filters on
    // `category == "proxmox.ve.host.${cloud.id}"`, and a sync copied from it that
    // kept a category filter while writing a different category would select every
    // record it does not own — deleting the QEMU VMs on its first run. VMSync
    // filters by type code for the same reason; this matches it.
    private static final String LXC_SERVER_CODE = 'proxmox-lxc-container'

    LxcSync(ProxmoxVePlugin proxmoxVePlugin, Cloud cloud, HttpApiClient apiClient, CloudProvider cloudProvider) {
        this.@plugin = proxmoxVePlugin
        this.@cloud = cloud
        this.@apiClient = apiClient
        this.@context = proxmoxVePlugin.morpheus
        this.@cloudProvider = cloudProvider
        this.@authConfig = plugin.getAuthConfig(cloud)
    }


    def execute() {
        log.debug("Execute LxcSync STARTED: ${cloud.id}")
        try {
            def listResults = ProxmoxApiComputeUtil.listLXCs(apiClient, authConfig)
            if (!listResults.success) {
                // A failed listing is not an empty one. Carrying on would hand an
                // empty collection to the sync task, whose removal step would then
                // delete every container record over a transient API blip.
                log.warn("LXC listing failed; leaving container records untouched: ${listResults.msg}")
                return
            }
            def cloudItems = listResults.data ?: []

            def domainRecords = context.async.computeServer.listIdentityProjections(cloud.id, null).filter {
                it.computeServerTypeCode == LXC_SERVER_CODE
            }

            SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask =
                    new SyncTask<>(domainRecords, cloudItems)

            syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                domainObject.externalId == cloudItem.vmid.toString()
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap =
                        updateItems.collectEntries { [(it.existingItem.id): it] }
                context.async.computeServer.listById(updateItems.collect { it.existingItem.id }).map { ComputeServer server ->
                    new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server,
                            masterItem: updateItemMap[server.id].masterItem)
                }
            }.onAdd { itemsToAdd ->
                addMissingContainers(cloud, itemsToAdd)
            }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                updateMatchedContainers(updateItems)
            }.onDelete { removeItems ->
                removeMissingContainers(removeItems)
            }.start()
        } catch (e) {
            log.error("Error in LxcSync: ${e}", e)
        }
        log.debug("Execute LxcSync COMPLETED: ${cloud.id}")
    }


    private addMissingContainers(Cloud cloud, Collection<Map> addList) {
        def serverType = new ComputeServerType(code: LXC_SERVER_CODE)
        // Without this the grid's HOST column is blank and _generalInfo.gsp has no
        // "Host (Hypervisor)" link to render — the container looks like it belongs
        // to no node. cluster/resources carries `node`; the /nodes/{n}/lxc payload
        // does not, which is why the listing is built from the former.
        def hostIdentitiesMap = context.async.computeServer.listIdentityProjections(cloud.id, null).filter {
            it.computeServerTypeCode == 'proxmox-ve-node'
        }.toMap { it.externalId }.blockingGet()
        def newContainers = []
        for (cloudItem in addList) {
            try {
                def parentServer = hostIdentitiesMap[cloudItem.node]
                if (!parentServer) {
                    log.warn("No hypervisor record for node ${cloudItem.node}; container ${cloudItem.name} will show no host")
                }
                // ComputeServer.usedCpu is a Float, not a Long.
                def usedCpuPercent = ((cloudItem.cpu ?: 0) * 100) as Float
                newContainers << new ComputeServer(
                        account          : cloud.owner,
                        externalId       : cloudItem.vmid.toString(),
                        uniqueId         : "${cloud.id}.lxc.${cloudItem.vmid}",
                        name             : cloudItem.name ?: "lxc-${cloudItem.vmid}",
                        hostname         : cloudItem.hostname ?: cloudItem.name,
                        externalIp       : cloudItem.ip,
                        internalIp       : cloudItem.ip,
                        sshHost          : cloudItem.ip,
                        cloud            : cloud,
                        provision        : false,
                        managed          : false,
                        serverType       : 'vm',
                        status           : 'provisioned',
                        powerState       : cloudItem.status == 'running'
                                ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
                        maxMemory        : cloudItem.maxmem?.toLong(),
                        maxStorage       : cloudItem.maxdisk?.toLong(),
                        usedStorage      : cloudItem.disk?.toLong(),
                        usedMemory       : cloudItem.mem?.toLong(),
                        usedCpu          : usedCpuPercent,
                        parentServer     : parentServer,
                        maxCores         : (cloudItem.maxCores ?: cloudItem.maxcpu ?: 0) as Long,
                        // Never null: a null here fails to dispatch in the host and server
                        // GSPs and Morpheus renders the failure as a 403 page. See
                        // docs/proxmox-cloud.md.
                        coresPerSocket   : (cloudItem.coresPerSocket ?: 0) as Long,
                        platform         : 'linux',
                        osType           : 'linux',
                        serverOs         : new OsType(code: 'linux'),
                        // The grid's TYPE column renders an icon, not the type name, so
                        // the type alone does not mark a container to the eye. A label
                        // does, and the VMs tab has a Labels filter beside the search.
                        labels           : [new Label(name: 'lxc', account: cloud.owner)],
                        category         : "proxmox.ve.lxc.${cloud.id}",
                        computeServerType: serverType
                )
            } catch (e) {
                log.error("Error building container record for ${cloudItem?.vmid}: ${e}", e)
            }
        }
        if (newContainers) {
            if (!context.async.computeServer.bulkCreate(newContainers).blockingGet()) {
                log.error("Error creating ${newContainers.size()} LXC container record(s)")
            } else {
                log.info("Discovered ${newContainers.size()} Proxmox LXC container(s)")
            }
        }
    }


    private updateMatchedContainers(List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems) {
        def updates = []
        def hostIdentitiesMap = context.async.computeServer.listIdentityProjections(cloud.id, null).filter {
            it.computeServerTypeCode == 'proxmox-ve-node'
        }.toMap { it.externalId }.blockingGet()
        for (updateItem in updateItems) {
            def existing = updateItem.existingItem
            def cloudItem = updateItem.masterItem
            def changed = false
            def fields = [
                    name       : cloudItem.name ?: existing.name,
                    hostname   : cloudItem.hostname ?: existing.hostname,
                    externalIp : cloudItem.ip,
                    internalIp : cloudItem.ip,
                    powerState : cloudItem.status == 'running'
                            ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
                    maxMemory  : cloudItem.maxmem?.toLong(),
                    maxStorage : cloudItem.maxdisk?.toLong(),
                    usedStorage: cloudItem.disk?.toLong(),
                    usedMemory : cloudItem.mem?.toLong(),
                    usedCpu    : ((cloudItem.cpu ?: 0) * 100) as Float,
                    maxCores   : (cloudItem.maxCores ?: cloudItem.maxcpu ?: 0) as Long,
                    // Repairs records written before these were set.
                    coresPerSocket: existing.coresPerSocket ?: (cloudItem.coresPerSocket ?: 0) as Long,
                    platform   : existing.platform ?: 'linux',
                    // Repairs records written before parentServer was set.
                    parentServer: existing.parentServer ?: hostIdentitiesMap[cloudItem.node],
            ]
            fields.each { k, v ->
                if (v != null && existing.hasProperty(k) && existing."$k" != v) {
                    existing."$k" = v
                    changed = true
                }
            }
            // Labels were applied on create only, so a container discovered by an
            // earlier build carries none. Add ours if it is missing, leaving any
            // label an operator added alone.
            if (!(existing.labels?.any { it.name == 'lxc' })) {
                existing.labels = (existing.labels ?: []) + [new Label(name: 'lxc', account: cloud.owner)]
                changed = true
            }

            if (changed) updates << existing
        }
        if (updates) context.async.computeServer.bulkSave(updates).blockingGet()
    }


    private removeMissingContainers(List<ComputeServerIdentityProjection> removeItems) {
        // Belt and braces: re-select by type code before removing, so a projection
        // that reached this list by any other route cannot be deleted here.
        def removable = context.services.computeServer.listIdentityProjections(
                new DataQuery().withFilter("id", "in", removeItems.collect { it.id })
                        .withFilter("computeServerType.code", LXC_SERVER_CODE))
        removable.each { ct ->
            log.info("Removing LXC container no longer in Proxmox: ${ct.name} (${ct.externalId})")
            try {
                context.services.computeServer.remove(ct)
            } catch (e) {
                log.error("Error removing container ${ct.name}: ${e}", e)
            }
        }
    }
}
