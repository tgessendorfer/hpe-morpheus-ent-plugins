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
import com.morpheusdata.core.web.MorpheusWebRequestService
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.Dispatcher
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * The log history page: how the appliance reaches it (route and permission), who may see it (the
 * Logs level of the signed-in user), what the request parameters can and cannot do, and what the
 * markup does with hostile server names.
 */
class FinopsLogsControllerSpec extends Specification {

	static final String HOSTILE = '"><img src=x onerror=alert(1)><script>alert(2)</script>'

	static class Holder {
		Map permissions
	}

	Plugin plugin = new FinopsInsightsPlugin()
	MorpheusContext morpheus = Mock()
	MorpheusWebRequestService webRequest = Mock()
	FinopsInsightsData data = Mock()
	FinopsLogsController controller

	def setup() {
		plugin.name = 'FinOps Insights Dashboard'
		morpheus.webRequest >> webRequest
		webRequest.nonceToken >> 'N0NCE123'
		webRequest.locale >> Locale.ENGLISH
		morpheus.getSettings(plugin) >> Single.just('{}')
		controller = new FinopsLogsController(plugin, morpheus, data)
	}

	private static User viewer(String logsLevel) {
		User u = new User()
		u.id = 7L
		u.permissions = logsLevel == null ? [:] : ['logs': logsLevel, 'operations-dashboard': 'full']
		u
	}

	private static ViewModel<Map> request(User user, Map params = [:]) {
		ViewModel<Map> model = new ViewModel<>()
		model.user = user
		model.object = params
		model
	}

	private static Map sources(List names) {
		[ok: true, locale: Locale.ENGLISH, isMaster: false,
		 sources: names.withIndex().collect { n, i -> [id: 100L + i, name: n as String, instanceId: i % 2 == 0 ? 500L + i : null] }]
	}

	def "the route is /finops-insights/logs, served by logsPage(ViewModel), and found by the dispatcher"() {
		given:
		plugin.controllers.add(controller)
		com.morpheusdata.core.PluginManager manager = Mock()
		manager.plugins >> new ArrayList<Plugin>([plugin])
		Dispatcher dispatcher = new Dispatcher(manager)
		data.logSources(_, _) >> sources(['web-01'])

		when:
		def hit = dispatcher.handleRoute('/finops-insights/logs', request(viewer('read')), ['logs': 'read'])
		def missing = dispatcher.handleRoute('/finops-insights/other', request(viewer('read')), ['logs': 'read'])
		def noKey = dispatcher.handleRoute('/finops-insights/logs', request(viewer('read')), [:])

		then:
		controller.routes.size() == 1
		controller.routes[0].url == '/finops-insights/logs'
		controller.routes[0].method == 'logsPage'
		FinopsLogsController.getMethod('logsPage', ViewModel) != null
		hit instanceof HTMLResponse
		hit.html.contains('data-mode="page"')
		missing == null
		noKey == null
	}

	def "only the read and full levels of Logs get the page; user, none and missing do not"() {
		given:
		data.logSources(_, _) >> sources(['web-01'])

		when:
		HTMLResponse out = controller.logsPage(request(viewer(level)))

		then:
		out.status == status
		out.html.contains('data-mode="page"') == shown
		out.html.contains('needed to see this page.</div>') == !shown

		where:
		level  | status | shown
		'read' | 200    | true
		'full' | 200    | true
		'user' | 403    | false
		'none' | 403    | false
		null   | 403    | false
	}

	def "a request without a user is refused and nothing is read"() {
		when:
		HTMLResponse out = controller.logsPage(request(null))

		then:
		0 * data._
		out.status == 403
		!out.html.contains('data-mode="page"')
	}

	def "mayViewLogs reads the permission map of the marshalled user"() {
		expect:
		FinopsLogsController.mayViewLogs(user) == expected

		where:
		user                                                | expected
		null                                                | false
		viewer('read')                                      | true
		viewer('full')                                      | true
		viewer('user')                                      | false
		viewer('none')                                      | false
		viewer(null)                                        | false
		new Holder(permissions: ['logs': 'full'])           | true
		new Holder(permissions: ['logs': 'user'])           | false
		new Holder(permissions: null)                       | false
		new Object()                                        | false
	}

	def "the page hands the script the viewer's sources only, as escaped JSON, never a parameter's ids"() {
		when:
		HTMLResponse out = controller.logsPage(request(viewer('read'), [source: '999', servers: ['5', '6'], serverIds: '7', ids: '8']))

		then:
		// the only option handed to the source query is the signed-in user; no request parameter is
		1 * data.logSources({ Map o -> o.keySet() == ['user'] as Set && o.user.id == 7L }, _) >> sources(['web-01', 'db-01'])
		out.html.contains('data-sources="[{&quot;id&quot;:100,&quot;n&quot;:&quot;web-01&quot;,&quot;i&quot;:500},{&quot;id&quot;:101,&quot;n&quot;:&quot;db-01&quot;,&quot;i&quot;:null}]"')
		// 999 is not one of the viewer's sources, so it is not selected and not echoed
		out.html.contains('&quot;source&quot;:null')
		!out.html.contains('999')
		!out.html.contains('&quot;servers&quot;')
	}

