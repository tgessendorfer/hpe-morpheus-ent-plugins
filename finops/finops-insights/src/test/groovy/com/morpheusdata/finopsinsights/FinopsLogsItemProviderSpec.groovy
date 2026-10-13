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
import com.morpheusdata.core.MorpheusPermissionService
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * The latest log entries widget: its item type (Logs permission, read and full only, own script), the
 * hide setting, the template model and the rendered markup, including what a hostile server name
 * does to it.
 */
class FinopsLogsItemProviderSpec extends Specification {

	static final String HOSTILE = '"><img src=x onerror=alert(1)><script>alert(2)</script>'

	static class LogsWidget extends FinopsLogsItemProvider {
		Renderer testRenderer

		LogsWidget(Plugin plugin, MorpheusContext morpheus, FinopsInsightsData data, Renderer testRenderer) {
			super(plugin, morpheus, data)
			this.testRenderer = testRenderer
		}

		@Override
		Renderer getRenderer() {
			testRenderer
		}
	}

	Plugin plugin = new FinopsInsightsPlugin()
	MorpheusContext morpheus = Mock()
	FinopsInsightsData data = Mock()

	private String render(Map loaded, Locale locale = Locale.ENGLISH) {
		morpheus.getSettings(plugin) >> Single.just('{}')
		data.logSources(_, _) >> loaded
		LogsWidget widget = new LogsWidget(plugin, morpheus, data, new HandlebarsRenderer('renderer', FinopsLogsItemProviderSpec.classLoader))
		widget.renderContent([:], locale).html
	}

	def "the item type needs the Logs permission with read or full, not the user level, and has its own script"() {
		given:
		MorpheusPermissionService permissions = Mock()
		morpheus.permission >> permissions
		Permission logs = new Permission('Logs', 'logs', [Permission.AccessType.read])
		FinopsLogsItemProvider widget = new FinopsLogsItemProvider(plugin, morpheus, data)

		when:
		def type = widget.dashboardItemType

		then:
		1 * permissions.getByCode('logs') >> Single.just(logs)
		type.code == 'finops-insights-logs'
		type.permission.is(logs)
		type.accessTypes == ['read', 'full']
		type.uiSize == 'lg'
		type.templatePath == 'hbs/finops-logs-widget'
		type.scriptPath == 'finops-insights-logs.js'
		new File('src/assets/javascripts/' + type.scriptPath).isFile()
		new File('src/main/resources/renderer/' + type.templatePath + '.hbs').isFile()
		widget.settingKey == 'hideLogs'
		// the three invoice widgets keep their permission and the shared script
		new FinopsKpiItemProvider(plugin, morpheus, data).permissionCode == 'operations-budgets'
		new FinopsKpiItemProvider(plugin, morpheus, data).scriptPath == 'finops-insights.js'
		new FinopsKpiItemProvider(plugin, morpheus, data).accessTypes == ['read', 'full']
	}

	def "with Hide Latest Log Entries ticked the widget renders the hidden template and reads no source"() {
		given:
		Renderer renderer = Mock()
		morpheus.getSettings(plugin) >> Single.just('{"hideLogs":"on"}')
		LogsWidget widget = new LogsWidget(plugin, morpheus, data, renderer)
		ViewModel captured

		when:
		widget.renderDashboardItem(null, [:])

		then:
		0 * data._
		1 * renderer.renderTemplate('hbs/finops-hidden-widget', _ as ViewModel) >> { String path, ViewModel vm ->
			captured = vm
			HTMLResponse.success('<div class="fi-off"></div>')
		}
		captured.object.title == 'Latest log entries'
		captured.object.hint == 'Switched off in the plugin settings.'
	}

	def "ticking another widget's box leaves the log widget shown"() {
		given:
		Renderer renderer = Mock()
		morpheus.getSettings(plugin) >> Single.just('{"hideKpi":"on","hideTrend":"on","hideSplit":"on"}')
		LogsWidget widget = new LogsWidget(plugin, morpheus, data, renderer)
		ViewModel captured

		when:
		widget.renderDashboardItem(null, [:])

		then:
		1 * data.logSources([:], Locale.ENGLISH) >> [ok: true, sources: [[id: 1L, name: 'web-01', instanceId: 2L]], locale: Locale.ENGLISH]
		1 * renderer.renderTemplate('hbs/finops-logs-widget', _ as ViewModel) >> { String path, ViewModel vm ->
			captured = vm
			HTMLResponse.success('<div></div>')
		}
		captured.object.hasSources
		captured.object.ok
		captured.object.windowDays == 7
		captured.object.fetchMax == 40
		captured.object.show == 10
		captured.object.historyUrl == '/plugin/finops-insights/logs'
		captured.object.standardUrl == '/monitoring/logs'
		captured.object.sourcesJson == '[{"id":1,"n":"web-01","i":2}]'
	}

