package com.morpheusdata.tenantchargeback

import spock.lang.Specification

/**
 * Guards the identity of the plugin: the code Morpheus persists, the repo link,
 * the registered providers and the message bundles.
 */
class TenantChargebackPluginSpec extends Specification {

	// The provider codes from the migration plan, section 4. Exactly one per plugin.
	static final List<String> PROVIDER_CODES = ['tenant-chargeback-report']

	TenantChargebackPlugin plugin = new TenantChargebackPlugin()

	def setup() {
		plugin.initialize()
	}

	def "getCode() matches the manifest Morpheus-Code"() {
		expect:
		System.getProperty('morpheus.code')
		plugin.code == System.getProperty('morpheus.code')
		plugin.code == 'morpheus-tenant-chargeback-plugin'
	}

	def "website URL matches the manifest Morpheus-Repo and points to finops/tenant-chargeback"() {
		expect:
		plugin.websiteUrl == System.getProperty('morpheus.repo')
		plugin.websiteUrl.endsWith('/tree/main/finops/tenant-chargeback')
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

	private static Properties bundle(String path) {
		Properties p = new Properties()
		InputStream is = TenantChargebackPluginSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		return p
	}
}
