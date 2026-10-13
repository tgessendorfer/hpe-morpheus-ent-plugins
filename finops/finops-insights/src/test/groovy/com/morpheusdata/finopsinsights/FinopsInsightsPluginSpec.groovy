package com.morpheusdata.finopsinsights

import com.morpheusdata.model.OptionType
import spock.lang.Specification

/**
 * Guards the identity of the plugin: the code Morpheus persists, the repo link, the registered
 * providers, the dashboard layout, the widget settings and the message bundles.
 */
class FinopsInsightsPluginSpec extends Specification {

	static final Set<String> PROVIDER_CODES = [FinopsKpiItemProvider.CODE, FinopsTrendItemProvider.CODE,
		FinopsSplitItemProvider.CODE, FinopsLogsItemProvider.CODE, FinopsInsightsDashboardProvider.CODE] as Set

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

	def "the plugin serves the log history page through one controller route"() {
		expect:
		plugin.controllers.size() == 1
		plugin.controllers[0] instanceof FinopsLogsController
		plugin.controllers[0].routes*.url == ['/finops-insights/logs']
		plugin.controllers[0].routes*.method == ['logsPage']
		plugin.controllers[0].routes[0].permissions*.code == ['logs']
		// the widget's link and the route are the same page
		FinopsLogsSupport.PAGE_PATH == '/plugin' + FinopsLogsController.ROUTE_URL
	}

	def "the dashboard lists every widget once, the FinOps KPIs above trend and split"() {
		given:
		FinopsInsightsDashboardProvider dash = plugin.getProviderByCode(FinopsInsightsDashboardProvider.CODE) as FinopsInsightsDashboardProvider

		when:
		def items = dash.buildItems()
		// Since 1.1.0 the standard home widgets are part of the layout; pick the FinOps ones.
		def finops = items.findAll { it.type.code.startsWith('finops-insights-') }
		def logs = items.findAll { it.itemGroup == 'logs' }

		then:
		items.size() == 15
		(items*.type*.code as Set).size() == items.size()
		finops*.type*.code == [FinopsKpiItemProvider.CODE, FinopsTrendItemProvider.CODE, FinopsSplitItemProvider.CODE,
			FinopsLogsItemProvider.CODE]
		finops*.itemGroup == ['finops-top', 'finops-main', 'finops-main', 'logs']
		finops*.groupRow == [3, 4, 4, 7]
		finops*.itemColumn == [0, 0, 1, 0]
		// 1.2.0: the latest log entries come first in the row, the standard Log History widget second.
		logs*.type*.code == [FinopsLogsItemProvider.CODE, 'dashboard-item-log-count']
		logs*.itemColumn == [0, 1]
		items[-1].type.code == 'dashboard-item-log-count'
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
		AbstractFinopsItemProvider.labels(Locale.GERMAN).keySet().containsAll(['kpi_title', 'trend_foot', 'split_byCloud', 'split_byTenant', 'split_empty', 'note_otherCurrencies', 'hint_noViewer', 'hint_loadFailed', 'hint_hidden',
			'logs_title', 'logs_history', 'logs_loading', 'logs_empty', 'logs_error', 'logs_noSources', 'logs_page_title',
			'logs_back', 'logs_standard', 'logs_window_1h', 'logs_window_24h', 'logs_window_7d', 'logs_window_30d',
			'logs_level', 'logs_source', 'logs_allSources', 'logs_text', 'logs_collapse', 'logs_more', 'logs_retention'])
		AbstractFinopsItemProvider.message('kpi.margin', Locale.GERMAN) == 'Marge'
		AbstractFinopsItemProvider.message('kpi.margin', Locale.FRENCH) == 'Margin'
	}

	def "the plugin offers one unticked Hide checkbox per widget and each widget reads its own"() {
		given:
		List<OptionType> settings = plugin.settings
		List<AbstractFinopsItemProvider> widgets = [FinopsKpiItemProvider.CODE, FinopsTrendItemProvider.CODE,
			FinopsSplitItemProvider.CODE, FinopsLogsItemProvider.CODE]
			.collect { plugin.getProviderByCode(it) as AbstractFinopsItemProvider }

		expect:
		settings*.code == ['finops-insights-hide-kpi', 'finops-insights-hide-trend', 'finops-insights-hide-split',
			'finops-insights-hide-logs']
		settings*.fieldName == ['hideKpi', 'hideTrend', 'hideSplit', 'hideLogs']
		settings*.fieldLabel[3] == 'Hide Latest Log Entries'
		settings*.displayOrder == [0, 1, 2, 3]
		settings.every { it.inputType == OptionType.InputType.CHECKBOX && it.fieldContext == 'config' && !it.required }
		// A fieldCode would replace the label with an i18n lookup; an unticked box needs no default.
		settings.every { it.fieldCode == null && it.defaultValue == null }
		settings.every { it.fieldLabel.startsWith('Hide ') && it.name == it.fieldLabel && it.helpText }
		widgets*.settingKey == settings*.fieldName
	}

	def "the switched-off template hides the widget container and keeps a one-line fallback"() {
		given:
		File template = new File('src/main/resources/renderer/hbs/finops-hidden-widget.hbs')

		expect:
		AbstractFinopsItemProvider.HIDDEN_TEMPLATE_PATH == 'hbs/finops-hidden-widget'
		template.isFile()
		template.text.contains('.widget-container:has(.fi-off) { display: none; }')
		template.text.contains('{{title}}')
		template.text.contains('{{hint}}')
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
