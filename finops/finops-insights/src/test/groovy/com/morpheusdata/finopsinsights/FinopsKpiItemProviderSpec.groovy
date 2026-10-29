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
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

import java.time.YearMonth

/**
 * The KPI strip's line about deactivated sub-tenants: its text in English and German, that it is
 * shown in the provider view only, that the cards stay as they are, and where the template puts it.
 */
class FinopsKpiItemProviderSpec extends Specification {

	FinopsKpiItemProvider kpi = new FinopsKpiItemProvider(new FinopsInsightsPlugin(), null, null)

	static final Map TWO = [count: 2, amounts: [[currency: 'EUR', rev: 1123.45G, cost: 90.00G], [currency: 'USD', rev: 5.00G, cost: 4.00G]]]

	private static Map loaded(Map m = [:]) {
		[ok: true, isMaster: true, currency: 'EUR', otherCurrencies: [], accountName: 'Provider',
		 months: [YearMonth.of(2026, 9), YearMonth.of(2026, 10)],
		 current: [rev: 120.00G, cost: 80.00G, margin: 40.00G], previous: [rev: 100.00G, cost: 70.00G, margin: 30.00G],
		 tenants: 2L, instances: 5L] + m
	}

	def "the line names the deactivated tenants, the month and revenue and cost per currency"() {
		expect:
		FinopsKpiItemProvider.deactivatedLine(loaded(deactivated: TWO), Locale.ENGLISH) ==
			'Not shown: 2 deactivated tenants with invoices in 2026-10: revenue 1,123.45 EUR, cost 90.00 EUR; revenue 5.00 USD, cost 4.00 USD'
		FinopsKpiItemProvider.deactivatedLine(loaded(deactivated: TWO), Locale.GERMANY) ==
			'Nicht gezeigt: 2 deaktivierte Tenants mit Rechnungen in 2026-10: Umsatz 1.123,45 EUR, Kosten 90,00 EUR; Umsatz 5,00 USD, Kosten 4,00 USD'
		FinopsKpiItemProvider.deactivatedLine(loaded(deactivated: [count: 1, amounts: [[currency: 'EUR', rev: 12.00G, cost: 10.00G]]]), Locale.ENGLISH) ==
			'Not shown: 1 deactivated tenant with invoices in 2026-10: revenue 12.00 EUR, cost 10.00 EUR'
	}

	def "no line without deactivated tenants, for a sub-tenant viewer, or when the data cannot be read"() {
		expect:
		FinopsKpiItemProvider.deactivatedLine(loaded(), Locale.ENGLISH) == null
		FinopsKpiItemProvider.deactivatedLine(loaded(deactivated: [count: 0, amounts: []]), Locale.ENGLISH) == null
		FinopsKpiItemProvider.deactivatedLine(loaded(isMaster: false, deactivated: TWO), Locale.ENGLISH) == null
		FinopsKpiItemProvider.deactivatedLine(loaded(deactivated: [count: 2, amounts: 'garbage']), Locale.ENGLISH) == null
	}

	def "the cards are the same with and without deactivated tenants"() {
		when:
		Map plain = kpi.model(loaded(), Locale.ENGLISH)
		Map withLine = kpi.model(loaded(deactivated: TWO), Locale.ENGLISH)

		then:
		withLine.cards == plain.cards
		withLine.cards.size() == 6
		withLine.cards[0].value == '120.00'
		withLine.cards.find { it.label == 'Tenants' }.value == '2'
		plain.deactivatedLine == null
		withLine.deactivatedLine.startsWith('Not shown: 2 deactivated tenants')
	}

	def "the template shows the line under the cards and nothing without it"() {
		given:
		Map plain = kpi.model(loaded(), Locale.ENGLISH)
		Map withLine = kpi.model(loaded(deactivated: TWO), Locale.ENGLISH)

		when:
		String shown = render(withLine)
		String none = render(plain)

		then:
		shown.indexOf('class="fi-cards"') < shown.indexOf('<div class="fi-deactivated"')
		shown.contains('>Not shown: 2 deactivated tenants with invoices in 2026-10: revenue 1,123.45 EUR, cost 90.00 EUR; revenue 5.00 USD, cost 4.00 USD</div>')
		!none.contains('<div class="fi-deactivated"')
	}

	/** A KPI widget with a test double as renderer (the real one needs the plugin's class loader). */
	static class KpiWidget extends FinopsKpiItemProvider {
		Renderer testRenderer

		KpiWidget(Plugin plugin, MorpheusContext morpheus, FinopsInsightsData data, Renderer testRenderer) {
			super(plugin, morpheus, data)
			this.testRenderer = testRenderer
		}

		@Override
		Renderer getRenderer() {
			testRenderer
		}
	}

	def "only the KPI strip asks the data loader for the deactivated tenants"() {
		given:
		Plugin plugin = new FinopsInsightsPlugin()
		MorpheusContext morpheus = Mock() { getSettings(plugin) >> Single.just('{}') }
		FinopsInsightsData data = Mock()
		Renderer renderer = Mock() { renderTemplate(_, _) >> HTMLResponse.success('<div></div>') }

		when:
		new KpiWidget(plugin, morpheus, data, renderer).renderDashboardItem(null, [:])
		new FinopsWidgetToggleSpec.TrendWidget(plugin, morpheus, data, renderer).renderDashboardItem(null, [:])

		then:
		1 * data.load([:], Locale.ENGLISH, true) >> [ok: false, reason: 'noViewer', locale: Locale.ENGLISH]
		1 * data.load([:], Locale.ENGLISH) >> [ok: false, reason: 'noViewer', locale: Locale.ENGLISH]
	}

	private static String render(Map model) {
		HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', FinopsKpiItemProviderSpec.classLoader)
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = [ok: true, title: 'FinOps at a glance', t: AbstractFinopsItemProvider.labels(Locale.ENGLISH)] + model
		renderer.renderTemplate('hbs/finops-kpi-widget', vm).html
	}
}
