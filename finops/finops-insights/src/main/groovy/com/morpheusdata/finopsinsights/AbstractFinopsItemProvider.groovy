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
import com.morpheusdata.core.dashboard.AbstractDashboardItemTypeProvider
import com.morpheusdata.model.DashboardItem
import com.morpheusdata.model.DashboardItemType
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * Shared behaviour of the three FinOps widgets: the item type skeleton (permission "Budgets",
 * read or full), the viewer's locale and messages, and the render path. The HTML is complete when
 * it leaves the server; the registered script is a no-op (see SCRIPT_PATH).
 *
 * Since 1.2.0 a widget can be switched off in the plugin settings (see FinopsInsightsPlugin). The
 * settings are read on every render, so a change shows on the next dashboard load, and a switched-off
 * widget renders a template that hides its own grid box and skips the invoice query. A setting that
 * is missing or cannot be read means the widget is shown.
 *
 * The permission, the access types and the script of an item type are overridable getters since
 * 1.2.0: the latest log entries widget needs the Logs permission and its own browser script. The
 * invoice path lives in renderContent so that widget can replace it.
 */
@Slf4j
abstract class AbstractFinopsItemProvider extends AbstractDashboardItemTypeProvider {

	// Users need Operations > Costing (operations-invoices) to see invoice figures.
	// 1.1.1: 'operations-budgets' instead of 'operations-invoices', so that a tenant FinOps role that
	// must not see provider invoices (owner decision 2026-10-08) still gets the widgets.
	static final String PERMISSION_CODE = 'operations-budgets'
	static final List<String> ACCESS_TYPES = ['read', 'full']

	static final String MESSAGE_PREFIX = 'finops-insights'

	// No browser code: the widgets are complete server-rendered HTML. Morpheus still resolves a
	// script per widget through the plugin's asset manifest and fails the whole dashboard without
	// one, so a no-op script is shipped (src/assets/javascripts/finops-insights.js).
	static final String SCRIPT_PATH = 'finops-insights.js'

	// What a switched-off widget renders instead of its content.
	static final String HIDDEN_TEMPLATE_PATH = 'hbs/finops-hidden-widget'

	private static final Map<String, Properties> BUNDLES = new java.util.concurrent.ConcurrentHashMap<String, Properties>()

	Plugin plugin
	MorpheusContext morpheus
	FinopsInsightsData data

	AbstractFinopsItemProvider(Plugin plugin, MorpheusContext morpheus, FinopsInsightsData data) {
		this.plugin = plugin
		this.morpheus = morpheus
		this.data = data
	}

	@Override MorpheusContext getMorpheus() { morpheus }
	@Override Plugin getPlugin() { plugin }

	/** The permission code a viewer needs (an item is only rendered when the viewer holds it). */
	String getPermissionCode() { PERMISSION_CODE }
	/** The access levels of that permission that are enough. */
	List<String> getAccessTypes() { ACCESS_TYPES }
	/** The script the dashboard loads for this item type (relative to src/assets/javascripts). */
	String getScriptPath() { SCRIPT_PATH }

	abstract String getTitle()
	abstract String getUiSize()
	abstract String getTemplatePath()
	/** fieldName of the plugin setting that hides this widget (FinopsInsightsPlugin.SETTING_HIDE_*). */
	abstract String getSettingKey()
	/** Fills the template model from the loaded data. */
	abstract Map model(Map loaded, Locale locale)

	@Override
	DashboardItemType getDashboardItemType() {
		DashboardItemType rtn = new DashboardItemType()
		rtn.name = getName()
		rtn.code = getCode()
		rtn.category = 'cost'
		rtn.title = getTitle()
		rtn.description = getTitle()
		rtn.uiSize = getUiSize()
		rtn.templatePath = getTemplatePath()
		rtn.scriptPath = getScriptPath()
		rtn.enabled = true
		try {
			rtn.permission = morpheus.permission.getByCode(getPermissionCode()).blockingGet()
		} catch(Exception e) {
			log.warn("FinOps insights: permission ${getPermissionCode()} could not be loaded: ${e.message}")
		}
		rtn.accessTypes = getAccessTypes()
		return rtn
	}

	/** The browser's locale from the web request; English when there is no request. */
	Locale browserLocale() {
		try {
			return morpheus?.webRequest?.locale ?: Locale.ENGLISH
		} catch(Exception ignored) {
			return Locale.ENGLISH
		}
	}

	/** Messages exist in English and German only; every other language gets English. */
	static Locale messageLocale(Locale locale) {
		locale?.language == 'de' ? Locale.GERMAN : Locale.ENGLISH
	}

