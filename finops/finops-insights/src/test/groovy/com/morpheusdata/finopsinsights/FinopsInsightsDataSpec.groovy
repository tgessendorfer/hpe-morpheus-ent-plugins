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
import com.morpheusdata.core.MorpheusReportService
import groovy.sql.Sql
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

import java.sql.Connection
import java.time.YearMonth

/**
 * The invoice load of the three FinOps widgets and its connection handling: whatever the load
 * ended with (figures, noViewer, loadFailed) is what the widget gets, also when handing the
 * connection back fails. The database is replaced through the newSql seam.
 */
class FinopsInsightsDataSpec extends Specification {

	MorpheusContext morpheus = Mock()
	MorpheusReportService report = Mock()
	Connection connection = Mock()
	Sql sql = Mock()
	FinopsLogSourcesSpec.SeamData data = new FinopsLogSourcesSpec.SeamData(morpheus, sql)

	def setup() {
		morpheus.report >> report
		report.getReadOnlyDatabaseConnection() >> Single.just(connection)
	}

	private static Map masterViewer() {
		[user_id: 7L, locale: 'en_US', account_id: 1L, account_name: 'Provider', currency: 'EUR', is_master: 1]
	}

	def "a failed connection release keeps the loaded invoice figures"() {
		given:
		String period = FinopsInsightsCalc.periods([YearMonth.now()])[0]

		when:
		Map out = data.load([userId: 7], Locale.ENGLISH)

		then:
		1 * sql.firstRow(FinopsInsightsData.VIEWER_SQL, [7L]) >> masterViewer()
		1 * sql.rows({ String q -> q.contains('FROM account_invoice i JOIN account a') }, _) >>
			[[account: 22L, accountName: 'Tenant', zone: 3L, zoneName: 'cloud-a', period: period, cur: 'EUR', rev: 120, cost: 80]]
		1 * sql.firstRow({ String q -> q.contains('AS tenants') }) >> [tenants: 2, instances: 5]
		0 * sql.rows(FinopsInsightsData.DEACTIVATED_SQL, _)
		1 * report.releaseDatabaseConnection(connection) >> Completable.error(new java.sql.SQLException('pool closed'))
		notThrown(Exception)
		out.ok
		out.isMaster
		out.current.rev == 120.00G
		out.current.cost == 80.00G
		out.tenants == 2L
		out.instances == 5L
		out.deactivated == null
	}

	def "the master view counts deactivated sub-tenants of the current month apart and leaves its figures unchanged"() {
		given:
		String period = FinopsInsightsCalc.periods([YearMonth.now()])[0]
		report.releaseDatabaseConnection(connection) >> Completable.complete()

		when: 'as the KPI strip asks'
		Map out = data.load([userId: 7], Locale.ENGLISH, true)

		then: 'the widgets\' own query still reads active sub-tenants only'
		1 * sql.firstRow(FinopsInsightsData.VIEWER_SQL, [7L]) >> masterViewer()
		1 * sql.rows({ String q -> q.contains('FROM account_invoice i JOIN account a') && q.contains('a.master_account = 0 AND a.active = 1') }, _) >>
			[[account: 22L, accountName: 'Tenant', zone: 3L, zoneName: 'cloud-a', period: period, cur: 'EUR', rev: 120, cost: 80]]
		1 * sql.firstRow({ String q -> q.contains('AS tenants') }) >> [tenants: 2, instances: 5]

		and: 'a check that deactivated sub-tenants exist, then one query for them, for the current month'
		1 * sql.firstRow(FinopsInsightsData.DEACTIVATED_EXISTS_SQL) >> [found: 1]
		1 * sql.rows(FinopsInsightsData.DEACTIVATED_SQL, [period]) >>
			[[account: 30L, cur: 'EUR', rev: 100, cost: 70], [account: 31L, cur: 'EUR', rev: 23.45, cost: 20], [account: 31L, cur: 'USD', rev: 5, cost: 4]]
		out.ok
		out.current.rev == 120.00G
		out.current.cost == 80.00G
		out.tenants == 2L
		out.deactivated == [count: 2, amounts: [[currency: 'EUR', rev: 123.45G, cost: 90.00G], [currency: 'USD', rev: 5.00G, cost: 4.00G]]]
	}

	def "a failing query for deactivated tenants keeps the figures and only drops the line"() {
		given:
		String period = FinopsInsightsCalc.periods([YearMonth.now()])[0]

		when:
		Map out = data.load([userId: 7], Locale.ENGLISH, true)

		then:
		1 * sql.firstRow(FinopsInsightsData.VIEWER_SQL, [7L]) >> masterViewer()
		1 * sql.rows({ String q -> q.contains('FROM account_invoice i JOIN account a') }, _) >>
			[[account: 22L, accountName: 'Tenant', zone: 3L, zoneName: 'cloud-a', period: period, cur: 'EUR', rev: 120, cost: 80]]
		1 * sql.firstRow({ String q -> q.contains('AS tenants') }) >> [tenants: 2, instances: 5]
		1 * sql.firstRow(FinopsInsightsData.DEACTIVATED_EXISTS_SQL) >> [found: 1]
		1 * sql.rows(FinopsInsightsData.DEACTIVATED_SQL, _) >> { throw new java.sql.SQLException("Unknown column 'active'") }
		1 * report.releaseDatabaseConnection(connection) >> Completable.complete()
		out.ok
		out.current.rev == 120.00G
		out.deactivated == null
	}

