package com.morpheusdata.proxmox.ve.util

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import org.apache.http.entity.ContentType
import groovy.json.JsonSlurper

/**
 * @author Neil van Rensburg
 */

@Slf4j
class ProxmoxApiComputeUtil {

    //static final String API_BASE_PATH = "/api2/json"
    static final Long API_CHECK_WAIT_INTERVAL = 2000

    /**
     * How long a job reuses its login ticket. Proxmox accepts a ticket for two hours; after one
     * hour the job logs in again, so a ticket handed to a long task wait stays well inside that.
     */
    static final long TICKET_MAX_AGE_MS = 60L * 60L * 1000L

    /** Key of the login ticket in a job's authConfig (see getApiV2Token). */
    static final String TICKET_KEY = 'apiTicket'

    /**
     * Seconds Proxmox gives a guest to shut down before it forces the VM off (Stop and delete:
     * shutdown with forceStop), or to go down before a reboot gives up (Restart).
     */
    static final long GUEST_SHUTDOWN_TIMEOUT_SEC = 120L

    /** Seconds a power task is waited for on top of GUEST_SHUTDOWN_TIMEOUT_SEC. */
    static final long POWER_TASK_MARGIN_SEC = 60L

    /** What validate() and a refresh report when the API's certificate is not trusted. */
    static final String CERTIFICATE_NOT_TRUSTED_MSG = 'The Proxmox API certificate is not trusted by the Morpheus appliance. ' +
            'Untick "Verify TLS Certificate" on the cloud, or give the cluster a certificate the appliance trusts.'

    /**
     * The ignoreSSL value of every request of a job: true (nothing is verified, as before 0.1.33)
     * unless the cloud's "Verify TLS Certificate" is ticked.
     *
     * HttpApiClient builds its connection client on its first request, with that request's
     * ignoreSSL, and reuses it for every later request whatever their ignoreSSL says, so one
     * client must never serve both modes. It does not: the mode is read once per job into the
     * job's authConfig (ProxmoxVePlugin.getAuthConfig, or validate()), every request takes it from
     * there, each client lives inside one job of one cloud, and the login has a client of its own.
     */
    static boolean ignoreSsl(Map authConfig) {
        return authConfig?.verifyTls != true
    }

    /** Whether a checkbox value from a cloud's config is ticked; Morpheus stores "on". */
    static boolean checkboxTicked(def raw) {
        return raw != null && raw.toString().trim().toLowerCase() in ['on', 'true', 'yes', '1']
    }

    /**
     * Whether a failed request's error text reads like a TLS certificate failure: a chain the
     * appliance does not trust, an expired certificate, or a host name the certificate lacks.
     */
    static boolean certificateFailure(String text) {
        return text ? (text =~ /(?i)PKIX|certification path|CertPathValidator|SSLHandshake|SSLPeerUnverified|subject alternative name|No name matching|certificate/).find() : false
    }

    /**
     * Proxmox's own words for a refused call: the "message" (and parameter "errors") of the JSON
     * body, else whatever the client recorded.
     */
    static String apiErrorMessage(ServiceResponse response) {
        String content = response?.content
        if (content) {
            try {
                def parsed = new JsonSlurper().parseText(content)
                if (parsed instanceof Map) {
                    String text = [parsed.message?.toString()?.trim(), parsed.errors ? parsed.errors.toString() : null].findAll { it }.join(' ')
                    if (text) {
                        return text
                    }
                }
            } catch (ignored) {
                // not JSON: fall through to the raw text
            }
        }
        def fallback = response?.msg ?: response?.error ?: content ?: (response?.errorCode ? "HTTP ${response.errorCode}" : null)
        return fallback ? fallback.toString().trim() : 'no response'
    }

    /**
     * A failed response with the text in msg as well: ServiceResponse.error() puts it only in
     * errors.error and leaves msg null, so a caller reading msg found nothing.
     */
    static ServiceResponse failure(String message) {
        ServiceResponse rtn = ServiceResponse.error(message)
        rtn.msg = message
        return rtn
    }

    /** The text of a response: its msg, else its error. */
    static String messageOf(ServiceResponse response) {
        return response?.msg ?: response?.error
    }

    /** The task id (UPID) a Proxmox call that starts a task answers with, or null. */
    static String taskUpid(ServiceResponse response) {
        def body = response?.data
        if (!(body instanceof Map) && response?.content) {
            try {
                body = new JsonSlurper().parseText(response.content)
            } catch (ignored) {
                body = null
            }
        }
        String upid = body instanceof Map ? body.data?.toString() : null
        return upid?.startsWith('UPID:') ? upid : null
    }

    /**
     * Gives the client the cloud's API proxy (authConfig.networkProxy, from the cloud's
     * "API Proxy" setting) unless it already has one. Every request to Proxmox goes through
     * here, so a cloud behind a proxy works without each caller remembering it.
     */
    static HttpApiClient withProxy(HttpApiClient client, Map authConfig) {
        if (client && authConfig?.networkProxy && !client.networkProxy) {
            client.networkProxy = authConfig.networkProxy
        }
        return client
    }


    static addVMNics(HttpApiClient client, Map authConfig, List<ComputeServerInterface> newNics, String node, String vmId, String nicModel = NIC_MODEL_LINUX) {
        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def diskAddOpts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body     : [:],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: ignoreSsl(authConfig)
            ]

            newNics.each { nic ->
                diskAddOpts.body["$nic.externalId"] = buildNicConfig(null, nic.network.externalId, nicModel)
            }

