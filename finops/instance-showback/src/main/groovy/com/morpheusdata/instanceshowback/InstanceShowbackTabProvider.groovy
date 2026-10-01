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

import com.morpheusdata.core.AbstractInstanceTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.Instance
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.sql.Connection

/**
 * "Costs" tab on the instance detail page.
 *
 * Reads the monthly invoices of the instance (internal table account_invoice, ref_type
 * Instance) over the plugin API's read-only database connection: the current month
 * (to date and forecast to month end), the three months before it, and the split into
 * compute, storage and licenses. Sums are kept per currency; different currencies are
 * never added up.
 *
 * The tab only reads the invoices of the instance it is shown on. show() returns true,
 * so visibility relies on Morpheus' own access check for the instance detail page.
 */
@Slf4j
class InstanceShowbackTabProvider extends AbstractInstanceTabProvider {

	static final String PROVIDER_CODE = 'instance-showback-tab'

	/**
	 * Tab title. getName() takes no request context, so the title is not localized; the
	 * content of the tab is.
	 */
	static final String TAB_NAME = 'Costs'

	static final int MONTHS = 4

	Plugin plugin
	MorpheusContext morpheus

	InstanceShowbackTabProvider(Plugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override MorpheusContext getMorpheus() { morpheus }
	@Override Plugin getPlugin() { plugin }
	@Override String getCode() { PROVIDER_CODE }
	@Override String getName() { TAB_NAME }

	@Override
	Boolean show(Instance instance, User user, Account account) { true }

	/** The viewer's locale, or null outside a web request. */
	protected Locale requestLocale() {
		try {
			return morpheus?.webRequest?.locale
		} catch (Throwable ignored) {
			return null
		}
	}

	/** A message from the plugin bundle in the viewer's language, else the English default. */
	protected String message(String key, String defaultText, Locale locale) {
		try {
			return morpheus?.webRequest?.getMessage(key, null, defaultText, ShowbackCalculator.localeOrDefault(locale)) ?: defaultText
		} catch (Throwable ignored) {
			return defaultText
		}
	}

	@Override
	HTMLResponse renderTemplate(Instance instance) {
		Locale locale = requestLocale()
		Calendar now = Calendar.instance
		List<String> periods = ShowbackCalculator.lastPeriods(MONTHS, now)
		List<Map> rows = []
		String masterCurrency = null
		Connection c = null
		try {
			c = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
			Sql sql = new Sql(c)
			// Grouped by month AND currency: amounts in different currencies are never added up.
			String placeholders = periods.collect { '?' }.join(', ')
			rows = sql.rows("""
				SELECT period, currency,
				       SUM(COALESCE(total_price,0)) AS price,
				       SUM(COALESCE(running_price,0)) AS running,
				       SUM(COALESCE(actual_compute_price,compute_price,0) + COALESCE(actual_memory_price,memory_price,0)) AS compute,
				       SUM(COALESCE(actual_storage_price,storage_price,0)) AS storage,
				       SUM(COALESCE(actual_license_price,license_price,0)) AS license,
				       MAX(plan_name) AS plan, MAX(last_cost_date) AS updated
				FROM account_invoice
				WHERE ref_type = 'Instance' AND ref_id = ? AND period_interval = 'month' AND period IN (${placeholders})
				GROUP BY period, currency ORDER BY period DESC, currency""".toString(), [instance.id] + periods)
				.collect { new LinkedHashMap(it) } as List<Map>
			// Fallback currency for invoices without one: the master tenant's currency.
			masterCurrency = sql.firstRow('SELECT currency FROM account WHERE master_account = 1 ORDER BY id LIMIT 1')?.currency as String
		} catch (Exception e) {
			log.error("Instance showback tab: loading invoices for instance ${instance?.id} failed: ${e.message}", e)
			return HTMLResponse.error(message("${PROVIDER_CODE}.loadError".toString(),
				'Cost data could not be loaded. The appliance log has the details.', locale))
		} finally {
			if (c) morpheus.report.releaseDatabaseConnection(c).blockingAwait()
		}
		Map model = ShowbackCalculator.buildModel(rows, periods, masterCurrency, locale, now)
		model.instanceName = instance.name
		ViewModel<Map> view = new ViewModel<>()
		view.object = model
		getRenderer().renderTemplate('hbs/instanceShowback', view)
	}
}
