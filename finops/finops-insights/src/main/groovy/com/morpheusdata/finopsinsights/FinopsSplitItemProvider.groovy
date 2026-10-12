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

/**
 * This month's revenue split by cloud and, for the provider, by tenant: top five plus "other",
 * each with its share and a bar.
 */
class FinopsSplitItemProvider extends AbstractFinopsItemProvider {

	static final String CODE = 'finops-insights-split'

	FinopsSplitItemProvider(Plugin plugin, MorpheusContext morpheus, FinopsInsightsData data) {
		super(plugin, morpheus, data)
	}

	@Override String getCode() { CODE }
	@Override String getName() { 'FinOps split' }
	@Override String getTitle() { 'Revenue by cloud and tenant' }
	@Override String getTitleKey() { 'split.title' }
	@Override String getUiSize() { 'lg' }
	@Override String getTemplatePath() { 'hbs/finops-split-widget' }

	@Override
	Map model(Map d, Locale locale) {
		String other = message('split.other', locale)
		[currency: d.currency, month: (d.months as List)[-1].format(FinopsInsightsCalc.SHOWN),
			byCloud: rows(d.byCloud as List<Map>, other, locale),
			byTenant: d.isMaster ? rows(d.byTenant as List<Map>, other, locale) : null]
	}

	static List<Map> rows(List<Map> list, String otherLabel, Locale locale) {
		list.collect { Map x ->
			[name: x.other ? otherLabel : x.name, revText: FinopsInsightsCalc.money(x.rev, locale),
				shareText: x.share != null ? FinopsInsightsCalc.pctText(x.share as BigDecimal, locale) : '-', bar: x.bar, other: x.other ? true : false]
		}
	}
}
