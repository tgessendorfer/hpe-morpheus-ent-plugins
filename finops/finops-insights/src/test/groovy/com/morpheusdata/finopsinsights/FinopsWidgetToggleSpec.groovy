package com.morpheusdata.finopsinsights

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * The widget on/off switch added in 1.2.0: how the stored plugin settings are read, that a widget is
 * hidden only by its own ticked checkbox, that anything unreadable keeps it shown, and that a hidden
 * widget runs no query.
 */
class FinopsWidgetToggleSpec extends Specification {

	/** A trend widget with a test double as renderer (the real one needs the plugin's class loader). */
	static class TrendWidget extends FinopsTrendItemProvider {
		Renderer testRenderer

		TrendWidget(Plugin plugin, MorpheusContext morpheus, FinopsInsightsData data, Renderer testRenderer) {
			super(plugin, morpheus, data)
			this.testRenderer = testRenderer
		}

		@Override
		Renderer getRenderer() {
			testRenderer
		}
	}

	Plugin plugin = new FinopsInsightsPlugin()

	def "flagOn accepts what a ticked checkbox or a JSON boolean carries and nothing else"() {
		expect:
		AbstractFinopsItemProvider.flagOn(value) == expected

		where:
		value   | expected
		null    | false
		'on'    | true
		'ON'    | true
		' on '  | true
		'true'  | true
		'Yes'   | true
		'1'     | true
		true    | true
		1       | true
		'off'   | false
		'false' | false
		false   | false
		''      | false
		'  '    | false
		'0'     | false
		0       | false
		'maybe' | false
	}

	def "parseConfig returns the stored settings or an empty map, never throws"() {
		expect:
		AbstractFinopsItemProvider.parseConfig(json) == expected

		where:
		json                                  | expected
		null                                  | [:]
		''                                    | [:]
		'   '                                 | [:]
		'null'                                | [:]
		'[]'                                  | [:]
		'"on"'                                | [:]
		'{not json'                           | [:]
		'{}'                                  | [:]
		'{"hideKpi":"on","other":1}'          | [hideKpi: 'on', other: 1]
		'{"hideTrend":true}'                  | [hideTrend: true]
	}

	def "a widget is hidden only when its own checkbox is ticked"() {
		given:
		MorpheusContext morpheus = Mock()
		morpheus.getSettings(plugin) >> Single.just(config)
		def kpi = new FinopsKpiItemProvider(plugin, morpheus, null)
		def trend = new FinopsTrendItemProvider(plugin, morpheus, null)
		def split = new FinopsSplitItemProvider(plugin, morpheus, null)

		expect:
		kpi.isHidden() == hideKpi
		trend.isHidden() == hideTrend
		split.isHidden() == hideSplit

		where:
		config                                       | hideKpi | hideTrend | hideSplit
		'{}'                                         | false   | false     | false
		'{"hideKpi":"on"}'                           | true    | false     | false
		'{"hideTrend":"on","hideSplit":true}'        | false   | true      | true
		'{"hideKpi":"on","hideTrend":"on","hideSplit":"on"}' | true | true   | true
		'{"hideKpi":"off","hideTrend":"false"}'      | false   | false     | false
		'{"unrelated":"on"}'                         | false   | false     | false
	}

	def "settings that cannot be read keep the widget shown"() {
		given:
		MorpheusContext morpheus = Mock()
		morpheus.getSettings(plugin) >> answer
		def kpi = new FinopsKpiItemProvider(plugin, morpheus, null)

		expect:
		!kpi.isHidden()

		where:
		answer << [Single.error(new IllegalStateException('core failure')), Single.just('not json'), Single.just('[]'), Single.just('')]
	}

	def "a throwing settings call or a missing context keeps the widget shown"() {
		given:
		MorpheusContext morpheus = Mock()
		morpheus.getSettings(plugin) >> { throw new IllegalStateException('core failure') }

		expect:
		!new FinopsKpiItemProvider(plugin, morpheus, null).isHidden()
		!new FinopsKpiItemProvider(plugin, null, null).isHidden()
	}

	def "a switched-off widget renders the hidden template and runs no query"() {
		given:
		MorpheusContext morpheus = Mock()
		FinopsInsightsData data = Mock()
		Renderer renderer = Mock()
		morpheus.getSettings(plugin) >> Single.just('{"hideTrend":"on"}')
		TrendWidget widget = new TrendWidget(plugin, morpheus, data, renderer)
		ViewModel captured

		when:
		HTMLResponse out = widget.renderDashboardItem(null, [:])

		then:
		0 * data._
		1 * renderer.renderTemplate('hbs/finops-hidden-widget', _ as ViewModel) >> { String path, ViewModel vm ->
			captured = vm
			HTMLResponse.success('<div class="fi-off"></div>')
		}
		out.html == '<div class="fi-off"></div>'
		captured.object.title == 'Six-month trend'
		captured.object.hint == 'Switched off in the plugin settings.'
	}

	def "without the setting the widget loads its data and renders its own template"() {
		given:
		MorpheusContext morpheus = Mock()
		FinopsInsightsData data = Mock()
		Renderer renderer = Mock()
		morpheus.getSettings(plugin) >> Single.just('{"hideKpi":"on"}')
		TrendWidget widget = new TrendWidget(plugin, morpheus, data, renderer)
		ViewModel captured

		when:
		widget.renderDashboardItem(null, [:])

		then:
		1 * data.load([:], Locale.ENGLISH) >> [ok: false, reason: 'noViewer', locale: Locale.ENGLISH]
		1 * renderer.renderTemplate('hbs/finops-trend-widget', _ as ViewModel) >> { String path, ViewModel vm ->
			captured = vm
			HTMLResponse.success('<div></div>')
		}
		captured.object.ok == false
		captured.object.hint == AbstractFinopsItemProvider.message('hint.noViewer', Locale.ENGLISH)
	}

	def "the switched-off template renders from the plugin resources"() {
		given:
		HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', FinopsWidgetToggleSpec.classLoader)
		ViewModel<Map> model = new ViewModel<>()
		model.object = [title: 'Six-month trend', hint: 'Switched off in the plugin settings.']

		when:
		String html = renderer.renderTemplate(AbstractFinopsItemProvider.HIDDEN_TEMPLATE_PATH, model).html

		then:
		html.contains('<div class="fi-off">')
		html.contains('.widget-container:has(.fi-off) { display: none; }')
		html.contains('Six-month trend: Switched off in the plugin settings.')
	}
}
