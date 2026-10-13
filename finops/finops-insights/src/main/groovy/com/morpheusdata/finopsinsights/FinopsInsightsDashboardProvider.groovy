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

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.dashboard.AbstractDashboardProvider
import com.morpheusdata.model.Dashboard
import com.morpheusdata.model.DashboardItem
import com.morpheusdata.model.DashboardItemType
import groovy.util.logging.Slf4j

/**
 * The "FinOps Insights" dashboard: a KPI strip on top, the six-month trend and the split by cloud
 * and tenant below, and the latest log entries at the bottom. The FinOps widgets are server-rendered
 * HTML and the dashboard script is a no-op; the log entries widget draws its list in the browser.
 */
@Slf4j
class FinopsInsightsDashboardProvider extends AbstractDashboardProvider {

	static final String CODE = 'finops-insights-dashboard'

	// Group name -> item type codes, in display order. Each group is one row of the dashboard.
	// Owner layout 2026-10-12: the standard home widgets (Morpheus item types, referenced by code)
	// interleaved with the FinOps widgets, so this dashboard replaces the default home dashboard
	// (Administration > Settings > Appliance > Dashboards to Display = only this one).
	static final Map<String, List<String>> LAYOUT = [
		'top'        : ['dashboard-item-environment-count', 'dashboard-item-current-health'],
		'instances'  : ['dashboard-item-instance-count', 'dashboard-item-instance-count-cloud'],
		'jobs'       : ['dashboard-item-job-execution-stats', 'dashboard-item-backup-stats',
		                'dashboard-item-task-execution-stats'],
		'finops-top' : [FinopsKpiItemProvider.CODE],
		'finops-main': [FinopsTrendItemProvider.CODE, FinopsSplitItemProvider.CODE],
		'main'       : ['dashboard-item-user-favorites', 'dashboard-item-current-alarms'],
		'activity'   : ['dashboard-item-recent-activity'],
		// Latest log entries first: while it is shown it hides the standard Log History widget next to
		// it with a CSS rule; with the plugin setting "Hide Latest Log Entries" ticked it renders a
		// hidden box and the standard widget is the one that shows.
		'logs'       : [FinopsLogsItemProvider.CODE, 'dashboard-item-log-count']
	]
	Plugin plugin
	MorpheusContext morpheus

	FinopsInsightsDashboardProvider(Plugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override MorpheusContext getMorpheus() { morpheus }
	@Override Plugin getPlugin() { plugin }
	@Override String getCode() { CODE }
	@Override String getName() { 'Nordwind Operations' }

	@Override
	Dashboard getDashboard() {
		Dashboard rtn = new Dashboard()
		rtn.name = getName()
		rtn.code = getCode()
		rtn.dashboardId = getCode()
		rtn.category = 'cost'
		rtn.title = 'Nordwind Operations'
		rtn.description = 'Home dashboard with the FinOps widgets: environment, instances, jobs, revenue, cost, margin, trend, split, favourites, alarms, activity, latest log entries'
		// Measured on 9.0.2: the dashboard controller answers {"success":false} for a dashboard whose
		// default_view flag is 0; the three standard dashboards all carry true, so this one does too.
		rtn.defaultDashboard = true
		rtn.enabled = true
		rtn.sourceType = 'plugin'
		rtn.templatePath = 'hbs/finops-dashboard'
		rtn.scriptPath = AbstractFinopsItemProvider.SCRIPT_PATH
		rtn.dashboardItems = buildItems()
		return rtn
	}

	/** One DashboardItem per layout entry; the type is looked up from the sibling providers. */
	List<DashboardItem> buildItems() {
		List<DashboardItem> items = []
		int groupRow = 0
		LAYOUT.each { String group, List<String> codes ->
			int column = 0
			codes.each { String code ->
				def provider = plugin.getProviderByCode(code)
				DashboardItem item = new DashboardItem()
				if(provider instanceof com.morpheusdata.core.dashboard.DashboardItemTypeProvider) {
					item.type = provider.dashboardItemType
					// The log list is the one item that shows other people's data, and an item type without
					// its permission is shown to everyone: when the Logs permission could not be loaded the
					// item is left out and the standard Log History widget in the row stays.
					if(provider instanceof FinopsLogsItemProvider && item.type?.permission == null) {
						log.error("FinOps insights: the Logs permission could not be loaded, the latest log entries widget is left out of the dashboard")
						return
					}
				} else {
					// Standard Morpheus widget: referenced by code only, the appliance resolves the type.
					item.type = new DashboardItemType(code: code)
				}
				item.itemGroup = group
				item.groupRow = groupRow
				item.itemRow = 0
				item.itemColumn = column
				items << item
				column++
			}
			groupRow++
		}
		return items
	}
}
