package com.morpheusdata.budgetburn

import spock.lang.Specification

/**
 * Guards the identity of the plugin: the code Morpheus persists, the repo link,
 * the registered providers and the message bundles.
 */
class BudgetBurnPluginSpec extends Specification {

	// The provider codes from the migration plan, section 4. Exactly one per plugin.
	static final List<String> PROVIDER_CODES = ['budget-burn-analytics']

	BudgetBurnPlugin plugin = new BudgetBurnPlugin()

	def setup() {
		plugin.initialize()
	}

	def "getCode() matches the manifest Morpheus-Code"() {
		expect:
		System.getProperty('morpheus.code')
		plugin.code == System.getProperty('morpheus.code')
		plugin.code == 'morpheus-budget-burn-plugin'
	}

	def "website URL matches the manifest Morpheus-Repo and points to finops/budget-burn"() {
		expect:
		plugin.websiteUrl == System.getProperty('morpheus.repo')
		plugin.websiteUrl.endsWith('/tree/main/finops/budget-burn')
	}

	def "description fits the 255 characters of plugin_instance.description"() {
		expect:
		plugin.description
		plugin.description.length() <= 255
	}

	def "the plugin registers exactly the expected providers"() {
		expect:
		plugin.providers.size() == PROVIDER_CODES.size()
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

	def "message bundles are pure ASCII, non-ASCII characters only as \\uXXXX escapes"() {
		expect:
		['i18n/messages.properties', 'i18n/messages_de.properties'].every { path ->
			BudgetBurnPluginSpec.classLoader.getResourceAsStream(path).bytes.every { it >= 0 }
		}
	}

	private static Properties bundle(String path) {
		Properties p = new Properties()
		InputStream is = BudgetBurnPluginSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		return p
	}
}
