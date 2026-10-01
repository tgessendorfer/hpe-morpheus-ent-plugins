/*
 * Copyright 2026 Thomas Gessendorfer.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.morpheusdata.socketreport

import java.math.RoundingMode
import java.text.NumberFormat

/**
 * Pure socket arithmetic of the report, free of Morpheus types so it can be unit tested.
 *
 * Counting rule (measured against GET /api/license currentUsage on Morpheus 9.0.2, may change):
 * <ul>
 *   <li>A hypervisor host counts its max_sockets; a host without a value counts the
 *       configured default (2 on 9.0.2).</li>
 *   <li>A server in a cloud without any discovered host counts as a VM: VMs / ratio
 *       (15 VMs = 1 socket on 9.0.2).</li>
 *   <li>VMs on discovered hosts add nothing.</li>
 * </ul>
 */
class SocketMath {

	static final BigDecimal DEFAULT_VMS_PER_SOCKET = new BigDecimal(15)
	static final BigDecimal DEFAULT_HOST_SOCKETS = new BigDecimal(2)
	/** Scale shown and stored for fractional socket values. */
	static final int SCALE = 3
	/** Internal scale of a single division before it is rounded for display. */
	static final int DIVISION_SCALE = 6

	/** Reads a report option from the top level of the config map or from its nested 'config' map. */
	static Object option(Object configMap, String key) {
		if (!(configMap instanceof Map)) {
			return null
		}
		Map m = (Map) configMap
		// Report options from the API arrive as a Grails JSONObject whose get() throws on a missing key.
		Object value = m.containsKey(key) ? m.get(key) : null
		if (value == null || value.toString().trim().isEmpty()) {
			Object nested = m.containsKey('config') ? m.get('config') : null
			value = (nested instanceof Map && ((Map) nested).containsKey(key)) ? ((Map) nested).get(key) : null
		}
		return value
	}

	/** Parses a positive number; null, blank, non-numeric, zero or negative input yields null. */
	static BigDecimal parsePositive(Object raw) {
		if (raw == null) {
			return null
		}
		String s = raw.toString().trim()
		if (s.isEmpty()) {
			return null
		}
		try {
			BigDecimal v = new BigDecimal(s)
			return v.signum() > 0 ? v : null
		} catch (NumberFormatException ignored) {
			return null
		}
	}

	/** True when the raw option is empty (default applies) or a positive number. */
	static boolean isValidOption(Object raw) {
		return raw == null || raw.toString().trim().isEmpty() || parsePositive(raw) != null
	}

	static BigDecimal positiveOr(Object raw, BigDecimal fallback) {
		return parsePositive(raw) ?: fallback
	}

	static BigDecimal num(Object v) {
		if (v == null) {
			return BigDecimal.ZERO
		}
		try {
			return new BigDecimal(v.toString().trim())
		} catch (NumberFormatException ignored) {
			return BigDecimal.ZERO
		}
	}

	static long count(Object v) {
		return num(v).longValue()
	}

	/** Sockets of hosts: the known max_sockets plus the default for each host without a value. */
	static BigDecimal hostSockets(Object knownSockets, Object hostsWithoutValue, BigDecimal defaultHostSockets) {
		return num(knownSockets) + num(hostsWithoutValue) * defaultHostSockets
	}

	/** Socket share of VMs in clouds without a discovered host. */
	static BigDecimal vmSockets(long vms, BigDecimal vmsPerSocket) {
		if (vmsPerSocket == null || vmsPerSocket.signum() <= 0) {
			throw new IllegalArgumentException('vmsPerSocket must be positive')
		}
		return new BigDecimal(vms).divide(vmsPerSocket, DIVISION_SCALE, RoundingMode.HALF_UP)
	}

