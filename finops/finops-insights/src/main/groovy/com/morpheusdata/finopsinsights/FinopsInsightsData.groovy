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
package com.morpheusdata.finopsinsights

import com.morpheusdata.core.MorpheusContext
import groovy.sql.Sql
import groovy.util.logging.Slf4j

import java.sql.Connection
import java.time.YearMonth

/**
 * Loads the invoice figures the three widgets share and resolves the viewer.
 *
 * Scope: a user of the master tenant sees the invoices of all active sub-tenants (the provider
 * view: revenue = tenant price, cost = purchase cost, margin). A user of a sub-tenant sees only
 * the invoices of that tenant (spend view: price only). When the viewer cannot be resolved from
 * the request options, nothing is loaded and the widgets show a hint instead of data.
 *
 * Reads Morpheus' internal tables (user, account, account_invoice, compute_zone, container)
 * through the read-only report connection. Tested on Morpheus 9.0.2 only.
 */
@Slf4j
class FinopsInsightsData {

	// Instance invoices plus invoices of servers that belong to no instance, so nothing is counted twice
	// (same rule as the MSP Tenant Overview plugin).
	static final String INVOICE_FILTER = "i.period_interval = 'month' AND (i.ref_type = 'Instance' OR (i.ref_type = 'ComputeServer' AND i.instance_id IS NULL" +
		" AND NOT EXISTS (SELECT 1 FROM container ct WHERE ct.server_id = i.ref_id AND ct.instance_id IS NOT NULL)))"

	static final String VIEWER_SQL = '''SELECT u.id AS user_id, u.locale, a.id AS account_id, a.name AS account_name, a.currency,
		CAST(a.master_account AS UNSIGNED) AS is_master FROM user u JOIN account a ON a.id = u.account_id WHERE u.id = ?'''

	// Servers that can have log entries: agent installed, logging not switched off, not removed. The
	// instance is the container.instance_id of the server when there is exactly one (a host that carries
	// several instances, or none, gets no instance: its line links to the server page). The instance's
	// tenant comes along, because the UI opens an instance only for its own tenant: a master tenant
	// user who opens a sub-tenant's instance lands on the instance list (measured on 9.1.0), while the
	// server page opens. Such a line links to the server page as well.
	// Newest activity first, so that the cap keeps the servers that are alive.
	static final int LOG_SOURCE_LIMIT = 50
	static final String LOG_SOURCES_BASE_SQL = '''SELECT cs.id AS id, cs.name AS name,
		(SELECT CASE WHEN COUNT(DISTINCT c.instance_id) = 1 THEN MIN(c.instance_id) END
			FROM container c WHERE c.server_id = cs.id AND c.instance_id IS NOT NULL) AS instance_id,
		(SELECT MIN(i.account_id) FROM container c JOIN instance i ON i.id = c.instance_id
			WHERE c.server_id = cs.id) AS instance_account_id
		FROM compute_server cs
		WHERE cs.agent_installed = 1 AND (cs.enable_logs IS NULL OR cs.enable_logs = 1)
		AND (cs.status IS NULL OR cs.status <> 'removed')'''
	static final String LOG_SOURCES_ORDER_SQL = " ORDER BY cs.last_updated DESC, cs.id DESC LIMIT ${LOG_SOURCE_LIMIT}"
	/** The master tenant sees every tenant's servers; a sub-tenant only its own. */
	static final String LOG_SOURCES_MASTER_SQL = LOG_SOURCES_BASE_SQL + LOG_SOURCES_ORDER_SQL
	static final String LOG_SOURCES_TENANT_SQL = LOG_SOURCES_BASE_SQL + ' AND cs.account_id = ?' + LOG_SOURCES_ORDER_SQL

	// Request option keys that may carry the viewer. Measured on 9.0.2: the dashboard controller
	// hands the widget an empty option map, so the viewer is taken from the request's security
	// context (securityContextUserId). The keys are only a fallback for when there is no context.
	static final List<String> USER_ID_KEYS = ['userId', 'user_id', 'currentUserId']
	static final List<String> USER_OBJECT_KEYS = ['user', 'currentUser', 'viewer']
	static final String SECURITY_CONTEXT_HOLDER = 'org.springframework.security.core.context.SecurityContextHolder'

	MorpheusContext morpheus

	FinopsInsightsData(MorpheusContext morpheus) {
		this.morpheus = morpheus
	}

	/** Seam for tests. */
	protected Sql newSql(Connection c) {
		new Sql(c)
	}

