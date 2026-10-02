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
package com.morpheusdata.instanceshowback

import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Language of the tab content: which locale counts, and the messages in that locale.
 *
 * Morpheus takes its UI language from the user's own setting (column locale of the user
 * table, for example en-US), while the web request of the plugin API carries the browser's
 * Accept-Language. The tab follows the Morpheus setting, so plugin content and Morpheus UI
 * are in the same language:
 * 1. the viewing user's locale setting,
 * 2. else the locale of the web request (the browser language),
 * 3. else English.
 *
 * Messages exist in English (default) and German. They are read from the plugin's own
 * bundles, not through the web request, because Morpheus' message source would pick the
 * language of the browser or of the appliance JVM for a locale the plugin has no bundle for.
 */
@Slf4j
class ShowbackLocale {

	/** The viewing user's language setting. Parameterised; the user id is never put into the text. */
	static final String USER_LOCALE_SQL = 'SELECT locale FROM user WHERE id = ?'

	/** Last resort when there is neither a user setting nor a web request. */
	static final Locale DEFAULT_LOCALE = Locale.ENGLISH

	/** Prefix of every key in the bundles; the template model uses the keys without it. */
	static final String KEY_PREFIX = InstanceShowbackTabProvider.PROVIDER_CODE + '.'

	static final String BUNDLE = 'i18n/messages'

	private static final Set<String> ISO_LANGUAGES = Locale.getISOLanguages() as Set<String>

	private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>()

	/**
	 * A Morpheus locale setting (en-US, de, en_US) as a Locale, or null when it is missing,
	 * blank or not a known language.
	 */
	static Locale parse(def setting) {
		String t = setting?.toString()?.trim()?.replace('_', '-')
		if (!t) return null
		Locale l = Locale.forLanguageTag(t)
		(l.language && ISO_LANGUAGES.contains(l.language)) ? l : null
	}

	/** The content locale: user setting, else request locale, else English. */
	static Locale resolve(def userSetting, Locale requestLocale) {
		parse(userSetting) ?: requestLocale ?: DEFAULT_LOCALE
	}

	/**
	 * The content locale of the user with the given id, read over the given connection.
	 * Without a user id, or when the lookup fails, the request locale counts; a failed
	 * lookup is logged on one debug line and never breaks the tab.
	 */
	static Locale forUser(Sql sql, def userId, Locale requestLocale) {
		if (userId == null || sql == null) return resolve(null, requestLocale)
		def setting = null
		try {
			setting = sql.firstRow(USER_LOCALE_SQL, [userId])?.locale
		} catch (Exception e) {
			log.debug("Instance showback tab: locale setting of user ${userId} not readable, using the request locale: ${e.message}")
		}
		resolve(setting, requestLocale)
	}

	/** The bundle language for a locale: German for de, English for everything else. */
	static Locale bundleLocale(Locale locale) {
		locale?.language == 'de' ? Locale.GERMAN : Locale.ENGLISH
	}

	/**
	 * All messages in the bundle language of the locale, keyed without the provider prefix
	 * (monthToDate, forecast, ...). German entries override the English defaults; a key
	 * missing in German stays English.
	 */
	static Map<String, String> messages(Locale locale) {
		String lang = bundleLocale(locale).language
		CACHE.computeIfAbsent(lang) { String l ->
			Properties p = load("${BUNDLE}.properties")
			if (l != 'en') p.putAll(load("${BUNDLE}_${l}.properties"))
			Map<String, String> m = [:]
			p.stringPropertyNames().each { String k ->
				if (k.startsWith(KEY_PREFIX)) m[k.substring(KEY_PREFIX.length())] = p.getProperty(k)
			}
			m.asImmutable()
		}
	}

	/** One message in the bundle language of the locale, else the English default given. */
	static String text(String key, String defaultText, Locale locale) {
		try {
			return messages(locale)[key] ?: defaultText
		} catch (Throwable ignored) {
			return defaultText
		}
	}

	private static Properties load(String path) {
		Properties p = new Properties()
		InputStream is = ShowbackLocale.classLoader.getResourceAsStream(path)
		if (is != null) is.withCloseable { p.load(it) }
		p
	}
}
