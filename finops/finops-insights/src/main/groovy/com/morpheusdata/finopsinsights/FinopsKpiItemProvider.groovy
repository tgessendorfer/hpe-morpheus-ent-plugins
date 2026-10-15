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

import java.math.BigDecimal

/**
 * KPI strip (full width, short): this month's revenue (forecast), cost, margin and the change
 * against the previous month, plus tenant and instance counts. A tenant sees spend instead of
 * revenue and no cost or margin.
 */
class FinopsKpiItemProvider extends AbstractFinopsItemProvider {

	static final String CODE = 'finops-insights-kpi'

	FinopsKpiItemProvider(Plugin plugin, MorpheusContext morpheus, FinopsInsightsData data) {
		super(plugin, morpheus, data)
	}

	@Override String getCode() { CODE }
	@Override String getName() { 'FinOps KPIs' }
	@Override String getTitle() { 'FinOps KPIs' }
	@Override String getTitleKey() { 'kpi.title' }
	@Override String getUiSize() { 'xl widget-short' }
	@Override String getTemplatePath() { 'hbs/finops-kpi-widget' }
	@Override String getSettingKey() { FinopsInsightsPlugin.SETTING_HIDE_KPI }

	@Override
	Map model(Map d, Locale locale) {
		Map cur = d.current as Map
		Map prev = d.previous as Map
		BigDecimal rev = cur.rev as BigDecimal
		BigDecimal cost = cur.cost as BigDecimal
		BigDecimal margin = cur.margin as BigDecimal
		BigDecimal revDelta = FinopsInsightsCalc.deltaPct(rev, prev?.rev)
		BigDecimal marginPct = FinopsInsightsCalc.pctValue(margin, rev)
		List<Map> cards = []
		cards << [value: FinopsInsightsCalc.money(rev, locale), unit: d.currency,
			label: message(d.isMaster ? 'kpi.revenue' : 'kpi.spend', locale) + ' ' + month(d, locale),
			sub: message('kpi.forecast', locale), accent: 'blue']
		if(d.isMaster) {
			cards << [value: FinopsInsightsCalc.money(cost, locale), unit: d.currency, label: message('kpi.cost', locale) + ' ' + month(d, locale),
				sub: message('kpi.purchase', locale), accent: 'orange']
			cards << [value: FinopsInsightsCalc.money(margin, locale), unit: d.currency, label: message('kpi.margin', locale) + ' ' + month(d, locale),
				sub: FinopsInsightsCalc.pctText(marginPct, locale) + ' ' + message('kpi.ofRevenue', locale), accent: margin.signum() >= 0 ? 'green' : 'red']
		}
		cards << [value: FinopsInsightsCalc.deltaText(revDelta, locale), unit: '',
			label: message('kpi.vsLastMonth', locale),
			sub: prev ? (FinopsInsightsCalc.money(prev.rev, locale) + ' ' + d.currency + ' ' + prevMonth(d, locale)) : '-',
			accent: revDelta == null ? 'grey' : (revDelta.signum() > 0 ? 'green' : (revDelta.signum() < 0 ? 'red' : 'grey'))]
		if(d.isMaster) {
			cards << [value: d.tenants.toString(), unit: '', label: message('kpi.tenants', locale), sub: message('kpi.active', locale), accent: 'grey']
		}
		cards << [value: d.instances.toString(), unit: '', label: message('kpi.instances', locale),
			sub: d.isMaster ? message('kpi.allTenants', locale) : (d.accountName as String), accent: 'grey']
		[cards: cards, otherCurrencies: d.otherCurrencies ? (d.otherCurrencies as List).join(', ') : null]
	}

	static String month(Map d, Locale locale) {
		(d.months as List)[-1].format(FinopsInsightsCalc.SHOWN)
	}

	static String prevMonth(Map d, Locale locale) {
		List months = d.months as List
		months.size() > 1 ? months[-2].format(FinopsInsightsCalc.SHOWN) : ''
	}
}
