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

import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

/**
 * Plugin entrypoint for Cost Threshold Approval.
 */
@Slf4j
class CostApprovalPlugin extends Plugin {

	static final String CODE = 'morpheus-cost-approval-plugin'
	static final String WEBSITE_URL = 'https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/tree/main/finops/cost-approval'

	// plugin_instance.description holds 255 characters. A longer text makes the whole
	// plugin registration fail with "Data too long for column 'description'".
	static final String DESCRIPTION = 'Approval integration that approves requests whose monthly price is at or below a configurable threshold. Requests above it, priced in another currency or without a price are rejected at once.'

	@Override
	String getCode() {
		return CODE
	}

	@Override
	void initialize() {
		this.setName('Cost Threshold Approval')
		// Shown in the plugin list; the manifest's Morpheus-Description is not read there.
		this.setDescription(DESCRIPTION)
		this.setAuthor('Thomas Gessendorfer')
		this.setWebsiteUrl(WEBSITE_URL)
		this.registerProvider(new CostApprovalProvider(this, morpheus))
	}

	@Override
	void onDestroy() {
		// nothing to clean up
	}
}
