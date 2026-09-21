package com.morpheusdata.proxmox.ve.util

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.LogLevel
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * @author Neil van Rensburg
 */

@Slf4j
class ProxmoxSshUtil {

    static String IMAGE_PATH_PREFIX = "/var/opt/morpheus/morpheus-ui/vms/morpheus-images"
    static String REMOTE_IMAGE_DIR = "/var/lib/vz/template/qemu"
    static Integer SSH_PORT = 22
    static Integer DEFAULT_TEMPLATE_CPUS = 1
    static Long DEFAULT_TEMPLATE_MEMORY = 1024L
    static String SNIPPETS_STORAGE = "local"
    static String SNIPPETS_DIR = "/var/lib/vz/snippets"
    static String SSH_HINT = "Check the SSH credentials of the host under the cloud's Hosts tab; the user must be root."

    /**
     * Proves that Morpheus can run qm on the node over SSH before anything is created there.
     * A failed login otherwise surfaces after the clone as an opaque "session is down", and
     * leaves a stopped VM behind.
     */
    static void checkSshAccess(MorpheusContext context, ComputeServer hvNode) {
        String target = "${hvNode.sshUsername}@${hvNode.sshHost}"
        TaskResult result
        try {
            result = context.executeSshCommand(hvNode.sshHost, SSH_PORT, hvNode.sshUsername, hvNode.sshPassword, "qm list >/dev/null", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
        } catch (Exception e) {
            throw new Exception("SSH to Proxmox node '${hvNode.externalId}' as $target failed: ${e.message}. $SSH_HINT", e)
        }
        if (!result?.success) {
            String detail = result?.error ?: result?.output ?: "exit code ${result?.exitCode}"
            throw new Exception("SSH to Proxmox node '${hvNode.externalId}' as $target cannot run qm: ${detail}. $SSH_HINT")
        }
        log.debug("SSH access to Proxmox node '${hvNode.externalId}' as $target verified")
    }

    /**
     * cicustom needs a storage that offers snippets. A fresh Proxmox install has none: 'local'
     * carries iso, vztmpl and backup only, and Proxmox refuses the snippet reference with
     * "storage 'local' does not support content type 'snippets'".
     */
    static void ensureSnippetsStorage(MorpheusContext context, HttpApiClient client, Map authConfig, ComputeServer hvNode) {
        String content = ProxmoxApiComputeUtil.getStorageContent(client, authConfig, hvNode.externalId, SNIPPETS_STORAGE)
        if (content != null && !ProxmoxApiComputeUtil.storageContentHasSnippets(content)) {
            log.info("Storage '$SNIPPETS_STORAGE' on node ${hvNode.externalId} offers no snippets (content: $content); adding the content type")
            runSshCmd(context, hvNode, "pvesm set $SNIPPETS_STORAGE --content ${content},snippets")
        }
        log.debug("Ensuring snippets directory on node: $hvNode.externalId")
        runSshCmd(context, hvNode, "mkdir -p $SNIPPETS_DIR")
    }

    /**
     * Writes Morpheus's cloud-init user-data as a snippet and points the VM at it, attaches the
     * cloud-init drive, and sets the network through Proxmox's own ipconfigN (see
     * {@link ProxmoxApiComputeUtil#buildCloudInitNetworkSettings}). Morpheus's network snippet
     * is not used: it names the interface eth0, and Proxmox's generated network-config matches
     * the NIC by MAC address instead.
     */
    static void createCloudInitDrive(MorpheusContext context, HttpApiClient client, Map authConfig,
                                     ComputeServer hvNode, WorkloadRequest workloadRequest,
                                     String vmId, String datastoreId, Map<String, String> networkSettings) {
        log.debug("Configuring Cloud-Init")
        ensureSnippetsStorage(context, client, authConfig, hvNode)
        String userFile = "$vmId-cloud-init-user-data.yml"
        log.debug("Creating cloud-init user-data file on hypervisor node: $SNIPPETS_DIR/$userFile")
        ProxmoxMiscUtil.sftpCreateFile(hvNode.sshHost, SSH_PORT, hvNode.sshUsername, hvNode.sshPassword, "$SNIPPETS_DIR/$userFile", workloadRequest.cloudConfigUser, null)
        String existingCloudInitVolume = ProxmoxApiComputeUtil.findCloudInitVolume(
                client, authConfig, hvNode.externalId, datastoreId, vmId)
        if (existingCloudInitVolume) {
            log.info("Reattaching existing cloud-init volume $existingCloudInitVolume")
            runSshCmd(context, hvNode, "qm set $vmId --ide2 $existingCloudInitVolume,media=cdrom")
        } else {
            log.debug("Creating cloud-init vm disk: $datastoreId:cloudinit")
            runSshCmd(context, hvNode, "qm set $vmId --ide2 $datastoreId:cloudinit")
        }
        log.debug("Mounting cloud-init data to disk...")
        runSshCmd(context, hvNode, "qm set $vmId --cicustom \"user=$SNIPPETS_STORAGE:snippets/$userFile\"")
        Map<String, String> settings = networkSettings ?: [:]
        if (!settings) {
            Map vmConfig = ProxmoxApiComputeUtil.getVMConfigById(client, authConfig, vmId, hvNode.externalId)
            if (!vmConfig?.ipconfig0) {
                settings = [ipconfig0: 'ip=dhcp']
            }
        }
        if (settings) {
            log.info("Cloud-init network for VM $vmId: $settings")
            String args = settings.collect { key, value -> "--$key '${value.replace("'", "")}'" }.join(' ')
            runSshCmd(context, hvNode, "qm set $vmId $args")
        }
    }

    /**
     * Removes the cloud-init snippets written for a VM. Both names are matched so a snippet
     * from an earlier build, which also wrote a network file, goes too.
     */
    static void removeCloudInitSnippets(MorpheusContext context, ComputeServer hvNode, String vmId) {
        if (!(vmId ==~ /\d+/)) {
            return
        }
        log.debug("Removing cloud-init snippets of VM $vmId on node ${hvNode.externalId}")
        runSshCmd(context, hvNode, "rm -f $SNIPPETS_DIR/$vmId-cloud-init-user-data.yml $SNIPPETS_DIR/$vmId-cloud-init-network.yml")
    }

    /**
     * Upload image file to Proxmox node (does not create template)
     */
    public static String uploadImage(MorpheusContext context, ComputeServer hvNode, String imageFile) {
        log.debug("Uploading image $imageFile to node $hvNode.name")

        TaskResult mkdirResult = context.executeSshCommand(hvNode.sshHost, SSH_PORT, hvNode.sshUsername, hvNode.sshPassword, "mkdir -p $REMOTE_IMAGE_DIR", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
        if (!mkdirResult.success) {
            log.error("SSH FAILED on ${hvNode.sshHost}: mkdir -p $REMOTE_IMAGE_DIR | Exit Code: ${mkdirResult.exitCode} | Output: ${mkdirResult.output} | Error: ${mkdirResult.error}")
            throw new Exception("Failed to create directory $REMOTE_IMAGE_DIR on ${hvNode.sshHost}: ${mkdirResult.error}")
        }
        
        ProxmoxMiscUtil.sftpUpload(hvNode.sshHost, SSH_PORT, hvNode.sshUsername, hvNode.sshPassword, "$IMAGE_PATH_PREFIX/$imageFile", REMOTE_IMAGE_DIR, null)

        String fileName = new File("$imageFile").getName()
        return "$REMOTE_IMAGE_DIR/$fileName"
    }

    /**
     * Create a template from an image file on Proxmox node
     */
    public static String createTemplateFromImage(MorpheusContext context, HttpApiClient client, Map authConfig, VirtualImage virtualImage, ComputeServer hvNode, String targetDS, String remoteImagePath) {
        log.debug("Creating template from image on node $hvNode.name, datastore $targetDS")

        ServiceResponse templateResp = ProxmoxApiComputeUtil.createImageTemplate(client, authConfig, virtualImage.name, hvNode.externalId, DEFAULT_TEMPLATE_CPUS, DEFAULT_TEMPLATE_MEMORY)
        if (!templateResp?.success || !templateResp?.data?.templateId) {
            throw new Exception("Failed to create template shell for ${virtualImage.name}: ${templateResp?.msg ?: 'no VM ID returned'}")
        }
        def imageExternalId = templateResp.data.templateId

        // Canonical syntax in current Proxmox VE, including PVE 9.
        TaskResult importResult = context.executeSshCommand(hvNode.sshHost, SSH_PORT, hvNode.sshUsername, hvNode.sshPassword, "qm disk import $imageExternalId $remoteImagePath $targetDS", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
        
        if (!importResult.success) {
            log.error("SSH FAILED on ${hvNode.sshHost}: qm disk import | Exit Code: ${importResult.exitCode} | Output: ${importResult.output} | Error: ${importResult.error}")
            throw new Exception("Failed to import disk for template $imageExternalId: ${importResult.error}")
        }
        
        String diskId = ProxmoxApiComputeUtil.getUnusedVMDisks(client, authConfig, hvNode.externalId, imageExternalId)?.last()?.volumeId
        if (diskId) {
            log.info("Detected imported disk identifier from VM configuration: ${diskId}")
        }

        String importOutput = importResult?.output ?: importResult?.data
        if (!diskId && importOutput) {
            def matcher = importOutput =~ /imported disk ['"]([^'"]+)['"]/
            if (matcher.find()) {  
                diskId = matcher.group(1)
                log.info("Detected imported disk identifier from output: ${diskId}")
            }
        }
        
        if (!diskId) {
            log.debug("Could not parse disk ID from import output, constructing based on image format")
            String imageFileName = remoteImagePath.substring(remoteImagePath.lastIndexOf('/') + 1)
            
            // Check if image is raw or qcow2 format 
            if (imageFileName.toLowerCase().endsWith('.raw') || imageFileName.toLowerCase().endsWith('.qcow2')) {
                // Directory-based storage 
                String extension = imageFileName.substring(imageFileName.lastIndexOf('.'))
                diskId = "$targetDS:$imageExternalId/vm-$imageExternalId-disk-0$extension"
                log.debug("Using directory-based storage format with extension: ${diskId}")
            } else {
                // Block-based storage 
                diskId = "$targetDS:vm-$imageExternalId-disk-0"
                log.debug("Using block-based storage format (no directory/extension): ${diskId}")
            }
        }
        
        TaskResult attachResult = context.executeSshCommand(hvNode.sshHost, SSH_PORT, hvNode.sshUsername, hvNode.sshPassword, "qm set $imageExternalId --scsi0 ${diskId} --boot order=scsi0", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
        if (!attachResult.success) {
            log.error("SSH FAILED on ${hvNode.sshHost}: qm set --scsi0 | Exit Code: ${attachResult.exitCode} | Output: ${attachResult.output} | Error: ${attachResult.error}")
            throw new Exception("Failed to attach disk to template $imageExternalId: ${attachResult.error}")
        }
        TaskResult agentResult = context.executeSshCommand(hvNode.sshHost, SSH_PORT, hvNode.sshUsername, hvNode.sshPassword, "qm set $imageExternalId --agent enabled=1", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
        if (!agentResult.success) {
            log.error("SSH FAILED on ${hvNode.sshHost}: qm set --agent | Exit Code: ${agentResult.exitCode} | Output: ${agentResult.output} | Error: ${agentResult.error}")
            throw new Exception("Failed to enable guest agent for template $imageExternalId: ${agentResult.error}")
        }

        return imageExternalId
    }


    /**
     * Legacy method - uploads image and creates template with lock mechanism
     * @deprecated Use uploadImage and createTemplateFromImage separately.
     * Note: When migrating, callers must implement their own locking mechanism using context.acquireLock()
     * with the lock key pattern: "proxmox.ve.imageupload.${cloud.regionCode}.${virtualImage?.id}"
     * to prevent concurrent image uploads to the same cloud region and virtual image.
     */
    public static String uploadImageAndCreateTemplate(MorpheusContext context, HttpApiClient client, Map authConfig, Cloud cloud, VirtualImage virtualImage, ComputeServer hvNode, String targetDS, String imageFile) {
        def imageExternalId
        def lockKey = "proxmox.ve.imageupload.${cloud.regionCode}.${virtualImage?.id}".toString()
        def lock

        try {
            //hold up to a 1 hour lock for image upload (large images can take time)
            lock = context.acquireLock(lockKey, [timeout: 60l * 60l * 1000l, ttl: 60l * 60l * 1000l]).blockingGet()

            String remoteImagePath = uploadImage(context, hvNode, imageFile)
            imageExternalId = createTemplateFromImage(context, client, authConfig, virtualImage, hvNode, targetDS, remoteImagePath)
        } catch (Exception e) {
            log.error("Failed to upload image and create template: ${e.message}", e)
            throw new Exception("Failed to upload image and create template for ${virtualImage.name}: ${e.message}", e)
        } finally {
            context.releaseLock(lockKey, [lock:lock]).blockingGet()
        }
        return imageExternalId
    }


    private static runSshCmd(MorpheusContext context, ComputeServer hvNode, String cmd) {
        TaskResult result = context.executeSshCommand(hvNode.sshHost, SSH_PORT, hvNode.sshUsername, hvNode.sshPassword, cmd, "", "", "", false, LogLevel.info, true, null, false).blockingGet()
        if (!result.success) {
            def errorMsg = "SSH FAILED on ${hvNode.sshHost}: ${cmd} | Exit Code: ${result.exitCode} | Output: ${result.output} | Error: ${result.error}"
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
    }

}
