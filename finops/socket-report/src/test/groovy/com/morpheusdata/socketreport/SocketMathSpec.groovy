package com.morpheusdata.socketreport

import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

/**
 * Socket arithmetic: option parsing, ratio division, host defaults, rounding, aggregation
 * and locale-aware formatting.
 */
class SocketMathSpec extends Specification {

	@Unroll
	def "option '#raw' parses to #expected"() {
		expect:
		SocketMath.parsePositive(raw) == expected
		SocketMath.isValidOption(raw) == valid

		where:
		raw     | expected | valid
		null    | null     | true
		''      | null     | true
		'  '    | null     | true
		'15'    | 15G      | true
		' 7.5 ' | 7.5G     | true
		10      | 10G      | true
		'0'     | null     | false
		'-3'    | null     | false
		'abc'   | null     | false
		'1,5'   | null     | false
	}

	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@Unroll
	def "option '#raw' is bounded: valid #valid, out of range #outOfRange"() {
		expect:
		SocketMath.isValidOption(raw) == valid
		SocketMath.isOutOfRange(raw) == outOfRange
		(SocketMath.parsePositive(raw) != null) == valid

		where:
		raw                    | valid | outOfRange
		'1000000'              | true  | false
		'1000000.000'          | true  | false
		'0.001'                | true  | false
		'7.500000'             | true  | false
		'1000000.001'          | false | true
		'2000000'              | false | true
		'0.0001'               | false | true
		'1e2'                  | false | true
		'1E2'                  | false | true
		'1e2000000000'         | false | true
		'1e-2000000000'        | false | true
		'9' * 40               | false | true
		'0e5'                  | false | false
		'-1e2'                 | false | false
		'abc'                  | false | false
		1000000G               | true  | false
		new BigDecimal('1E+3') | true  | false
		new BigDecimal('1E+2000000000') | false | true
	}

	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@Unroll
	def "a long option text of #length characters is classified in linear time: out of range #outOfRange"() {
		expect:
		SocketMath.isValidOption(raw) == false
		SocketMath.isOutOfRange(raw) == outOfRange

		where:
		raw                              | outOfRange
		('1' * 30000) + 'x'              | false
		'1.' + ('1' * 30000) + 'x'       | false
		('1' * 30000) + 'e' + ('1' * 30000) + 'x' | false
		('1' * 30000) + 'e5'             | true
		('0' * 30000) + 'e5'             | false
		('9' * 100000)                   | true
		('1' * 100000) + 'x'             | false

		length = raw.length()
	}

	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	def "an exponent option of 1e2000000000 falls back to the default without expanding it"() {
		expect:
		SocketMath.positiveOr('1e2000000000', SocketMath.DEFAULT_VMS_PER_SOCKET) == 15G
		SocketMath.positiveOr(new BigDecimal('1E+2000000000'), SocketMath.DEFAULT_HOST_SOCKETS) == 2G
		SocketMath.summarize([], SocketMath.positiveOr('1e2000000000', SocketMath.DEFAULT_VMS_PER_SOCKET), 2G).footer.vmsPerSocket == '15'
	}

	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	def "plainOption refuses a value outside the option bounds instead of expanding it"() {
		when:
		SocketMath.plainOption(new BigDecimal('1E+2000000000'))

		then:
		thrown(IllegalArgumentException)

		when:
		String upper = SocketMath.plainOption(1000000G)
		String fraction = SocketMath.plainOption(new BigDecimal('7.500'))

		then:
		upper == '1000000'
		fraction == '7.5'
	}

	def "an invalid or empty option falls back to the default"() {
		expect:
		SocketMath.positiveOr(null, SocketMath.DEFAULT_VMS_PER_SOCKET) == 15G
		SocketMath.positiveOr('0', SocketMath.DEFAULT_VMS_PER_SOCKET) == 15G
		SocketMath.positiveOr('x', SocketMath.DEFAULT_HOST_SOCKETS) == 2G
		SocketMath.positiveOr('20', SocketMath.DEFAULT_VMS_PER_SOCKET) == 20G
	}

	def "an option is read from the top level or from the nested config map"() {
		expect:
		SocketMath.option([vmsPerSocket: '10'], 'vmsPerSocket') == '10'
		SocketMath.option([config: [vmsPerSocket: '12']], 'vmsPerSocket') == '12'
		SocketMath.option([vmsPerSocket: '', config: [vmsPerSocket: '12']], 'vmsPerSocket') == '12'
		SocketMath.option(null, 'vmsPerSocket') == null
		SocketMath.option('not a map', 'vmsPerSocket') == null
	}

	def "a missing option on a map whose get() throws, like the API's JSONObject, yields null"() {
		given:
		Map strict = new LinkedHashMap() {
			@Override
			Object get(Object key) {
				if (!containsKey(key)) throw new IllegalStateException("${key} not found")
				return super.get(key)
			}
		}
		strict.put('vmsPerSocket', '10')

		expect:
		SocketMath.option(strict, 'vmsPerSocket') == '10'
		SocketMath.option(strict, 'defaultHostSockets') == null
	}

	@Unroll
	def "#vms VMs at #ratio per socket give #expected sockets after rounding"() {
		expect:
		SocketMath.plain(SocketMath.vmSockets(vms, ratio)) == expected

		where:
		vms | ratio | expected
		0   | 15G   | '0.000'
		15  | 15G   | '1.000'
		1   | 15G   | '0.067'
		7   | 15G   | '0.467'
		30  | 15G   | '2.000'
		1   | 3G    | '0.333'
		2   | 3G    | '0.667'
		10  | 7.5G  | '1.333'
	}

