package com.morpheusdata.proxmox.ve.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ServicePlan
import groovy.util.logging.Slf4j

/**
 * Picks the service plan for a discovered guest (unmanaged QEMU VM or LXC container).
 *
 * Morpheus prices a server's usage from the price sets of its plan. A discovered
 * guest had no plan, so with costing on it was always priced at zero. The REST API
 * cannot set a plan on an existing server, so the sync has to.
 *
 * A guest whose cores and memory equal one of the plugin's `proxmox-ve-vm-*` plans
 * gets that plan; any other guest gets `proxmox-ve-internal-custom`, which a price
 * set of type component prices by the server's own cores, memory and storage. No
 * price lives in the plugin: the plans carry whatever price sets the operator attaches.
 */
@Slf4j
class GuestPlanResolver {

    static final String PROVISION_TYPE_CODE = 'proxmox-provision-provider'
    static final String SIZED_PLAN_PREFIX = 'proxmox-ve-vm-'
    static final String CUSTOM_PLAN_CODE = 'proxmox-ve-internal-custom'

    private final Collection<ServicePlan> sizedPlans
    private final ServicePlan customPlan

    GuestPlanResolver(Collection<ServicePlan> sizedPlans, ServicePlan customPlan) {
        this.sizedPlans = sizedPlans ?: []
        this.customPlan = customPlan
    }

    /**
     * Loads the plugin's own plans only. Other plans on the same provision type, such
     * as an operator's tenant plans, are never assigned to a discovered guest.
     */
    static GuestPlanResolver load(MorpheusContext context) {
        Collection<ServicePlan> plans = context.services.servicePlan.list(new DataQuery()
                .withFilter('provisionType.code', PROVISION_TYPE_CODE)) ?: []
        def sized = plans.findAll { it.active != false && it.code?.startsWith(SIZED_PLAN_PREFIX) }
        def custom = plans.find { it.code == CUSTOM_PLAN_CODE }
        if (!custom) {
            log.warn("Service plan ${CUSTOM_PLAN_CODE} not found; discovered guests that match no " +
                    "${SIZED_PLAN_PREFIX}* plan stay without a plan")
        }
        return new GuestPlanResolver(sized, custom)
    }

    /** The plan for a guest of this size, or null when there is none to give. */
    ServicePlan resolve(Long maxMemory, Long maxCores) {
        if (maxMemory && maxCores) {
            def sized = sizedPlans.find { it.maxMemory == maxMemory && (it.maxCores ?: 1L) == maxCores }
            if (sized) {
                return sized
            }
        }
        return customPlan
    }

    /**
     * Whether the sync may set or change a server's plan: it has none yet, or it has
     * one this resolver hands out. Any other plan was chosen by someone else and stays.
     */
    static boolean mayAssign(ServicePlan current) {
        return !current || current.code == CUSTOM_PLAN_CODE || current.code?.startsWith(SIZED_PLAN_PREFIX)
    }
}
