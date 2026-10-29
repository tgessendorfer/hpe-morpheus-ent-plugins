package com.morpheusdata.proxmox.ve.sync


import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.MetadataTag
import com.morpheusdata.model.MetadataTagType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.proxmox.ve.ProxmoxVePlugin
import com.morpheusdata.proxmox.ve.ProxmoxVeProvisionProvider
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.proxmox.ve.util.ProxmoxMiscUtil
import groovy.util.logging.Slf4j

/**
 * @author Neil van Rensburg
 */

@Slf4j
class VMSync {

    private Cloud cloud
    private MorpheusContext context
    private ProxmoxVePlugin plugin
    private HttpApiClient apiClient
    private CloudProvider cloudProvider
    private Map authConfig
    private GuestPlanResolver planResolver
    private static final String UNMANAGED_SERVER_CODE = 'proxmox-qemu-vm-unmanaged'
    private static final String MANAGED_SERVER_CODE = 'proxmox-qemu-vm'

    VMSync(ProxmoxVePlugin proxmoxVePlugin, Cloud cloud, HttpApiClient apiClient, CloudProvider cloudProvider, Map authConfig = null) {
        this.@plugin = proxmoxVePlugin
        this.@cloud = cloud
        this.@apiClient = apiClient
        this.@context = proxmoxVePlugin.morpheus
        this.@cloudProvider = cloudProvider
        // A refresh passes its own, which carries the refresh's login ticket
        this.@authConfig = authConfig ?: plugin.getAuthConfig(cloud)
    }