	/** The user id from the request options, or null. */
	static Long userIdFrom(Map<String, Object> opts) {
		if(opts == null) return null
		for(String k : USER_OBJECT_KEYS) {
			def u = opts[k]
			def id = (u instanceof Map) ? u.id : (u?.hasProperty('id') ? u.id : null)
			if(id != null && id.toString().isLong()) return id.toString().toLong()
		}
		for(String k : USER_ID_KEYS) {
			def v = opts[k]
			if(v != null && v.toString().isLong()) return v.toString().toLong()
		}
		return null
	}

	/**
	 * The signed-in user's id from the Spring Security context of the current request thread, or
	 * null. Widgets render inside the HTTP request, so the context is set; the class is loaded by
	 * name so the plugin compiles without a Spring dependency.
	 */
	static Long securityContextUserId() {
		try {
			ClassLoader cl = Thread.currentThread().contextClassLoader ?: FinopsInsightsData.classLoader
			Class holder = Class.forName(SECURITY_CONTEXT_HOLDER, true, cl)
			def context = holder.getMethod('getContext').invoke(null)
			def principal = context?.authentication?.principal
			def id = principal?.hasProperty('id') ? principal.id : null
			return (id != null && id.toString().isLong()) ? id.toString().toLong() : null
		} catch(Throwable t) {
			log.debug("FinOps insights: security context not readable: ${t.message}")
			return null
		}
	}

	/** The viewer: the signed-in user of the request's security context first, the options only without one. */
	static Long resolveUserId(Map<String, Object> opts) {
		securityContextUserId() ?: userIdFrom(opts)
	}

	/** Seam for tests: the signed-in user of the current request's security context, or null. */
	protected Long securityUserId() {
		securityContextUserId()
	}

	/** The viewer of the current request: the security context first, the options only without one. */
	protected Long viewerId(Map<String, Object> opts) {
		securityUserId() ?: userIdFrom(opts)
	}

	/**
	 * Everything the widgets need, computed once per render:
	 *  ok, viewer (isMaster, accountName), currency, otherCurrencies, locale,
	 *  months (YearMonth list), periods, trend (per period rev/cost/margin), current, previous,
	 *  byCloud, byTenant (master only), tenants, instances.
	 */
	Map load(Map<String, Object> opts, Locale locale) {
		Map out = [ok: false, locale: locale]
		Long userId = viewerId(opts)
		if(userId == null) {
			log.warn("FinOps insights: no viewer in the request options (keys: ${opts?.keySet()}) and no security context")
			out.reason = 'noViewer'
			return out
		}
		Connection c = null
		try {
			c = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
			Sql sql = newSql(c)
			def viewer = sql.firstRow(VIEWER_SQL, [userId])
			if(viewer == null) { out.reason = 'noViewer'; return out }
			boolean isMaster = (viewer.is_master as Integer) == 1
			Locale userLocale = parseLocale(viewer.locale) ?: locale
			out.locale = userLocale
			out.isMaster = isMaster
			out.accountName = viewer.account_name as String
			YearMonth now = YearMonth.now()
			List<YearMonth> months = FinopsInsightsCalc.trendMonths(now)
			List<String> periods = FinopsInsightsCalc.periods(months)
			String inList = periods.collect { '?' }.join(',')
			String scope = isMaster ? 'a.master_account = 0 AND a.active = 1' : 'a.id = ?'
			List params = isMaster ? periods : [viewer.account_id] + periods
			List<Map> rows = sql.rows("""SELECT i.account_id AS account, a.name AS accountName, i.zone_id AS zone,
				COALESCE(z.name, '-') AS zoneName, i.period AS period, i.currency AS cur,
				SUM(COALESCE(i.total_price, 0)) AS rev, SUM(COALESCE(i.total_cost, 0)) AS cost
				FROM account_invoice i JOIN account a ON a.id = i.account_id LEFT JOIN compute_zone z ON z.id = i.zone_id
				WHERE ${INVOICE_FILTER} AND ${scope} AND i.period IN (${inList})
				GROUP BY i.account_id, a.name, i.zone_id, z.name, i.period, i.currency""".toString(), params)
			String ccy = FinopsInsightsCalc.mainCurrency(rows, viewer.currency)
			List<Map> use = FinopsInsightsCalc.inCurrency(rows, ccy, viewer.currency)
			Set<String> others = (rows.collect { FinopsInsightsCalc.currency(it.cur, viewer.currency) } as Set) - ccy
			List<Map> trend = FinopsInsightsCalc.perPeriod(use, periods)
			out.currency = ccy
			out.otherCurrencies = others.sort()
			out.months = months
			out.periods = periods
			out.trend = trend
			out.current = trend[-1]
			out.previous = trend.size() > 1 ? trend[-2] : null
			String curPeriod = periods[-1]
			out.byCloud = FinopsInsightsCalc.topBy(use, curPeriod, 'zone', 'zoneName', FinopsInsightsCalc.TOP_ROWS, 'other')
			out.byTenant = isMaster ? FinopsInsightsCalc.topBy(use, curPeriod, 'account', 'accountName', FinopsInsightsCalc.TOP_ROWS, 'other') : []
			def counts = isMaster ?
				sql.firstRow('''SELECT (SELECT COUNT(*) FROM account a WHERE a.master_account = 0 AND a.active = 1) AS tenants,
					(SELECT COUNT(*) FROM instance i JOIN account a ON a.id = i.account_id WHERE a.master_account = 0 AND a.active = 1) AS instances''') :
				sql.firstRow('SELECT 1 AS tenants, (SELECT COUNT(*) FROM instance i WHERE i.account_id = ?) AS instances', [viewer.account_id])
			out.tenants = (counts?.tenants ?: 0) as Long
			out.instances = (counts?.instances ?: 0) as Long
			out.ok = true
		} catch(Exception e) {
			log.error("FinOps insights: loading invoice data failed: ${e.message}", e)
			out.reason = 'loadFailed'
		} finally {
			if(c) morpheus.report.releaseDatabaseConnection(c).blockingAwait()
		}
		return out
	}

