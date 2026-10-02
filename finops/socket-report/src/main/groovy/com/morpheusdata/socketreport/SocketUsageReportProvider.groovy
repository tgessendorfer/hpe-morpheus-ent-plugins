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
package com.morpheusdata.socketreport

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.sql.Connection

/**
 * Socket and license consumption per cloud and tenant, counted the way the license page
 * (GET /api/license, currentUsage) counted on Morpheus 9.0.2 - see {@link SocketMath}.
 * The license page stays authoritative; the report shows where the sockets come from.
 * masterOnly, because the query reads the servers of every tenant.
 */
@Slf4j
class SocketUsageReportProvider extends AbstractReportProvider {

	static final String PROVIDER_CODE = 'socket-usage-report'
	static final String OPTION_VMS_PER_SOCKET = "${PROVIDER_CODE}-vms-per-socket"
	static final String OPTION_DEFAULT_HOST_SOCKETS = "${PROVIDER_CODE}-default-host-sockets"
	static final String FIELD_VMS_PER_SOCKET = 'vmsPerSocket'
	static final String FIELD_DEFAULT_HOST_SOCKETS = 'defaultHostSockets'

	Plugin plugin
	MorpheusContext morpheus

	SocketUsageReportProvider(Plugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override MorpheusContext getMorpheus() { morpheus }
	@Override Plugin getPlugin() { plugin }
	@Override String getCode() { PROVIDER_CODE }
	@Override String getName() { 'Socket Usage' }
	@Override String getDescription() { 'Sockets of hypervisor hosts and of VMs without a discovered host, per cloud and tenant' }
	@Override String getCategory() { 'inventory' }
	@Override Boolean getOwnerOnly() { false }
	@Override Boolean getMasterOnly() { true }
	@Override Boolean getSupportsAllZoneTypes() { true }

	@Override
	List<OptionType> getOptionTypes() {
		List<OptionType> optionTypes = [
			new OptionType(code: OPTION_VMS_PER_SOCKET, name: 'VMs per Socket', fieldName: FIELD_VMS_PER_SOCKET,
				fieldContext: 'config', fieldLabel: 'VMs per Socket', inputType: OptionType.InputType.NUMBER,
				displayOrder: 0, required: false, defaultValue: SocketMath.plainOption(SocketMath.DEFAULT_VMS_PER_SOCKET),
				helpText: 'VMs in clouds without a discovered hypervisor host count as a share of a socket. Measured on Morpheus 9.0.2: 15 VMs = 1 socket. May change with other versions.'),
			new OptionType(code: OPTION_DEFAULT_HOST_SOCKETS, name: 'Sockets per Host Without Value', fieldName: FIELD_DEFAULT_HOST_SOCKETS,
				fieldContext: 'config', fieldLabel: 'Sockets per Host Without Value', inputType: OptionType.InputType.NUMBER,
				displayOrder: 1, required: false, defaultValue: SocketMath.plainOption(SocketMath.DEFAULT_HOST_SOCKETS),
				helpText: 'Sockets counted for a hypervisor host that reports no socket count. Measured on Morpheus 9.0.2: 2. May change with other versions.')
		]
		// Labels and help texts resolve through the plugin's i18n bundles in
		// src/main/resources/i18n, in the language Morpheus picks for the form; the literal texts above
		// stay as the fallback.
		optionTypes.each { OptionType optionType ->
			optionType.fieldCode = "${optionType.code}.label".toString()
			optionType.helpTextI18nCode = "${optionType.code}.help".toString()
		}
		return optionTypes
	}

	@Override
	ServiceResponse validateOptions(Map opts) {
		Map<String, String> errors = [:]
		[FIELD_VMS_PER_SOCKET, FIELD_DEFAULT_HOST_SOCKETS].each { String field ->
			Object raw = SocketMath.option(opts, field)
			if (SocketMath.isOutOfRange(raw)) {
				errors[field] = message("${PROVIDER_CODE}.error.outOfRange", 'Enter a number up to 1,000,000 with at most 3 decimal places, without exponent notation, or leave the field empty for the default.')
			} else if (!SocketMath.isValidOption(raw)) {
				errors[field] = message("${PROVIDER_CODE}.error.positiveNumber", 'Enter a number greater than 0, or leave the field empty for the default.')
			}
		}
		if (errors) {
			return ServiceResponse.error(errors.values().first(), errors)
		}
		return ServiceResponse.success()
	}

	/*
	 * Reads internal tables (compute_server, compute_server_type, compute_zone,
	 * compute_zone_type, account). Tested on 9.0.2 only; a schema change in a later
	 * appliance version makes the report fail with a log entry instead of wrong numbers.
	 */
	static final String SQL = '''
		SELECT z.id AS cloud_id, z.name AS cloud, zt.name AS cloud_type, zt.cloud AS category,
		  a.id AS tenant_id, a.name AS tenant,
		  CAST(a.master_account AS UNSIGNED) AS is_master,
		  SUM(CASE WHEN h.is_host = 1 THEN 1 ELSE 0 END) AS hosts,
		  SUM(CASE WHEN h.is_host = 1 AND h.max_sockets IS NOT NULL THEN h.max_sockets ELSE 0 END) AS host_sockets_known,
		  SUM(CASE WHEN h.is_host = 1 AND h.max_sockets IS NULL THEN 1 ELSE 0 END) AS hosts_default,
		  SUM(CASE WHEN h.is_host = 0 AND zh.zone_hosts = 0 THEN 1 ELSE 0 END) AS vms_without_host,
		  SUM(CASE WHEN h.is_host = 0 AND zh.zone_hosts > 0 THEN 1 ELSE 0 END) AS vms_on_hosts
		FROM (SELECT cs.id, cs.zone_id, cs.account_id, cs.max_sockets,
		        CASE WHEN cst.vm_hypervisor = 1 OR cst.node_type LIKE '%Metal' THEN 1 ELSE 0 END AS is_host
		      FROM compute_server cs LEFT JOIN compute_server_type cst ON cst.id = cs.compute_server_type_id) h
		JOIN compute_zone z ON z.id = h.zone_id
		LEFT JOIN compute_zone_type zt ON zt.id = z.zone_type_id
		JOIN account a ON a.id = h.account_id
		JOIN (SELECT cs.zone_id, SUM(CASE WHEN cst.vm_hypervisor = 1 OR cst.node_type LIKE '%Metal' THEN 1 ELSE 0 END) AS zone_hosts
		      FROM compute_server cs LEFT JOIN compute_server_type cst ON cst.id = cs.compute_server_type_id GROUP BY cs.zone_id) zh ON zh.zone_id = h.zone_id
		GROUP BY z.id, z.name, zt.name, zt.cloud, a.id, a.name, a.master_account
		ORDER BY z.name, a.master_account DESC, a.name'''

	@Override
	void process(ReportResult reportResult) {
		morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.generating).blockingAwait()
		try {
			Map config = reportResult.configMap
			BigDecimal vmsPerSocket = optionOrDefault(reportResult, config, FIELD_VMS_PER_SOCKET, SocketMath.DEFAULT_VMS_PER_SOCKET)
			BigDecimal defaultHostSockets = optionOrDefault(reportResult, config, FIELD_DEFAULT_HOST_SOCKETS, SocketMath.DEFAULT_HOST_SOCKETS)
			List<GroovyRowResult> rows = []
			withDbConnection { Connection c -> rows = new Sql(c).rows(SQL) }
			Map summary = SocketMath.summarize(rows as List<Map>, vmsPerSocket, defaultHostSockets)

			Long order = 0
			List<ReportResultRow> out = []
			summary.rows.each { Map row ->
				out << new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: order++, dataMap: row)
			}
			summary.tenants.each { Map row ->
				out << new ReportResultRow(section: ReportResultRow.SECTION_HEADER, displayOrder: order++, dataMap: row)
			}
			out << new ReportResultRow(section: ReportResultRow.SECTION_FOOTER, displayOrder: order++, dataMap: summary.footer)
			out.collate(50).each { morpheus.report.appendResultRows(reportResult, it).blockingGet() }
			morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.ready).blockingAwait()
		} catch (Exception e) {
			log.error("Socket usage report ${reportResult?.id} failed. It reads internal tables tested on Morpheus 9.0.2 only; " +
				"a newer appliance version may have changed them: ${e.message}", e)
			morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
		}
	}

	/*
	 * A stored option that validateOptions would reject (a report created before 1.1.1, or
	 * through a path that skips validation) falls back to the default with a log entry,
	 * so the report never works with an unbounded value.
	 */
	private static BigDecimal optionOrDefault(ReportResult reportResult, Map config, String field, BigDecimal fallback) {
		Object raw = SocketMath.option(config, field)
		if (!SocketMath.isValidOption(raw)) {
			String shown = raw.toString().trim()
			log.warn("Socket usage report ${reportResult?.id}: option ${field} '${shown.length() > 40 ? shown.take(40) + '...' : shown}' " +
				"is not a number greater than 0 and up to ${SocketMath.plainOption(SocketMath.MAX_OPTION)} with at most " +
				"${SocketMath.MAX_OPTION_FRACTION_DIGITS} decimal places; using the default ${SocketMath.plainOption(fallback)}.")
		}
		return SocketMath.positiveOr(raw, fallback)
	}

	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		Locale locale = contentLocale(reportResult)
		ViewModel<Map> model = new ViewModel<>()
		model.object = [
			text   : ContentLocale.texts(locale),
			rows   : reportRowsBySection.main?.collect { SocketMath.localize(it.dataMap, locale) } ?: [],
			tenants: reportRowsBySection.header?.collect { SocketMath.localize(it.dataMap, locale) } ?: [],
			footer : SocketMath.localize(reportRowsBySection.footer?.getAt(0)?.dataMap ?: [:], locale)
		]
		return getRenderer().renderTemplate('hbs/socketUsageReport', model)
	}

	/*
	 * Language of the rendered report: the Morpheus language setting of the user, then the
	 * browser language of the request, then English (see ContentLocale). The template gets its
	 * texts from the model instead of the {{i18n}} helper, because that helper always uses the
	 * request locale. Plugin API 1.4.2 hands renderTemplate no viewing user, so the user is the
	 * one who ran the report (ReportResult.createdBy); without one the browser language applies
	 * as before 1.2.0.
	 */
	Locale contentLocale(ReportResult reportResult) {
		Locale browser = viewerLocale()
		Long userId = null
		try {
			userId = reportResult?.createdBy?.id
		} catch (Exception ignored) {
			// no user on the result: the browser language applies
		}
		return ContentLocale.resolve(userId != null ? userLocale(userId) : null, browser)
	}

	/** The parsed Morpheus language setting of a user; null when it is empty, unparsable or cannot be read. */
	Locale userLocale(Long userId) {
		Object raw
		try {
			raw = readUserSetting(userId)
		} catch (Exception e) {
			log.debug("Socket usage report: language setting of user ${userId} not readable, using the browser language: ${e.message}")
			return null
		}
		Locale parsed = ContentLocale.parse(raw)
		if (parsed == null && raw != null && raw.toString().trim()) {
			log.debug("Socket usage report: language setting of user ${userId} is not a language tag, using the browser language.")
		}
		return parsed
	}

	/** The raw language setting of a user, read over the report's read-only database connection. */
	Object readUserSetting(Long userId) {
		Object raw = null
		withDbConnection { Connection c -> raw = ContentLocale.lookupSetting(new Sql(c), userId) }
		return raw
	}

	/** Locale of the viewing user's request (browser language); English when there is no request or no locale. */
	Locale viewerLocale() {
		try {
			return morpheus?.webRequest?.locale ?: Locale.ENGLISH
		} catch (Exception ignored) {
			return Locale.ENGLISH
		}
	}

	/*
	 * Validation messages are shown while a report is created; validateOptions gets no user,
	 * so they follow the browser language as before 1.2.0.
	 */
	private String message(String key, String fallback) {
		try {
			def web = morpheus?.webRequest
			return web ? (web.getMessage(key, null, fallback, viewerLocale()) ?: fallback) : fallback
		} catch (Exception ignored) {
			return fallback
		}
	}
}
