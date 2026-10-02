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
package com.morpheusdata.socketreport

import groovy.sql.Sql

import java.util.concurrent.ConcurrentHashMap

/**
 * Language of the report content, free of Morpheus types so it can be unit tested.
 *
 * Morpheus takes its UI language from the user's own setting (column locale of table user,
 * e.g. "en-US"; GET /api/user-settings shows it), while the web request locale of the plugin
 * API is the browser's Accept-Language. Measured on 9.0.2: an English UI showed the plugin
 * content in German. The content therefore follows the user setting first:
 * <ol>
 *   <li>the user's Morpheus language setting, when it parses to a known ISO language;</li>
 *   <li>otherwise the locale of the web request (browser language);</li>
 *   <li>otherwise English.</li>
 * </ol>
 * Numbers use the resolved locale as is; texts use the bundle of its language (English or
 * German), English for any other language.
 */
class ContentLocale {

	/** The user's own language setting; parameterised, the id is never part of the text. */
	static final String USER_LOCALE_SQL = 'SELECT locale FROM user WHERE id = ?'

	static final String MESSAGE_PREFIX = 'socket-usage-report.'
	/** Languages with a message bundle in src/main/resources/i18n; the first is the default. */
	static final List<String> BUNDLE_LANGUAGES = ['en', 'de']

	private static final Set<String> ISO_LANGUAGES = Locale.getISOLanguages() as Set<String>
	private static final Map<String, Properties> BUNDLES = new ConcurrentHashMap<String, Properties>()

	/** Reads the raw locale setting of a user; null when the user has none or does not exist. */
	static Object lookupSetting(Sql sql, Long userId) {
		if (sql == null || userId == null) {
			return null
		}
		return sql.firstRow(USER_LOCALE_SQL, [userId])?.get('locale')
	}

	/**
	 * Parses a Morpheus locale setting ("en-US", "de", "de_DE"); null for null, blank text,
	 * or anything whose language is not an ISO 639 code.
	 */
	static Locale parse(Object raw) {
		String s = raw?.toString()?.trim()
		if (!s || s.length() > 35) {
			return null
		}
		Locale l = Locale.forLanguageTag(s.replace('_', '-'))
		return l.language && ISO_LANGUAGES.contains(l.language) ? l : null
	}

	/** The setting when there is one, else the browser locale, else English. */
	static Locale resolve(Locale setting, Locale browser) {
		return setting ?: browser ?: Locale.ENGLISH
	}

	/** Locale of the bundle used for texts: German for any German locale, English otherwise. */
	static Locale messageLocale(Locale locale) {
		String language = locale?.language
		return Locale.forLanguageTag(BUNDLE_LANGUAGES.contains(language) ? language : BUNDLE_LANGUAGES[0])
	}

	/** Text of a bundle key in the language of the locale; the English text when the key is missing there. */
	static String text(String key, Locale locale) {
		String language = messageLocale(locale).language
		return bundle(language).getProperty(key) ?: bundle(BUNDLE_LANGUAGES[0]).getProperty(key)
	}

	/**
	 * All report texts (keys starting with {@link #MESSAGE_PREFIX}) in the language of the
	 * locale, nested by the key's parts: "socket-usage-report.stat.total" becomes
	 * texts.stat.total, which the template reads as {{text.stat.total}}.
	 */
	static Map texts(Locale locale) {
		Map out = [:]
		bundle(BUNDLE_LANGUAGES[0]).stringPropertyNames().findAll { it.startsWith(MESSAGE_PREFIX) }.sort().each { String key ->
			List<String> parts = key.substring(MESSAGE_PREFIX.length()).tokenize('.')
			Map node = out
			parts.init().each { String part -> node = (Map) node.computeIfAbsent(part) { [:] } }
			node[parts.last()] = text(key, locale)
		}
		return out
	}

	private static Properties bundle(String language) {
		return BUNDLES.computeIfAbsent(language) { String lang ->
			String name = lang == BUNDLE_LANGUAGES[0] ? '/i18n/messages.properties' : "/i18n/messages_${lang}.properties"
			Properties p = new Properties()
			InputStream stream = ContentLocale.getResourceAsStream(name)
			if (stream != null) {
				stream.withStream { p.load(it) }
			}
			return p
		}
	}
}