            def results = withProxy(client, authConfig).callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                    null, null,
                    new HttpApiClient.RequestOptions(diskAddOpts),
                    'POST'
            )

            return results
        } catch (e) {
            log.error "Error Adding NICs: ${e}", e
            return ServiceResponse.error("Error Adding NICs: ${e}")
        }
    }


    static List<Map> getExistingVMInterfaces(HttpApiClient client, Map authConfig, String nodeId, String vmId) {

        def vmConfigInfo = callListApiV2(client, "nodes/$nodeId/qemu/$vmId/config", authConfig).data
        log.debug("VM Config Info: $vmConfigInfo")
        def nicInterfaces = vmConfigInfo.findAll { k, v -> k ==~ /net\d+/ }.collect { k, v -> [ label: k, value: v] }
        return nicInterfaces
    }

    /** Return imported disks which have not yet been attached to a VM bus. */
    static List<Map> getUnusedVMDisks(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        ServiceResponse vmConfigInfo = callListApiV2(client, "nodes/$nodeId/qemu/$vmId/config", authConfig)
        if (!vmConfigInfo?.success || !(vmConfigInfo.data instanceof Map)) {
            return []
        }

        return vmConfigInfo.data.findAll { key, value ->
            key ==~ /unused\d+/ && value instanceof String && value.contains(':')
        }.collect { key, value ->
            [label: key, volumeId: value.toString().split(',', 2)[0]]
        }.sort { left, right -> left.label <=> right.label }
    }

    static Map parseProxmoxVersion(String version) {
        def matcher = (version ?: '') =~ /^(\d+)\.(\d+)(?:\.(\d+))?/
        if (!matcher.find()) return [:]
        return [major: matcher.group(1).toInteger(), minor: matcher.group(2).toInteger(),
                patch: matcher.group(3) ? matcher.group(3).toInteger() : 0]
    }

    static ServiceResponse getProxmoxVersion(HttpApiClient client, Map authConfig) {
        return callListApiV2(client, 'version', authConfig)
    }

    static String findCloudInitVolume(HttpApiClient client, Map authConfig, String nodeId,
                                      String datastoreId, String vmId) {
        ServiceResponse contentResponse = callListApiV2(
                client,
                "nodes/$nodeId/storage/$datastoreId/content?content=images&vmid=$vmId",
                authConfig
        )
        if (!contentResponse?.success || !(contentResponse.data instanceof Collection)) {
            return null
        }

        return findCloudInitVolumeId(contentResponse.data, vmId)
    }

    static String findCloudInitVolumeId(Collection storageContent, String vmId) {
        String expectedName = "vm-$vmId-cloudinit"
        return storageContent?.find { item ->
            item?.volid?.toString()?.contains(expectedName)
        }?.volid?.toString()
    }

    /**
     * The content types a storage offers on a node, as Proxmox lists them ("iso,vztmpl,backup").
     * Null when the storage is not attached to the node or the listing fails.
     */
    static String getStorageContent(HttpApiClient client, Map authConfig, String nodeId, String storageId) {
        ServiceResponse resp = callListApiV2(client, "nodes/$nodeId/storage", authConfig)
        if (!resp?.success || !(resp.data instanceof Collection)) {
            return null
        }
        return resp.data.find { it?.storage?.toString() == storageId }?.content?.toString()
    }

    /**
     * Proxmox's own cloud-init network settings for the VM's interfaces, as arguments for
     * qm set: [ipconfig0: 'ip=dhcp', ipconfig1: 'ip=10.0.0.5/24,gw=10.0.0.1', nameserver: '...',
     * searchdomain: '...']. Proxmox then generates a network-config that matches each NIC by
     * MAC address. A Morpheus network snippet names the interface (eth0) instead, which a
     * Debian guest does not have, so the guest stayed without an address.
     */
    static Map<String, String> buildCloudInitNetworkSettings(Collection<ComputeServerInterface> interfaces) {
        Map<String, String> rtn = [:]
        List<String> nameservers = []
        String searchDomain = null
        interfaces?.eachWithIndex { ComputeServerInterface iface, int idx ->
            String key = iface.externalId ==~ /net\d+/ ? iface.externalId.replace('net', 'ipconfig') : "ipconfig$idx"
            def net = iface.subnet ?: iface.network
            boolean useDhcp = !iface.ipAddress || iface.dhcp == true || iface.ipMode == 'dhcp'
            if (useDhcp) {
                rtn[key] = 'ip=dhcp'
                return
            }
            Integer prefix = prefixLengthOf(net)
            String config = "ip=${iface.ipAddress}/${prefix ?: 24}"
            if (net?.gateway) {
                config += ",gw=${net.gateway}"
            }
            rtn[key] = config
            [net?.dnsPrimary, net?.dnsSecondary].each { String dns ->
                if (dns && !nameservers.contains(dns)) {
                    nameservers << dns
                }
            }
            searchDomain = searchDomain ?: (iface.networkDomain?.name ?: net?.networkDomain?.name)
        }
        if (nameservers) {
            rtn.nameserver = nameservers.join(' ')
        }
        if (searchDomain) {
            rtn.searchdomain = searchDomain
        }
        return rtn
    }

    /**
     * The prefix length of a network or subnet, from prefixLength, cidr or netmask.
     */
    static Integer prefixLengthOf(def net) {
        if (net == null) {
            return null
        }
        if (net.prefixLength) {
            return net.prefixLength as Integer
        }
        String cidr = net.cidr?.toString()
        if (cidr?.contains('/')) {
            return cidr.substring(cidr.indexOf('/') + 1).toInteger()
        }
        return netmaskToPrefixLength(net.netmask?.toString())
    }

    static Integer netmaskToPrefixLength(String netmask) {
        if (!netmask) {
            return null
        }
        List<String> octets = netmask.tokenize('.')
        if (octets.size() != 4) {
            return null
        }
        int bits = 0
        for (String octet : octets) {
            bits += Integer.bitCount(octet.toInteger() & 0xFF)
        }
        return bits
    }

    static final String NIC_MODEL_LINUX = 'virtio'
    static final String NIC_MODEL_WINDOWS = 'e1000e'

    /**
     * virtio for Linux guests: Debian's cloud kernel ships no e1000e driver, so an e1000e NIC
     * is invisible to such a guest. e1000e for Windows, which has no virtio driver until one is
     * installed.
     */
    static String nicModelFor(ComputeServer server) {
        boolean windows = server?.osType == 'windows' || server?.serverOs?.platform?.toString() == 'windows'
        return windows ? NIC_MODEL_WINDOWS : NIC_MODEL_LINUX
    }

    /**
     * The server's interfaces in the order they get net0, net1, ...: the primary interface
     * first, then by display order, otherwise as listed. net0 is the template's own NIC, which
     * keeps its model and MAC address, and ipconfig0 follows it.
     */
    static List<ComputeServerInterface> orderInterfaces(Collection<ComputeServerInterface> interfaces) {
        List<ComputeServerInterface> list = (interfaces ?: []) as List<ComputeServerInterface>
        List<Integer> listed = (0..<list.size()).toList()
        return listed.sort(false) { int a, int b ->
            ComputeServerInterface left = list[a]
            ComputeServerInterface right = list[b]
            // 0 counts as unset on purpose: ComputeServerInterface starts displayOrder at 0, not
            // null (plugin API 1.4.2), so an interface nobody numbered must not sort first.
            (right.primaryInterface == true ? 1 : 0) <=> (left.primaryInterface == true ? 1 : 0) ?:
                    (left.displayOrder ?: Integer.MAX_VALUE) <=> (right.displayOrder ?: Integer.MAX_VALUE) ?:
                    a <=> b
        }.collect { list[it] }
    }

    /**
     * The index of the next free netN key: one above the highest the VM has, not the number of
     * NICs, so a VM with net0 and net2 gets net3 instead of overwriting net2.
     */
    static int nextNicIndex(Collection<Map> existingNics) {
        List<Integer> used = (existingNics ?: []).collect { Map nic ->
            def m = (nic?.label?.toString() =~ /^net(\d+)$/)
            m.find() ? (m.group(1) as Integer) : null
        }.findAll { it != null }
        return used ? used.max() + 1 : 0
    }

    /**
     * The netN value for a NIC on a bridge. An existing value keeps its model, MAC address and
     * other options, so a resize neither swaps the device nor hands the guest a new address; a
     * new NIC gets the given model and a MAC address from Proxmox.
     */
    static String buildNicConfig(String existing, String bridge, String model) {
        String current = existing?.trim()
        if (current) {
            return current.contains('bridge=') ? current.replaceAll(/bridge=[^,]*/, "bridge=$bridge") : "$current,bridge=$bridge"
        }
        return "bridge=$bridge,model=$model"
    }

    static boolean storageContentHasSnippets(String content) {
        return content?.split(',')?.collect { it.trim() }?.contains('snippets') ?: false
    }

    /**
     * Whether the VM's config enables the QEMU guest agent: "1", "enabled=1" or either followed
     * by further options.
     */
    static boolean guestAgentEnabled(Map vmConfig) {
        String first = vmConfig?.agent?.toString()?.split(',')?.first()?.trim()
        return first == '1' || first == 'enabled=1'
    }

    /**
     * Whether listVMs asks a VM's guest agent for its interfaces: only for a running VM whose
     * config enables the agent. A null config (it could not be read) leaves the agent setting open.
     */
    static boolean shouldQueryGuestAgent(Map vm, Map vmConfig) {
        if (vm?.status?.toString() != 'running') {
            return false
        }
        return vmConfig == null || guestAgentEnabled(vmConfig)
    }

    static String getVMStatus(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        ServiceResponse resp = callListApiV2(client, "nodes/$nodeId/qemu/$vmId/status/current", authConfig)
        if (!resp?.success || !(resp.data instanceof Map)) {
            return null
        }
        return resp.data.status?.toString()
    }

    static List<String> getGuestAgentIpv4Addresses(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        ServiceResponse resp = callListApiV2(client, "nodes/$nodeId/qemu/$vmId/agent/network-get-interfaces", authConfig)
        if (!resp?.success || !(resp.data instanceof Map)) {
            return []
        }
        return extractGuestIpv4Addresses(resp.data)
    }

    /**
     * The usable IPv4 addresses in a guest agent network-get-interfaces result: loopback and
     * link-local addresses are skipped.
     */
    static List<String> extractGuestIpv4Addresses(Map agentResult) {
        List<String> rtn = []
        agentResult?.result?.each { iface ->
            iface?.'ip-addresses'?.each { addr ->
                String ip = addr?.'ip-address'?.toString()
                if (addr?.'ip-address-type' == 'ipv4' && ip && !ip.startsWith('127.') && !ip.startsWith('169.254.')) {
                    rtn << ip
                }
            }
        }
        return rtn
    }

    /**
     * Waits until the VM runs and its guest agent reports an IPv4 address. Returns
     * [success, ipAddress, status]; status is null when the VM cannot be queried at all.
     */
    // Every poll before the agent runs makes HttpApiClient log a WARN for the 500 that Proxmox
    // answers with; 15 s keeps that to a few lines per boot.
    static Map waitForGuestIp(HttpApiClient client, Map authConfig, String nodeId, String vmId, Long timeoutInSec, Long intervalMs = 15000L) {
        Map rtn = [success: false, ipAddress: null, status: null]
        Long started = System.currentTimeMillis()
        Long deadline = started + timeoutInSec * 1000
        while (true) {
            try {
                rtn.status = getVMStatus(client, authConfig, nodeId, vmId)
                if (rtn.status == 'running') {
                    List<String> ips = getGuestAgentIpv4Addresses(client, authConfig, nodeId, vmId)
                    if (ips) {
                        rtn.ipAddress = ips.first()
                        rtn.success = true
                        log.debug("VM $vmId reports IP ${rtn.ipAddress} after ${(System.currentTimeMillis() - started) / 1000}s")
                        return rtn
                    }
                }
            } catch (e) {
                log.warn("Error while waiting for the guest IP of VM $vmId: ${e.message}")
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("VM $vmId reported no IPv4 address through the guest agent within ${timeoutInSec}s (status: ${rtn.status})")
                return rtn
            }
            sleep(intervalMs)
        }
    }


    static removeNetworkInterfaces(HttpApiClient client, Map authConfig, List<ComputeServerInterface> deletedNics, String node, String vmId) {
        log.debug("deleteVolumes")
        def tokenCfg = getApiV2Token(authConfig).data
        def nicRemoveOpts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        delete: ""
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: ignoreSsl(authConfig)
        ]

        try {
            def success = true
            def errorMsg = ""
            deletedNics.each { ComputeServerInterface nic ->
                nicRemoveOpts.body.delete = nic.externalId
                def nicRemoveResults = withProxy(client, authConfig).callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                        null, null,
                        new HttpApiClient.RequestOptions(nicRemoveOpts),
                        'PUT'
                )
                if (!nicRemoveResults.success) {
                    errorMsg += "$nicRemoveResults.error\n"
                    success = false
                }
            }
            return new ServiceResponse(success: success, msg: errorMsg)
        } catch (e) {
            log.error "Error removing VM Network Interface: ${e}", e
            return ServiceResponse.error("Error removing VM Network Interface: ${e}")
        }
    }


    static resizeVMDisk(HttpApiClient client, Map authConfig, StorageVolume updatedVolume, String node, String vmId) {
        def tokenCfg = getApiV2Token(authConfig).data
        def diskResizeOpts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        disk: "$updatedVolume.deviceName",
                        size: "${updatedVolume.maxStorage as Long / 1024 / 1024 / 1024}G"
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: ignoreSsl(authConfig)
        ]

        def results = withProxy(client, authConfig).callJsonApi(
                (String) authConfig.apiUrl,
                "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/resize",
                null, null,
                new HttpApiClient.RequestOptions(diskResizeOpts),
                'PUT'
        )

        return results
    }


    static addVMDisks(HttpApiClient client, Map authConfig, List<StorageVolume> newVolumes, String node, String vmId) {
        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def diskAddOpts = [
                headers  : [
                    'Content-Type'       : 'application/json',
                    'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                    'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                    delete: ""
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: ignoreSsl(authConfig)
            ]

            newVolumes.each { vol ->
                def size = "${vol.maxStorage as Long / 1024 / 1024 / 1024}"
                diskAddOpts.body["$vol.deviceName"] = "${vol.datastore.externalId}:$size,size=${size}G"
            }

            def results = withProxy(client, authConfig).callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                    null, null,
                    new HttpApiClient.RequestOptions(diskAddOpts),
                    'POST'
            )

            return results
        } catch (e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
    }


    static deleteVolumes(HttpApiClient client, Map authConfig, String node, String vmId, List<String> ids) {
        log.debug("deleteVolumes")
        def tokenCfg = getApiV2Token(authConfig).data
        def diskRemoveOpts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        delete: ""
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: ignoreSsl(authConfig)
        ]

        try {
            def success = true
            def errorMsg = ""
            ids.each { String diskId ->
                diskRemoveOpts.body.delete = diskId
                log.debug("Delete request path: \n${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config")
                // The body only: the headers carry the session ticket and CSRF token.
                log.debug("Delete request body: \n${diskRemoveOpts.body}")
                def diskRemoveResults = withProxy(client, authConfig).callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                        null, null,
                        new HttpApiClient.RequestOptions(diskRemoveOpts),
                        'PUT'
                )
                if (!diskRemoveResults.success) {
                    errorMsg += "$diskRemoveResults.error\n"
                    success = false
                }
            }
            return new ServiceResponse(success: success, msg: errorMsg)
        } catch (e) {
            log.error "Error removing VM disk: ${e}", e
            return ServiceResponse.error("Error removing VM disk: ${e}")
        }
    }


    static int getHighestScsiDisk(diskList) {
        def scsiDisks = diskList.findAll { it.label ==~ /scsi\d+/ }
        if (!scsiDisks) return -1

        def highest = scsiDisks.max { it.label.replace("scsi", "").toInteger() }
        return highest.label.replace("scsi", "").toInteger()
    }



    /**
     * The disk entries resizeVM may add to the VM config: one `<storage>:<sizeG>,size=<size>G`
     * per non-root volume whose device name is not yet a key of the VM's config. A disk the
     * VM already has is never re-allocated, whatever its size; disk resizes go through the
     * resize endpoint. A null config (VM not readable) allocates nothing.
     */
    static Map<String, String> disksToAllocate(Map vmConfig, List<StorageVolume> volumes) {
        Map<String, String> rtn = [:]
        if (vmConfig == null) {
            log.warn("VM config not readable; no data disks are allocated")
            return rtn
        }
        volumes?.each { vol ->
            if (vol.rootVolume || !vol.deviceName) {
                return
            }
            if (vmConfig.containsKey(vol.deviceName)) {
                log.debug("Disk ${vol.deviceName} exists on the VM; leaving it as it is")
                return
            }
            def size = "${vol.maxStorage as Long / 1024 / 1024 / 1024}"
            rtn[vol.deviceName as String] = "${vol.datastore.externalId}:$size,size=${size}G".toString()
        }
        return rtn
    }

    static resizeVM(HttpApiClient client, Map authConfig, String node, String vmId, Long cpu, Long ram, List<StorageVolume> volumes, List<ComputeServerInterface> nics, String nicModel = NIC_MODEL_LINUX) {
        log.debug("resizeVMCompute")
        Long ramValue = ram / 1024 / 1024

        def rootVolume = volumes.find {it.rootVolume }

        try {
            log.debug("Resize Boot Disk...")
            def initialTemlpateDisks = getExistingVMStorage(client, authConfig, node, vmId)
            def tokenCfg = getApiV2Token(authConfig).data
            def resizeOpts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        disk  : "${initialTemlpateDisks[0].label}",
                        size  : "${rootVolume.maxStorage as Long / 1024 / 1024 / 1024}G",
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: ignoreSsl(authConfig)
            ]

            def resizeResults = withProxy(client, authConfig).callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/resize",
                    null, null,
                    new HttpApiClient.RequestOptions(resizeOpts),
                    'PUT'
            )

            log.debug("Post deployment Resize results: $resizeResults")
            log.debug("Resize compute, add additional Disks...")
            def opts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        node  : node,
                        vcpus : cpu,
                        cores : cpu,
                        memory: ramValue
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: ignoreSsl(authConfig)
            ]

            Map vmConfig = getVMConfigById(client, authConfig, vmId, node)
            // `<storage>:<size>` on a disk key allocates a NEW empty volume and moves the one
            // the key had to unusedN. Only disks the VM does not have yet may be written that
            // way (the clone-time data disks); a plan change on a running VM must leave every
            // existing disk alone, or each data disk is replaced by a blank one.
            Map<String, String> newDisks = disksToAllocate(vmConfig, volumes)
            opts.body.putAll(newDisks)

            nics.each { nic ->
                opts.body["$nic.externalId"] = buildNicConfig(vmConfig?.get(nic.externalId)?.toString(), nic.network.externalId, nicModel)
            }

            log.debug("Setting VM Compute Size $vmId on node $node...")
            def results = withProxy(client, authConfig).callJsonApi(
                (String) authConfig.apiUrl,
                "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                null, null,
                new HttpApiClient.RequestOptions(opts),
                'POST'
            )

            return results
        } catch (e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
    }



    /**
     * Runs a call on a new client and shuts the client down afterwards. Each step of a clone
     * (task wait of up to an hour, resize) gets its own client, as before; they were never shut
     * down, so each clone left their connection pools open.
     */
    static <T> T withFreshClient(Closure<T> call) {
        HttpApiClient client = new HttpApiClient()
        try {
            return call(client)
        } finally {
            client.shutdownClient()
        }
    }

    /** How often a clone or a template create asks cluster/nextid for a fresh id after the id it had was taken. */
    static final int CLONE_ID_ATTEMPTS = 3

    /**
     * Whether a refused clone or create says the new VMID is in use ("VM 105 already exists on
     * node 'pve1'", "unable to create VM 105: config file already exists").
     */
    static boolean vmIdTaken(def responseContent) {
        return responseContent?.toString()?.contains('already exists') ?: false
    }

    static cloneTemplate(HttpApiClient client, Map authConfig, String templateId, String name, String nodeId, ComputeServer server) {
        log.debug("cloneTemplate: $templateId")
        Long vcpus = server.maxCores
        Long ram = server.maxMemory
        List<StorageVolume> volumes = server.volumes
        List<ComputeServerInterface> nics = server.interfaces
        def rtn = new ServiceResponse(success: true)
        def nextId = callListApiV2(client, "cluster/nextid", authConfig).data
        log.debug("Next VM Id is: $nextId")
        StorageVolume rootVolume = volumes.find { it.rootVolume }
        
        // Find the actual node where the template resides (multi-node cluster support)
        String templateNode = findNodeForVM(client, authConfig, templateId)
        if (!templateNode) {
            log.error("Cannot find template $templateId on any node in the cluster")
            return ServiceResponse.error("Template $templateId not found on any node in the cluster")
        }
        
        if (templateNode != nodeId) {
            log.debug("Template $templateId is on node $templateNode, but cloning to node $nodeId (cross-node clone)")
        }
        
        try {
            def tokenCfg = getApiV2Token(authConfig).data
            rtn.data = []
            
            // Build request body
            def requestBody = [
                    newid: nextId,
                    target: nodeId,  // Target node for the new VM
                    vmid: templateId,
                    name: name,
                    full: true
            ]
            
            // For cross-node clones, check if storage exists on source node
            // If storage is specified and this is a cross-node clone, we need to be careful
            String requestedStorage = rootVolume?.datastore?.externalId
            if (requestedStorage) {
                if (templateNode == nodeId) {
                    // Same node clone - use specified storage
                    requestBody.storage = requestedStorage
                    log.debug("Same-node clone: using storage '$requestedStorage'")
                } else {
                    // Cross-node clone - only specify storage if it's shared storage
                    def sourceNodeStorages = getNodeStorages(client, authConfig, templateNode)
                    def targetNodeStorages = getNodeStorages(client, authConfig, nodeId)
                    
                    boolean isSharedStorage = sourceNodeStorages?.contains(requestedStorage) && 
                                            targetNodeStorages?.contains(requestedStorage)
                    
                    if (isSharedStorage) {
                        requestBody.storage = requestedStorage
                        log.debug("Cross-node clone: using shared storage '$requestedStorage'")
                    } else {
                        // Non-shared storage detected - omit storage parameter
                        // Proxmox will use default storage on target node
                        log.warn("Cross-node clone: storage '$requestedStorage' not shared between nodes, omitting storage parameter")
                    }
                }
            }
            
            log.debug("Selected Resource Pool Name: ${server?.resourcePool?.name}")
            if (server?.resourcePool?.name) requestBody.pool = server.resourcePool.name
            
            def opts = [
                    headers: [
                            'Content-Type': 'application/json',
                            'Cookie': "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body: requestBody,
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: ignoreSsl(authConfig)
            ]

            log.debug("Cloning template $templateId from node $templateNode to VM $name($nextId) on node $nodeId")
            
            // Clone from the node where template actually resides. cluster/nextid reserves
            // nothing: a parallel provision can take the same id first, and Proxmox then refuses
            // the clone with "VM N already exists". Ask for a fresh id and try again.
            def results = null
            for (int attempt = 1; attempt <= CLONE_ID_ATTEMPTS; attempt++) {
                results = withProxy(client, authConfig).callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$templateNode/qemu/$templateId/clone",
                        null, null,
                        new HttpApiClient.RequestOptions(opts),
                        'POST'
                )
                if ((results?.success && !results?.hasErrors()) || attempt == CLONE_ID_ATTEMPTS || !vmIdTaken(results?.content)) {
                    break
                }
                def freshId = callListApiV2(client, "cluster/nextid", authConfig).data
                log.warn("VM id $nextId was taken before the clone of template $templateId started; retrying with $freshId")
                nextId = freshId
                requestBody.newid = nextId
            }

            if(!results?.success || results?.hasErrors()) {
                def errorMsg = "Clone operation failed"
                try {
                    def errorData = new JsonSlurper().parseText(results.content)
                    if (errorData?.message) {
                        errorMsg = errorData.message.trim()
                    } else if (errorData?.data?.message) {
                        errorMsg = errorData.data.message.trim()
                    } else if (results?.content) {
                        errorMsg = results.content
                    }
                } catch (e) {
                    log.warn("Failed to parse error response: ${e.message}")
                    if (results?.content) {
                        errorMsg = results.content
                    }
                }
                return ServiceResponse.error(errorMsg)
            }

            def resultData = null
            try {
                resultData = new JsonSlurper().parseText(results.content)
            } catch (e) {
                log.error("Failed to parse clone response: ${e.message}")
                return ServiceResponse.error("Failed to parse clone API response")
            }
            
            if (resultData?.errors) {
                log.error("Clone operation returned errors: ${resultData.errors}")
                return ServiceResponse.error("Clone failed: ${resultData.errors}")
            }

            rtn.success = true
            rtn.data = [vmId: nextId, apiResult: resultData]

            // Proxmox returns a task UPID that we should monitor
            def taskUPID = resultData?.data
            if (taskUPID) {
                log.debug("Clone task started with UPID: $taskUPID on node $templateNode")
                // Wait for the Proxmox task to complete on the source node
                ServiceResponse taskWaitResult = withFreshClient { HttpApiClient c -> waitForTaskComplete(c, authConfig, templateNode, taskUPID, 3600L) }
                
                if (!taskWaitResult?.success) {
                    log.error("Clone task failed for VM $nextId: ${taskWaitResult.msg}")
                    return ServiceResponse.error("Error Provisioning VM. Clone task error: ${taskWaitResult.msg}")
                }
                log.debug("Clone task completed successfully for VM $nextId")
            } else {
                log.warn("No task UPID returned from clone API, falling back to VM config check")
                // Fallback to old method
                ServiceResponse cloneWaitResult = withFreshClient { HttpApiClient c -> waitForCloneToComplete(c, authConfig, templateId, nextId, nodeId, 3600L) }
                
                if (!cloneWaitResult?.success) {
                    log.error("Clone wait failed for VM $nextId: ${cloneWaitResult.msg}")
                    return ServiceResponse.error("Error Provisioning VM. Wait for clone error: ${cloneWaitResult.msg}")
                }
            }

            log.debug("Resizing newly cloned VM. Spec: CPU: $vcpus,\n RAM: $ram,\n Volumes: $volumes,\n NICs: $nics")
            ServiceResponse rtnResize = withFreshClient { HttpApiClient c -> resizeVM(c, authConfig, nodeId, nextId, vcpus, ram, volumes, nics, nicModelFor(server)) }

            if (!rtnResize?.success) {
                log.error("Resize failed for VM $nextId: ${rtnResize.msg}")
                return ServiceResponse.error("Error Sizing VM Compute. Resize compute error: ${rtnResize.msg}")
            }

            log.debug("Successfully cloned and configured VM $nextId on node $nodeId")
        } catch(e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
        return rtn
    }




    static List<Map> getExistingVMStorage(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        // First try the provided node
        def vmConfigInfo = callListApiV2(client, "nodes/$nodeId/qemu/$vmId/config", authConfig)
        
        // If VM/template not found on the provided node, search across cluster
        if (!vmConfigInfo.success || vmConfigInfo.hasErrors()) {
            log.warn("VM/template $vmId not found on node $nodeId, searching other nodes in cluster...")
            String actualNode = findNodeForVM(client, authConfig, vmId)
            if (actualNode && actualNode != nodeId) {
                log.debug("Found VM/template $vmId on node $actualNode instead of $nodeId")
                vmConfigInfo = callListApiV2(client, "nodes/$actualNode/qemu/$vmId/config", authConfig)
            }
        }
        
        if (!vmConfigInfo.success || !vmConfigInfo.data) {
            log.error("Failed to retrieve VM/template $vmId configuration from any node")
            return []
        }
        
        def vmConfigData = vmConfigInfo.data
        def validBootDisks = ["scsi0", "virtio0", "sata0", "ide0"]
        def bootEntries = vmConfigData.boot?.trim()?.replaceAll("order=", "")?.split(/[;,\s]+/)
        def bootDisk = ""

        def extractDiskKeys  = { config ->
            def diskPrefixes = ["scsi", "virtio", "sata", "ide"]
            def diskKeys = config.keySet().findAll { key ->
                diskPrefixes.any { prefix -> key ==~ /${prefix}\d+/ }
            }
            return diskKeys
        }
        List vmStorageList = []

        def vmDiskKeys = extractDiskKeys(vmConfigData)

        //Boot disk specified in config boot order
        bootEntries.each { String diskLabel ->
            if (!bootDisk && validBootDisks.contains(diskLabel)) {
                bootDisk = diskLabel
            }
        }

        if (!bootDisk) {
            //select boot disk based on proxmox disk names
            validBootDisks.each { String diskLabel ->
                if (!bootDisk && vmDiskKeys.contains(diskLabel)) {
                    bootDisk = diskLabel
                }
            }
        }

        if (!bootDisk) {
            throw new Exception("Boot disk for VM not found!")
        }

        vmStorageList << [ label: "$bootDisk", config: vmConfigData[bootDisk], isRoot: true ]
        vmDiskKeys.each { String diskLabel ->
            if (diskLabel != bootDisk) {
                vmStorageList << [ label: "$diskLabel", config: vmConfigData[diskLabel], isRoot: false ]
            }
        }

        return vmStorageList
    }
    /**
     * Find which node a VM or template resides on in a multi-node cluster
     * 
     * Uses /cluster/resources?type=vm endpoint to fetch only QEMU VMs/templates,
     * then filters client-side by vmid. Falls back to all resources if type parameter fails,
     * and ultimately checks each node individually if cluster query fails.
     * 
     * Proxmox API limitation: There is NO endpoint like /api2/json/qemu/{vmid}
     * All VM queries require: /api2/json/nodes/{node}/qemu/{vmid}
     * 
     * @param client HttpApiClient instance
     * @param authConfig Authentication configuration
     * @param vmId The VM or template ID to locate
     * @return The node name where the VM/template resides, or null if not found
     */
    /**
     * The node of the template with this id in a cluster/resources listing, or null when the id
     * is not listed or belongs to a guest that is not a template: a full clone of a running VM
     * must never stand in for a missing template.
     */
    static String templateNodeIn(Collection resources, String templateId) {
        def resource = resources?.find { it?.type == 'qemu' && it?.vmid?.toString() == templateId }
        return resource?.template?.toString() in ['1', 'true'] ? resource.node?.toString() : null
    }

    /**
     * The node holding template `templateId`, or null when the cluster has no template with
     * that id. Throws when the cluster cannot be listed, so a provision fails instead of
     * creating a second template for an image that already has one.
     */
    static String findNodeForTemplate(HttpApiClient client, Map authConfig, String templateId) {
        ServiceResponse resources = callListApiV2(client, "cluster/resources?type=vm", authConfig)
        if (!resources?.success || !(resources.data instanceof Collection)) {
            throw new Exception("Unable to list the cluster's resources to find template $templateId: ${resources?.msg}")
        }
        String node = templateNodeIn(resources.data, templateId)
        if (!node && resources.data.any { it?.type == 'qemu' && it?.vmid?.toString() == templateId }) {
            log.warn("VM $templateId is not a template; it is not used as the image's template")
        }
        return node
    }

    static String findNodeForVM(HttpApiClient client, Map authConfig, String vmId) {
        try {
            // Try with type=vm filter first for better performance
            def qemuResources = callListApiV2(client, "cluster/resources?type=vm", authConfig)
            
            // If type parameter not supported, fall back to unfiltered query
            if (!qemuResources?.success || qemuResources?.hasErrors()) {
                log.debug("cluster/resources?type=vm not supported, trying without filter")
                qemuResources = callListApiV2(client, "cluster/resources", authConfig)
            }
            
            if (qemuResources?.success && qemuResources?.data) {
                // Filter for the specific VM/template (client-side filtering)
                def vmResource = qemuResources.data.find { 
                    it.type == "qemu" && it.vmid?.toString() == vmId?.toString()
                }
                
                if (vmResource?.node) {
                    log.debug("VM/template $vmId found on node: ${vmResource.node}")
                    return vmResource.node
                }
            }
            
            // Fallback: Check each node individually
            log.debug("VM/template $vmId not found in cluster resources, checking nodes individually")
            ServiceResponse nodeIds = getProxmoxHypervisorNodeIds(client, authConfig)
            List<String> nodes = nodeIds.success ? (nodeIds.data as List<String>) : []
            
            for (String node : nodes) {
                try {
                    def vmConfig = callListApiV2(client, "nodes/$node/qemu/$vmId/config", authConfig)
                    if (vmConfig.success && vmConfig.data) {
                        log.debug("VM/template $vmId found on node $node via direct query")
                        return node
                    }
                } catch (Exception nodeEx) {
                    // VM not on this node, continue checking
                    log.debug("VM/template $vmId not on node $node")
                }
            }
            
            log.warn("VM/template $vmId not found on any node in the cluster")
            return null
        } catch (e) {
            log.error("Error finding node for VM $vmId: ${e}", e)
            return null
        }
    }

    /**
     * Get list of storage names available on a specific node
     */
    static List<String> getNodeStorages(HttpApiClient client, Map authConfig, String nodeId) {
        try {
            def storageResponse = callListApiV2(client, "nodes/${nodeId}/storage", authConfig)
            if (storageResponse?.data) {
                def storageNames = storageResponse.data.collect { it.storage }
                log.debug("Node $nodeId has storages: $storageNames")
                return storageNames
            }
            return []
        } catch (e) {
            log.error("Error getting storages for node $nodeId: ${e}", e)
            return []
        }
    }

    /**
     * Get the actual storage name used by a VM's root disk
     * @param client HttpApiClient
     * @param authConfig Authentication configuration
     * @param nodeId Node where VM resides
     * @param vmId VM ID
     * @return Storage name (e.g., "local-lvm", "Ceph.HDD.01")
     */
    static String getVMActualStorage(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        try {
            def vmStorageList = getExistingVMStorage(client, authConfig, nodeId, vmId)
            if (vmStorageList && vmStorageList.size() > 0) {
                def rootDisk = vmStorageList.find { it.isRoot }
                if (rootDisk?.config) {
                    // Disk config format: "storage:vm-123-disk-0,size=10G" or "storage:123/vm-123-disk-0.qcow2"
                    def diskConfig = rootDisk.config
                    def storageName = diskConfig.split(':')[0]
                    log.debug("VM $vmId on node $nodeId uses storage: $storageName")
                    return storageName
                }
            }
            log.warn("Could not determine actual storage for VM $vmId on node $nodeId")
            return null
        } catch (e) {
            log.error("Error getting actual storage for VM $vmId: ${e}", e)
            return null
        }
    }


    static startVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("startVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "start")
    }

    /** Reboot through the guest (agent or ACPI); Proxmox gives up after GUEST_SHUTDOWN_TIMEOUT_SEC. */
    static rebootVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("rebootVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "reboot", [timeout: GUEST_SHUTDOWN_TIMEOUT_SEC])
    }

    /**
     * Shutdown through the guest agent when the VM has one, ACPI otherwise; Proxmox forces the VM
     * off after GUEST_SHUTDOWN_TIMEOUT_SEC (forceStop).
     */
    static shutdownVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("shutdownVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "shutdown", [forceStop: 1, timeout: GUEST_SHUTDOWN_TIMEOUT_SEC])
    }

    /** Hard stop: the VM is powered off at once, the guest is not asked. */
    static stopVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("stopVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "stop")
    }

    static resetVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("resetVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "reset")
    }

    /**
     * Stop as the Proxmox UI's Shutdown does it (shutdownVM), and return once its task has ended.
     * A failed or timed-out task fails with Proxmox's message. Before 0.1.33 Stop was the hard
     * stop, which pulls the plug at once.
     */
    static ServiceResponse shutdownVMAndWait(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        return waitForPowerTask(client, authConfig, nodeId, vmId, 'shutdown', shutdownVM(client, authConfig, nodeId, vmId))
    }

    /** The hard stop, waited for. */
    static ServiceResponse stopVMAndWait(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        return waitForPowerTask(client, authConfig, nodeId, vmId, 'stop', stopVM(client, authConfig, nodeId, vmId))
    }

    /**
     * Restart as a real reboot: Proxmox shuts the guest down and starts it again. When the reboot
     * task fails or times out (a guest that ignores the shutdown), the VM is reset instead, a hard
     * restart. A reboot Proxmox refuses outright (a locked VM, a missing permission) or that starts
     * no task is not followed by a reset: no shutdown failed, and the answer carries Proxmox's
     * message. A stopped VM is started, as the stop-and-start before 0.1.33 did.
     */
    static ServiceResponse rebootVMAndWait(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        if (getVMStatus(client, authConfig, nodeId, vmId) == 'stopped') {
            log.info("VM $vmId on node $nodeId is stopped; restart starts it")
            ServiceResponse started = startVM(client, authConfig, nodeId, vmId)
            return started?.success ? started : failure("Starting the stopped VM $vmId on node $nodeId failed: ${apiErrorMessage(started)}")
        }
        ServiceResponse requested = rebootVM(client, authConfig, nodeId, vmId)
        ServiceResponse rebooted = waitForPowerTask(client, authConfig, nodeId, vmId, 'reboot', requested)
        if (rebooted.success) {
            return rebooted
        }
        if (!requested?.success || !taskUpid(requested)) {
            // Refused, or no task to wait for: the VM was never asked to go down, so no reset
            log.warn("Restart of VM $vmId on node $nodeId failed: ${messageOf(rebooted)}")
            return rebooted
        }
        log.warn("Reboot of VM $vmId on node $nodeId did not complete (${messageOf(rebooted)}); resetting the VM instead")
        ServiceResponse reset = waitForPowerTask(client, authConfig, nodeId, vmId, 'reset', resetVM(client, authConfig, nodeId, vmId))
        if (!reset.success) {
            return failure("Restarting VM $vmId failed: ${messageOf(rebooted)}. The reset that followed failed too: ${messageOf(reset)}")
        }
        log.info("VM $vmId on node $nodeId was reset because its reboot did not complete")
        return reset
    }

    /**
     * Powers a VM off before it is destroyed: the shutdown of shutdownVMAndWait, and the hard stop
     * when that fails. A VM Proxmox reports as stopped is left alone. The result is the last step's.
     */
    static ServiceResponse powerOffForDelete(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        if (getVMStatus(client, authConfig, nodeId, vmId) == 'stopped') {
            return ServiceResponse.success()
        }
        ServiceResponse shutdown = shutdownVMAndWait(client, authConfig, nodeId, vmId)
        if (shutdown.success) {
            return shutdown
        }
        log.warn("Shutdown of VM $vmId on node $nodeId before its delete failed (${messageOf(shutdown)}); stopping it hard")
        ServiceResponse hardStop = stopVMAndWait(client, authConfig, nodeId, vmId)
        if (!hardStop.success) {
            return failure("Shutdown of VM $vmId failed (${messageOf(shutdown)}), and so did the hard stop (${messageOf(hardStop)})")
        }
        return hardStop
    }

    /**
     * Waits for the task a power action started (Proxmox answers with its UPID) and turns the
     * outcome into one response: the call refused, no task returned, or the task's end, with
     * Proxmox's exit status when it failed.
     */
    static ServiceResponse waitForPowerTask(HttpApiClient client, Map authConfig, String nodeId, String vmId, String action, ServiceResponse started) {
        if (!started?.success) {
            return failure("Proxmox refused the $action of VM $vmId on node $nodeId: ${apiErrorMessage(started)}")
        }
        String upid = taskUpid(started)
        if (!upid) {
            return failure("Proxmox returned no task for the $action of VM $vmId on node $nodeId")
        }
        ServiceResponse done = waitForTaskComplete(client, authConfig, nodeId, upid, GUEST_SHUTDOWN_TIMEOUT_SEC + POWER_TASK_MARGIN_SEC)
        if (!done?.success) {
            return failure("The $action of VM $vmId on node $nodeId failed: ${messageOf(done)}")
        }
        log.debug("VM $vmId $action completed (task $upid)")
        return new ServiceResponse(success: true, msg: "VM $vmId: $action completed", data: [upid: upid])
    }


    /**
     * POST nodes/{node}/qemu/{vmid}/status/{action}. `params` are the action's parameters
     * (timeout, forceStop); they go in the body, never in the path.
     */
    static actionVMStatus(HttpApiClient client, Map authConfig, String nodeId, String vmId, String action, Map params = [:], int maxRetries = 3) {
        int retryCount = 0
        Exception lastException = null

        while (retryCount < maxRetries) {
            try {
                def tokenCfg = getApiV2Token(authConfig).data
                def opts = [
                        headers  : [
                                'Content-Type'       : 'application/json',
                                'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                                'CSRFPreventionToken': tokenCfg.csrfToken
                        ],
                        body     : [
                                vmid: vmId,
                                node: nodeId
                        ] + (params ?: [:]),
                        contentType: ContentType.APPLICATION_JSON,
                        ignoreSSL: ignoreSsl(authConfig)
                ]

                log.debug("Post path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/status/$action/ (attempt ${retryCount + 1}/$maxRetries)")
                def results = withProxy(client, authConfig).callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/status/$action/",
                        null, null,
                        new HttpApiClient.RequestOptions(opts),
                        'POST'
                )

                if (results?.success) {
                    log.debug("VM $action successful on attempt ${retryCount + 1}")
                    return results
                } else if (retryCount < maxRetries - 1) {
                    log.warn("VM $action failed on attempt ${retryCount + 1}, retrying...")
                    sleep(2000) // Wait 2 seconds before retry
                    retryCount++
                } else {
                    return results
                }
            } catch (org.apache.http.NoHttpResponseException e) {
                lastException = e
                log.warn("NoHttpResponseException performing $action on VM $vmId (attempt ${retryCount + 1}/$maxRetries): ${e.message}")
                if (retryCount < maxRetries - 1) {
                    sleep(3000) // Wait 3 seconds before retry on connection error
                    retryCount++
                } else {
                    log.error("Failed to perform $action on VM $vmId after $maxRetries attempts", e)
                    return ServiceResponse.error("Error performing $action on VM after $maxRetries attempts: Connection to Proxmox failed")
                }
            } catch (Exception e) {
                lastException = e
                log.error("Error performing $action on VM $vmId (attempt ${retryCount + 1}/$maxRetries): ${e.message}", e)
                if (retryCount < maxRetries - 1) {
                    sleep(2000)
                    retryCount++
                } else {
                    return ServiceResponse.error("Error performing $action on VM: ${e.message}")
                }
            }
        }

        return ServiceResponse.error("Error performing $action on VM after $maxRetries attempts: ${lastException?.message}")
    }


    static destroyVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("destroyVM")
        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def opts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body: null,
                    ignoreSSL: ignoreSsl(authConfig),
                    contentType: ContentType.APPLICATION_JSON,
            ]

            log.debug("Delete path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/")

            def results = withProxy(client, authConfig).callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/",
                    new HttpApiClient.RequestOptions(opts),
                    'DELETE'
            )

            log.debug("VM Delete Response Details: ${results.toMap()}")
            return results

        //TODO - check for non 200 response
        } catch (e) {
            log.error "Error Destroying VM: ${e}", e
            return ServiceResponse.error("Error Destroying VM: ${e}")
        }
    }


    static createImageTemplate(HttpApiClient client, Map authConfig, String imageName, String nodeId, int cpu, Long ram, String sourceUri = null) {
        log.debug("createImage: $imageName")

        def rtn = new ServiceResponse(success: true)
        def nextId = callListApiV2(client, "cluster/nextid", authConfig).data
        log.debug("Next VM Id is: $nextId")

        try {
            def tokenCfg = getApiV2Token(authConfig).data
            rtn.data = []
            def opts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body     : [
                            vmid: nextId,
                            node: nodeId,
                            name: imageName.replaceAll(/\s+/, ''),
                            template: true,
                            scsihw: "virtio-scsi-single"
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: ignoreSsl(authConfig)
            ]

            log.debug("Creating blank template for attaching qcow2...")
            log.debug("Path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/")
            // The same race as in cloneTemplate: cluster/nextid reserves nothing, so a parallel
            // provision can take the id first, and Proxmox refuses the create. Fresh id, again.
            def results = null
            for (int attempt = 1; attempt <= CLONE_ID_ATTEMPTS; attempt++) {
                results = withProxy(client, authConfig).callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$nodeId/qemu/",
                        null, null,
                        new HttpApiClient.RequestOptions(opts),
                        'POST'
                )
                if ((results?.success && !results?.hasErrors()) || attempt == CLONE_ID_ATTEMPTS || !vmIdTaken(results?.content)) {
                    break
                }
                def freshId = callListApiV2(client, "cluster/nextid", authConfig).data
                log.warn("VM id $nextId was taken before template $imageName was created; retrying with $freshId")
                nextId = freshId
                opts.body.vmid = nextId
            }

            def resultData = new JsonSlurper().parseText(results.content)
            if (results?.success && !results?.hasErrors()) {
                rtn.success = true
                rtn.data = resultData
                rtn.data.templateId = nextId
            } else {
                rtn.msg = "Template create failed: $results.data $results $results.errorCode $results.content"
                rtn.success = false
            }
        } catch (e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
        return rtn
    }


    static ServiceResponse waitForCloneToComplete(HttpApiClient client, Map authConfig, String templateId, String vmId, String nodeId, Long timeoutInSec) {
        Long timeout = timeoutInSec * 1000
        Long duration = 0
        log.debug("waitForCloneToComplete: $templateId")

        try {
            def opts = [
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: ignoreSsl(authConfig)
            ]

            log.debug("Checking VM Status after clone template $templateId to VM $vmId on node $nodeId")
            log.debug("Path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/config")

            while (duration < timeout) {
                log.debug("Checking VM $vmId status on node $nodeId")
                // The job's ticket on every poll (getApiV2Token renews it after an hour), so an
                // hour-long wait never runs on a ticket that is older than Proxmox accepts.
                def tokenCfg = getApiV2Token(authConfig).data
                opts.headers = [
                        'Content-Type': 'application/json',
                        'Cookie': "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ]
                def results = withProxy(client, authConfig).callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/config",
                        null, null,
                        new HttpApiClient.RequestOptions(opts),
                        'GET'
                )

                if (!results.success) {
                    log.error("Error checking VM clone result status.")
                    return results
                }

                def resultData = new JsonSlurper().parseText(results.content)
                if (!resultData.data.containsKey("lock")) {
                    return results
                } else {
                    log.debug("VM Still Locked, wait ${API_CHECK_WAIT_INTERVAL}ms and check again...")
                }
                sleep(API_CHECK_WAIT_INTERVAL)
                duration += API_CHECK_WAIT_INTERVAL
            }
            return new ServiceResponse(success: false, msg: "Timeout", data: "Timeout")
        } catch(e) {
            log.error "Error Checking VM Clone Status: ${e}", e
            return ServiceResponse.error("Error Checking VM Clone Status: ${e}")
        }
    }

    /**
     * Wait for a Proxmox task (identified by UPID) to complete
     * @param client HttpApiClient
     * @param authConfig Authentication configuration
     * @param nodeId Node where the task is running
     * @param taskUPID Task UPID from Proxmox API
     * @param timeoutInSec Timeout in seconds
     * @return ServiceResponse
     */
    static ServiceResponse waitForTaskComplete(HttpApiClient client, Map authConfig, String nodeId, String taskUPID, Long timeoutInSec) {
        Long timeout = timeoutInSec * 1000
        Long duration = 0
        log.debug("waitForTaskComplete: $taskUPID on node $nodeId")

        try {
            def opts = [
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: ignoreSsl(authConfig)
            ]

            // Poll task status
            while (duration < timeout) {
                log.debug("Checking task $taskUPID status on node $nodeId")
                // The job's ticket on every poll, as in waitForCloneToComplete
                def tokenCfg = getApiV2Token(authConfig).data
                opts.headers = [
                        'Content-Type': 'application/json',
                        'Cookie': "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ]
                def results = withProxy(client, authConfig).callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$nodeId/tasks/$taskUPID/status",
                        null, null,
                        new HttpApiClient.RequestOptions(opts),
                        'GET'
                )

                if (!results.success) {
                    log.error("Error checking task status: ${results.errorCode}")
                    sleep(API_CHECK_WAIT_INTERVAL)
                    duration += API_CHECK_WAIT_INTERVAL
                    continue
                }

                def resultData = new JsonSlurper().parseText(results.content)
                def status = resultData?.data?.status
                
                log.debug("Task status: $status")
                
                if (status == "stopped") {
                    def exitstatus = resultData?.data?.exitstatus
                    if (exitstatus == "OK") {
                        log.debug("Task $taskUPID completed successfully")
                        return new ServiceResponse(success: true, data: resultData)
                    } else {
                        log.error("Task $taskUPID failed with exit status: $exitstatus")
                        return new ServiceResponse(success: false, msg: "Task failed with status: $exitstatus", data: resultData)
                    }
                } else {
                    log.debug("Task still running (status: $status), waiting ${API_CHECK_WAIT_INTERVAL}ms...")
                }
                
                sleep(API_CHECK_WAIT_INTERVAL)
                duration += API_CHECK_WAIT_INTERVAL
            }
            
            return new ServiceResponse(success: false, msg: "Task timeout after ${timeoutInSec}s")
        } catch(e) {
            log.error "Error checking task status: ${e}", e
            return ServiceResponse.error("Error checking task status: ${e}")
        }
    }


    static ServiceResponse getProxmoxDatastoresById(HttpApiClient client, Map authConfig, List storageIds) {

        List<Map> filteredDS = listProxmoxDatastores(client, authConfig).data.findAll { storageIds.contains(it.storage) }

        return new ServiceResponse(success: true, data: filteredDS)
    }


    static ServiceResponse listProxmoxDatastores(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxDatastores...")

        var allowedDatastores = ["rbd", "cifs", "zfspool", "nfs", "lvmthin", "lvm", "cephfs", "iscsi", "dir"]
        Collection<Map> validDatastores = []
        ServiceResponse datastoreResults = callListApiV2(client, "storage", authConfig)
        if (!datastoreResults?.success || !(datastoreResults.data instanceof Collection)) {
            return ServiceResponse.error("Unable to list the cluster's storages: ${datastoreResults?.msg}")
        }
        ServiceResponse nodeList = getProxmoxHypervisorNodeIds(client, authConfig)
        if (!nodeList?.success || !nodeList.data) {
            return ServiceResponse.error("Unable to list the cluster's nodes: ${nodeList?.msg}")
        }
        def queryNode = nodeList.data[0]

        datastoreResults.data.each { Map ds ->
            if (allowedDatastores.contains(ds.type)) {
                if (ds.containsKey("nodes")) {
                    //some pools don't belong to any node, but api path needs node for status details
                    queryNode = ((String) ds.nodes).split(",")[0]
                } else {
                    ds.nodes = "all"
                }

                try {
                    ServiceResponse dsInfoResponse = callListApiV2(client, "nodes/${queryNode}/storage/${ds.storage}/status", authConfig)
                    
                    if (dsInfoResponse.success && dsInfoResponse.data instanceof Map) {
                        Map dsInfo = dsInfoResponse.data as Map
                        ds.total = dsInfo.total ?: 0
                        ds.avail = dsInfo.avail ?: 0
                        ds.used = dsInfo.used ?: 0
                        ds.enabled = dsInfo.enabled ?: 0
                    } else {
                        // Handle case where API call fails (like for offline nodes)
                        log.warn("Failed to get storage status for ${ds.storage} on node ${queryNode}, using defaults")
                        ds.total = 0
                        ds.avail = 0
                        ds.used = 0
                        ds.enabled = 0
                    }

                    validDatastores << ds
                    
                } catch (Exception e) {
                    log.error("Error getting datastore status for ${ds.storage} on node ${queryNode}: ${e.message}")
                    // Set default values and include the datastore anyway
                    ds.total = 0
                    ds.avail = 0
                    ds.used = 0
                    ds.enabled = 0
                    validDatastores << ds
                }
            } else {
                log.warn("Storage ${ds} ignored...")
            }
        }

        return new ServiceResponse(success: true, data: validDatastores)
    }



    static ServiceResponse listProxmoxNetworks(HttpApiClient client, Map authConfig, uniqueIfaces = false) {
        log.debug("listProxmoxNetworks...")

        Collection<Map> networks = []
        List<String> failedHosts = []
        ServiceResponse hostList = getProxmoxHypervisorNodeIds(client, authConfig)
        if (!hostList?.success || !(hostList.data instanceof Collection)) {
            return ServiceResponse.error("Unable to list the cluster's nodes: ${hostList?.msg}")
        }
        List<String> hosts = hostList.data

        hosts.each { host ->
            try {
                ServiceResponse hostNetworks = callListApiV2(client, "nodes/$host/network", authConfig)
                if (!hostNetworks.success) {
                    log.warn("Failed to get networks for host ${host}: ${hostNetworks.msg}")
                    failedHosts << host
                } else if (hostNetworks.data) {
                    hostNetworks.data.each { Map network ->
                        if (['bridge', 'vlan'].contains(network?.type)) {
                            network.networkAddress = ""
                            if (network?.cidr) {
                                network.networkAddress = ProxmoxMiscUtil.getNetworkAddress(network.cidr)
                            } else if (network?.address && network?.netmask) {
                                network.networkAddress = ProxmoxMiscUtil.getNetworkAddress("$network.address/$network.netmask")
                            }
                            network.host = host
                            networks << network
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to get networks for host ${host}: ${e.message}")
                failedHosts << host
            }
        }
        // A listing that failed on any node is no listing: reporting the networks that were
        // read as the whole cluster would make the sync delete the rest.
        if (failedHosts) {
            return ServiceResponse.error("Unable to list the networks of node(s) ${failedHosts.join(', ')}")
        }

        try {
            ServiceResponse sdnNetworks = callListApiV2(client, "cluster/sdn/vnets", authConfig)
            // Same rule as the bridges: a failed vnet listing reported as "no vnets" made the
            // sync delete every SDN network. Only a cluster without SDN (404/501) has none.
            if (!sdnNetworks?.success && !sdnUnavailable(sdnNetworks?.errorCode)) {
                return ServiceResponse.error("Unable to list the SDN vnets: ${sdnNetworks?.msg}")
            }
            if (sdnNetworks.success && sdnNetworks.data) {
                log.debug("Found SDN Networks: ${sdnNetworks.data}")
                sdnNetworks.data.each { Map sdn ->
                    ServiceResponse sdnSubnets = callListApiV2(client, "cluster/sdn/vnets/${sdn.vnet}/subnets",
                            authConfig)
                    List<NetworkSubnet> subnets = []
                    if (sdnSubnets.success && sdnSubnets.data) {
                        log.debug("Found SDN Subnets for ${sdn.vnet}: ${sdnSubnets.data}")
                        sdnSubnets.data.each { Map subnet ->
                            log.debug("Saving values: name as ${subnet.subnet}, cidr as ${subnet.cidr}," +
                                    " gateway as ${subnet.gateway}")
                            NetworkSubnet subnetData = new NetworkSubnet(
                                name: subnet.subnet,
                                cidr: subnet.cidr,
                                netmask: subnet.mask,
                                subnetAddress: subnet.network,
                                gateway: subnet.gateway
                            )
                            subnets << subnetData
                        }
                    }
                    if (subnets.size() > 0) {
                        sdn.networkAddress = subnets[0].cidr
                        sdn.gateway = subnets[0].gateway
                        sdn.netmask = subnets[0].netmask
                        sdn.subnetAddress = subnets[0].subnetAddress
                    } else {
                        sdn.networkAddress = ""
                        sdn.gateway = ""
                        sdn.netmask = ""
                        sdn.subnetAddress = ""
                    }
                    sdn.subnets = subnets
                    sdn.iface = sdn.vnet
                    sdn.host = "all"
                    networks << sdn
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get SDN networks: ${e.message}")
            return ServiceResponse.error("Unable to list the SDN vnets: ${e.message}")
        }

        if (uniqueIfaces) {
            Set seenIfaces = new HashSet<>()
            List<Map> uniqueNetworks = networks.findAll { map ->
                if (map.iface && !seenIfaces.contains(map.iface)) {
                    seenIfaces << map.iface
                    return true
                }
                return false
            }
            return new ServiceResponse(success: true, data: uniqueNetworks)
        }

        return new ServiceResponse(success: true, data: networks)
    }



    /**
     * Whether a failed SDN call means the cluster has no SDN API (404, or 501 "not implemented"
     * without the SDN package) rather than a failure of a cluster that has one.
     */
    static boolean sdnUnavailable(def errorCode) {
        return errorCode?.toString() in ['404', '501']
    }

    static ServiceResponse getTemplateById(HttpApiClient client, Map authConfig, Long templateId) {

        def resp = listTemplates(client, authConfig)
        def filteredTemplate = resp.data.find { it.vmid == templateId}

        return new ServiceResponse(success: resp.success, data: filteredTemplate)
    }


    static ServiceResponse listTemplates(HttpApiClient client, Map authConfig) {
        log.debug("API Util listTemplates")
        def vms = []
        def qemuVMs = callListApiV2(client, "cluster/resources", authConfig)
        if (!qemuVMs?.success || !(qemuVMs.data instanceof Collection)) {
            return ServiceResponse.error("Unable to list the cluster's resources: ${qemuVMs?.msg}")
        }
        qemuVMs.data.each { Map vm ->
            if (vm?.template == 1 && vm?.type == "qemu") {
                vm.ip = ""
                def vmConfigInfo = callListApiV2(client, "nodes/$vm.node/qemu/$vm.vmid/config", authConfig)
                def vmCfg = (vmConfigInfo?.data instanceof Map && vmConfigInfo.data.data instanceof Map)
                        ? vmConfigInfo.data.data
                        : (vmConfigInfo?.data instanceof Map ? vmConfigInfo.data : [:])
                vm.osCode = morpheusOsCode(vmCfg.ostype as String)
                vm.uuid = vmUuid(vmCfg)
                vm.maxCores = (vmConfigInfo?.data?.sockets?.toInteger() ?: 0) * (vmConfigInfo?.data?.cores?.toInteger() ?: 0)
                vm.coresPerSocket = vmConfigInfo?.data?.cores?.toInteger() ?: 0

                vm.datastores = vmConfigInfo.data?.findAll { k, v ->
                    // Match disk keys like 'virtio0', 'scsi0', 'ide1', etc.
                    k ==~ /^(virtio|scsi|ide|sata)\d+$/ && v instanceof String && v.contains(':')
                }.collect { k, v ->
                    // Extract the storage ID before the colon
                    v.split(':')[0]
                }.unique()

                vms << vm
            }
        }
        return new ServiceResponse(success: true, data: vms)
    }


    /**
     * List LXC containers. Proxmox keeps them apart from QEMU guests: they appear in
     * cluster/resources with type "lxc", and their detail lives under
     * nodes/{node}/lxc/{vmid} rather than .../qemu/{vmid}. listVMs filters this type
     * out, which is why a container is invisible to Morpheus without this.
     *
     * No guest agent is involved. A container's address is in its config's netN
     * string — "name=eth0,bridge=vmbr0,ip=<address>/<prefix>,..." — so it is parsed
     * rather than queried, and a container set to DHCP simply has no address to give.
     */
    /**
     * Morpheus OS code for a Proxmox `ostype`.
     *
     * Proxmox records it in every guest's config — "l26" for a modern Linux, "win11",
     * "solaris" and so on — and the plugin discarded it, hard-coding "unknown" so
     * every synced VM showed a grey question mark where an OS icon belongs.
     * Anything unrecognised stays "unknown" rather than being guessed at.
     */
    static String morpheusOsCode(String proxmoxOsType) {
        if (!proxmoxOsType) return 'unknown'
        String t = proxmoxOsType.toLowerCase()
        if (t in ['l24', 'l26']) return 'linux'
        if (t == 'solaris') return 'solaris'
        if (t.startsWith('w') || t.startsWith('win')) return 'windows'
        return 'unknown'
    }


    static ServiceResponse listLXCs(HttpApiClient client, Map authConfig) {
        log.debug("API Util listLXCs")
        def containers = []
        def resources = callListApiV2(client, "cluster/resources", authConfig)
        if (!resources?.success || !(resources.data instanceof Collection)) {
            return ServiceResponse.error("Unable to list the cluster's resources: ${resources?.msg}")
        }
        resources.data.each { Map ct ->
            if (ct?.type != "lxc" || ct?.template == 1) return
            def cfg = callListApiV2(client, "nodes/$ct.node/lxc/$ct.vmid/config", authConfig)
            // callListApiV2 already unwraps to the response body's `data`, so the
            // config map is cfg.data. Accept the doubly-nested shape too rather than
            // depending on which it is: reading one level too deep silently yields an
            // empty map, and an empty map here looks exactly like a container with no
            // address and no cores.
            def cfgData = (cfg?.data instanceof Map && cfg.data.data instanceof Map) ? cfg.data.data
                        : (cfg?.data instanceof Map ? cfg.data : [:])
            ct.ip = extractLxcIpv4(cfgData)
            // cluster/resources reports maxcpu; /nodes/{node}/lxc reports cpus. The
            // config's `cores` is the authority when present.
            ct.maxCores = (cfgData.cores?.toString()?.isInteger() ? cfgData.cores.toInteger()
                          : (ct.maxcpu ?: ct.cpus ?: 0))
            ct.coresPerSocket = ct.maxCores
            ct.hostname = cfgData.hostname ?: ct.name
            ct.ostype = cfgData.ostype
            // rootfs reads "local-lvm:vm-101-disk-0,size=50G"; the datastore is the
            // part before the colon, matching how listVMs reports disk storage.
            ct.datastores = ([cfgData.rootfs] + cfgData.findAll { k, v -> k ==~ /^mp\d+$/ }.values())
                    .findAll { it instanceof String && it.contains(':') }
                    .collect { it.split(':')[0] }.unique()
            containers << ct
        }
        log.debug("Found ${containers.size()} LXC container(s)")
        return new ServiceResponse(success: true, data: containers)
    }


    /**
     * IPv4 out of a Proxmox netN config string, or null.
     *
     * Handles the cases that actually occur: no netN at all, ip=dhcp, ip=manual,
     * an IPv6-only container, and the CIDR suffix. Returning null rather than an
     * empty string keeps "unknown" distinct from "none".
     */
    static String extractLxcIpv4(Map config) {
        def nets = config?.findAll { k, v -> k ==~ /^net\d+$/ && v instanceof String }?.values() ?: []
        for (String net : nets) {
            def m = (net =~ /(?:^|,)ip=([^,]+)/)
            if (!m.find()) continue
            String raw = m.group(1).trim()
            if (raw in ['dhcp', 'manual', 'auto']) continue
            String addr = raw.split('/')[0]
            // Reject IPv6 and anything that is not four dotted octets.
            if (addr ==~ /^\d{1,3}(\.\d{1,3}){3}$/) return addr
        }
        return null
    }


    /**
     * The guest's stable identity from its config: the `uuid=` of `smbios1`, which Proxmox
     * sets on every clone and keeps for the VM's life, or null when the config has none.
     * The VMID is not an identity: `cluster/nextid` hands a freed id to the next VM.
     */
    static String vmUuid(Map vmConfig) {
        String smbios = vmConfig?.smbios1?.toString()
        if (!smbios) {
            return null
        }
        def m = (smbios =~ /(?:^|,)uuid=([0-9a-fA-F-]{36})(?:,|$)/)
        return m.find() ? m.group(1).toLowerCase() : null
    }

    /**
     * Whether a stored identity still names the VM behind an id. True when nothing is stored
     * (a record from before identities were kept) or when the stored and the actual uuid are
     * equal; false when the VM behind the id is another guest, or when its uuid cannot be read.
     */
    static boolean sameGuest(String storedUuid, Map vmConfig) {
        if (!storedUuid) {
            return true
        }
        String actual = vmUuid(vmConfig)
        return actual != null && actual == storedUuid.toLowerCase()
    }

    static ServiceResponse listVMs(HttpApiClient client, Map authConfig) {
        log.debug("API Util listVMs")
        def vms = []
        def qemuVMs = callListApiV2(client, "cluster/resources", authConfig)
        if (!qemuVMs?.success || !(qemuVMs.data instanceof Collection)) {
            return ServiceResponse.error("Unable to list the cluster's resources: ${qemuVMs?.msg}")
        }
        qemuVMs.data.each { Map vm ->
            if (vm?.template == 0 && vm?.type == "qemu") {
                def vmConfigInfo = callListApiV2(client, "nodes/$vm.node/qemu/$vm.vmid/config", authConfig)
                def vmCfg = (vmConfigInfo?.data instanceof Map && vmConfigInfo.data.data instanceof Map)
                        ? vmConfigInfo.data.data
                        : (vmConfigInfo?.data instanceof Map ? vmConfigInfo.data : [:])
                vm.ip = ""
                // Proxmox answers 500 for a stopped VM or one without the agent, and core's
                // HttpApiClient logs a WARN for it on every refresh; skip the call in those cases.
                def vmAgentInfo = shouldQueryGuestAgent(vm, vmConfigInfo?.success ? vmCfg : null)
                        ? callListApiV2(client, "nodes/$vm.node/qemu/$vm.vmid/agent/network-get-interfaces", authConfig)
                        : null
                if (vmAgentInfo?.success && vmAgentInfo.data?.result) {
                    def interfaces = vmAgentInfo.data.result
                    // Iterate through each network interface
                    interfaces.each { iface ->
                        if (iface.'ip-addresses') {
                            // Iterate through each IP address in the interface
                            iface.'ip-addresses'.each { ipAddr ->
                                def ipAddress = ipAddr.'ip-address'
                                def ipType = ipAddr.'ip-address-type'
                                if (ipType == "ipv4" && ipAddress != "127.0.0.1" && vm.ip == "") {
                                    log.debug("Setting IP address for VM ${vm.vmid}: ${ipAddress}")
                                    vm.ip = ipAddress
                                }
                            }
                        }
                    }
                }
                vm.osCode = morpheusOsCode(vmCfg.ostype as String)
                vm.uuid = vmUuid(vmCfg)
                // A clone in progress carries `lock: clone` until its disks are copied.
                vm.lock = vmCfg.lock ?: vm.lock
                vm.maxCores = (vmConfigInfo?.data?.data?.sockets?.toInteger() ?: 0) * (vmConfigInfo?.data?.data?.cores?.toInteger() ?: 0)
                vm.coresPerSocket = vmConfigInfo?.data?.data?.cores?.toInteger() ?: 0

               vm.datastores = vmConfigInfo.data?.data.findAll { k, v ->
                    // Match disk keys like 'virtio0', 'scsi0', 'ide1', etc.
                    k ==~ /^(virtio|scsi|ide|sata)\d+$/ && v instanceof String && v.contains(':')
                }.collect { k, v ->
                    // Extract the storage ID before the colon
                    v.split(':')[0]
                }.unique()

                vms << vm
            }
        }
        return new ServiceResponse(success: true, data: vms)
    }


    /**
     * The VM's config as Proxmox reports it, or null when the VM cannot be read.
     */
    static Map getVMConfigById(HttpApiClient client, Map authConfig, String vmId, String nodeId = "0") {

        //proxmox api limitation. If we don't have the node we need to query all
        if (!nodeId || nodeId == "0") {
            Map vm = listVMs(client, authConfig).data.find { it.vmid?.toString() == vmId?.toString() }
            if (!vm) {
                throw new Exception("Error: VM with ID $vmId not found.")
            }
            nodeId = vm.node?.toString()
        }
        ServiceResponse vmConfigInfo = callListApiV2(client, "nodes/$nodeId/qemu/$vmId/config", authConfig)
        if (!vmConfigInfo?.success || !(vmConfigInfo.data instanceof Map)) {
            log.warn("Could not read the config of VM $vmId on node $nodeId: ${vmConfigInfo?.msg}")
            return null
        }
        return vmConfigInfo.data
    }


    static ServiceResponse listProxmoxPools(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxPools...")
        def pools = []

        ServiceResponse poolList = callListApiV2(client, "pools", authConfig)
        if (!poolList?.success || !(poolList.data instanceof Collection)) {
            return ServiceResponse.error("Unable to list the cluster's pools: ${poolList?.msg}")
        }
        List<Map> poolIds = poolList.data

        for (Map pool in poolIds) {
            ServiceResponse poolDetail = callListApiV2(client, "pools/$pool.poolid", authConfig)
            if (!poolDetail?.success || !(poolDetail.data instanceof Map)) {
                return ServiceResponse.error("Unable to read pool '${pool.poolid}': ${poolDetail?.msg}")
            }
            pools << poolDetail.data
        }

        return new ServiceResponse(success: true, data: pools)
    }

    /**
     * The storages of each pool, from the pool records that {@link #listProxmoxPools} returns:
     * storage name to the ids of the pools it belongs to. A pool's members list guests and
     * storages alike, and a storage member appears once per node, so the sets are de-duplicated.
     * Storages in no pool are absent from the map.
     */
    static Map<String, Set<String>> storagePoolMembership(Collection<Map> pools) {
        Map<String, Set<String>> rtn = [:]
        pools?.each { Map pool ->
            String poolId = pool?.poolid
            if (!poolId) {
                return
            }
            pool.members?.each { Map member ->
                if (member?.type == 'storage' && member.storage) {
                    rtn.computeIfAbsent(member.storage as String) { new LinkedHashSet<String>() } << poolId
                }
            }
        }
        return rtn
    }


    static ServiceResponse getProxmoxHypervisorHostByName(HttpApiClient client, Map authConfig, String nodeId) {
        def resp = listProxmoxHypervisorHosts(client, authConfig)
        if (!resp?.success || !(resp.data instanceof Collection)) {
            return ServiceResponse.error(resp?.msg ?: "Unable to list the cluster's nodes")
        }
        def node = resp.data.find { it.node == nodeId }

        return new ServiceResponse(success: true, data: node)
    }


    /**
     * The cluster's node list, or an error. A failed or empty `nodes` call is an error, never
     * an empty list: the host and network syncs delete every record that is not in the listing,
     * so an empty answer during an API outage would wipe the cloud's hosts and networks.
     */
    static ServiceResponse listClusterNodes(HttpApiClient client, Map authConfig) {
        ServiceResponse nodeList = callListApiV2(client, "nodes", authConfig)
        if (!nodeList?.success || !(nodeList.data instanceof Collection)) {
            return ServiceResponse.error("Unable to list the cluster's nodes: ${nodeList?.msg ?: 'no response'}")
        }
        List<Map> nodes = nodeList.data.findAll { it instanceof Map && it.node }
        if (!nodes) {
            return ServiceResponse.error("Unable to list the cluster's nodes: the API returned no node")
        }
        return new ServiceResponse(success: true, data: nodes)
    }


    static ServiceResponse getProxmoxHypervisorNodeIds(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxHosts...")

        ServiceResponse nodes = listClusterNodes(client, authConfig)
        if (!nodes.success) {
            return nodes
        }
        List<String> hvHostIds = nodes.data.collect { it.node as String }

        return new ServiceResponse(success: true, data: hvHostIds)
    }


    static ServiceResponse listProxmoxHypervisorHosts(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxHosts...")

        // Workaround: Get networks safely with error handling
        List<Map> allInterfaces = []
        try {
            ServiceResponse networkResponse = listProxmoxNetworks(client, authConfig)
            if (networkResponse?.success && networkResponse?.data) {
                allInterfaces = networkResponse.data
            }
        } catch (Exception e) {
            log.warn("Failed to get network interfaces, continuing without them: ${e.message}")
            allInterfaces = []
        }
        
        // Get datastores safely with error handling  
        List<Map> allDatastores = []
        try {
            ServiceResponse datastoreResponse = listProxmoxDatastores(client, authConfig)
            if (datastoreResponse?.success && datastoreResponse?.data) {
                allDatastores = datastoreResponse.data
            }
        } catch (Exception e) {
            log.warn("Failed to get datastores, continuing without them: ${e.message}")
            allDatastores = []
        }

        ServiceResponse nodeList = listClusterNodes(client, authConfig)
        if (!nodeList.success) {
            return nodeList
        }
        List<Map> nodes = nodeList.data
        nodes.each { Map hvHost ->
            try {
                def nodeNetworkInfo = callListApiV2(client, "nodes/$hvHost.node/network", authConfig)
                
                // Check if network info was retrieved successfully
                if (!nodeNetworkInfo.success || !nodeNetworkInfo.data) {
                    log.warn("Failed to retrieve network info for node ${hvHost.node}, setting default IP")
                    hvHost.ipAddress = ""  // Set default IP as empty for offline nodes
                } else {
                    def sortedNetworks = nodeNetworkInfo.data.sort { a, b ->
                        def aIface = a?.iface
                        def bIface = b?.iface

                        // Push null/empty iface to the bottom
                        if (!aIface && bIface) return 1
                        if (!bIface && aIface) return -1
                        if (!aIface && !bIface) return 0

                        // Prioritize vmbr0
                        if (aIface == 'vmbr0') return -1
                        if (bIface == 'vmbr0') return 1

                        // Normal alphabetical sort
                        return aIface <=> bIface
                    }
                    
                    log.debug("Sorted Networks for node ${hvHost.node}: $sortedNetworks")
                    
                    // Find the first network interface with a valid address
                    def validInterface = sortedNetworks.find { it != null && it.address != null && it.address.trim() != "" }
                    
                    if (validInterface) {
                        hvHost.ipAddress = validInterface.address
                        log.debug("Set IP address for node ${hvHost.node}: ${hvHost.ipAddress}")
                    } else {
                        log.warn("No valid network interface found for node ${hvHost.node}, using node name as fallback")
                        hvHost.ipAddress = hvHost.node  // Use node name as fallback
                    }
                }

                // Socket topology, which /nodes does not carry.
                //
                // This is what Morpheus licensing counts: it derives sockets on
                // a hypervisor host from its core count and cores-per-socket,
                // and with cores-per-socket at 0 the answer is 0 sockets. The
                // appliance then falls back to counting guests as public-cloud
                // workloads at 15:1 -- a one-socket host running four VMs
                // reported 0.2667 sockets instead of 1.
                //
                // /nodes/<node>/status has it: cpuinfo.sockets and cpuinfo.cores.
                // Best effort -- a node that does not answer keeps the old
                // behaviour rather than failing the whole host sync.
                try {
                    def statusInfo = callListApiV2(client, "nodes/$hvHost.node/status", authConfig)
                    def cpuinfo = statusInfo?.success ? statusInfo.data?.cpuinfo : null
                    if (cpuinfo) {
                        def sockets = (cpuinfo.sockets ?: 0) as Integer
                        def cores = (cpuinfo.cores ?: 0) as Integer
                        hvHost.cpuSockets = sockets
                        // Morpheus wants cores PER SOCKET, and Proxmox reports
                        // the totals. Guard the division: a node reporting 0
                        // sockets must leave this 0 rather than throw.
                        hvHost.coresPerSocket = (sockets > 0 && cores > 0)
                                ? (cores.intdiv(sockets)) : 0
                        log.debug("Node ${hvHost.node}: ${sockets} socket(s), ${cores} core(s) -> coresPerSocket=${hvHost.coresPerSocket}")
                    } else {
                        log.warn("No cpuinfo for node ${hvHost.node}; socket count will stay unknown")
                        hvHost.cpuSockets = 0
                        hvHost.coresPerSocket = 0
                    }
                } catch (Exception e) {
                    log.warn("Failed to read status for node ${hvHost.node}: ${e.message}")
                    hvHost.cpuSockets = 0
                    hvHost.coresPerSocket = 0
                }

                // Set networks (with null checking and safe fallback)
                if (allInterfaces) {
                    hvHost.networks = allInterfaces
                            ?.findAll { it?.host == hvHost.node || it?.host == 'all' }
                            ?.collect { it?.iface }
                            ?.findAll { it != null } ?: []
                } else {
                    // Fallback: extract from direct node network call
                    hvHost.networks = nodeNetworkInfo?.data?.findAll { it?.iface }?.collect { it.iface } ?: []
                }

                // Set datastores (with null checking and safe fallback)
                if (allDatastores) {
                    hvHost.datastores = allDatastores
                            ?.findAll { ds -> 
                                def dsNodes = ds?.nodes?.toString()
                                return dsNodes && (dsNodes.split(",").contains(hvHost.node) || dsNodes == 'all')
                            }
                            ?.collect { it?.storage }
                            ?.findAll { it != null } ?: []
                } else {
                    // Fallback: try to get datastores directly for this node
                    try {
                        ServiceResponse nodeStorageResponse = callListApiV2(client, "nodes/${hvHost.node}/storage", authConfig)
                        if (nodeStorageResponse?.success && nodeStorageResponse?.data) {
                            hvHost.datastores = nodeStorageResponse.data.collect { it?.storage }.findAll { it != null }
                        } else {
                            hvHost.datastores = []
                        }
                    } catch (Exception e) {
                        log.warn("Failed to get storage for node ${hvHost.node}: ${e.message}")
                        hvHost.datastores = []
                    }
                }
                        
            } catch (Exception e) {
                log.error("Error processing node ${hvHost.node}: ${e.message}", e)
                // Set default values for failed nodes
                hvHost.ipAddress = ""
                hvHost.networks = []
                hvHost.datastores = []
            }
        }

        return new ServiceResponse(success: true, data: nodes)
    }
    
    
    static ServiceResponse callListApiV2(HttpApiClient client, String path, Map authConfig) {
        log.debug("callListApiV2: path: ${path}")

        def tokenCfg = getApiV2Token(authConfig).data
        def rtn = new ServiceResponse(success: false)
        try {
            rtn.data = []
            
            // Separate path and query string for proper HTTP client handling
            def pathParts = path.split('\\?', 2)
            def actualPath = "${authConfig.v2basePath}/${pathParts[0]}"
            def queryString = pathParts.length > 1 ? pathParts[1] : null
            
            log.debug("callListApiV2: actualPath=${actualPath}, queryString=${queryString}")
            
            def opts = new HttpApiClient.RequestOptions(
                    headers: [
                        'Content-Type': 'application/json',
                        'Cookie': "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: ignoreSsl(authConfig)
            )
            // The query goes in queryParams: the third argument of callJsonApi is the user name,
            // so a query string passed there was dropped and every listing came back unfiltered.
            Map<CharSequence, CharSequence> queryParams = parseQueryString(queryString)
            if (queryParams) {
                opts.queryParams = queryParams
            }

            def results = withProxy(client, authConfig).callJsonApi(authConfig.apiUrl, actualPath, null, null, opts, 'GET')
            
            // The client hands a body it did not parse over as byte[]; read it as JSON before
            // unwrapping, instead of failing with "No signature of method: [B.getAt()".
            def body = results.data
            if (body instanceof byte[]) {
                body = body.length ? new JsonSlurper().parseText(new String(body, 'UTF-8')) : null
            }
            def resultData = (body instanceof Map) ? body.data : null
            log.debug("callListApiV2 results: ${resultData}")
            if(results?.success && !results?.hasErrors()) {
                rtn.success = true
                rtn.data = resultData
            } else {
                if(!rtn.success) {
                    rtn.msg = results.data + results.errors
                    rtn.errorCode = results?.errorCode
                    rtn.success = false
                }
            }
        } catch(e) {
            log.error "Error in callListApiV2: ${e}", e
            rtn.msg = "Error in callListApiV2: ${e}"
            rtn.success = false
        }
        return rtn
    }


    /**
     * The parameters of a query string ("content=images&vmid=101") as a map for
     * RequestOptions.queryParams; empty for none.
     */
    static Map<CharSequence, CharSequence> parseQueryString(String queryString) {
        Map<CharSequence, CharSequence> params = [:]
        (queryString ?: '').split('&').each { String pair ->
            if (pair) {
                List<String> kv = pair.split('=', 2) as List<String>
                params[URLDecoder.decode(kv[0], 'UTF-8')] = kv.size() > 1 ? URLDecoder.decode(kv[1], 'UTF-8') : ''
            }
        }
        return params
    }

    /**
     * The session ticket and CSRF token for a job's requests. The first call of a job logs in
     * (POST access/ticket) and keeps both in the job's authConfig, with the time it got them;
     * later calls return them while they are younger than TICKET_MAX_AGE_MS. Before 0.1.33 every
     * request logged in again. Nothing outlives the job: each job builds its own authConfig
     * (ProxmoxVePlugin.getAuthConfig, or validate()), and no code saves or logs that map. A failed
     * login keeps nothing, so the next call tries again.
     */
    static ServiceResponse getApiV2Token(Map authConfig) {
        Map ticket = cachedTicket(authConfig, System.currentTimeMillis())
        if (ticket) {
            return new ServiceResponse(success: true, data: ticket)
        }
        // A client of its own for the login (see ignoreSsl)
        HttpApiClient client = new HttpApiClient()
        try {
            return login(client, authConfig)
        } finally {
            // Without this each login kept its connection pool open until garbage collection.
            client.shutdownClient()
        }
    }

    /** The job's ticket as [csrfToken, token] while it is younger than TICKET_MAX_AGE_MS, else null. */
    static Map cachedTicket(Map authConfig, long now) {
        def kept = authConfig?.get(TICKET_KEY)
        if (!(kept instanceof Map) || !kept.token || !kept.csrfToken || !(kept.obtainedAt instanceof Number)) {
            return null
        }
        long age = now - (kept.obtainedAt as long)
        return (age >= 0 && age < TICKET_MAX_AGE_MS) ? [csrfToken: kept.csrfToken, token: kept.token] : null
    }

    /**
     * Logs in through the given client and keeps the ticket in the job's authConfig. The login's
     * response is kept out of the log: HttpApiClient prints a successful response at DEBUG, and
     * this one holds the ticket.
     */
    static ServiceResponse login(HttpApiClient client, Map authConfig) {
        def path = "access/ticket"
        def rtn = new ServiceResponse(success: false)
        try {

            def encUid = URLEncoder.encode((String) authConfig.username, "UTF-8")
            def encPwd = URLEncoder.encode((String) authConfig.password, "UTF-8")
            def bodyStr = "username=" + "$encUid" + "&password=$encPwd"

            HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                    headers: ['Content-Type':'application/x-www-form-urlencoded'],
                    body: bodyStr,
                    contentType: ContentType.APPLICATION_FORM_URLENCODED,
                    ignoreSSL: ignoreSsl(authConfig),
                    suppressLog: true
            )
            def results = withProxy(client, authConfig).callJsonApi(authConfig.apiUrl,"${authConfig.v2basePath}/${path}", opts, 'POST')

            if(results?.success && !results?.hasErrors()) {
                def tokenData = results.data.data
                rtn.success = true
                rtn.data = [csrfToken: tokenData.CSRFPreventionToken, token: tokenData.ticket]
                authConfig[TICKET_KEY] = [token: tokenData.ticket, csrfToken: tokenData.CSRFPreventionToken,
                                          obtainedAt: System.currentTimeMillis()]
            } else {
                String detail = [results?.error, results?.errors ? results.errors.toString() : null, results?.content]
                        .findAll { it }.join(' ')
                rtn.success = false
                if (!ignoreSsl(authConfig) && certificateFailure(detail)) {
                    rtn.msg = CERTIFICATE_NOT_TRUSTED_MSG
                    log.error("Login to ${authConfig.apiUrl} failed, the certificate is not trusted: ${detail}")
                } else {
                    rtn.msg = "Error retrieving token: ${detail ?: results?.data}"
                    log.error("Error retrieving token: ${detail ?: results?.data}")
                }
            }
            return rtn
        } catch(e) {
            log.error "Error in getApiV2Token: ${e}", e
            rtn.msg = "Error in getApiV2Token: ${e}"
            rtn.success = false
        }
        return rtn
    }
}
