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
package com.morpheusdata.finopsinsights

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import groovy.util.logging.Slf4j

/**
 * Plugin entrypoint for the FinOps Insights dashboard.
 *
 * Registers one dashboard ("FinOps Insights"), the three FinOps dashboard item types and the latest
 * log entries item type it is made of, and the log history page those entries link to.
 * Morpheus lists the dashboard under Administration > Settings > Appliance > Dashboards; once it
 * is enabled there, users pick it from the dashboard selector on Operations > Dashboard.
 *
 * Since 1.2.0 the plugin has four settings (Administration > Integrations > Plugins), one
 * checkbox per widget that hides it for every viewer. The widgets read them on every render.
 */
@Slf4j
class FinopsInsightsPlugin extends Plugin {

	static final String CODE = 'morpheus-finops-insights-plugin'
	static final String WEBSITE_URL = 'https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/finops/finops-insights'

	// Plugin settings: one inverted checkbox per widget ("Hide ..."), so that a missing or unreadable
	// value, which is what an appliance without the setting stores, keeps the widget shown. The keys
	// are the fieldNames of the option types and the keys of the config JSON that getSettings returns.
	static final String SETTING_HIDE_KPI = 'hideKpi'
	static final String SETTING_HIDE_TREND = 'hideTrend'
	static final String SETTING_HIDE_SPLIT = 'hideSplit'
	static final String SETTING_HIDE_LOGS = 'hideLogs'

	// plugin_instance.description holds 255 characters.
	static final String DESCRIPTION ='Dashboard "FinOps Insights": revenue, cost and margin of the current month, a six-month trend, the split by cloud and tenant and the latest log entries with a history page. Providers see all sub-tenants, tenants their own.'

	@Override
	String getCode() {
		return CODE
	}

	@Override
	void initialize() {
		this.setName('FinOps Insights Dashboard')
		this.setDescription(DESCRIPTION)
		this.setAuthor('Thomas Gessendorfer')
		this.setWebsiteUrl(WEBSITE_URL)
		this.setSettings(settingsOptionTypes())
		FinopsInsightsData data = new FinopsInsightsData(morpheus)
		this.registerProvider(new FinopsKpiItemProvider(this, morpheus, data))
		this.registerProvider(new FinopsTrendItemProvider(this, morpheus, data))
		this.registerProvider(new FinopsSplitItemProvider(this, morpheus, data))
		this.registerProvider(new FinopsLogsItemProvider(this, morpheus, data))
		this.registerProvider(new FinopsInsightsDashboardProvider(this, morpheus))
		// The log history page: the appliance serves it at /plugin/finops-insights/logs.
		this.controllers.add(new FinopsLogsController(this, morpheus, data))
	}

	/**
	 * The four "Hide ..." checkboxes. No defaultValue (unticked means shown) and no fieldCode:
	 * a fieldCode replaces the fieldLabel with an i18n lookup, and the labels are English only.
	 */
	static List<OptionType> settingsOptionTypes() {
		[
			setting('hide-kpi', SETTING_HIDE_KPI, 'Hide FinOps at a Glance', 0,
				'Hides the widget "FinOps at a glance" (revenue, cost, margin, tenants, instances) on the dashboard for every user. Takes effect on the next dashboard load.'),
			setting('hide-trend', SETTING_HIDE_TREND, 'Hide Six-Month Trend', 1,
				'Hides the widget "Six-month trend" on the dashboard for every user. Takes effect on the next dashboard load.'),
			setting('hide-split', SETTING_HIDE_SPLIT, 'Hide This Month by Cloud and Tenant', 2,
				'Hides the widget "This month by cloud and tenant" on the dashboard for every user. Takes effect on the next dashboard load.'),
			setting('hide-logs', SETTING_HIDE_LOGS, 'Hide Latest Log Entries', 3,
				'Hides the widget "Latest log entries" and switches its log history page off. The standard Log History widget is shown again. Takes effect on the next dashboard load.')
		]
	}

	private static OptionType setting(String codeSuffix, String fieldName, String label, int order, String help) {
		new OptionType(code: "finops-insights-${codeSuffix}".toString(), name: label, fieldName: fieldName, fieldContext: 'config',
			fieldLabel: label, inputType: OptionType.InputType.CHECKBOX, displayOrder: order, required: false, helpText: help)
	}

	@Override
	void onDestroy() {
		// nothing to clean up
	}
}
