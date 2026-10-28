package com.morpheus.test

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.proxmox.ve.ProxmoxVeProvisionProvider
import com.morpheusdata.proxmox.ve.sync.VMSync
import com.morpheusdata.proxmox.ve.sync.VirtualImageLocationSync
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.proxmox.ve.util.ProxmoxSshUtil
import spock.lang.Specification

class ProvisionAndSyncSafetySpec extends Specification {

    // ---- NIC numbering ----

    def "every interface gets its own netN, the primary interface net0"() {
        given:
        def second = new ComputeServerInterface(name: 'second', displayOrder: 2)
        def primary = new ComputeServerInterface(name: 'primary', primaryInterface: true, displayOrder: 1)
        def third = new ComputeServerInterface(name: 'third', displayOrder: 3)
        def unordered = new ComputeServerInterface(name: 'unordered')

        expect:
        ProxmoxApiComputeUtil.orderInterfaces([third, unordered, second, primary])*.name == ['primary', 'second', 'third', 'unordered']
        ProxmoxApiComputeUtil.orderInterfaces([]) == []
        ProxmoxApiComputeUtil.orderInterfaces(null) == []
    }

    def "display order 0 is the model's default and counts as unset"() {
        given:
        def numbered = new ComputeServerInterface(name: 'numbered', displayOrder: 1)
        def unnumbered = new ComputeServerInterface(name: 'unnumbered')

        expect:
        unnumbered.displayOrder == 0
        ProxmoxApiComputeUtil.orderInterfaces([unnumbered, numbered])*.name == ['numbered', 'unnumbered']
    }

    def "a NIC added on reconfigure takes the index above the highest one in use"() {
        expect:
        ProxmoxApiComputeUtil.nextNicIndex(nics) == expected

        where:
        nics                                            || expected
        [[label: 'net0'], [label: 'net2']]              || 3
        [[label: 'net0'], [label: 'net1']]              || 2
        [[label: 'net1']]                               || 2
        []                                              || 0
        null                                            || 0
        [[label: 'scsi0'], [label: 'net0']]             || 1
    }

    // ---- template lookup ----

    def "only a template stands in for an image's template id"() {
        given:
        def resources = [
                [type: 'qemu', vmid: 9000, node: 'pve1', template: 1],
                [type: 'qemu', vmid: 101, node: 'pve2', template: 0],
                [type: 'lxc', vmid: 200, node: 'pve1', template: 0]
        ]

        expect:
        ProxmoxApiComputeUtil.templateNodeIn(resources, '9000') == 'pve1'
        ProxmoxApiComputeUtil.templateNodeIn(resources, '101') == null
        ProxmoxApiComputeUtil.templateNodeIn(resources, '200') == null
        ProxmoxApiComputeUtil.templateNodeIn(resources, '555') == null
        ProxmoxApiComputeUtil.templateNodeIn(null, '9000') == null
    }

    def "this cloud's locations name the template; the image's global id only without one"() {
        expect:
        ProxmoxVeProvisionProvider.templateCandidates(locationIds, globalId) == expected

        where:
        locationIds            | globalId || expected
        ['9001', '9002']       | '9000'   || ['9001', '9002']
        ['9001', null, '9001'] | '9000'   || ['9001']
        []                     | '9000'   || ['9000']
        null                   | '9000'   || ['9000']
        []                     | null     || []
    }

    // ---- VM sync ----

    def "a VM that is still being cloned or restored is not added as discovered"() {
        expect:
        VMSync.beingCreated(item) == expected

        where:
        item                     || expected
        [lock: 'clone']          || true
        [lock: 'create']         || true
        [lock: 'backup']         || false
        [lock: 'migrate']        || false
        [:]                      || false
        null                     || false
    }

    def "an empty listed address leaves the recorded address alone"() {
        expect:
        VMSync.addressFields('192.0.2.10') == [externalIp: '192.0.2.10', internalIp: '192.0.2.10']
        VMSync.addressFields('') == [:]
        VMSync.addressFields(null) == [:]
    }

