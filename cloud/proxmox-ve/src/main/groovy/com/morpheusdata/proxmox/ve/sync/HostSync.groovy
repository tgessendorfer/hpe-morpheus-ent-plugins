package com.morpheusdata.proxmox.ve.sync

import com.morpheusdata.proxmox.ve.ProxmoxVePlugin
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.proxmox.ve.util.ProxmoxMiscUtil
import groovy.util.logging.Slf4j

/**
 * @author Neil van Rensburg
 */

@Slf4j
class HostSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ProxmoxVePlugin plugin
    private HttpApiClient apiClient
    private Map authConfig
    private String hostUID
    private String hostPWD
    private String clusterVersion

    /**
     * @author Neil van Rensburg
     */

    HostSync(ProxmoxVePlugin proxmoxVePlugin, Cloud cloud, HttpApiClient apiClient) {
        this.@plugin = proxmoxVePlugin
        this.@cloud = cloud
        this.@morpheusContext = proxmoxVePlugin.morpheus
        this.@apiClient = apiClient
        this.@authConfig = plugin.getAuthConfig(cloud)

        this.@hostUID = cloud.configMap.hostUsername
        this.@hostPWD = cloud.configMap.hostPassword
    }


    def execute() {
        log.debug "Execute HostSync STARTED: ${cloud.id}"

        try {
            def hostListResults = ProxmoxApiComputeUtil.listProxmoxHypervisorHosts(apiClient, authConfig)
            log.debug("Host list results: $hostListResults")

            if (hostListResults.success) {
                def cloudItems = hostListResults?.data

                def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).filter {
                    ComputeServerIdentityProjection projection ->
                    if (projection.category == "proxmox.ve.host.${cloud.id}") {
                        return true
                    }
                    false
                }

                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, cloudItems)
                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                    domainObject.externalId == cloudItem?.node
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheusContext.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
                        return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: updateItemMap[server.id].masterItem)
                    }
                }.onAdd { itemsToAdd ->
                    addMissingHosts(cloud, itemsToAdd)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateMatchedHosts(cloud, updateItems)
                }.onDelete { removeItems ->
                    removeMissingHosts(cloud, removeItems)
                }.start()
            } else {
                log.error "Error in getting hosts : ${hostListResults}"
            }
        } catch(e) {
            log.error "Error in HostSync execute : ${e}", e
        }
        log.debug "Execute HostSync COMPLETED: ${cloud.id}"
    }


    /**
     * Proxmox VE version of the cluster, e.g. "9.2.10". Fetched once per sync and
     * cached; nodes in a cluster run the same version. Null if it cannot be read,
     * which must never stop the sync.
     */
    private String getClusterVersion() {
        if (this.@clusterVersion == null) {
            try {
                def versionResponse = ProxmoxApiComputeUtil.getProxmoxVersion(apiClient, authConfig)
                this.@clusterVersion = versionResponse?.success ? versionResponse?.data?.version?.toString() : ''
            } catch (e) {
                log.warn("Unable to read the Proxmox VE version: ${e.message}")
                this.@clusterVersion = ''
            }
        }
        return this.@clusterVersion ?: null
    }


    private addMissingHosts(Cloud cloud, Collection<Map> addList) {
        log.debug "addMissingHosts: ${cloud} ${addList.size()}"
        def serverType = new ComputeServerType(code: 'proxmox-ve-node')
        def serverOs = new OsType(code: 'linux')

        for (cloudItem in addList) {
            try {
                log.debug("Adding cloud host: $cloudItem with IP $cloudItem.ipAddress")
                
                // Handle null values with safe defaults for offline nodes
                def maxCpu = cloudItem.maxcpu ?: 0
                def maxMem = cloudItem.maxmem ?: 0
                def usedMem = cloudItem.mem ?: 0
                def maxDisk = cloudItem.maxdisk ?: 0
                def usedDisk = cloudItem.disk ?: 0
                def usedCpu = cloudItem.cpu ?: 0
                def usedCpuPercent = usedCpu * 100
                
                def serverConfig = [
                        account          : cloud.owner,
                        category         : "proxmox.ve.host.${cloud.id}",
                        cloud            : cloud,
                        name             : cloudItem.node,
                        resourcePool     : null,
                        externalId       : cloudItem.node,
                        uniqueId         : "${cloud.id}.${cloudItem.node}",
                        sshHost          : cloudItem.ipAddress,
                        sshUsername      : hostUID,
                        sshPassword      : hostPWD,
                        status           : 'provisioned',
                        provision        : false,
                        serverType       : 'hypervisor',
                        computeServerType: serverType,
                        serverOs         : serverOs,
                        platformVersion  : getClusterVersion(),
                        // Must not be null. views/admin/servers/_dashboard.gsp passes it to a
                        // Long method, and a null there fails to dispatch — "Ambiguous method
                        // overloading ... between [Character] and [Number]" — which Morpheus
                        // renders as a 403 page, so the host detail page looks like a
                        // permissions problem and is not one.
                        //
                        // Read from /nodes/<node>/status now (see
                        // ProxmoxApiComputeUtil.listProxmoxHypervisorHosts). This is what
                        // licensing counts sockets from: hardcoded to 0, a one-socket host
                        // reported 0 sockets, and its guests fell into the public-cloud
                        // bucket at 15:1. Still 0 when the node cannot say, which is the
                        // honest answer and the old behaviour.
                        coresPerSocket   : (cloudItem.coresPerSocket ?: 0) as Integer,
                        // Both must be non-null or the host detail page cannot render.
                        // admin/servers/_capacityInfo.gsp line 7 evaluates
                        //   ((capacityInfo?.maxMemory ?: 0l) - server.reservedMemory) * server.provisionPercent
                        // with a ?: guard on maxMemory and none on the other two, and the
                        // template runs only for vmHypervisor types — so a null here throws
                        // "Ambiguous method overloading for method java.lang.Long", which
                        // Morpheus serves as its 403 "You do not have permissions" page. The
                        // error names the wrong subsystem entirely; four separate permission
                        // theories were built on it before the stack trace was read.
                        //
                        // These properties do not exist in plugin-api 1.2.13. They were added
                        // in 1.4.x, which is why this plugin now builds against 1.4.2.
                        reservedMemory   : 0.0d,
                        provisionPercent : 1.0d,
                        // The host page prints "${server.platform} ${server.platformVersion}",
                        // but the whole block is behind <g:if test="${server.platform}">, so
                        // leaving platform null hides the Proxmox version even when
                        // platformVersion is set. osType alone feeds a different line.
                        platform         : 'linux',
                        osType           : 'linux',
                        hostname         : cloudItem.node,
                        externalIp       : cloudItem.ipAddress,
                        powerState       : (cloudItem.status == 'online') ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
                ]

                ComputeCapacityInfo capacityInfo = new ComputeCapacityInfo()

                Map capacityFieldValueMap = [
                        maxCores   : maxCpu.toLong(),
                        maxStorage : maxDisk.toLong(),
                        usedStorage: usedDisk.toLong(),
                        maxMemory  : maxMem.toLong(),
                        usedMemory : usedMem.toLong(),
                        usedCpu    : usedCpuPercent.toLong(),
                ]

                ComputeServer newServer = new ComputeServer(serverConfig)
                ProxmoxMiscUtil.doUpdateDomainEntity(capacityInfo, capacityFieldValueMap)
                newServer.capacityInfo = capacityInfo
                log.debug("Adding Compute Server: $serverConfig")
                if (!morpheusContext.async.computeServer.bulkCreate([newServer]).blockingGet()){
                    log.error "Error in creating host server ${newServer}"
                }

            } catch(e) {
                log.error "Error in creating host: ${e}", e
            }
        }
    }


    private updateMatchedHosts(Cloud cloud, List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems) {
        log.debug("Updating ${updateItems.size()} Hosts...")
        def updates = []

        try {
            for (def updateItem in updateItems) {
                def existingItem = updateItem.existingItem
                def cloudItem = updateItem.masterItem
                def doUpdate = false

                ComputeCapacityInfo capacityInfo = existingItem.getComputeCapacityInfo() ?: new ComputeCapacityInfo()

                // Handle null values with safe defaults for offline nodes
                def maxCpu = cloudItem.maxcpu ?: 0
                def maxMem = cloudItem.maxmem ?: 0
                def usedMem = cloudItem.mem ?: 0
                def maxDisk = cloudItem.maxdisk ?: 0
                def usedDisk = cloudItem.disk ?: 0
                def usedCpu = cloudItem.cpu  ?: 0
                def usedCpuPercent = usedCpu * 100

                Map serverFieldValueMap = [
                        account     : cloud.owner,
                        category    : "proxmox.ve.host.${cloud.id}",
                        cloud       : cloud,
                        name        : cloudItem.node,
                        resourcePool: null,
                        uniqueId    : "${cloud.id}.${cloudItem.node}",
                        hostname    : cloudItem.hostName ?: cloudItem.node,
                        externalIp  : cloudItem.ipAddress,
                        platformVersion: getClusterVersion(),
                        platform       : 'linux',
                        // The freshly read topology wins, so a host created by an earlier
                        // build — every one of which stored 0 — is repaired on the next
                        // sync rather than keeping its zero for ever. Falls back to what is
                        // already stored when the node did not answer.
                        coresPerSocket : ((cloudItem.coresPerSocket ?: existingItem.coresPerSocket) ?: 0) as Integer,
                        // Repairs hosts synced by an earlier build, which were created before
                        // these could be set and whose detail page therefore 403s.
                        reservedMemory : existingItem.reservedMemory ?: 0.0d,
                        provisionPercent: existingItem.provisionPercent ?: 1.0d,
                        maxCores    : maxCpu.toLong(),
                        maxStorage  : maxDisk.toLong(),
                        usedStorage : usedDisk.toLong(),
                        maxMemory   : maxMem.toLong(),
                        usedMemory  : usedMem.toLong(),
                        usedCpu     : usedCpuPercent.toLong(),
                        powerState  : (cloudItem.status == 'online') ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
                ]
                // The cloud form's node SSH account is what provisioning uses on every node, so
                // it wins over whatever the host record holds. A host created by an earlier build,
                // or edited by hand, is repaired on the next sync; an empty cloud value changes
                // nothing.
                if (hostUID && hostPWD) {
                    if (existingItem.sshUsername != hostUID) {
                        log.info("Host ${cloudItem.node}: SSH user '${existingItem.sshUsername}' replaced by the cloud's node SSH user '${hostUID}'")
                    }
                    serverFieldValueMap.sshUsername = hostUID
                    serverFieldValueMap.sshPassword = hostPWD
                }
                if (cloudItem.ipAddress && !existingItem.sshHost) {
                    serverFieldValueMap.sshHost = cloudItem.ipAddress
                }

                Map capacityFieldValueMap = [
                        maxCores   : maxCpu.toLong(),
                        maxStorage : maxDisk.toLong(),
                        usedStorage: usedDisk.toLong(),
                        maxMemory  : maxMem.toLong(),
                        usedMemory : usedMem.toLong(),
                        usedCpu    : usedCpuPercent.toLong(),
                ]

                if (ProxmoxMiscUtil.doUpdateDomainEntity(existingItem, serverFieldValueMap) ||
                        ProxmoxMiscUtil.doUpdateDomainEntity(capacityInfo, capacityFieldValueMap)) {
                    existingItem.capacityInfo = capacityInfo
                    updates << existingItem
                }
            }

            if (updates) morpheusContext.async.computeServer.bulkSave(updates).blockingGet()

        } catch(e) {
            log.warn("error updating host stats: ${e}", e)
        }

        //Examples:
        // Nutanix - https://github.com/gomorpheus/morpheus-nutanix-prism-plugin/blob/api-1.1.x/src/main/groovy/com/morpheusdata/nutanix/prism/plugin/sync/HostsSync.groovy
        // XCP-ng - https://github.com/gomorpheus/morpheus-xenserver-plugin/blob/main/src/main/groovy/com/morpheusdata/xen/sync/HostSync.groovy
        // Openstack - https://github.com/gomorpheus/morpheus-openstack-plugin/blob/main/src/main/groovy/com/morpheusdata/openstack/plugin/sync/HostsSync.groovy
    }


    private removeMissingHosts(Cloud cloud, List<ComputeServerIdentityProjection> removeList) {
        log.debug("Remove Hosts...")
        morpheusContext.async.computeServer.bulkRemove(removeList).blockingGet()
    }
}
