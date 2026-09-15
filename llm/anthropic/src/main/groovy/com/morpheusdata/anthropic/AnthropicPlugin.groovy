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
package com.morpheusdata.anthropic

import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

/**
 * Plugin entrypoint for the Anthropic Claude LLM Engine integration.
 * Registers the AnthropicProvider which implements LlmProvider against the
 * native Anthropic Messages API (/v1/messages).
 */
@Slf4j
class AnthropicPlugin extends Plugin {

	// plugin_instance.description holds 255 characters. A longer text does not get
	// truncated - the whole plugin registration fails with "Data too long for column
	// 'description'", and the appliance keeps running the previous build.
	static final String DESCRIPTION = 'Anthropic Claude for Morpheus AI agents via the native Messages API - direct, ' +
		'or through OpenRouter with Claude models only. Other OpenRouter models belong to a separate OpenRouter plugin.'

	@Override
	String getCode() {
		return 'morpheus-anthropic-plugin'
	}

	@Override
	void initialize() {
		this.setName('Anthropic Claude')
		// Shown in the plugin list; the manifest's Morpheus-Description is not read there.
		this.setDescription(DESCRIPTION)
		this.setAuthor('Thomas Gessendorfer')
		this.setWebsiteUrl('https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/llm/anthropic')
		AnthropicProvider anthropicProvider = new AnthropicProvider(this, morpheus)
		this.pluginProviders.put(anthropicProvider.code, anthropicProvider)
	}

	@Override
	void onDestroy() {
		// nothing to clean up
	}
}
