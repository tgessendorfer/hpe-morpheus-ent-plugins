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

	/** Largest value an option accepts; far above any real ratio or socket count. */
	static final BigDecimal MAX_OPTION = new BigDecimal(1000000)
	/** Most fraction digits an option accepts. */
	static final int MAX_OPTION_FRACTION_DIGITS = SCALE
	/** Longest option text that is parsed at all; "1000000.000" has 11 characters. */
	static final int MAX_OPTION_LENGTH = 32

	/** Parses any number in plain notation; null, blank, too long, non-numeric or exponent input yields null. */
	static BigDecimal parsePlain(Object raw) {
		if (raw == null) {
			return null
		}
		if (raw instanceof BigDecimal) {
			// Checked by digit counts, so a huge exponent is never expanded to its plain form.
			BigDecimal d = (BigDecimal) raw
			return d.precision() - d.scale() <= MAX_OPTION_LENGTH && d.scale() <= MAX_OPTION_LENGTH ? d : null
		}
		String s = raw.toString().trim()
		// Exponent notation is rejected before parsing: 1e2000000000 parses cheaply, but any
		// later toPlainString() or setScale() on it would build a two-billion-digit string.
		if (s.isEmpty() || s.length() > MAX_OPTION_LENGTH || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
			return null
		}
		try {
			return new BigDecimal(s)
		} catch (NumberFormatException ignored) {
			return null
		}
	}

	/**
	 * Parses a positive option value; null, blank, non-numeric, zero, negative, exponent
	 * notation, more than {@link #MAX_OPTION_FRACTION_DIGITS} fraction digits or more than
	 * {@link #MAX_OPTION} yields null.
	 */
	static BigDecimal parsePositive(Object raw) {
		BigDecimal v = parsePlain(raw)
		if (v == null || v.signum() <= 0 || v.compareTo(MAX_OPTION) > 0) {
			return null
		}
		return v.stripTrailingZeros().scale() > MAX_OPTION_FRACTION_DIGITS ? null : v
	}

	/** True when the raw option is empty (default applies) or a positive number within the bounds. */
	static boolean isValidOption(Object raw) {
		return raw == null || raw.toString().trim().isEmpty() || parsePositive(raw) != null
	}

	/** True when the raw option is a positive plain number that only fails the bounds of {@link #parsePositive}. */
	static boolean isOutOfRange(Object raw) {
		if (raw == null || raw.toString().trim().isEmpty() || parsePositive(raw) != null) {
			return false
		}
		BigDecimal v = parsePlain(raw)
		if (v != null) {
			return v.signum() > 0
		}
		// Exponent notation or an over-long number: matched as text, never parsed, so the
		// check stays cheap whatever the input. Positive when the mantissa has a digit other than 0.
		java.util.regex.Matcher m = POSITIVE_NUMBER_TEXT.matcher(raw.toString().trim())
		return m.matches() && NONZERO_DIGIT.matcher(m.group(1)).find()
	}

	/**
	 * A positive number as text, plain or with an exponent; group 1 is the mantissa. Possessive
	 * quantifiers never give back digits, so a non-matching text fails in linear time.
	 */
	private static final java.util.regex.Pattern POSITIVE_NUMBER_TEXT = ~/^\+?(\d++(?:\.\d*+)?|\.\d++)(?:[eE][+-]?\d++)?$/
	private static final java.util.regex.Pattern NONZERO_DIGIT = ~/[1-9]/

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

	/**
	 * Plain value without trailing zeros for option echoes, e.g. "15" or "7.5".
	 * Only for values that passed {@link #parsePositive}; anything else is refused instead of
	 * expanded, since the plain form of an unbounded exponent has billions of digits.
	 */
	static String plainOption(BigDecimal v) {
		if (v == null || v.abs().compareTo(MAX_OPTION) > 0 || v.stripTrailingZeros().scale() > MAX_OPTION_FRACTION_DIGITS) {
			throw new IllegalArgumentException('option value out of range')
		}
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
