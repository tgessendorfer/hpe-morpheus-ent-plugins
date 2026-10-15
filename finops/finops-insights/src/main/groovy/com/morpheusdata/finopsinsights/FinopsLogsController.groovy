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
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * The log history page, served by the plugin at /plugin/finops-insights/logs.
 *
 * The appliance maps every path below /plugin/ to the plugin routes (the signed-in session is
 * required; the route's permission is only checked for presence, so the access level of the Logs
 * permission is checked here). The page is complete HTML that the appliance writes to the response
 * as it is: there is no Morpheus navigation around it, which is why it carries its own header with
 * links back to the dashboard and to the standard log viewer.
 *
 * The page lists the viewer's log sources (names in a filter, ids for the browser script) from the
 * database; the log entries themselves are fetched by the browser script from the platform's log
 * API for exactly those servers. Request parameters only set the initial filter and are cut down
 * to a whitelist (FinopsLogsSupport.initialState); a server id from a parameter is used only if it
 * is one of the viewer's own sources.
 */
@Slf4j
class FinopsLogsController implements PluginController {

	static final String CODE = 'finops-insights-logs-page'
	/** The route as the dispatcher compares it: the part of the URL after /plugin. */
	static final String ROUTE_URL = '/finops-insights/logs'
	static final String ROUTE_METHOD = 'logsPage'
	static final String PAGE_TEMPLATE = 'hbs/finops-logs-page'
	static final List<String> ALLOWED_ACCESS = ['read', 'full']

	Plugin plugin
	MorpheusContext morpheus
	FinopsInsightsData data
	private Renderer renderer

	FinopsLogsController(Plugin plugin, MorpheusContext morpheus, FinopsInsightsData data) {
		this.plugin = plugin
		this.morpheus = morpheus
		this.data = data
	}

	@Override MorpheusContext getMorpheus() { morpheus }
	@Override Plugin getPlugin() { plugin }
	@Override String getCode() { CODE }
	@Override String getName() { 'FinOps log history' }

	@Override
	List<Route> getRoutes() {
		[Route.build(ROUTE_URL, ROUTE_METHOD, Permission.build('Logs', FinopsLogsItemProvider.LOGS_PERMISSION_CODE,
			[Permission.AccessType.read, Permission.AccessType.full]))]
	}

	/** Own renderer with the nonce and asset helpers; created on first use. */
	Renderer getRenderer() {
		if(renderer == null) {
			HandlebarsRenderer created = new HandlebarsRenderer('renderer', plugin?.classLoader ?: FinopsLogsController.classLoader)
			created.registerAssetHelper(plugin?.name ?: 'FinOps Insights Dashboard')
			created.registerNonceHelper(morpheus?.webRequest)
			renderer = created
		}
		renderer
	}

	/**
	 * The route handler. The dispatcher finds it by its name and its single ViewModel parameter, so
	 * both stay as they are. Never throws: every failure is a page with a message.
	 */
	HTMLResponse logsPage(ViewModel model) {
		Locale locale = browserLocale()
		try {
			if(!mayViewLogs(model?.user)) {
				return page(pageModel('noAccess', locale, [:]), 403)
			}
			if(AbstractFinopsItemProvider.flagOn(settings()[FinopsInsightsPlugin.SETTING_HIDE_LOGS])) {
				return page(pageModel('hidden', locale, [:]), 200)
			}
			Map loaded = data.logSources([user: model?.user] as Map<String, Object>, locale)
			Locale shown = (loaded.locale as Locale) ?: locale
			if(!loaded.ok) {
				return page(pageModel(loaded.reason == 'noViewer' ? 'noViewer' : 'loadFailed', shown, [:]), 200)
			}
			List<Map> sources = loaded.sources as List<Map>
			if(sources.isEmpty()) {
				return page(pageModel('noSources', shown, [:]), 200)
			}
			Map params = requestParams(model)
			Map initial = FinopsLogsSupport.initialState(params, sources.collect { it.id as Long })
			return page(pageModel('ready', shown, [sources: sources, initial: initial]), 200)
		} catch(Exception e) {
			log.error("FinOps insights: rendering the log history page failed: ${e.message}", e)
			return page(pageModel('loadFailed', locale, [:]), 200)
		}
	}

	/** True when the viewer's Logs permission is read or full (the "user" level is not enough). */
	static boolean mayViewLogs(Object user) {
		if(user == null) return false
		Object permissions = user.hasProperty('permissions') ? user.permissions : null
		if(!(permissions instanceof Map)) return false
		String level = ((Map) permissions).get(FinopsLogsItemProvider.LOGS_PERMISSION_CODE)?.toString()
		level in ALLOWED_ACCESS
	}

	/** The model of the page template for one state. */
	Map pageModel(String state, Locale locale, Map extra) {
		List<Map> sources = (extra.sources ?: []) as List<Map>
		Map initial = (extra.initial ?: [:]) as Map
		boolean ready = state == 'ready'
		String hintKey = ['noAccess': 'logs.page.noAccess', 'hidden': 'hint.hidden', 'noViewer': 'logs.noViewer',
			'loadFailed': 'logs.loadFailed', 'noSources': 'logs.noSources'][state]
		[state      : state,
		 ready      : ready,
		 lang       : AbstractFinopsItemProvider.messageLocale(locale).language,
		 pageTitle  : AbstractFinopsItemProvider.message('logs.page.title', locale),
		 hint       : hintKey ? AbstractFinopsItemProvider.message(hintKey, locale) : null,
		 t          : FinopsLogsSupport.logLabels(locale),
		 labelsJson : FinopsLogsSupport.labelsJson(locale),
		 sourcesJson: FinopsLogsSupport.sourcesJson(sources),
		 initialJson: groovy.json.JsonOutput.toJson(initial),
		 pageSize   : FinopsLogsSupport.PAGE_SIZE,
		 backUrl    : FinopsLogsSupport.DASHBOARD_PATH,
		 standardUrl: FinopsLogsSupport.STANDARD_LOGS_PATH]
	}

	private HTMLResponse page(Map model, int status) {
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = model
		HTMLResponse out = getRenderer().renderTemplate(PAGE_TEMPLATE, vm)
		if(out != null) out.status = status
		out
	}

	/** The request parameters as a map: the route's model object when it is one, else the servlet request. */
	static Map requestParams(ViewModel model) {
		Map out = [:]
		Object object = model?.object
		def request = model?.request
		['window', 'level', 'source', 'q', 'collapse'].each { String name ->
			Object value = null
			if(object instanceof Map) {
				value = ((Map) object).get(name)
			}
			if(value == null && request != null) {
				value = request.getParameterValues(name)
			}
			if(value != null) out[name] = value
		}
		out
	}

	private Map settings() {
		try {
			return AbstractFinopsItemProvider.parseConfig(morpheus.getSettings(plugin).blockingGet())
		} catch(Exception e) {
			log.debug("FinOps insights: plugin settings could not be read: ${e.message}")
			return [:]
		}
	}

	private Locale browserLocale() {
		try {
			return morpheus?.webRequest?.locale ?: Locale.ENGLISH
		} catch(Exception ignored) {
			return Locale.ENGLISH
		}
	}
}
