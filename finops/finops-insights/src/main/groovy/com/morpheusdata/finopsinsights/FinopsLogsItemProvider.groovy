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
import com.morpheusdata.views.ViewModel

/**
 * The latest log entries of the viewer's servers (half width), with a link to the full log history
 * page. It replaces the standard Log History widget, which only looks at today and at a very small
 * default scope: while this widget is shown it hides the standard one with a CSS rule, and while
 * the plugin setting "Hide Latest Log Entries" is ticked it renders nothing and the standard
 * widget stays.
 *
 * No plugin service returns log entries, so the entries are fetched by the browser script
 * (finops-insights-logs.js) from the platform's log API. The server only decides which servers
 * that may be: the viewer's log sources from the database (see FinopsInsightsData.logSources).
 *
 * Permission: Logs, read or full. The standard widget also admits "user", the level that limits a
 * viewer to the instances of his own; this widget lists the servers of the whole tenant, so that
 * level does not get it.
 */
class FinopsLogsItemProvider extends AbstractFinopsItemProvider {

	static final String CODE = 'finops-insights-logs'
	static final String LOGS_PERMISSION_CODE = 'logs'
	static final List<String> LOGS_ACCESS_TYPES = ['read', 'full']
	static final String LOGS_SCRIPT_PATH = 'finops-insights-logs.js'

	FinopsLogsItemProvider(Plugin plugin, MorpheusContext morpheus, FinopsInsightsData data) {
		super(plugin, morpheus, data)
	}

	@Override String getCode() { CODE }
	@Override String getName() { 'FinOps latest log entries' }
	@Override String getTitle() { 'Latest log entries' }
	@Override String getTitleKey() { 'logs.title' }
	@Override String getUiSize() { 'lg' }
	@Override String getTemplatePath() { 'hbs/finops-logs-widget' }
	@Override String getSettingKey() { FinopsInsightsPlugin.SETTING_HIDE_LOGS }
	@Override String getPermissionCode() { LOGS_PERMISSION_CODE }
	@Override List<String> getAccessTypes() { LOGS_ACCESS_TYPES }
	@Override String getScriptPath() { LOGS_SCRIPT_PATH }

	/** The log sources of the viewer; no invoice data is read. */
	@Override
	HTMLResponse renderContent(Map<String, Object> opts, Locale locale) {
		Map loaded = data.logSources(opts, locale)
		Locale shown = (loaded.locale as Locale) ?: locale
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = model(loaded, shown)
		getRenderer().renderTemplate(getTemplatePath(), vm)
	}

	/** The template model: state, texts, and the sources and labels as JSON for the browser script. */
	@Override
	Map model(Map loaded, Locale locale) {
		List<Map> sources = (loaded.sources ?: []) as List<Map>
		boolean ok = loaded.ok == true
		String hint = null
		if(!ok) {
			hint = message(loaded.reason == 'noViewer' ? 'logs.noViewer' : 'logs.loadFailed', locale)
		} else if(sources.isEmpty()) {
			hint = message('logs.noSources', locale)
		}
		[ok         : ok,
		 hasSources : ok && !sources.isEmpty(),
		 hint       : hint,
		 title      : message(getTitleKey(), locale),
		 t          : FinopsLogsSupport.logLabels(locale),
		 labelsJson : FinopsLogsSupport.labelsJson(locale),
		 sourcesJson: FinopsLogsSupport.sourcesJson(sources),
		 historyUrl : FinopsLogsSupport.PAGE_PATH,
		 standardUrl: FinopsLogsSupport.STANDARD_LOGS_PATH,
		 windowDays : FinopsLogsSupport.WIDGET_DAYS,
		 fetchMax   : FinopsLogsSupport.WIDGET_FETCH,
		 show       : FinopsLogsSupport.WIDGET_SHOW]
	}
}