	def "with sources the markup carries them as an attribute, hides the standard widget and links the history page"() {
		when:
		String html = render([ok: true, locale: Locale.ENGLISH,
			sources: [[id: 31L, name: 'web-01', instanceId: 410L], [id: 32L, name: 'db-01', instanceId: null]]])

		then:
		html.contains('.widget-container:has(#log-count-widget) { display: none; }')
		html.contains('data-mode="widget"')
		html.contains('data-window-days="7"')
		html.contains('data-max="40"')
		html.contains('data-show="10"')
		html.contains('href="/plugin/finops-insights/logs"')
		html.contains('Full history')
		html.contains('Latest log entries')
		// the sources JSON is HTML-escaped inside the attribute
		html.contains('data-sources="[{&quot;id&quot;:31,&quot;n&quot;:&quot;web-01&quot;,&quot;i&quot;:410},')
		!html.contains('class="fi-hint"')
	}

	def "a hostile server name stays text: no tag, no attribute break-out, no script"() {
		when:
		String html = render([ok: true, locale: Locale.ENGLISH, sources: [[id: 31L, name: HOSTILE, instanceId: 410L]]])

		then:
		!html.contains('<img')
		!html.contains('<script')
		!html.contains('onerror=alert(1)><')
		html.contains('&lt;img src')
		html.contains('&lt;script&gt;')
		// the attribute still ends where the template ends it
		html.contains('&quot; data-labels=') || html.contains('" data-labels="')
	}

	def "a tenant without servers gets the hint, no script root, and the standard widget is still replaced"() {
		when:
		String html = render([ok: true, locale: Locale.ENGLISH, sources: []])

		then:
		html.contains('No servers with logging are visible to this tenant.')
		!html.contains('class="fi-logs"')
		!html.contains('Full history')
		html.contains('.widget-container:has(#log-count-widget)')
	}

	def "when the sources cannot be loaded the standard widget stays and a link to it is shown"() {
		when:
		String html = render([ok: false, reason: reason, locale: Locale.ENGLISH, sources: []])

		then:
		html.contains(text)
		!html.contains('.widget-container:has(#log-count-widget)')
		!html.contains('class="fi-logs"')
		html.contains('href="/monitoring/logs"')

		where:
		reason       | text
		'noViewer'   | 'could not identify the signed-in user'
		'loadFailed' | 'The log sources could not be loaded'
	}

	def "the widget speaks German when the viewer's language is German"() {
		when:
		String html = render([ok: true, locale: Locale.GERMAN, sources: [[id: 1L, name: 'web-01', instanceId: null]]], Locale.GERMAN)

		then:
		html.contains('Neueste Logeinträge')
		html.contains('Gesamte Historie')
		html.contains('Logeinträge werden geladen')
	}

	def "every label the browser script reads exists in both languages"() {
		given:
		List<String> used = ['logs_loading', 'logs_empty', 'logs_error', 'logs_noSources', 'logs_window', 'logs_window_1h',
			'logs_window_24h', 'logs_window_7d', 'logs_window_30d', 'logs_level', 'logs_source', 'logs_allSources', 'logs_text',
			'logs_textHint', 'logs_collapse', 'logs_more', 'logs_noLevel', 'logs_pageEmpty', 'logs_showing', 'logs_showingOf',
			'logs_timezone', 'logs_retention']

		expect:
		FinopsLogsSupport.logLabels(Locale.ENGLISH).keySet().containsAll(used)
		FinopsLogsSupport.logLabels(Locale.GERMAN).keySet() == FinopsLogsSupport.logLabels(Locale.ENGLISH).keySet()
		used.every { FinopsLogsSupport.logLabels(Locale.GERMAN)[it] }
		FinopsLogsSupport.logLabels(Locale.ENGLISH).every { k, v -> v && !v.startsWith('logs.') }
	}
}