	/**
	 * The servers whose log entries the latest log entries widget and the log history page may ask
	 * for: ids, names and (when the server belongs to exactly one instance) the instance id, newest
	 * activity first, at most LOG_SOURCE_LIMIT.
	 *
	 * <p>The ids come from this query for the signed-in viewer and from nowhere else: the browser is
	 * only ever handed ids of the viewer's own tenant (the master tenant sees all tenants). The
	 * request options are read for the viewer only (viewerId prefers the security context); a
	 * server, account or instance id in them is ignored.
	 *
	 * <p>Returns {@code [ok, reason, locale, isMaster, accountName, sources: [[id, name, instanceId]]]}.
	 */
	Map logSources(Map<String, Object> opts, Locale locale = null) {
		Map out = [ok: false, locale: locale, isMaster: false, sources: []]
		Long userId = viewerId(opts)
		if(userId == null) {
			log.warn("FinOps insights: no viewer for the log sources (option keys: ${opts?.keySet()}) and no security context")
			out.reason = 'noViewer'
			return out
		}
		Connection c = null
		try {
			c = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
			Sql sql = newSql(c)
			def viewer = sql.firstRow(VIEWER_SQL, [userId])
			if(viewer == null) { out.reason = 'noViewer'; return out }
			boolean isMaster = (viewer.is_master as Integer) == 1
			out.isMaster = isMaster
			out.accountName = viewer.account_name as String
			out.locale = parseLocale(viewer.locale) ?: locale
			List<Map> rows = isMaster ? sql.rows(LOG_SOURCES_MASTER_SQL.toString()) :
				sql.rows(LOG_SOURCES_TENANT_SQL.toString(), [viewer.account_id])
			Long viewerAccount = viewer.account_id as Long
			out.sources = rows.take(LOG_SOURCE_LIMIT).collect { Map r ->
				Long id = r.id as Long
				boolean ownInstance = r.instance_id != null && r.instance_account_id != null &&
					(r.instance_account_id as Long) == viewerAccount
				[id: id, name: (r.name as String)?.trim() ?: "#${id}".toString(), instanceId: ownInstance ? (r.instance_id as Long) : null]
			}
			out.ok = true
		} catch(Exception e) {
			log.error("FinOps insights: loading the log sources failed: ${e.message}", e)
			out.reason = 'loadFailed'
			out.sources = []
		} finally {
			if(c) morpheus.report.releaseDatabaseConnection(c).blockingAwait()
		}
		return out
	}

	/** Parses a Morpheus user locale setting ('en-US', 'de_DE', 'de'); null when unusable. */
	static Locale parseLocale(Object setting) {
		String s = setting?.toString()?.trim()
		if(!s) return null
		try {
			Locale l = Locale.forLanguageTag(s.replace('_', '-'))
			return (l?.language && Locale.getISOLanguages().contains(l.language)) ? l : null
		} catch(Exception ignored) {
			return null
		}
	}
}