	def "a ratio of zero or below is rejected"() {
		when:
		SocketMath.vmSockets(3, 0G)

		then:
		thrown(IllegalArgumentException)
	}

	def "hosts without a socket count use the default"() {
		expect:
		SocketMath.hostSockets(8, 0, 2G) == 8G
		SocketMath.hostSockets(8, 3, 2G) == 14G
		SocketMath.hostSockets(null, 2, 4G) == 8G
		SocketMath.hostSockets('6', '1', 1G) == 7G
	}

	def "rounding is half up to three decimals"() {
		expect:
		SocketMath.plain(0.0005G) == '0.001'
		SocketMath.plain(0.0004999G) == '0.000'
		SocketMath.plain(1.2345G) == '1.235'
		SocketMath.plain(null) == '0.000'
	}

	def "summarize builds rows, tenant subtotals master first and a grand total"() {
		given:
		List<Map> raw = [
			row('Cloud A', 'Tenant B', 0, 20, hosts: 2, host_sockets_known: 4, hosts_default: 0, vms_without_host: 0, vms_on_hosts: 10),
			row('Cloud A', 'Provider', 1, 1, hosts: 1, host_sockets_known: 0, hosts_default: 1, vms_without_host: 0, vms_on_hosts: 3),
			row('Cloud B', 'Tenant B', 0, 20, hosts: 0, host_sockets_known: 0, hosts_default: 0, vms_without_host: 1, vms_on_hosts: 0),
			row('Cloud C', 'Tenant B', 0, 20, hosts: 0, host_sockets_known: 0, hosts_default: 0, vms_without_host: 1, vms_on_hosts: 0),
			row('Cloud B', 'Provider', 1, 1, hosts: 0, host_sockets_known: 0, hosts_default: 0, vms_without_host: 14, vms_on_hosts: 0)
		]

		when:
		Map s = SocketMath.summarize(raw, 15G, 2G)

		then: 'one row per cloud and tenant, values as plain strings'
		s.rows.size() == 5
		s.rows[0].hostSockets == '4.000'
		s.rows[1].hostSockets == '2.000'
		s.rows[1].hostsDefault == '1'
		s.rows[2].vmSockets == '0.067'
		s.rows[4].sockets == '0.933'

		and: 'tenant subtotals, master tenant first, divided once from the summed VM count'
		s.tenants*.tenant == ['Provider', 'Tenant B']
		s.tenants[0].sockets == '2.933'
		s.tenants[1].vmsWithoutHost == '2'
		s.tenants[1].vmSockets == '0.133'
		s.tenants[1].sockets == '4.133'

		and: 'grand total: 6 host sockets plus 16 VMs / 15'
		s.footer.hosts == '3'
		s.footer.hostsDefault == '1'
		s.footer.hostSockets == '6.000'
		s.footer.vmsWithoutHost == '16'
		s.footer.vmsOnHosts == '13'
		s.footer.vmSockets == '1.067'
		s.footer.sockets == '7.067'
		s.footer.vmsPerSocket == '15'
		s.footer.defaultHostSockets == '2'
	}

	def "tenants with the same name stay apart when their ids differ"() {
		given:
		List<Map> raw = [
			row('Cloud A', 'Same', 0, 5, hosts: 1, host_sockets_known: 2, hosts_default: 0, vms_without_host: 0, vms_on_hosts: 0),
			row('Cloud A', 'Same', 0, 6, hosts: 1, host_sockets_known: 4, hosts_default: 0, vms_without_host: 0, vms_on_hosts: 0)
		]

		expect:
		SocketMath.summarize(raw, 15G, 2G).tenants*.hostSockets == ['2.000', '4.000']
	}

	def "an empty result gives zero totals"() {
		when:
		Map s = SocketMath.summarize([], 15G, 2G)

		then:
		s.rows.isEmpty()
		s.tenants.isEmpty()
		s.footer.sockets == '0.000'
		s.footer.hosts == '0'
	}

	@Unroll
	def "#value formats as '#text' in #locale"() {
		expect:
		SocketMath.formatDecimal(value, locale) == text

		where:
		value      | locale         | text
		'1234.567' | Locale.ENGLISH | '1,234.567'
		'1234.567' | Locale.GERMAN  | '1.234,567'
		'2'        | Locale.ENGLISH | '2.000'
		'0.0005'   | Locale.GERMAN  | '0,001'
		'1234.5'   | null           | '1,234.500'
	}

	def "counts and options format without forced decimals"() {
		expect:
		SocketMath.formatNumber('12345', Locale.ENGLISH) == '12,345'
		SocketMath.formatNumber('12345', Locale.GERMAN) == '12.345'
		SocketMath.formatNumber('7.5', Locale.GERMAN) == '7,5'
	}

	def "localize formats only the number fields and keeps names"() {
		when:
		Map out = SocketMath.localize([tenant: 'Tenant 1000', hosts: '1200', sockets: '1500.5', vmsPerSocket: '15'], Locale.GERMAN)

		then:
		out.tenant == 'Tenant 1000'
		out.hosts == '1.200'
		out.sockets == '1.500,500'
		out.vmsPerSocket == '15'
	}

	private static Map row(Map counts, String cloud, String tenant, int master, long tenantId) {
		[cloud: cloud, cloud_type: 'Type', category: 'cat', tenant: tenant, tenant_id: tenantId, is_master: master] + counts
	}
}
