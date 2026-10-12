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
import groovy.util.logging.Slf4j

/**
 * The "FinOps Insights" dashboard: a KPI strip on top, the six-month trend and the split by cloud
 * and tenant below. Every widget is server-rendered HTML; the dashboard script is a no-op.
 */
@Slf4j
class FinopsInsightsDashboardProvider extends AbstractDashboardProvider {

	static final String CODE = 'finops-insights-dashboard'

	// Group name -> item type codes, in display order. Each group is one row of the dashboard.
	static final Map<String, List<String>> LAYOUT = [
		'finops-top' : [FinopsKpiItemProvider.CODE],
		'finops-main': [FinopsTrendItemProvider.CODE, FinopsSplitItemProvider.CODE]
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
	@Override String getName() { 'FinOps Insights' }

	@Override
	Dashboard getDashboard() {
		Dashboard rtn = new Dashboard()
		rtn.name = getName()
		rtn.code = getCode()
		rtn.dashboardId = getCode()
		rtn.category = 'cost'
		rtn.title = 'FinOps Insights'
		rtn.description = 'Revenue, cost and margin, six-month trend, split by cloud and tenant'
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
				if(provider instanceof com.morpheusdata.core.dashboard.DashboardItemTypeProvider) {
					DashboardItem item = new DashboardItem()
					item.type = provider.dashboardItemType
					item.itemGroup = group
					item.groupRow = groupRow
					item.itemRow = 0
					item.itemColumn = column
					items << item
					column++
				} else {
					log.warn("FinOps insights: dashboard item type ${code} is not registered")
				}
			}
			groupRow++
		}
		return items
	}
}
