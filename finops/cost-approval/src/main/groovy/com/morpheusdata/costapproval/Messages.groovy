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

import java.text.MessageFormat

/**
 * Reads the plugin's own i18n bundles (src/main/resources/i18n). Plugin API 1.4.2 offers
 * no message lookup for provider code, and the approval calls carry no user locale, so
 * texts written into a request use {@link #DEFAULT_LOCALE}. Form labels are translated by
 * Morpheus itself through OptionType.fieldCode and helpTextI18nCode.
 */
class Messages {

	static final Locale DEFAULT_LOCALE = Locale.ENGLISH
	static final String BUNDLE = 'i18n/messages'

	private static final ResourceBundle.Control NO_FALLBACK =
		ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

	/** The message for key in locale (English when missing), with {0}, {1} ... filled in. */
	static String text(String key, Locale locale, Object... args) {
		String pattern = pattern(key, locale ?: DEFAULT_LOCALE)
		if (pattern == null) {
			return key
		}
		MessageFormat format = new MessageFormat(pattern, locale ?: DEFAULT_LOCALE)
		return format.format(args ?: new Object[0])
	}

	private static String pattern(String key, Locale locale) {
		for (Locale candidate : [locale, DEFAULT_LOCALE]) {
			try {
				ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, candidate, Messages.classLoader, NO_FALLBACK)
				if (bundle.containsKey(key)) {
					return bundle.getString(key)
				}
			} catch (MissingResourceException ignored) {
				// try the next candidate
			}
		}
		return null
	}
}
