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
import groovy.util.logging.Slf4j

/**
 * Plugin entrypoint for the FinOps Insights dashboard.
 *
 * Registers one dashboard ("FinOps Insights") and the three dashboard item types it is made of.
 * Morpheus lists the dashboard under Administration > Settings > Appliance > Dashboards; once it
 * is enabled there, users pick it from the dashboard selector on Operations > Dashboard.
 */
@Slf4j
class FinopsInsightsPlugin extends Plugin {

	static final String CODE = 'morpheus-finops-insights-plugin'
	static final String WEBSITE_URL = 'https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/finops/finops-insights'

	// plugin_instance.description holds 255 characters.
	static final String DESCRIPTION = 'Dashboard "FinOps Insights": revenue, cost and margin of the current month, a six-month trend and the split by cloud and tenant, read from the invoices. Providers see all sub-tenants, tenants their own spend.'

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
		FinopsInsightsData data = new FinopsInsightsData(morpheus)
		this.registerProvider(new FinopsKpiItemProvider(this, morpheus, data))
		this.registerProvider(new FinopsTrendItemProvider(this, morpheus, data))
		this.registerProvider(new FinopsSplitItemProvider(this, morpheus, data))
		this.registerProvider(new FinopsInsightsDashboardProvider(this, morpheus))
	}

	@Override
	void onDestroy() {
		// nothing to clean up
	}
}