	private static Properties bundle(Locale locale) {
		String path = messageLocale(locale) == Locale.GERMAN ? 'i18n/messages_de.properties' : 'i18n/messages.properties'
		BUNDLES.computeIfAbsent(path) { String p ->
			Properties props = new Properties()
			try {
				InputStream is = AbstractFinopsItemProvider.classLoader.getResourceAsStream(p)
				if(is != null) is.withCloseable { props.load(it) }
			} catch(Exception e) {
				log.debug("FinOps insights: bundle ${p} could not be read: ${e.message}")
			}
			props
		}
	}

	static String message(String key, Locale locale) {
		String full = "${MESSAGE_PREFIX}.${key}".toString()
		bundle(locale).getProperty(full) ?: bundle(Locale.ENGLISH).getProperty(full) ?: key
	}

	/** Every message of the bundle, keyed with '_' instead of '.' ('kpi_revenue'), for the template. */
	static Map<String, String> labels(Locale locale) {
		Map<String, String> out = [:]
		Properties en = bundle(Locale.ENGLISH)
		en.stringPropertyNames().findAll { it.startsWith(MESSAGE_PREFIX + '.') }.each { String full ->
			String key = full.substring(MESSAGE_PREFIX.length() + 1)
			out[key.replace('.', '_')] = message(key, locale)
		}
		out
	}

	/** True for what a ticked Morpheus checkbox or a JSON boolean carries: 'on', 'true', 'yes', '1', true. */
	static boolean flagOn(Object value) {
		if(value == null) return false
		if(value instanceof Boolean) return (Boolean) value
		value.toString().trim().toLowerCase() in ['on', 'true', 'yes', '1']
	}

	/** The stored plugin settings; an empty map for null, blank or anything that is not a JSON object. */
	static Map parseConfig(String json) {
		if(!json?.trim()) return [:]
		try {
			def parsed = new JsonSlurper().parseText(json)
			return parsed instanceof Map ? (parsed as Map) : [:]
		} catch(Exception e) {
			log.debug("FinOps insights: plugin settings are not a JSON object: ${e.message}")
			return [:]
		}
	}

	/** The plugin settings as stored. The core keeps no cache, so every render sees the latest save. */
	Map pluginConfig() {
		try {
			return parseConfig(morpheus.getSettings(plugin).blockingGet())
		} catch(Exception e) {
			log.debug("FinOps insights: plugin settings could not be read: ${e.message}")
			return [:]
		}
	}

	/** True when the administrator ticked this widget's "Hide ..." setting. Unreadable means shown. */
	boolean isHidden() {
		flagOn(pluginConfig()[getSettingKey()])
	}

	/** A switched-off widget: no data, no query; the template hides the grid box (or shows one muted line). */
	HTMLResponse renderHidden(Locale locale) {
		ViewModel<Map> model = new ViewModel<>()
		model.object = [title: message(getTitleKey(), locale), hint: message('hint.hidden', locale)]
		getRenderer().renderTemplate(HIDDEN_TEMPLATE_PATH, model)
	}

	@Override
	HTMLResponse renderDashboardItem(DashboardItem dashboardItem, Map<String, Object> opts) {
		Locale locale = browserLocale()
		if(isHidden()) {
			return renderHidden(locale)
		}
		return renderContent(opts, locale)
	}

	/** The invoice figures this widget shows; the KPI strip also asks for the deactivated sub-tenants. */
	protected Map loadFigures(Map<String, Object> opts, Locale locale) {
		data.load(opts, locale)
	}

	/** The visible widget: loads the invoice figures, fills the template model, renders the template. */
	HTMLResponse renderContent(Map<String, Object> opts, Locale locale) {
		Map loaded = loadFigures(opts, locale)
		Locale shown = (loaded.locale as Locale) ?: locale
		Map m = [ok: loaded.ok, reason: loaded.reason, t: labels(shown), isMaster: loaded.isMaster, title: message(getTitleKey(), shown)]
		if(loaded.ok) {
			try {
				m.putAll(model(loaded, shown))
			} catch(Exception e) {
				log.error("FinOps insights: building the ${getCode()} widget failed: ${e.message}", e)
				m.ok = false
				m.reason = 'loadFailed'
			}
		}
		if(!m.ok) m.hint = message('hint.' + (m.reason ?: 'loadFailed'), shown)
		ViewModel<Map> model = new ViewModel<>()
		model.object = m
		getRenderer().renderTemplate(getTemplatePath(), model)
	}

	/** Message key of the widget title. */
	abstract String getTitleKey()
}
