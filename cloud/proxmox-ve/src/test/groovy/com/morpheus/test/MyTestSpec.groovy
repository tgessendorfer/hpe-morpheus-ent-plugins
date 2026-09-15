package com.morpheus.test

import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.proxmox.ve.ProxmoxNetworkProvider
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkSubnet
import spock.lang.Specification

class MyTestSpec extends Specification {

    def "parses Proxmox VE release versions"() {
        expect:
        ProxmoxApiComputeUtil.parseProxmoxVersion(version) == expected

        where:
        version         || expected
        '9.0.3'         || [major: 9, minor: 0, patch: 3]
        '9.1'           || [major: 9, minor: 1, patch: 0]
        '8.4.1-pve1'    || [major: 8, minor: 4, patch: 1]
        ''              || [:]
        'not-a-version' || [:]
    }

    def "network edit callbacks return required response data"() {
        given:
        def provider = new ProxmoxNetworkProvider(null, null)
        def network = new Network(name: 'vmbr0')
        def subnet = new NetworkSubnet(name: 'vmbr0-subnet')

        expect:
        provider.updateNetwork(network, [:]).data.is(network)
        provider.validateNetwork(network, [:]).data.is(network)
        provider.createSubnet(subnet, network, [:]).data.is(subnet)
        provider.updateSubnet(subnet, network, [:]).data.is(subnet)
    }

    def "finds an orphaned cloud-init volume for provisioning retry"() {
        given:
        def volumes = [
                [volid: 'LAB-V5-L2:vm-137-disk-0'],
                [volid: 'LAB-V5-L2:vm-137-cloudinit'],
                [volid: 'LAB-V5-L2:vm-138-cloudinit']
        ]

        expect:
        ProxmoxApiComputeUtil.findCloudInitVolumeId(volumes, '137') == 'LAB-V5-L2:vm-137-cloudinit'
        ProxmoxApiComputeUtil.findCloudInitVolumeId(volumes, '139') == null
    }
}