    def execute() {
        try {
            log.debug "Execute VMSync STARTED: ${cloud.id}"
            def listResults = ProxmoxApiComputeUtil.listVMs(apiClient, authConfig)
            // Never sync against a failed listing: an empty result would remove every VM
            if (!listResults?.success || !(listResults.data instanceof Collection)) {
                log.warn("VMSync skipped for cloud ${cloud.id}: ${listResults?.msg ?: 'VM listing failed'}")
                return
            }
            def cloudItems = listResults.data
            planResolver = GuestPlanResolver.load(context)
            // Sync BOTH managed and unmanaged VMs
            def domainRecords = context.async.computeServer.listIdentityProjections(cloud.id, null).filter {
                it.computeServerTypeCode in ['proxmox-qemu-vm', 'proxmox-qemu-vm-unmanaged']
            }

            log.debug("VM cloudItems: ${cloudItems.collect { it.toString() }}")
            log.debug("VM domainObjects: ${domainRecords.map { "${it.externalId} - ${it.name} - ${it.computeServerTypeCode}" }.toList().blockingGet()}")

            SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, cloudItems)
            syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                domainObject.externalId == cloudItem.vmid.toString()
            }.onAdd { itemsToAdd ->
                addMissingVirtualMachines(cloud, itemsToAdd)
            }.onDelete { itemsToDelete ->
                removeMissingVMs(itemsToDelete)
            }.withLoadObjectDetails { updateItems ->
                Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                return context.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
                    return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: updateItemMap[server.id].masterItem)
                } 
            }.onUpdate { itemsToUpdate ->
                updateMatchingVMs(itemsToUpdate)
            }.start()
        } catch(e) {
            log.error "Error in VMSync execute : ${e}", e
        }
        log.debug "Execute VMSync COMPLETED: ${cloud.id}"
    }


    private void addMissingVirtualMachines(Cloud cloud, Collection items) {
        log.debug("Adding ${items.size()} new VMs for Proxmox cloud ${cloud.name}")

        def newVMs = []

        // Fixed: Get the hypervisor hosts (nodes) map, not VMs
        def hostIdentitiesMap = context.async.computeServer.listIdentityProjections(cloud.id, null).filter {
            it.computeServerTypeCode == 'proxmox-ve-node'
        }.toMap { it.externalId }.blockingGet()

        def computeServerType = cloudProvider.computeServerTypes.find {
            it.code == 'proxmox-qemu-vm-unmanaged'
        }

        items.each { Map cloudItem ->
            if (beingCreated(cloudItem)) {
                // Most likely Morpheus's own clone: the server record gets the VMID only once the
                // clone is done, and a discovered record now would duplicate it (billed twice).
                log.debug("VM ${cloudItem.vmid} (${cloudItem.name}) is locked '${cloudItem.lock}'; not adding it before the lock is released")
                return
            }
            def parentServer = hostIdentitiesMap[cloudItem.node]
            if (!parentServer) {
                log.warn("Could not find parent server for VM ${cloudItem.name} on node ${cloudItem.node}")
            }
            def usedCpu = cloudItem.cpu  ?: 0
            def usedCpuPercent = usedCpu * 100

            def newVM = new ComputeServer(
                account          : cloud.account,
                externalId       : cloudItem.vmid.toString(),
                name             : cloudItem.name,
                externalIp       : cloudItem.ip,
                internalIp       : cloudItem.ip,
                //sshHost          : cloudItem.ip,
                //sshUsername      : 'root',
                provision        : false,
                cloud            : cloud,
                lvmEnabled       : false,
                managed          : false,
                // Morpheus keeps usage of a discovered server under its own ref type and
                // picks that type by this flag. Without it a usage restart looks for the
                // wrong records and leaves the old one running.
                discovered       : true,
                serverType       : 'vm',
                status           : 'provisioned',
                uniqueId         : cloudItem.vmid.toString(),
                powerState       : cloudItem.status == 'running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
                maxMemory        : cloudItem.maxmem,
                maxCores         : cloudItem.maxCores,
                coresPerSocket   : cloudItem.coresPerSocket,
                usedStorage      : cloudItem.disk?.toLong(),
                usedMemory       : cloudItem.mem?.toLong(),
                usedCpu          : usedCpuPercent.toLong(),
                parentServer     : parentServer,
                osType           : cloudItem.osCode ?: 'unknown',
                serverOs         : new OsType(code: cloudItem.osCode ?: 'unknown'),
                category         : "proxmox.ve.vm.${cloud.id}",
                computeServerType: computeServerType,
                plan             : planResolver?.resolve(cloudItem.maxmem?.toLong(),
                        (cloudItem.maxCores ?: cloudItem.maxcpu)?.toLong())
            )
            newVMs << newVM
        }
        
        if (newVMs) {
            context.async.computeServer.bulkCreate(newVMs).blockingGet()
            log.debug("Successfully added ${newVMs.size()} VMs")
        }
    }


    private updateMatchingVMs(List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems) {
        log.debug("Updating ${updateItems.size()} existing VMs")

        def updates = []
        def repriced = []

        try {
            def hostIdentitiesMap = context.async.computeServer.listIdentityProjections(cloud.id, null).filter {
                it.computeServerTypeCode == 'proxmox-ve-node'
            }.toMap { it.externalId }.blockingGet()

            for (def updateItem in updateItems) {
                def existingItem = updateItem.existingItem
                def cloudItem = updateItem.masterItem
                def needsUpdate = false

                ComputeCapacityInfo capacityInfo = existingItem.getComputeCapacityInfo() ?: new ComputeCapacityInfo()
                def usedCpu = cloudItem.cpu  ?: 0
                def usedCpuPercent = usedCpu * 100
                // Fix power state comparison
                def cloudPowerState = (cloudItem.status == 'running') ? ComputeServer.PowerState.on : ComputeServer.PowerState.off

                Map serverFieldValueMap = [
                        hostname   : cloudItem.name,
                        maxCores   : cloudItem.maxCores ?: cloudItem.maxcpu?.toLong(),
                        maxStorage : cloudItem.maxdisk?.toLong(),
                        usedStorage: cloudItem.disk?.toLong(),
                        maxMemory  : cloudItem.maxmem?.toLong(),
                        usedMemory : cloudItem.mem?.toLong(),
                        usedCpu    : usedCpuPercent.toLong(),
                        powerState : cloudPowerState,
                        // Repairs records synced before the OS was read from the
                        // Proxmox config. Only upgrades away from 'unknown': if an
                        // operator has set a more specific OS by hand, keep theirs.
                        osType     : (existingItem.osType in [null, '', 'unknown'])
                                ? (cloudItem.osCode ?: 'unknown') : existingItem.osType
                ] + addressFields(cloudItem.ip as String)

                Map capacityFieldValueMap = [
                        maxCores   : cloudItem.maxCores ?: cloudItem.maxcpu?.toLong(),
                        maxStorage : cloudItem.maxdisk?.toLong(),
                        usedStorage: cloudItem.disk?.toLong(),
                        maxMemory  : cloudItem.maxmem?.toLong(),
                        usedMemory : cloudItem.mem?.toLong(),
                        usedCpu    : usedCpuPercent.toLong(),
                ]

                if (ProxmoxMiscUtil.doUpdateDomainEntity(existingItem, serverFieldValueMap)) {
                    needsUpdate = true
                }
                
                if (ProxmoxMiscUtil.doUpdateDomainEntity(capacityInfo, capacityFieldValueMap)) {
                    existingItem.capacityInfo = capacityInfo
                    needsUpdate = true
                }
                
                // Discovered VMs only: a managed VM keeps the plan it was provisioned with.
                if (existingItem.computeServerType?.code == UNMANAGED_SERVER_CODE) {
                    boolean reprice = false
                    // Repairs records synced before the flag was set (see addMissingVirtualMachines).
                    if (existingItem.discovered != true) {
                        existingItem.discovered = true
                        reprice = true
                    }
                    if (GuestPlanResolver.mayAssign(existingItem.plan)) {
                        def plan = planResolver?.resolve(existingItem.maxMemory, existingItem.maxCores)
                        if (plan && plan.id != existingItem.plan?.id) {
                            log.info("VM ${existingItem.name}: plan ${existingItem.plan?.code} -> ${plan.code}")
                            existingItem.plan = plan
                            reprice = true
                        }
                    }
                    if (reprice) {
                        repriced << existingItem.id
                        needsUpdate = true
                    }
                }

                if ((existingItem.serverOs?.code in [null, '', 'unknown']) && cloudItem.osCode
                        && cloudItem.osCode != 'unknown') {
                    existingItem.serverOs = new OsType(code: cloudItem.osCode)
                    needsUpdate = true
                }

                if (existingItem.computeServerType?.code == MANAGED_SERVER_CODE && trackIdentity(existingItem, cloudItem)) {
                    needsUpdate = true
                }

                if (trackParentNode(existingItem, cloudItem.node as String, hostIdentitiesMap)) {
                    needsUpdate = true
                }

                if (needsUpdate) {
                    updates << existingItem
                }
            }
            
            if (updates) {
                context.async.computeServer.bulkSave(updates).blockingGet()
            }
            if (repriced) {
                // Closes the open usage records and starts new ones on the new plan;
                // without this the old price runs on until the next power change.
                context.async.usage.restartServerUsage(repriced).blockingGet()
            }
        } catch(e) {
            log.error("Error updating VM properties and stats: ${e}", e)
        }
    }


    /**
     * Whether a listed VM is still being created: a clone or restore holds the config lock
     * `clone` or `create` until it is done.
     */
    static boolean beingCreated(Map cloudItem) {
        return cloudItem?.lock?.toString() in ['clone', 'create']
    }

    /**
     * The address fields of a sync update: the guest agent's IPv4 address when the listing has
     * one. The listing leaves it empty for a stopped VM or one whose agent was not asked, and
     * writing that would wipe the address the agent or Morpheus reported before.
     */
    static Map addressFields(String ip) {
        return ip ? [externalIp: ip, internalIp: ip] : [:]
    }

    /**
     * Follows a VM to the node Proxmox lists it on. After a migration or an HA failover the
     * record kept the old node, and start, stop, resize and delete went to a node where the VM's
     * config no longer exists. A node unknown to Morpheus leaves the record alone. Returns true
     * when the record changed.
     */
    static boolean trackParentNode(ComputeServer existingItem, String node, Map hostsByExternalId) {
        def host = node ? hostsByExternalId?.get(node) : null
        if (!host || existingItem.parentServer?.id == host.id) {
            return false
        }
        if (existingItem.parentServer) {
            log.info("VM ${existingItem.name}: node ${existingItem.parentServer.externalId ?: existingItem.parentServer.id} -> ${node}")
        }
        existingItem.parentServer = host
        return true
    }

    /**
     * Keeps the managed server's record in step with the VM's smbios uuid. A record with no
     * stored uuid (provisioned before 0.1.31) adopts the VM's uuid once, but only while the VM
     * still carries the name it was provisioned with. A stored uuid that differs from the VM's
     * means the VMID was freed and reused: the record is flagged, so the operator sees it, and
     * the provision provider refuses to stop or destroy that VM. Returns true when the record
     * changed.
     */
    static boolean trackIdentity(ComputeServer existingItem, Map cloudItem) {
        String actual = cloudItem?.uuid
        if (!actual) {
            return false
        }
        String stored = existingItem.getConfigProperty(ProxmoxVeProvisionProvider.GUEST_UUID_PROPERTY)
        if (!stored) {
            if (cloudItem.name && cloudItem.name == existingItem.name) {
                existingItem.setConfigProperty(ProxmoxVeProvisionProvider.GUEST_UUID_PROPERTY, actual)
                return true
            }
            return false
        }
        if (stored.toLowerCase() == actual.toLowerCase()) {
            return false
        }
        String message = "VM ${cloudItem.vmid} is another guest now (uuid ${actual}, expected ${stored}); the id was reused in Proxmox"
        if (existingItem.statusMessage != message) {
            log.warn("Server ${existingItem.name}: ${message}")
            existingItem.statusMessage = message
            return true
        }
        return false
    }


    private removeMissingVMs(List<ComputeServerIdentityProjection> removeItems) {
        // A managed VM that is gone from Proxmox is not deleted from Morpheus: the operator
        // decides. The record is flagged and powered off, so it is not matched to the next
        // VM that takes the freed id, and stop or destroy find nothing to act on.
        def missingManaged = context.services.computeServer.listIdentityProjections(
                new DataQuery().withFilter("id", "in", removeItems.collect { it.id })
                        .withFilter("computeServerType.code", MANAGED_SERVER_CODE)
        )
        if (missingManaged) {
            try {
                List<ComputeServer> flagged = []
                context.async.computeServer.listById(missingManaged.collect { it.id }).blockingSubscribe { ComputeServer server ->
                    String message = "VM ${server.externalId} no longer exists in Proxmox"
                    if (server.statusMessage != message || server.powerState != ComputeServer.PowerState.off) {
                        log.warn("Managed server ${server.name}: ${message}")
                        server.statusMessage = message
                        server.powerState = ComputeServer.PowerState.off
                        flagged << server
                    }
                }
                if (flagged) {
                    context.async.computeServer.bulkSave(flagged).blockingGet()
                }
            } catch (e) {
                log.error("Error flagging managed VMs missing in Proxmox: ${e}", e)
            }
        }
        def removeUnmanagedItems = context.services.computeServer.listIdentityProjections(
                new DataQuery().withFilter("id", "in", removeItems.collect { it.id })
                        .withFilter("computeServerType.code", UNMANAGED_SERVER_CODE)
        )
        log.debug("Removing ${removeUnmanagedItems.size()} VMs that no longer exist in Proxmox...")
        removeUnmanagedItems.each { vm ->
            log.debug("Removing orphaned VM: ${vm.name} (ID: ${vm.id}, External ID: ${vm.externalId}, Type: ${vm.computeServerTypeCode})")
            try {
                context.services.computeServer.remove(vm)
                log.debug("Successfully removed VM: ${vm.name} (ID: ${vm.id})")
            } catch (e) {
                log.error("Error removing VM ${vm.name} (ID: ${vm.id}): ${e}", e)
            }
        }
    }
}
