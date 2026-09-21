package com.morpheus.test

import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.proxmox.ve.ProxmoxNetworkProvider
import com.morpheusdata.model.ComputeServerInterface
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

    def "picks usable IPv4 addresses out of a guest agent interface listing"() {
        given:
        def agentResult = [result: [
                [name: 'lo', 'ip-addresses': [
                        ['ip-address': '127.0.0.1', 'ip-address-type': 'ipv4', prefix: 8],
                        ['ip-address': '::1', 'ip-address-type': 'ipv6', prefix: 128]]],
                [name: 'ens18', 'ip-addresses': [
                        ['ip-address': '169.254.10.5', 'ip-address-type': 'ipv4', prefix: 16],
                        ['ip-address': 'fe80::be24:11ff:fe7b:fe7e', 'ip-address-type': 'ipv6', prefix: 64],
                        ['ip-address': '192.168.0.87', 'ip-address-type': 'ipv4', prefix: 24]]]
        ]]

        expect:
        ProxmoxApiComputeUtil.extractGuestIpv4Addresses(agentResult) == ['192.168.0.87']
        ProxmoxApiComputeUtil.extractGuestIpv4Addresses([result: []]) == []
        ProxmoxApiComputeUtil.extractGuestIpv4Addresses(null) == []
    }

    def "builds Proxmox cloud-init network settings from the server interfaces"() {
        given:
        def lan = new Network(name: 'vmbr0', cidr: '192.168.0.0/24', gateway: '192.168.0.1', dhcpServer: true)
        def dmz = new Network(name: 'vmbr1', netmask: '255.255.255.0', gateway: '10.0.0.1',
                dnsPrimary: '10.0.0.53', dnsSecondary: '10.0.0.54')
        def dhcpNic = new ComputeServerInterface(externalId: 'net0', network: lan, dhcp: true)
        def staticNic = new ComputeServerInterface(externalId: 'net1', network: dmz, ipAddress: '10.0.0.20', dhcp: false)
        def unnamedNic = new ComputeServerInterface(network: lan)

        expect:
        ProxmoxApiComputeUtil.buildCloudInitNetworkSettings([dhcpNic, staticNic]) == [
                ipconfig0 : 'ip=dhcp',
                ipconfig1 : 'ip=10.0.0.20/24,gw=10.0.0.1',
                nameserver: '10.0.0.53 10.0.0.54'
        ]
        ProxmoxApiComputeUtil.buildCloudInitNetworkSettings([unnamedNic]) == [ipconfig0: 'ip=dhcp']
        ProxmoxApiComputeUtil.buildCloudInitNetworkSettings([]) == [:]
        ProxmoxApiComputeUtil.buildCloudInitNetworkSettings(null) == [:]
    }

    def "keeps an existing NIC's model and MAC address and gives a new NIC the guest's model"() {
        expect:
        ProxmoxApiComputeUtil.buildNicConfig('virtio=BC:24:11:7B:FE:7E,bridge=vmbr0', 'vmbr1', 'e1000e') == 'virtio=BC:24:11:7B:FE:7E,bridge=vmbr1'
        ProxmoxApiComputeUtil.buildNicConfig('virtio=BC:24:11:7B:FE:7E,bridge=vmbr0,firewall=1', 'vmbr0', 'virtio') == 'virtio=BC:24:11:7B:FE:7E,bridge=vmbr0,firewall=1'
        ProxmoxApiComputeUtil.buildNicConfig('e1000e=BC:24:11:09:1B:9D', 'vmbr0', 'virtio') == 'e1000e=BC:24:11:09:1B:9D,bridge=vmbr0'
        ProxmoxApiComputeUtil.buildNicConfig(null, 'vmbr0', 'virtio') == 'bridge=vmbr0,model=virtio'
        ProxmoxApiComputeUtil.buildNicConfig('', 'vmbr0', 'e1000e') == 'bridge=vmbr0,model=e1000e'
    }

    def "converts a netmask to a prefix length"() {
        expect:
        ProxmoxApiComputeUtil.netmaskToPrefixLength(netmask) == expected

        where:
        netmask           || expected
        '255.255.255.0'   || 24
        '255.255.0.0'     || 16
        '255.255.255.128' || 25
        '255.255.255.255' || 32
        'garbage'         || null
        null              || null
    }

    def "recognises whether a storage offers snippets"() {
        expect:
        ProxmoxApiComputeUtil.storageContentHasSnippets(content) == expected

        where:
        content                          || expected
        'iso,vztmpl,backup'              || false
        'iso,vztmpl,backup,snippets'     || true
        'snippets'                       || true
        'images, snippets'               || true
        ''                               || false
        null                             || false
    }

    def "reads the guest agent switch from a VM config"() {
        expect:
        ProxmoxApiComputeUtil.guestAgentEnabled(agent == null ? [:] : [agent: agent]) == expected

        where:
        agent                              || expected
        '1'                                || true
        'enabled=1'                        || true
        '1,fstrim_cloned_disks=1'          || true
        'enabled=1,fstrim_cloned_disks=1'  || true
        '0'                                || false
        'enabled=0'                        || false
        null                               || false
    }
}
