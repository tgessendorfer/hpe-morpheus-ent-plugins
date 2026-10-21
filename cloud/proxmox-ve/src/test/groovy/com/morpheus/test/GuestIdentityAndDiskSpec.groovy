package com.morpheus.test

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.proxmox.ve.ProxmoxVeProvisionProvider
import com.morpheusdata.proxmox.ve.sync.VMSync
import com.morpheusdata.proxmox.ve.sync.VirtualImageLocationSync
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import spock.lang.Specification

class GuestIdentityAndDiskSpec extends Specification {

    static final long GB = 1024L * 1024L * 1024L
    static final String UUID_A = '5c1a3b7e-0d2f-4c9a-8e6b-1f2a3b4c5d6e'
    static final String UUID_B = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'

    static StorageVolume volume(String device, boolean root, long size, String storage = 'local-lvm') {
        new StorageVolume(deviceName: device, rootVolume: root, maxStorage: size,
                datastore: new Datastore(externalId: storage))
    }

    // ---- resizeVM: existing disks are never re-allocated ----

    def "only disks the VM does not have are allocated"() {
        given:
        Map vmConfig = [scsi0: 'local-lvm:vm-101-disk-0,size=20G', scsi1: 'local-lvm:vm-101-disk-1,size=50G']
        def volumes = [volume('scsi0', true, 20 * GB), volume('scsi1', false, 50 * GB), volume('scsi2', false, 10 * GB)]

        expect:
        ProxmoxApiComputeUtil.disksToAllocate(vmConfig, volumes) == [scsi2: 'local-lvm:10,size=10G']
    }

    def "a plan change on a VM that has all its disks allocates nothing"() {
        given:
        Map vmConfig = [scsi0: 'local-lvm:vm-101-disk-0,size=20G', scsi1: 'local-lvm:vm-101-disk-1,size=50G']
        def volumes = [volume('scsi0', true, 20 * GB), volume('scsi1', false, 80 * GB)]

        expect:
        ProxmoxApiComputeUtil.disksToAllocate(vmConfig, volumes).isEmpty()
    }

    def "an unreadable VM config allocates nothing"() {
        expect:
        ProxmoxApiComputeUtil.disksToAllocate(null, [volume('scsi1', false, GB)]).isEmpty()
    }

    def "a fresh clone with only the root disk gets its data disks"() {
        given:
        Map vmConfig = [scsi0: 'local-lvm:vm-101-disk-0,size=20G']
        def volumes = [volume('scsi0', true, 20 * GB), volume('scsi1', false, 5 * GB, 'ceph')]

        expect:
        ProxmoxApiComputeUtil.disksToAllocate(vmConfig, volumes) == [scsi1: 'ceph:5,size=5G']
    }

    // ---- guest identity ----

    def "the smbios uuid is read from the VM config"() {
        expect:
        ProxmoxApiComputeUtil.vmUuid(config) == expected

        where:
        config                                                            || expected
        [smbios1: "uuid=${UUID_A.toUpperCase()}"]                         || UUID_A
        [smbios1: "manufacturer=QUE=,uuid=${UUID_A},base64=1"]            || UUID_A
        [smbios1: 'manufacturer=QUE='.toString()]                         || null
        [:]                                                               || null
        null                                                              || null
    }

    def "a stored uuid must equal the VM's; a record without one passes"() {
        expect:
        ProxmoxApiComputeUtil.sameGuest(stored, config) == expected

        where:
        stored | config                     || expected
        null   | [smbios1: "uuid=${UUID_A}"] || true
        UUID_A | [smbios1: "uuid=${UUID_A}"] || true
        UUID_A | [smbios1: "uuid=${UUID_B}"] || false
        UUID_A | [:]                         || false
        UUID_A | null                        || false
    }

    def "the sync adopts the uuid of a record from before 0.1.31 only while the name still matches"() {
        given:
        ComputeServer server = new ComputeServer(name: name)
        boolean changed = VMSync.trackIdentity(server, [vmid: 101, name: 'web-01', uuid: UUID_A])
        String stored = server.getConfigProperty(ProxmoxVeProvisionProvider.GUEST_UUID_PROPERTY)

        expect:
        changed == expected
        stored == (expected ? UUID_A : null)

        where:
        name     || expected
        'web-01' || true
        'db-01'  || false
    }

    def "the sync flags a record whose VMID now belongs to another guest, once"() {
        given:
        ComputeServer server = new ComputeServer(name: 'web-01')
        server.setConfigProperty(ProxmoxVeProvisionProvider.GUEST_UUID_PROPERTY, UUID_A)
        boolean first = VMSync.trackIdentity(server, [vmid: 101, name: 'other', uuid: UUID_B])
        boolean second = VMSync.trackIdentity(server, [vmid: 101, name: 'other', uuid: UUID_B])
        String message = server.statusMessage
        String stored = server.getConfigProperty(ProxmoxVeProvisionProvider.GUEST_UUID_PROPERTY)

        expect:
        first
        !second
        message.contains(UUID_B)
        message.contains(UUID_A)
        stored == UUID_A
    }

    def "the sync leaves a record alone whose uuid still matches, and one whose VM reports none"() {
        given:
        ComputeServer server = new ComputeServer(name: 'web-01')
        server.setConfigProperty(ProxmoxVeProvisionProvider.GUEST_UUID_PROPERTY, UUID_A)

        expect:
        !VMSync.trackIdentity(server, [vmid: 101, name: 'web-01', uuid: UUID_A])
        !VMSync.trackIdentity(server, [vmid: 101, name: 'web-01', uuid: null])
        server.statusMessage == null
    }

    // ---- image removal ----

    def "only the sync's own single-location template images may be deleted with their template"() {
        given:
        VirtualImage image = new VirtualImage(category: category, systemImage: system, userUploaded: uploaded)
        image.imageLocations = (1..locations).collect { new VirtualImageLocation() }

        expect:
        VirtualImageLocationSync.syncOwnsImage(image) == expected

        where:
        category        | system | uploaded | locations || expected
        'proxmox.image' | false  | false    | 1         || true
        'proxmox.image' | false  | null     | 1         || true
        'proxmox.image' | false  | true     | 1         || false
        'proxmox.image' | true   | false    | 1         || false
        'proxmox.image' | false  | false    | 2         || false
        'other'         | false  | false    | 1         || false
    }
}
