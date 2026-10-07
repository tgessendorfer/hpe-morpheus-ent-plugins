package com.morpheus.test

import com.morpheusdata.model.ServicePlan
import com.morpheusdata.proxmox.ve.sync.GuestPlanResolver
import spock.lang.Specification

class GuestPlanResolverSpec extends Specification {

    static final long GB = 1024L * 1024L * 1024L

    static ServicePlan plan(Long id, String code, Long cores, Long memory) {
        def p = new ServicePlan(code: code, maxCores: cores, maxMemory: memory)
        p.id = id
        return p
    }

    def custom = plan(9L, 'proxmox-ve-internal-custom', 1L, 0L)
    def resolver = new GuestPlanResolver([
            plan(1L, 'proxmox-ve-vm-1024', 1L, GB),
            plan(2L, 'proxmox-ve-vm-2048', 1L, 2 * GB),
            plan(3L, 'proxmox-ve-vm-2048-2', 2L, 2 * GB),
    ], custom)

    def "a guest whose cores and memory equal a sized plan gets that plan"() {
        expect:
        resolver.resolve(memory, cores)?.code == expected

        where:
        memory | cores || expected
        GB     | 1L    || 'proxmox-ve-vm-1024'
        2 * GB | 1L    || 'proxmox-ve-vm-2048'
        2 * GB | 2L    || 'proxmox-ve-vm-2048-2'
    }

    def "any other guest gets the custom plan"() {
        expect:
        resolver.resolve(memory, cores).is(custom)

        where:
        memory              | cores
        384L * 1024L * 1024 | 1L
        2 * GB              | 4L
        10 * GB             | 4L
        null                | 1L
        GB                  | null
    }

    def "without a custom plan an unmatched guest gets none"() {
        expect:
        new GuestPlanResolver([], null).resolve(GB, 1L) == null
    }

    def "the sync changes only plans it hands out itself"() {
        expect:
        GuestPlanResolver.mayAssign(current) == expected

        where:
        current                                     || expected
        null                                        || true
        plan(1L, 'proxmox-ve-vm-1024', 1L, GB)      || true
        plan(9L, 'proxmox-ve-internal-custom', 1L, 0L) || true
        plan(5L, 'contoso-small', 1L, GB)           || false
    }
}
