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

import groovy.json.JsonOutput

/**
 * What the latest log entries widget and the log history page share: the paths, the limits, the
 * JSON handed to the browser script and the whitelist for the history page's request parameters.
 *
 * Nothing here trusts the request: a server id never comes from a parameter (the ids are the
 * viewer's log sources from FinopsInsightsData.logSources), and every parameter that is read is
 * cut down to a value from a fixed list, a source id that is in that list, or a short plain text.
 */
final class FinopsLogsSupport {

	/** The history page, served by FinopsLogsController. */
	static final String PAGE_PATH = '/plugin/finops-insights/logs'
	/** Morpheus' own Logs page; it takes no query parameters. */
	static final String STANDARD_LOGS_PATH = '/monitoring/logs'
	static final String DASHBOARD_PATH = '/operations/dashboard'

	/** The widget asks for the last WIDGET_DAYS days, fetches WIDGET_FETCH entries and shows WIDGET_SHOW groups. */
	static final int WIDGET_DAYS = 7
	static final int WIDGET_FETCH = 40
	static final int WIDGET_SHOW = 10

	static final int PAGE_SIZE = 100
	static final int TEXT_MAX = 100
	static final List<String> WINDOWS = ['1h', '24h', '7d', '30d']
	static final String DEFAULT_WINDOW = '24h'
	static final List<String> LEVELS = ['INFO', 'WARN', 'ERROR', 'DEBUG']

	private FinopsLogsSupport() {}

	/** The sources as the browser script reads them: [{id, n (name), i (instance id or null)}]. */
	static String sourcesJson(List<Map> sources) {
		JsonOutput.toJson((sources ?: []).collect { Map s ->
			[id: s.id as Long, n: s.name as String, i: s.instanceId != null ? (s.instanceId as Long) : null]
		})
	}

	/** The message texts the script needs: every "logs." message of the bundle, keyed logs_xxx. */
	static Map<String, String> logLabels(Locale locale) {
		AbstractFinopsItemProvider.labels(locale).findAll { String k, String v -> k.startsWith('logs_') } as Map<String, String>
	}

	static String labelsJson(Locale locale) {
		JsonOutput.toJson(logLabels(locale))
	}

	/**
	 * The initial filter state of the history page from the request parameters. Unknown or malformed
	 * values fall back to the defaults: window one of WINDOWS, levels a non-empty subset of LEVELS,
	 * source a digit string that is one of the viewer's own source ids, q at most TEXT_MAX characters
	 * of plain text, collapse only "1", "true" or "on".
	 */
	static Map initialState(Map<String, Object> params, Collection<Long> sourceIds) {
		String window = first(params?.window)
		List<String> levels = splitList(params?.level).collect { it.toUpperCase() }.findAll { it in LEVELS }.unique()
		Long source = null
		String rawSource = first(params?.source)
		if(rawSource != null && rawSource ==~ /\d{1,18}/) {
			Long candidate = rawSource.toLong()
			if(sourceIds?.contains(candidate)) source = candidate
		}
		[window  : window in WINDOWS ? window : DEFAULT_WINDOW,
		 levels  : levels ? levels : new ArrayList<String>(LEVELS),
		 source  : source,
		 q       : plainText(first(params?.q), TEXT_MAX),
		 collapse: (first(params?.collapse)?.toLowerCase() in ['1', 'true', 'on'])]
	}

	/** The first value of a parameter that may arrive as a string, a list or an array. */
	static String first(Object value) {
		if(value == null) return null
		if(value instanceof Collection) return value ? first(((Collection) value).iterator().next()) : null
		if(value instanceof Object[]) return value ? first(((Object[]) value)[0]) : null
		value.toString()
	}

	/** Parameter values as a flat list: repeated parameters and "WARN|ERROR" or "WARN,ERROR" lists. */
	static List<String> splitList(Object value) {
		List<String> out = []
		if(value == null) return out
		if(value instanceof Collection) {
			((Collection) value).each { out.addAll(splitList(it)) }
		} else if(value instanceof Object[]) {
			((Object[]) value).each { out.addAll(splitList(it)) }
		} else {
			value.toString().split(/[|,]/).each { String s -> if(s.trim()) out << s.trim() }
		}
		out
	}

	/** One line of plain text: control characters dropped, whitespace collapsed, at most max characters. */
	static String plainText(String value, int max) {
		if(!value) return ''
		String s = value.replaceAll('[\\x00-\\x1f\\x7f-\\x9f]+', ' ').replaceAll('\\s+', ' ').trim()
		s.length() > max ? s.substring(0, max) : s
	}
}
