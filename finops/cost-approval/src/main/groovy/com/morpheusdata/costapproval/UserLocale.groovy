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
package com.morpheusdata.costapproval

import groovy.sql.Sql

/**
 * The language of a Morpheus user. Morpheus takes the UI language from the user's own setting
 * (column {@code locale} of the internal table {@code user}, e.g. {@code en-US}), not from the
 * browser; the model User of plugin API 1.4.2 carries no locale, so the setting is read from the
 * table. Order: the user's setting, else the locale of the web request (the browser language),
 * else English. Only the bundles the plugin has (English, German) are used for texts; numbers
 * follow the resolved locale.
 */
class UserLocale {

	/** Parameterised; the user id is never put into the SQL text. */
	static final String USER_LOCALE_SQL = 'SELECT locale FROM user WHERE id = ?'

	/** A locale setting such as en-US, de_DE or de, or null when blank or not a language tag. */
	static Locale parse(Object setting) {
		String tag = setting?.toString()?.trim()?.replace('_', '-')
		if (!tag) {
			return null
		}
		Locale locale = Locale.forLanguageTag(tag)
		return locale?.language ? locale : null
	}

	/**
	 * The user's setting when it parses, else the browser locale, else English. A browser lookup
	 * that throws (no web request in this thread) counts as none.
	 */
	static Locale resolve(Object setting, Closure<Locale> browser) {
		Locale own = parse(setting)
		if (own) {
			return own
		}
		Locale fromBrowser = null
		try {
			fromBrowser = browser?.call()
		} catch (Exception ignored) {
			// no web request: English below
		}
		return fromBrowser ?: Locale.ENGLISH
	}

	/** The locale setting of the user, or null when the user has none or the row is missing. */
	static String setting(Sql sql, Long userId) {
		if (sql == null || userId == null) {
			return null
		}
		def row = sql.firstRow(USER_LOCALE_SQL, [userId])
		return row?.locale?.toString()
	}

	/**
	 * The id of the user who asked for an approval, or null. The model Request of plugin API
	 * 1.4.2 has no user, but Morpheus 9.0.2 puts its internal domain objects into
	 * {@code Request.refs}, and each of them points back to the internal request, which holds
	 * {@code requestByUserId}. Everything is read by property name, never cast.
	 */
	static Long requestingUserId(Object request) {
		Long id = toId(CostApprovalLogic.refValue(request, 'requestByUserId'))
		if (id != null) {
			return id
		}
		List refs = []
		try {
			refs = (CostApprovalLogic.refValue(request, 'refs') ?: []) as List
		} catch (Exception ignored) {
			return null
		}
		for (Object ref : refs) {
			try {
				id = toId(CostApprovalLogic.refValue(CostApprovalLogic.refValue(ref, 'request'), 'requestByUserId'))
			} catch (Exception ignored) {
				id = null
			}
			if (id != null) {
				return id
			}
		}
		return null
	}

	private static Long toId(Object value) {
		if (value instanceof Number) {
			return ((Number) value).longValue()
		}
		String text = value?.toString()?.trim()
		return text ==~ /\d{1,18}/ ? Long.valueOf(text) : null
	}
}