	@spock.lang.Unroll
	def "the invoice query for deactivated tenants is not run when #what"() {
		given:
		String period = FinopsInsightsCalc.periods([YearMonth.now()])[0]
		report.releaseDatabaseConnection(connection) >> Completable.complete()

		when:
		Map out = data.load([userId: 7], Locale.ENGLISH, true)

		then:
		1 * sql.firstRow(FinopsInsightsData.VIEWER_SQL, [7L]) >> masterViewer()
		1 * sql.rows({ String q -> q.contains('FROM account_invoice i JOIN account a') }, _) >>
			[[account: 22L, accountName: 'Tenant', zone: 3L, zoneName: 'cloud-a', period: period, cur: 'EUR', rev: 120, cost: 80]]
		1 * sql.firstRow({ String q -> q.contains('AS tenants') }) >> [tenants: 2, instances: 5]
		1 * sql.firstRow(FinopsInsightsData.DEACTIVATED_EXISTS_SQL) >> { check() }
		0 * sql.rows(FinopsInsightsData.DEACTIVATED_SQL, _)
		out.ok
		out.current.rev == 120.00G
		out.tenants == 2L
		out.deactivated == null

		where:
		what                                     | check
		'no deactivated sub-tenant exists'       | { null }
		'the check for them fails'               | { throw new java.sql.SQLException("Unknown column 'active'") }
	}

	def "a sub-tenant viewer runs no query for deactivated tenants"() {
		given:
		String period = FinopsInsightsCalc.periods([YearMonth.now()])[0]
		report.releaseDatabaseConnection(connection) >> Completable.complete()

		when:
		Map out = data.load([userId: 7], Locale.ENGLISH, true)

		then:
		1 * sql.firstRow(FinopsInsightsData.VIEWER_SQL, [7L]) >> [user_id: 7L, locale: 'en_US', account_id: 22L, account_name: 'Tenant', currency: 'EUR', is_master: 0]
		1 * sql.rows({ String q -> q.contains('FROM account_invoice i JOIN account a') }, _) >>
			[[account: 22L, accountName: 'Tenant', zone: 3L, zoneName: 'cloud-a', period: period, cur: 'EUR', rev: 120, cost: 80]]
		1 * sql.firstRow({ String q -> q.contains('AS tenants') }, [22L]) >> [tenants: 1, instances: 3]
		0 * sql.firstRow(FinopsInsightsData.DEACTIVATED_EXISTS_SQL)
		0 * sql.rows(FinopsInsightsData.DEACTIVATED_SQL, _)
		out.ok
		!out.isMaster
		out.deactivated == null
	}

	def "the query for deactivated tenants uses the widgets' invoice filter and one month"() {
		given:
		String q = FinopsInsightsData.DEACTIVATED_SQL

		expect:
		q.contains(FinopsInsightsData.INVOICE_FILTER)
		q.contains(' AND i.period = ? ')
		q.contains('i.account_id IN (SELECT a.id FROM account a WHERE a.master_account = 0 AND a.active = 0)')
		q.endsWith('GROUP BY i.account_id, i.currency')

		and: 'asked first: whether any deactivated sub-tenant exists at all'
		FinopsInsightsData.DEACTIVATED_EXISTS_SQL == 'SELECT 1 AS found FROM account a WHERE a.master_account = 0 AND a.active = 0 LIMIT 1'
	}

	def "a failed connection release keeps the noViewer state of an unknown user"() {
		when:
		Map out = data.load([userId: 7], Locale.ENGLISH)

		then:
		1 * sql.firstRow(_, _) >> null
		1 * report.releaseDatabaseConnection(connection) >> Completable.error(new IllegalStateException('pool closed'))
		notThrown(Exception)
		!out.ok
		out.reason == 'noViewer'
	}

	def "a failed connection release keeps the loadFailed state of a failed query"() {
		when:
		Map out = data.load([userId: 7], Locale.ENGLISH)

		then:
		1 * sql.firstRow(_, _) >> { throw new java.sql.SQLException('boom') }
		1 * report.releaseDatabaseConnection(connection) >> Completable.error(new java.sql.SQLException('pool closed'))
		notThrown(Exception)
		!out.ok
		out.reason == 'loadFailed'
	}

	def "a connection that was never opened is not released"() {
		when:
		Map out = data.load([userId: 7], Locale.ENGLISH)

		then:
		1 * report.getReadOnlyDatabaseConnection() >> Single.error(new java.sql.SQLException('no connection'))
		0 * report.releaseDatabaseConnection(_)
		!out.ok
		out.reason == 'loadFailed'
	}
}
