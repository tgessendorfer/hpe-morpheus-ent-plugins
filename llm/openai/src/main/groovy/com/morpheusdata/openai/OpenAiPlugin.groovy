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
package com.morpheusdata.openai

import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

/**
 * Plugin entrypoint for the OpenAI-compatible LLM integration.
 * Registers the OpenAiProvider, which implements LlmProvider against any
 * OpenAI-compatible chat completions API (/chat/completions, /models).
 */
@Slf4j
class OpenAiPlugin extends Plugin {

	// plugin_instance.description holds 255 characters. A longer text does not get
	// truncated - the whole plugin registration fails with "Data too long for column
	// 'description'", and the appliance keeps running the previous build.
	static final String DESCRIPTION = 'Any OpenAI-compatible chat API for Morpheus AI agents - api.openai.com, OpenRouter, ' +
		'LiteLLM, vLLM, Ollama - over HTTP or HTTPS, with MCP tool use, streaming, errors in the chat and a usage footer.'

	@Override
	String getCode() {
		return 'morpheus-openai-plugin'
	}

	@Override
	void initialize() {
		this.setName('OpenAI-Compatible API')
		// Shown in the plugin list; the manifest's Morpheus-Description is not read there.
		this.setDescription(DESCRIPTION)
		this.setAuthor('Thomas Gessendorfer')
		this.setWebsiteUrl('https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/llm/openai')
		OpenAiProvider openAiProvider = new OpenAiProvider(this, morpheus)
		this.pluginProviders.put(openAiProvider.code, openAiProvider)
		this.registerProvider(new OpenAiOptionSourceProvider(this, morpheus))
	}

	@Override
	void onDestroy() {
		// nothing to clean up
	}
}
