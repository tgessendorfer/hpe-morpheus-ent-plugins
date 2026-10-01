package com.morpheusdata.instanceshowback

import spock.lang.Specification

/**
 * Guards the identity of the plugin: the code Morpheus persists, the repo link,
 * the registered providers and the message bundles.
 */
class InstanceShowbackPluginSpec extends Specification {

	// The provider codes from the migration plan, section 4. Exactly one per plugin.
	static final List<String> PROVIDER_CODES = ['instance-showback-tab']

	InstanceShowbackPlugin plugin = new InstanceShowbackPlugin()

	def setup() {
		plugin.initialize()
	}

	def "getCode() matches the manifest Morpheus-Code"() {
		expect:
		System.getProperty('morpheus.code')
		plugin.code == System.getProperty('morpheus.code')
		plugin.code == 'morpheus-instance-showback-plugin'
	}

	def "website URL matches the manifest Morpheus-Repo and points to finops/instance-showback"() {
		expect:
		plugin.websiteUrl == System.getProperty('morpheus.repo')
		plugin.websiteUrl.endsWith('/tree/main/finops/instance-showback')
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
		InputStream is = InstanceShowbackPluginSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		return p
	}
}
