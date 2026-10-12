package com.morpheusdata.finopsinsights

import spock.lang.Specification

/**
 * Guards the identity of the plugin: the code Morpheus persists, the repo link, the registered
 * providers, the dashboard layout and the message bundles.
 */
class FinopsInsightsPluginSpec extends Specification {

	static final Set<String> PROVIDER_CODES = [FinopsKpiItemProvider.CODE, FinopsTrendItemProvider.CODE,
		FinopsSplitItemProvider.CODE, FinopsInsightsDashboardProvider.CODE] as Set

	FinopsInsightsPlugin plugin = new FinopsInsightsPlugin()

	def setup() {
		plugin.initialize()
	}

	def "getCode() matches the manifest Morpheus-Code"() {
		expect:
		System.getProperty('morpheus.code')
		plugin.code == System.getProperty('morpheus.code')
		plugin.code == 'morpheus-finops-insights-plugin'
	}

	def "website URL matches the manifest Morpheus-Repo and points to finops/finops-insights"() {
		expect:
		plugin.websiteUrl == System.getProperty('morpheus.repo')
		plugin.websiteUrl.endsWith('/tree/main/finops/finops-insights')
	}

	def "description fits the 255 characters of plugin_instance.description"() {
		expect:
		plugin.description
		plugin.description.length() <= 255
	}

	def "the plugin registers exactly the expected providers"() {
		expect:
		plugin.providers*.code as Set == PROVIDER_CODES
	}

	def "the dashboard lists every widget once, KPIs on top"() {
		given:
		FinopsInsightsDashboardProvider dash = plugin.getProviderByCode(FinopsInsightsDashboardProvider.CODE) as FinopsInsightsDashboardProvider

		when:
		def items = dash.buildItems()

		then:
		items*.type*.code == [FinopsKpiItemProvider.CODE, FinopsTrendItemProvider.CODE, FinopsSplitItemProvider.CODE]
		items*.itemGroup == ['finops-top', 'finops-main', 'finops-main']
		items*.groupRow == [0, 1, 1]
		items*.itemColumn == [0, 0, 1]
	}

	def "widgets declare the Costing permission with read and full and the no-op script"() {
		given:
		def kpi = plugin.getProviderByCode(FinopsKpiItemProvider.CODE) as FinopsKpiItemProvider

		when:
		def type = kpi.dashboardItemType

		then:
		type.code == FinopsKpiItemProvider.CODE
		type.accessTypes == ['read', 'full']
		type.scriptPath == 'finops-insights.js'
		type.templatePath == 'hbs/finops-kpi-widget'
		type.uiSize == 'xl widget-short'
		new File('src/assets/javascripts/' + AbstractFinopsItemProvider.SCRIPT_PATH).isFile()
	}

	def "English and German message bundles have the same keys and every template label exists"() {
		given:
		Properties en = bundle('i18n/messages.properties')
		Properties de = bundle('i18n/messages_de.properties')

		expect:
		!en.isEmpty()
		en.stringPropertyNames() == de.stringPropertyNames()
		AbstractFinopsItemProvider.labels(Locale.GERMAN).keySet().containsAll(['kpi_title', 'trend_foot', 'split_byCloud', 'split_byTenant', 'split_empty', 'note_otherCurrencies', 'hint_noViewer', 'hint_loadFailed'])
		AbstractFinopsItemProvider.message('kpi.margin', Locale.GERMAN) == 'Marge'
		AbstractFinopsItemProvider.message('kpi.margin', Locale.FRENCH) == 'Margin'
	}

	def "the viewer id is read from the request options"() {
		expect:
		FinopsInsightsData.userIdFrom(opts) == expected

		where:
		opts                                   | expected
		null                                   | null
		[:]                                    | null
		[userId: '7']                          | 7L
		[user: [id: 3]]                        | 3L
		[currentUser: [id: '12'], userId: '1'] | 12L
		[userId: 'abc']                        | null
	}

	private static Properties bundle(String path) {
		Properties p = new Properties()
		InputStream is = FinopsInsightsPluginSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		return p
	}
}