	static BigDecimal round(BigDecimal v) {
		return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, RoundingMode.HALF_UP)
	}

	/** Plain machine-readable value as stored in the report rows, e.g. "1234.567". */
	static String plain(BigDecimal v) {
		return round(v).toPlainString()
	}

	/** Plain value without trailing zeros for option echoes, e.g. "15" or "7.5". */
	static String plainOption(BigDecimal v) {
		BigDecimal s = v.stripTrailingZeros()
		return (s.scale() < 0 ? s.setScale(0) : s).toPlainString()
	}

	/** Locale-aware decimal with exactly {@link #SCALE} fraction digits and grouping. */
	static String formatDecimal(Object v, Locale locale) {
		NumberFormat f = NumberFormat.getNumberInstance(locale ?: Locale.ENGLISH)
		f.setGroupingUsed(true)
		f.setMinimumFractionDigits(SCALE)
		f.setMaximumFractionDigits(SCALE)
		f.setRoundingMode(RoundingMode.HALF_UP)
		return f.format(round(num(v)))
	}

	/** Locale-aware number with grouping and no forced fraction digits, for counts and options. */
	static String formatNumber(Object v, Locale locale) {
		NumberFormat f = NumberFormat.getNumberInstance(locale ?: Locale.ENGLISH)
		f.setGroupingUsed(true)
		f.setMaximumFractionDigits(SCALE)
		f.setRoundingMode(RoundingMode.HALF_UP)
		return f.format(num(v))
	}

	/**
	 * Turns the raw SQL rows into per cloud/tenant rows, per tenant subtotals and a grand total.
	 * Every input row needs: cloud, cloud_type, category, tenant, is_master, hosts,
	 * host_sockets_known, hosts_default, vms_without_host, vms_on_hosts; tenant_id is optional
	 * and keeps two tenants with the same name apart.
	 * Subtotals and the total divide the summed VM count once, so they are not the sum of
	 * rounded row values.
	 */
	static Map summarize(List<Map> rawRows, BigDecimal vmsPerSocket, BigDecimal defaultHostSockets) {
		List<Map> rows = []
		Map<String, Map> tenants = [:]
		Map total = [hosts: 0L, hostSockets: BigDecimal.ZERO, vms: 0L, vmsOnHosts: 0L, hostsDefault: 0L]
		rawRows.each { Map r ->
			long hosts = count(r.hosts)
			long hostsDefault = count(r.hosts_default)
			long vms = count(r.vms_without_host)
			long vmsOnHosts = count(r.vms_on_hosts)
			BigDecimal hs = hostSockets(r.host_sockets_known, hostsDefault, defaultHostSockets)
			BigDecimal vs = vmSockets(vms, vmsPerSocket)
			String tenant = r.tenant?.toString() ?: ''
			boolean master = count(r.is_master) == 1L
			rows << [
				cloud         : r.cloud?.toString() ?: '',
				cloudType     : r.cloud_type?.toString() ?: '',
				category      : r.category?.toString() ?: '',
				tenant        : tenant,
				isMaster      : master.toString(),
				hosts         : hosts.toString(),
				hostSockets   : plain(hs),
				hostsDefault  : hostsDefault.toString(),
				vmsWithoutHost: vms.toString(),
				vmsOnHosts    : vmsOnHosts.toString(),
				vmSockets     : plain(vs),
				sockets       : plain(hs + vs)
			]
			String tenantKey = r.tenant_id != null ? r.tenant_id.toString() : tenant
			Map t = tenants.computeIfAbsent(tenantKey) { [tenant: tenant, master: master, hosts: 0L, hostSockets: BigDecimal.ZERO, vms: 0L] }
			t.hosts += hosts
			t.hostSockets += hs
			t.vms += vms
			total.hosts += hosts
			total.hostSockets += hs
			total.vms += vms
			total.vmsOnHosts += vmsOnHosts
			total.hostsDefault += hostsDefault
		}
		List<Map> tenantRows = tenants.values()
			.sort { a, b -> (b.master <=> a.master) ?: (a.tenant.toLowerCase() <=> b.tenant.toLowerCase()) }
			.collect { Map t ->
				BigDecimal vs = vmSockets(t.vms as long, vmsPerSocket)
				[
					tenant        : t.tenant,
					isMaster      : t.master.toString(),
					hosts         : t.hosts.toString(),
					hostSockets   : plain(t.hostSockets as BigDecimal),
					vmsWithoutHost: t.vms.toString(),
					vmSockets     : plain(vs),
					sockets       : plain((t.hostSockets as BigDecimal) + vs)
				]
			}
		BigDecimal totalVs = vmSockets(total.vms as long, vmsPerSocket)
		Map footer = [
			hosts             : total.hosts.toString(),
			hostsDefault      : total.hostsDefault.toString(),
			hostSockets       : plain(total.hostSockets as BigDecimal),
			vmsWithoutHost    : total.vms.toString(),
			vmsOnHosts        : total.vmsOnHosts.toString(),
			vmSockets         : plain(totalVs),
			sockets           : plain((total.hostSockets as BigDecimal) + totalVs),
			vmsPerSocket      : plainOption(vmsPerSocket),
			defaultHostSockets: plainOption(defaultHostSockets)
		]
		return [rows: rows, tenants: tenantRows, footer: footer]
	}

	/** Field names of a stored row that are shown as decimals with {@link #SCALE} digits. */
	static final List<String> DECIMAL_FIELDS = ['hostSockets', 'vmSockets', 'sockets']
	/** Field names of a stored row that are shown as locale-formatted whole numbers or options. */
	static final List<String> NUMBER_FIELDS = ['hosts', 'hostsDefault', 'vmsWithoutHost', 'vmsOnHosts', 'vmsPerSocket', 'defaultHostSockets']

	/** Copy of a stored row with every number replaced by its locale-formatted text. */
	static Map localize(Map stored, Locale locale) {
		Map out = new LinkedHashMap(stored ?: [:])
		DECIMAL_FIELDS.each { if (out.containsKey(it)) out[it] = formatDecimal(out[it], locale) }
		NUMBER_FIELDS.each { if (out.containsKey(it)) out[it] = formatNumber(out[it], locale) }
		return out
	}
}
