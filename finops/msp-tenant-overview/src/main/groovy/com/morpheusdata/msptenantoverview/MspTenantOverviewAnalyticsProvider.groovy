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
package com.morpheusdata.msptenantoverview

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.AbstractAnalyticsProvider
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.sql.Connection
import java.time.YearMonth

/**
 * Provider view per sub-tenant: users, groups, instances, vCPU, memory, servers, revenue (price),
 * cost and margin for the previous month and the current month (forecast). Visible in the master
 * tenant only (masterTenantOnly); loadData also checks that the user belongs to the master account.
 *
 * Reads Morpheus' internal tables (user, account, compute_site, instance, compute_server,
 * account_invoice) through the read-only report connection. Tested on Morpheus 9.0.2 only.
 */
@Slf4j
class MspTenantOverviewAnalyticsProvider extends AbstractAnalyticsProvider {

	static final String PROVIDER_CODE = 'msp-tenant-overview-analytics'

	// Instance invoices plus invoices of servers that belong to no instance, so nothing is counted twice.
	static final String INVOICE_FILTER = "period_interval = 'month' AND (ref_type = 'Instance' OR (ref_type = 'ComputeServer' AND instance_id IS NULL))"

	Plugin plugin
	MorpheusContext morpheus

	MspTenantOverviewAnalyticsProvider(Plugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override MorpheusContext getMorpheus() { morpheus }
	@Override Plugin getPlugin() { plugin }
	@Override String getCode() { PROVIDER_CODE }
	@Override String getName() { 'MSP Tenant Overview' }
	@Override String getCategory() { 'cost' }
	@Override String getDescription() { 'Resources, revenue, cost and margin per tenant' }
	@Override Boolean getMasterTenantOnly() { true }
	@Override Boolean getSubTenantOnly() { false }
	@Override Integer getDisplayOrder() { 51 }

	/** The viewer's locale for numbers and messages; English when the request has no locale. */
	Locale viewerLocale() {
		try {
			return morpheus?.webRequest?.locale ?: Locale.ENGLISH
		} catch(Exception ignored) {
			return Locale.ENGLISH
		}
	}

	String message(String key, String english, Locale locale) {
		try {
			return morpheus?.webRequest?.getMessage("${PROVIDER_CODE}.${key}".toString(), null, english, locale) ?: english
		} catch(Exception ignored) {
			return english
		}
	}

	@Override
	ServiceResponse<Map<String, Object>> loadData(User user, Map<String, Object> opts) {
		Locale locale = viewerLocale()
		Connection c = null
		try {
			c = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
			Sql sql = new Sql(c)
			def me = sql.firstRow('''SELECT CAST(a.master_account AS UNSIGNED) AS is_master, a.currency
				FROM user u JOIN account a ON a.id = u.account_id WHERE u.id = ?''', [user.id])
			if((me?.is_master as Integer) != 1) {
				return ServiceResponse.error(message('error.masterOnly', 'Only the provider (master) tenant can open this page.', locale))
			}
			String masterCurrency = me.currency as String
			// Current month in the JVM time zone of the appliance (documented limit).
			Map<String, String> m = MspTenantOverviewCalc.months(YearMonth.now())
			def tenants = sql.rows('''
				SELECT a.id, a.name,
				  (SELECT COUNT(*) FROM user u WHERE u.account_id = a.id AND u.enabled = 1) AS users,
				  (SELECT COUNT(*) FROM compute_site s WHERE s.account_id = a.id) AS sites,
				  (SELECT COUNT(*) FROM instance i WHERE i.account_id = a.id) AS instances,
				  (SELECT COALESCE(SUM(i.max_cores),0) FROM instance i WHERE i.account_id = a.id) AS cores,
				  (SELECT COALESCE(SUM(i.max_memory),0) FROM instance i WHERE i.account_id = a.id) AS memory,
				  (SELECT COUNT(*) FROM compute_server cs WHERE cs.account_id = a.id) AS servers
				FROM account a WHERE a.master_account = 0 AND a.active = 1 ORDER BY a.name''')
			Map<String, Map<String, BigDecimal>> totals = new TreeMap<String, Map<String, BigDecimal>>()
			long instTotal = 0, coresTotal = 0
			List items = []
			tenants.each { t ->
				List<Map> invRows = sql.rows("""SELECT currency AS cur, period,
					SUM(COALESCE(total_price,0)) AS rev, SUM(COALESCE(total_cost,0)) AS cost
					FROM account_invoice WHERE ${INVOICE_FILTER} AND account_id = ? AND period IN (?, ?)
					GROUP BY currency, period""".toString(), [t.id, m.cur, m.last])
				Map<String, Map<String, BigDecimal>> byCur = MspTenantOverviewCalc.groupByCurrency(invRows, m.cur, masterCurrency)
				MspTenantOverviewCalc.addTo(totals, byCur)
				instTotal += MspTenantOverviewCalc.num(t.instances).longValue()
				coresTotal += MspTenantOverviewCalc.num(t.cores).longValue()
				boolean first = true
				byCur.each { String ccy, Map<String, BigDecimal> x ->
					items << ([first: first, name: t.name, currency: ccy, users: t.users, sites: t.sites, instances: t.instances,
						cores: t.cores, memoryGb: MspTenantOverviewCalc.memoryGb(t.memory, locale), servers: t.servers] +
						MspTenantOverviewCalc.texts(x, locale))
					first = false
				}
			}
			List totalRows = totals.collect { String ccy, Map<String, BigDecimal> x -> [currency: ccy] + MspTenantOverviewCalc.texts(x, locale) }
			return ServiceResponse.success([items: items, count: tenants.size(), totals: totalRows,
				month: m.curShown, lastMonth: m.lastShown, instances: instTotal, cores: coresTotal])
		} catch(Exception e) {
			log.error("MSP tenant overview: loading tenant data failed: ${e.message}", e)
			return ServiceResponse.error(message('error.loadFailed',
				'Tenant data could not be loaded. This page reads internal Morpheus tables and is tested on Morpheus 9.0.2 only.', locale))
		} finally {
			if(c) morpheus.report.releaseDatabaseConnection(c).blockingAwait()
		}
	}

	@Override
	HTMLResponse renderTemplate(User user, Map<String, Object> data, Map<String, Object> opts) {
		ViewModel<Map> model = new ViewModel<>()
		model.object = data
		getRenderer().renderTemplate('hbs/mspTenantOverview', model)
	}
}
