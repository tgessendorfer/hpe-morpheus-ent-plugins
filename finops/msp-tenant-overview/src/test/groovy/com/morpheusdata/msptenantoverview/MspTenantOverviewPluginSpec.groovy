package com.morpheusdata.msptenantoverview

import spock.lang.Specification

/**
 * Guards the identity of the plugin: the code Morpheus persists, the repo link,
 * the registered providers and the message bundles.
 */
class MspTenantOverviewPluginSpec extends Specification {

	// The provider codes from the migration plan, section 4. Exactly one per plugin.
	static final List<String> PROVIDER_CODES = ['msp-tenant-overview-analytics']

	MspTenantOverviewPlugin plugin = new MspTenantOverviewPlugin()

	def setup() {
		plugin.initialize()
	}

	def "getCode() matches the manifest Morpheus-Code"() {
		expect:
		System.getProperty('morpheus.code')
		plugin.code == System.getProperty('morpheus.code')
		plugin.code == 'morpheus-msp-tenant-overview-plugin'
	}

	def "website URL matches the manifest Morpheus-Repo and points to finops/msp-tenant-overview"() {
		expect:
		plugin.websiteUrl == System.getProperty('morpheus.repo')
		plugin.websiteUrl.endsWith('/tree/main/finops/msp-tenant-overview')
	}

	def "description fits the 255 characters of plugin_instance.description"() {
		expect:
		plugin.description
		plugin.description.length() <= 255
	}

	def "the plugin registers exactly the expected providers"() {
		expect:
		plugin.providers*.code as Set == PROVIDER_CODES as Set
	}

	def "English and German message bundles have the same keys"() {
		given:
		Properties en = bundle('i18n/messages.properties')
		Properties de = bundle('i18n/messages_de.properties')

		expect:
		!en.isEmpty()
		en.stringPropertyNames() == de.stringPropertyNames()
	}

	def "message bundles are pure ASCII and the German one decodes to real umlauts"() {
		given: 'Properties files are read as ISO-8859-1, so raw UTF-8 would show up garbled'
		byte[] en = raw('i18n/messages.properties')
		byte[] de = raw('i18n/messages_de.properties')

		expect:
		en.every { it >= 0 }
		de.every { it >= 0 }
		bundle('i18n/messages_de.properties').getProperty('msp-tenant-overview-analytics.title') == 'MSP-Tenant-Übersicht'
	}

	private static byte[] raw(String path) {
		InputStream is = MspTenantOverviewPluginSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { it.bytes }
	}

	private static Properties bundle(String path) {
		Properties p = new Properties()
		InputStream is = MspTenantOverviewPluginSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		return p
	}
}
