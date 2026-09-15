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
 *
 * Derived from the Apache 2.0 licensed HPE morpheus-copilot-plugin.
 */
package com.morpheusdata.openrouter

import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

/**
 * Plugin entrypoint for the OpenRouter LLM integration.
 * Registers the OpenRouterProvider, which implements LlmProvider against
 * OpenRouter's OpenAI-compatible API (/api/v1/chat/completions).
 */
@Slf4j
class OpenRouterPlugin extends Plugin {

	// plugin_instance.description holds 255 characters. A longer text does not get
	// truncated - the whole plugin registration fails with "Data too long for column
	// 'description'", and the appliance keeps running the previous build.
	static final String DESCRIPTION = 'OpenRouter for Morpheus AI agents over its OpenAI-compatible API: models from ' +
		'every vendor OpenRouter serves, with MCP tool use, streaming and a per-question cost footer.'

	@Override
	String getCode() {
		return 'morpheus-openrouter-plugin'
	}

	@Override
	void initialize() {
		this.setName('OpenRouter')
		// Shown in the plugin list; the manifest's Morpheus-Description is not read there.
		this.setDescription(DESCRIPTION)
		this.setAuthor('Thomas Gessendorfer')
		this.setWebsiteUrl('https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/llm/openrouter')
		OpenRouterProvider openRouterProvider = new OpenRouterProvider(this, morpheus)
		this.pluginProviders.put(openRouterProvider.code, openRouterProvider)
		this.registerProvider(new OpenRouterOptionSourceProvider(this, morpheus))
	}

	@Override
	void onDestroy() {
		// nothing to clean up
	}
}
