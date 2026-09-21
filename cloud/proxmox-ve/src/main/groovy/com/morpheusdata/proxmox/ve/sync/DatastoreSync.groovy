package com.morpheusdata.proxmox.ve.sync

import com.morpheusdata.proxmox.ve.ProxmoxVePlugin
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.proxmox.ve.util.ProxmoxMiscUtil
import groovy.util.logging.Slf4j


/**
 * @author Neil van Rensburg
 */

@Slf4j
class DatastoreSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ProxmoxVePlugin plugin
    private HttpApiClient apiClient
    private Map authConfig


    public DatastoreSync(ProxmoxVePlugin proxmoxVePlugin, Cloud cloud, HttpApiClient apiClient) {
        this.@plugin = proxmoxVePlugin
        this.@cloud = cloud
        this.@morpheusContext = proxmoxVePlugin.morpheus
        this.@apiClient = apiClient
        this.@authConfig = plugin.getAuthConfig(cloud)
    }



    def execute() {
        log.debug "Datastore Sync STARTED: ${cloud.id}"

        def datastoreResults = ProxmoxApiComputeUtil.listProxmoxDatastores(apiClient, authConfig)
        log.debug("Datastore list results: $datastoreResults")

        if (!datastoreResults?.success || !(datastoreResults.data instanceof Collection)) {
            log.warn("Datastore sync skipped, the storage listing failed: ${datastoreResults?.msg}")
            return
        }

        // The pool membership decides which resource pools a datastore is offered for
        // (assignedZonePools, read by filterDatastores). A failed pool listing must not
        // sync either: it would strip every datastore of its pools.
        def poolResults = ProxmoxApiComputeUtil.listProxmoxPools(apiClient, authConfig)
        if (!poolResults?.success || !(poolResults.data instanceof Collection)) {
            log.warn("Datastore sync skipped, the pool listing failed: ${poolResults?.msg}")
            return
        }
        Map<String, List<CloudPool>> poolsByStorage = loadPoolsByStorage(poolResults.data as Collection<Map>)

        def cloudItems = datastoreResults?.data
        def domainRecords = morpheusContext.async.cloud.datastore.listSyncProjections(cloud.id)

        SyncTask<DatastoreIdentity, Map, StorageVolume> syncTask = new SyncTask<>(domainRecords, cloudItems as Collection)
        syncTask.addMatchFunction { DatastoreIdentity domainObject, Map cloudItem ->
            domainObject.externalId == cloudItem.storage
        }.onAdd { itemsToAdd ->
            addMissingDatastores(cloud, itemsToAdd, poolsByStorage)
        }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<DatastoreIdentity, Map>> updateItems ->
            Map<Long, SyncTask.UpdateItemDto<DatastoreIdentity, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
            return morpheusContext.async.cloud.datastore.listById(updateItems?.collect { it.existingItem.id }).map { Datastore datastore ->
                return new SyncTask.UpdateItem<Datastore, Map>(existingItem: datastore, masterItem: updateItemMap[datastore.id].masterItem)
            }
        }.onUpdate { List<SyncTask.UpdateItem<Datastore, Map>> updateItems ->
            updateMatchedDatastores(cloud, updateItems, poolsByStorage)
        }.onDelete { removeItems ->
            removeMissingDatastores(removeItems)
        }.start()

        log.debug "Datastore Sync COMPLETED: ${cloud.id}"
    }


    /**
     * The synced CloudPool records of every Proxmox pool with storage members, keyed by the
     * Proxmox storage name. PoolSync runs first in refresh(), so a pool that Proxmox lists is
     * normally already a CloudPool; one that is not yet is left out until the next refresh.
     */
    private Map<String, List<CloudPool>> loadPoolsByStorage(Collection<Map> proxmoxPools) {
        Map<String, Set<String>> membership = ProxmoxApiComputeUtil.storagePoolMembership(proxmoxPools)
        Map<String, List<CloudPool>> rtn = [:]
        Set<String> poolIds = membership.values().flatten() as Set<String>
        if (!poolIds) {
            return rtn
        }
        Map<String, CloudPool> cloudPoolsByExternalId = [:]
        morpheusContext.async.cloud.pool.listByCloudAndExternalIdIn(cloud.id, poolIds).blockingSubscribe { CloudPool pool ->
            cloudPoolsByExternalId[pool.externalId] = pool
        }
        membership.each { String storage, Set<String> storagePoolIds ->
            rtn[storage] = storagePoolIds.collect { cloudPoolsByExternalId[it] }.findAll { it != null }
        }
        log.debug("Datastore pool membership for cloud ${cloud.id}: ${rtn.collectEntries { k, v -> [(k): v*.externalId] }}")
        return rtn
    }


    private addMissingDatastores(Cloud cloud, Collection itemsToAdd, Map<String, List<CloudPool>> poolsByStorage) {
        try {
            def adds = []
            itemsToAdd?.each { cloudItem ->
                log.debug("Adding datastore: $cloudItem")
                // Only allow provisioning if datastore supports VM disk images
                boolean supportsImages = cloudItem.content?.toLowerCase()?.contains("images") ?: false
                if (!supportsImages) {
                    log.info("Datastore '${cloudItem.storage}' does not support VM images (content: ${cloudItem.content}). Setting allowProvision to false.")
                }
                def datastoreConfig = [
                    owner          : new Account(id: cloud.owner.id),
                    name           : cloudItem.storage,
                    externalId     : cloudItem.storage,
                    cloud          : cloud,
                    storageSize    : cloudItem.total.toLong(),
                    freeSpace      : cloudItem.avail.toLong(),
                    category       : "proxmox-ve-datastore.${cloud.id}",
                    drsEnabled     : false,
                    online         : true,
                    allowProvision : supportsImages,
                    refType        : 'ComputeZone',
                    refId          : cloud.id,
                    rawData        : cloudItem.nodes
                ]
                log.debug("Adding datastore: $datastoreConfig")
                Datastore add = new Datastore(datastoreConfig)
                add.assignedZonePools = (poolsByStorage[cloudItem.storage as String] ?: []) as List<CloudPool>
                adds << add
            }
            if (adds.size() > 0) {
                morpheusContext.async.cloud.datastore.bulkCreate(adds).blockingGet()
                log.debug("Added ${adds.size()} datastores to cloud ${cloud.name}")
            }
        } catch (e) {
            log.error "Error in addMissingDatastores: ${e}", e
        }
    }


    private updateMatchedDatastores(Cloud cloud, List<SyncTask.UpdateItem<Datastore, Map>> updateItems, Map<String, List<CloudPool>> poolsByStorage) {
        def updates = []
        try {
            for (def updateItem in updateItems) {
                def existingItem = updateItem.existingItem
                def cloudItem = updateItem.masterItem

                    // Only allow provisioning if datastore supports VM disk images
                    boolean supportsImages = cloudItem.content?.toLowerCase()?.contains("images") ?: false
                    if (!supportsImages) {
                        log.info("Datastore '${cloudItem.storage}' does not support VM images (content: ${cloudItem.content}). Setting allowProvision to false.")
                    }
                    Map datastoreFieldValueMap = [
                            owner          : new Account(id: cloud.owner.id),
                            name           : cloudItem.storage,
                            cloud          : cloud,
                            storageSize    : cloudItem.total.toLong(),
                            freeSpace      : cloudItem.avail.toLong(),
                            category       : "proxmox-ve-datastore.${cloud.id}",
                            drsEnabled     : false,
                            online         : true,
                            allowProvision : supportsImages,
                            refType        : 'ComputeZone',
                            refId          : cloud.id,
                            rawData        : cloudItem.nodes
                    ]

                    boolean changed = ProxmoxMiscUtil.doUpdateDomainEntity(existingItem, datastoreFieldValueMap)

                    // Reconcile the pool membership: pools the storage joined are added, pools it
                    // left are removed, compared by id so an unchanged list is not saved again.
                    List<CloudPool> assignedPools = (poolsByStorage[cloudItem.storage as String] ?: []) as List<CloudPool>
                    Set<Long> currentPoolIds = (existingItem.assignedZonePools?.collect { it.id } ?: []) as Set<Long>
                    Set<Long> assignedPoolIds = assignedPools.collect { it.id } as Set<Long>
                    if (currentPoolIds != assignedPoolIds) {
                        log.info("Datastore '${cloudItem.storage}' pools changed: ${existingItem.assignedZonePools*.externalId} -> ${assignedPools*.externalId}")
                        existingItem.assignedZonePools = assignedPools
                        changed = true
                    }

                    if (changed) {
                        updates << existingItem
                    }
            }
            if (updates) {
                morpheusContext.async.cloud.datastore.bulkSave(updates).blockingGet()

            }
        } catch (e) {
            log.warn("error datastore VM properties and stats: ${e}", e)
        }

        //Example:
        // Openstack - https://github.com/gomorpheus/morpheus-nutanix-prism-plugin/blob/master/src/main/groovy/com/morpheusdata/nutanix/prism/plugin/sync/DatastoresSync.groovy
    }

    private removeMissingDatastores(List<DatastoreIdentity> removeItems) {
        log.debug("Removing Datastores that no longer exist")
        morpheusContext.services.cloud.datastore.bulkRemove(removeItems)
    }
}
