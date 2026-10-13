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

import java.time.YearMonth

/**
 * Six-month trend as an inline SVG bar chart: revenue and cost per month for the provider, spend
 * per month for a tenant. The current month is the forecast Morpheus projects to month end.
 */
class FinopsTrendItemProvider extends AbstractFinopsItemProvider {

	static final String CODE = 'finops-insights-trend'

	FinopsTrendItemProvider(Plugin plugin, MorpheusContext morpheus, FinopsInsightsData data) {
		super(plugin, morpheus, data)
	}

	@Override String getCode() { CODE }
	@Override String getName() { 'FinOps trend' }
	@Override String getTitle() { 'Revenue and cost, six months' }
	@Override String getTitleKey() { 'trend.title' }
	@Override String getUiSize() { 'lg' }
	@Override String getTemplatePath() { 'hbs/finops-trend-widget' }
	@Override String getSettingKey() { FinopsInsightsPlugin.SETTING_HIDE_TREND }

	@Override
	Map model(Map d, Locale locale) {
		Map chart = FinopsInsightsCalc.trendChart(d.trend as List<Map>, d.months as List<YearMonth>, d.isMaster as boolean, locale)
		[chart: chart, currency: d.currency,
			seriesA: message(d.isMaster ? 'kpi.revenue' : 'kpi.spend', locale),
			seriesB: d.isMaster ? message('kpi.cost', locale) : null]
	}
}
