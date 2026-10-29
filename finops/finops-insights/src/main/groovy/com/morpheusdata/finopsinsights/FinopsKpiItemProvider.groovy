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
import groovy.util.logging.Slf4j

import java.math.BigDecimal

/**
 * KPI strip (full width, short): this month's revenue (forecast), cost, margin and the change
 * against the previous month, plus tenant and instance counts. A tenant sees spend instead of
 * revenue and no cost or margin. The provider view adds one line under the cards when deactivated
 * sub-tenants, which the figures leave out, have invoices this month.
 */
@Slf4j
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

	/** The KPI strip is the one widget that shows the line about deactivated sub-tenants. */
	@Override
	protected Map loadFigures(Map<String, Object> opts, Locale locale) {
		data.load(opts, locale, true)
	}

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
		[cards: cards, otherCurrencies: d.otherCurrencies ? (d.otherCurrencies as List).join(', ') : null,
			deactivatedLine: deactivatedLine(d, locale)]
	}

	/**
	 * The line under the cards about the deactivated sub-tenants the figures leave out (provider
	 * view only), for example "Not shown: 2 deactivated tenants with invoices in 2026-10: revenue
	 * 123.45 EUR, cost 100.00 EUR", one revenue and cost pair per currency. Null when there are none.
	 * A problem here drops the line only, never the widget.
	 */
	static String deactivatedLine(Map d, Locale locale) {
		try {
			Map x = d.isMaster ? (d.deactivated as Map) : null
			int count = (x?.count ?: 0) as int
			if(count == 0) return null
			String head = message(count == 1 ? 'kpi.deactivated.one' : 'kpi.deactivated.other', locale)
				.replace('{0}', count.toString()).replace('{1}', month(d, locale))
			String amounts = (x.amounts as List<Map>).collect { Map a ->
				message('kpi.deactivated.amounts', locale)
					.replace('{0}', FinopsInsightsCalc.money(a.rev, locale) + ' ' + a.currency)
					.replace('{1}', FinopsInsightsCalc.money(a.cost, locale) + ' ' + a.currency)
			}.join('; ')
			return head + ': ' + amounts
		} catch(Exception e) {
			log.warn("FinOps insights: the line about deactivated tenants was left out: ${e.message}")
			return null
		}
	}

	static String month(Map d, Locale locale) {
		(d.months as List)[-1].format(FinopsInsightsCalc.SHOWN)
	}

	static String prevMonth(Map d, Locale locale) {
		List months = d.months as List
		months.size() > 1 ? months[-2].format(FinopsInsightsCalc.SHOWN) : ''
	}
}
