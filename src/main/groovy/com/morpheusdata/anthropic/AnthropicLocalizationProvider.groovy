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
package com.morpheusdata.anthropic

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.LocalizationProvider
import com.morpheusdata.model.CustomLocale
import com.morpheusdata.model.User

/**
 * Adds the languages this plugin is translated into but Morpheus itself does not
 * offer to the Default Locale list in User Settings.
 *
 * Without it a Czech, Hungarian or Romanian bundle could never be chosen. Picking
 * one of them translates this plugin's form; the rest of Morpheus has no strings
 * in those languages and stays English.
 */
class AnthropicLocalizationProvider implements LocalizationProvider {

	static final List<List<String>> LOCALES = [['Czech', 'cs'], ['Hungarian', 'hu'], ['Romanian', 'ro']]

	Plugin plugin
	MorpheusContext morpheusContext

	AnthropicLocalizationProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	List<CustomLocale> getCustomLocales(User user) {
		return getCustomLocales()
	}

	@Override
	List<CustomLocale> getCustomLocales() {
		return LOCALES.collect { List<String> locale -> new CustomLocale(locale[0], locale[1]) }
	}

	@Override
	MorpheusContext getMorpheus() { return this.morpheusContext }

	@Override
	Plugin getPlugin() { return this.plugin }

	@Override
	String getCode() { return 'anthropic-claude-locales' }

	@Override
	String getName() { return 'Anthropic Claude Languages' }
}
