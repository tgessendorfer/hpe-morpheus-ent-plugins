package com.morpheusdata.proxmox.ve

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.CloudSummaryProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import groovy.util.logging.Slf4j

/**
 * Contributes a block to the cloud's Summary panel.
 *
 * The panel itself is a fixed set of core fields — Appliance URL, Datacenter ID,
 * Network Mode, Time Zone and so on — and a plugin cannot add to it. An earlier
 * attempt used OptionType.displayValueOnDetails and produced nothing: the field
 * registered on the zone type, serviceVersion held the version, and the panel
 * carried on showing its nine fields. None of the 24 built-in cloud types uses
 * that flag, which was the warning.
 *
 * This is the supported route, and it is wired end to end on 9.0.1:
 *
 *   CloudSummaryProvider          morpheus-plugin-api-1.4.1.jar  (the appliance's own)
 *   CloudPluginComputeService     morpheus-core-9.0.1.jar        (sets summaryHtml)
 *   plugins/_zoneSummary.gsp      ${raw(zoneSummary?.summaryHtml)}
 *
 * The contract is raw HTML, injected verbatim. So the markup here must match the
 * page's own conventions rather than invent styling, and every value must be
 * escaped: it is rendered unescaped by design.
 */
@Slf4j
class ProxmoxVeCloudSummaryProvider implements CloudSummaryProvider {

    protected MorpheusContext context
    protected ProxmoxVePlugin plugin

    ProxmoxVeCloudSummaryProvider(ProxmoxVePlugin plugin, MorpheusContext context) {
        this.@plugin = plugin
        this.@context = context
    }

    @Override
    MorpheusContext getMorpheus() { return context }

    @Override
    Plugin getPlugin() { return plugin }

    @Override
    String getCode() { return 'proxmox-ve-cloud-summary' }

    @Override
    String getName() { return 'Proxmox VE Cloud Summary' }

    /**
     * Required by UIExtensionProvider. Nothing here is templated — renderCloudSummary
     * builds its own markup — but the interface demands a renderer, so give it a real
     * one rather than null, which would NPE if anything ever asked for it.
     */
    @Override
    Renderer<?> getRenderer() {
        return new HandlebarsRenderer(plugin.classLoader)
    }

    @Override
    HTMLResponse renderCloudSummary(Cloud cloud, User user, Object opts) {
        log.info("renderCloudSummary called for cloud ${cloud?.id} (${cloud?.name})")
        try {
            def rows = []
            if (cloud?.serviceVersion) {
                rows << ['Proxmox VE Version', cloud.serviceVersion]
            }
            if (cloud?.serviceUrl) {
                rows << ['API Endpoint', cloud.serviceUrl]
            }

            // Counted rather than assumed: "how many nodes" is the question an
            // operator actually asks of a cluster, and the cloud panel does not
            // otherwise answer it.
            def counts = countByType(cloud)
            if (counts.nodes != null) rows << ['Proxmox Nodes', counts.nodes.toString()]
            if (counts.vms != null) rows << ['QEMU VMs', counts.vms.toString()]
            if (counts.containers != null) rows << ['LXC Containers', counts.containers.toString()]

            if (!rows) {
                // Nothing known yet — before the first refresh. Rendering an empty
                // block would look like a broken panel, so render nothing at all.
                return HTMLResponse.success('')
            }

            StringBuilder html = new StringBuilder()
            html << '<div class="info-section"><div class="info-title">Proxmox VE</div>'
            html << '<div class="collapsible-section collapsible-flex"><dl class="info-list">'
            rows.each { pair ->
                html << '<div><dt>' << esc(pair[0]) << ': </dt><dd>' << esc(pair[1]) << '</dd></div>'
            }
            html << '</dl></div></div>'
            return HTMLResponse.success(html.toString())
        } catch (e) {
            // A summary panel must never take the cloud page down with it. The page
            // rendering an exception is how a 403 gets served for something that is
            // not a permissions problem at all.
            log.error("Error rendering the Proxmox cloud summary: ${e}", e)
            return HTMLResponse.success('')
        }
    }

    /** Counts by server type code, or nulls when they cannot be read. */
    private Map countByType(Cloud cloud) {
        def out = [nodes: null, vms: null, containers: null]
        try {
            def codes = context.async.computeServer.listIdentityProjections(cloud.id, null)
                    .map { it.computeServerTypeCode }.toList().blockingGet()
            out.nodes = codes.count { it == 'proxmox-ve-node' }
            out.vms = codes.count { it in ['proxmox-qemu-vm', 'proxmox-qemu-vm-unmanaged'] }
            out.containers = codes.count { it == 'proxmox-lxc-container' }
        } catch (e) {
            log.warn("Could not count Proxmox servers for the summary: ${e.message}")
        }
        return out
    }

    /** The template injects this with raw(), so escaping here is not optional. */
    private static String esc(Object value) {
        return value?.toString()
                ?.replace('&', '&amp;')?.replace('<', '&lt;')?.replace('>', '&gt;')
                ?.replace('"', '&quot;')?.replace("'", '&#39;') ?: ''
    }
}
