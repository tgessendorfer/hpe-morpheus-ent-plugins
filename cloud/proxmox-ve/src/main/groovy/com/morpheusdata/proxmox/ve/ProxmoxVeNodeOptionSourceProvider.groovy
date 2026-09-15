package com.morpheusdata.proxmox.ve

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import groovy.util.logging.Slf4j

@Slf4j
class ProxmoxVeNodeOptionSourceProvider extends AbstractOptionSourceProvider {
    ProxmoxVePlugin plugin
    MorpheusContext morpheusContext

    ProxmoxVeNodeOptionSourceProvider(ProxmoxVePlugin plugin, MorpheusContext context) {
        this.plugin = plugin
        this.morpheusContext = context
    }

    @Override
    MorpheusContext getMorpheus() {
        morpheusContext
    }

    @Override
    Plugin getPlugin() {
        plugin
    }

    @Override
    String getCode() {
        'proxmox-ve-node-options'
    }

    @Override
    String getName() {
        'Proxmox VE Node Options'
    }

    @Override
    List<String> getMethodNames() {
        ['proxmoxNodes']
    }

    def proxmoxNodes(args) {
        log.debug "proxmoxNodes: ${args}"
        def cloudId = args?.size() > 0 ? args.getAt(0).zoneId.toLong() : null
        def ids = []
        morpheusContext.async.computeServer.listIdentityProjections(cloudId, null).filter { ComputeServerIdentityProjection projection ->
            projection.category == "proxmox.ve.host.${cloudId}"
        }.blockingSubscribe { ids << it.id }

        def options = []
        if(ids) {
            morpheusContext.async.computeServer.listById(ids).blockingSubscribe { ComputeServer server ->
                def inactive = server.powerState != ComputeServer.PowerState.on
                def name = server.name
                if(inactive)
                    name += ' (inactive)'
                options << [name: name, value: server.externalId, inactive: inactive]
            }
        }

        options = options.sort { a, b ->
            if(a.inactive == b.inactive)
                a.name <=> b.name
            else if(!a.inactive && b.inactive)
                -1
            else
                1
        }
        return options.collect { [name: it.name, value: it.value] }
    }
}
