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
package com.morpheusdata.openrouter

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

/**
 * Option lists for the integration form. OptionType carries no static list of
 * choices, so even a fixed select needs a method here, named by its optionSource.
 */
@Slf4j
class OpenRouterOptionSourceProvider extends AbstractOptionSourceProvider {

	Plugin plugin
	MorpheusContext morpheusContext

	OpenRouterOptionSourceProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return plugin
	}

	@Override
	String getCode() {
		return 'openrouter-option-source'
	}

	@Override
	String getName() {
		return 'OpenRouter Options'
	}

	@Override
	List<String> getMethodNames() {
		return ['openRouterReasoningEfforts']
	}

	/**
	 * "Model default" is a real entry rather than an empty one: Morpheus 9.0.1
	 * renders plugin selects without an empty entry, so a blank value could be
	 * chosen once and never again.
	 */
	List<Map> openRouterReasoningEfforts(def args) {
		return [
			[name: 'Model default', value: OpenRouterProvider.REASONING_EFFORT_DEFAULT],
			[name: 'Low', value: 'low'],
			[name: 'Medium', value: 'medium'],
			[name: 'High', value: 'high']
		]
	}
}