    def "the sync follows a VM to the node Proxmox lists it on"() {
        given:
        def pve1 = new ComputeServer(id: 1L, externalId: 'pve1')
        def pve2 = new ComputeServer(id: 2L, externalId: 'pve2')
        def hosts = [pve1: pve1, pve2: pve2]
        def migrated = new ComputeServer(name: 'web-01', parentServer: pve1)
        def unmoved = new ComputeServer(name: 'web-02', parentServer: pve2)
        def orphan = new ComputeServer(name: 'web-03')

        expect:
        VMSync.trackParentNode(migrated, 'pve2', hosts)
        migrated.parentServer.id == 2L
        !VMSync.trackParentNode(unmoved, 'pve2', hosts)
        !VMSync.trackParentNode(unmoved, 'pve9', hosts)
        !VMSync.trackParentNode(unmoved, null, hosts)
        unmoved.parentServer.id == 2L
        VMSync.trackParentNode(orphan, 'pve1', hosts)
        orphan.parentServer.id == 1L
    }

    // ---- image sync ----

    def "the image sync rewrites only its own template records"() {
        expect:
        VirtualImageLocationSync.syncMayRewriteImage(
                new VirtualImage(category: category, systemImage: system, userUploaded: uploaded)) == expected

        where:
        category        | system | uploaded || expected
        'proxmox.image' | false  | false    || true
        'proxmox.image' | null   | null     || true
        'proxmox.image' | true   | false    || false
        'proxmox.image' | false  | true     || false
        'other'         | false  | false    || false
        null            | false  | false    || false
    }

    // ---- shell and API helpers ----

    def "values in node commands stay one shell word"() {
        expect:
        ProxmoxSshUtil.shellQuote(value) == expected

        where:
        value                                   || expected
        '/var/lib/vz/template/qemu/debian.qcow2' || "'/var/lib/vz/template/qemu/debian.qcow2'"
        'my image.qcow2'                        || "'my image.qcow2'"
        'a;rm -rf x $(id).qcow2'                || "'a;rm -rf x \$(id).qcow2'"
        "it's.qcow2"                            || "'it'\\''s.qcow2'"
        null                                    || "''"
    }

    def "the node's file check answer is read line by line"() {
        expect:
        ProxmoxSshUtil.saysExists(output) == expected

        where:
        output                                  || expected
        'EXISTS'                                || true
        'EXISTS\n'                              || true
        'Welcome to pve1\nEXISTS\n'             || true
        'NOT_EXISTS'                            || false
        'Welcome to pve1\nNOT_EXISTS\n'         || false
        ''                                      || false
        null                                    || false
    }

    def "a query string becomes request query parameters"() {
        expect:
        ProxmoxApiComputeUtil.parseQueryString('content=images&vmid=101') == [content: 'images', vmid: '101']
        ProxmoxApiComputeUtil.parseQueryString('type=vm') == [type: 'vm']
        ProxmoxApiComputeUtil.parseQueryString('flag') == [flag: '']
        ProxmoxApiComputeUtil.parseQueryString(null) == [:]
    }

    def "only a missing SDN API counts as no vnets"() {
        expect:
        ProxmoxApiComputeUtil.sdnUnavailable(code) == expected

        where:
        code  || expected
        '501' || true
        '404' || true
        501   || true
        '500' || false
        '401' || false
        null  || false
    }

    def "a clone refused for a taken VMID is recognised"() {
        expect:
        ProxmoxApiComputeUtil.vmIdTaken('{"data":null,"message":"VM 105 already exists on node \'pve1\'\\n"}')
        ProxmoxApiComputeUtil.vmIdTaken('unable to create VM 105: config file already exists')
        !ProxmoxApiComputeUtil.vmIdTaken('{"data":null,"message":"storage \'nfs\' is not online"}')
        !ProxmoxApiComputeUtil.vmIdTaken(null)
    }
}