	def "a source parameter is honoured only when it is one of the viewer's own sources"() {
		given:
		data.logSources(_, _) >> sources(['web-01', 'db-01'])

		when:
		HTMLResponse out = controller.logsPage(request(viewer('full'), [source: '101']))

		then:
		out.html.contains('&quot;source&quot;:101')
	}

	def "window, level, text and collapse are cut down to the whitelist"() {
		expect:
		FinopsLogsSupport.initialState(params, [100L, 101L]) == expected

		where:
		params                                                              | expected
		[:]                                                                 | [window: '24h', levels: ['INFO', 'WARN', 'ERROR', 'DEBUG'], source: null, q: '', collapse: false]
		[window: '7d', level: 'warn|error', collapse: 'on']                 | [window: '7d', levels: ['WARN', 'ERROR'], source: null, q: '', collapse: true]
		[window: '90d', level: 'FATAL']                                     | [window: '24h', levels: ['INFO', 'WARN', 'ERROR', 'DEBUG'], source: null, q: '', collapse: false]
		[window: ['1h', '30d'], level: ['ERROR', 'bogus', 'INFO']]          | [window: '1h', levels: ['ERROR', 'INFO'], source: null, q: '', collapse: false]
		[source: ['100'], q: "  a\u0007b\n\tc  "]                           | [window: '24h', levels: ['INFO', 'WARN', 'ERROR', 'DEBUG'], source: 100L, q: 'a b c', collapse: false]
		[source: '100abc']                                                  | [window: '24h', levels: ['INFO', 'WARN', 'ERROR', 'DEBUG'], source: null, q: '', collapse: false]
		[source: '-5', collapse: 'maybe']                                   | [window: '24h', levels: ['INFO', 'WARN', 'ERROR', 'DEBUG'], source: null, q: '', collapse: false]
	}

	def "the text filter is limited to 100 characters"() {
		expect:
		FinopsLogsSupport.initialState([q: 'x' * 500], []).q.length() == 100
	}

	def "a hostile server name or text parameter cannot break out of the page's attributes or add markup"() {
		given:
		data.logSources(_, _) >> sources([HOSTILE])

		when:
		HTMLResponse out = controller.logsPage(request(viewer('read'), [q: HOSTILE, window: HOSTILE, level: HOSTILE]))

		then:
		!out.html.contains('<img')
		!out.html.contains('onerror=alert(1)><')
		// the only script element is the plugin's own file with the request's nonce
		out.html.count('<script') == 1
		out.html.contains('<script nonce="N0NCE123" src="/assets/plugin/finops-insights-dashboard/finops-insights-logs.js"></script>')
		out.html.contains('&lt;img src')
	}

	def "the page links back to the dashboard and to the standard log viewer and loads the script with the nonce"() {
		given:
		data.logSources(_, _) >> sources(['web-01'])

		when:
		HTMLResponse out = controller.logsPage(request(viewer('read')))

		then:
		out.status == 200
		out.html.contains('<title>Log history</title>')
		out.html.contains('href="/operations/dashboard"')
		out.html.contains('href="/monitoring/logs"')
		out.html.contains('data-page-size="100"')
		out.html.contains('<meta name="layout" content="main"/>')
	}

	def "Hide Latest Log Entries switches the page off and no source is read"() {
		given:
		MorpheusContext hiddenMorpheus = Mock()
		hiddenMorpheus.webRequest >> webRequest
		hiddenMorpheus.getSettings(plugin) >> Single.just('{"hideLogs":"on"}')
		FinopsLogsController off = new FinopsLogsController(plugin, hiddenMorpheus, data)

		when:
		HTMLResponse out = off.logsPage(request(viewer('full')))

		then:
		0 * data._
		out.status == 200
		out.html.contains('Switched off in the plugin settings.')
		!out.html.contains('data-mode="page"')
		!out.html.contains('<script')
	}

	def "states without data show a message and no script"() {
		given:
		data.logSources(_, _) >> loaded

		when:
		HTMLResponse out = controller.logsPage(request(viewer('read')))

		then:
		out.status == 200
		out.html.contains(text)
		!out.html.contains('data-mode="page"')
		!out.html.contains('<script')

		where:
		loaded                                                                    | text
		[ok: true, sources: [], locale: Locale.ENGLISH]                           | 'No servers with logging are visible to this tenant.'
		[ok: false, reason: 'noViewer', sources: [], locale: Locale.ENGLISH]      | 'could not identify the signed-in user'
		[ok: false, reason: 'loadFailed', sources: [], locale: Locale.ENGLISH]    | 'The log sources could not be loaded'
	}

	def "an exception while building the page is a message, not a stack trace"() {
		given:
		data.logSources(_, _) >> { throw new IllegalStateException('core failure') }

		when:
		HTMLResponse out = controller.logsPage(request(viewer('read')))

		then:
		out.status == 200
		out.html.contains('The log sources could not be loaded')
		!out.html.contains('core failure')
	}

	def "the page uses the viewer's language"() {
		given:
		data.logSources(_, _) >> [ok: true, locale: Locale.GERMAN, sources: [[id: 1L, name: 'web-01', instanceId: null]]]

		when:
		HTMLResponse out = controller.logsPage(request(viewer('read')))

		then:
		out.html.contains('<html lang="de">')
		out.html.contains('<title>Loghistorie</title>')
		out.html.contains('Zurück zum Dashboard')
	}
}
