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
		1 * sql.rows({ String q -> q.contains('FROM account_invoice i') }, _) >>
			[[account: 22L, accountName: 'Tenant', zone: 3L, zoneName: 'cloud-a', period: period, cur: 'EUR', rev: 120, cost: 80]]
		1 * sql.firstRow({ String q -> q.contains('AS tenants') }) >> [tenants: 2, instances: 5]
		1 * report.releaseDatabaseConnection(connection) >> Completable.error(new java.sql.SQLException('pool closed'))
		notThrown(Exception)
		out.ok
		out.isMaster
		out.current.rev == 120.00G
		out.current.cost == 80.00G
		out.tenants == 2L
		out.instances == 5L
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
