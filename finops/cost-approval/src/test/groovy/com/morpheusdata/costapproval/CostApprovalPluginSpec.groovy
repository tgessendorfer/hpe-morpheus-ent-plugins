package com.morpheusdata.costapproval

import spock.lang.Specification

/**
 * Guards the identity of the plugin: the code Morpheus persists, the repo link,
 * the registered providers and the message bundles.
 */
class CostApprovalPluginSpec extends Specification {

	// The provider codes from the migration plan, section 4. Exactly one per plugin.
	static final List<String> PROVIDER_CODES = ['cost-threshold-approval']

	CostApprovalPlugin plugin = new CostApprovalPlugin()

	def setup() {
		plugin.initialize()
	}

	def "getCode() matches the manifest Morpheus-Code"() {
		expect:
		System.getProperty('morpheus.code')
		plugin.code == System.getProperty('morpheus.code')
		plugin.code == 'morpheus-cost-approval-plugin'
	}

	def "website URL matches the manifest Morpheus-Repo and points to finops/cost-approval"() {
		expect:
		plugin.websiteUrl == System.getProperty('morpheus.repo')
		plugin.websiteUrl.endsWith('/tree/main/finops/cost-approval')
	}

	def "description fits the 255 characters of plugin_instance.description"() {
		expect:
		plugin.description
		plugin.description.length() <= 255
	}

	def "description equals the manifest Morpheus-Description and names the rejection"() {
		expect:
		System.getProperty('morpheus.description')
		plugin.description == System.getProperty('morpheus.description')
		plugin.description.contains('rejected')
		!plugin.description.contains('human')
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
		InputStream is = CostApprovalPluginSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		return p
	}
}
