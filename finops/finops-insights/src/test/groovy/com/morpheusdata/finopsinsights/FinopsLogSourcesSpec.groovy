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

/**
 * Which servers the latest log entries widget and the history page may ask the platform for: the
 * SQL and its parameters for the master tenant and for a sub-tenant, that the ids come from the
 * database for the signed-in viewer and from nothing the request options carry (they name the
 * viewer only when there is no security context), the cap, and the failure states. The database is
 * replaced through the newSql seam.
 */
class FinopsLogSourcesSpec extends Specification {

	static class SeamData extends FinopsInsightsData {
		Sql sql
		/** The user of the request's security context; null outside a request. */
		Long contextUser

		SeamData(MorpheusContext morpheus, Sql sql) {
			super(morpheus)
			this.sql = sql
		}

		@Override
		protected Sql newSql(Connection c) {
			sql
		}

		@Override
		protected Long securityUserId() {
			contextUser
		}
	}

	MorpheusContext morpheus = Mock()
	MorpheusReportService report = Mock()
	Connection connection = Mock()
	Sql sql = Mock()
	SeamData data = new SeamData(morpheus, sql)

	private void connectionAvailable() {
		interaction {
			morpheus.report >> report
			report.getReadOnlyDatabaseConnection() >> Single.just(connection)
			report.releaseDatabaseConnection(connection) >> Completable.complete()
		}
	}

	private static Map viewer(int master) {
		[user_id: 7L, locale: 'de_DE', account_id: master ? 1L : 22L, account_name: master ? 'Provider' : 'Tenant', currency: 'EUR', is_master: master]
	}

	def "the master tenant gets every tenant's servers from one query without an account filter"() {
		given:
		connectionAvailable()

		when:
		Map out = data.logSources([userId: '7'], Locale.ENGLISH)

		then:
		1 * sql.firstRow(FinopsInsightsData.VIEWER_SQL, [7L]) >> viewer(1)
		1 * sql.rows({ String q ->
			q.contains('FROM compute_server cs') && !q.contains('cs.account_id') && q.contains('cs.agent_installed = 1') &&
				q.contains('cs.enable_logs IS NULL OR cs.enable_logs = 1') && q.contains("cs.status <> 'removed'") &&
				q.contains('MIN(c.instance_id)') && q.contains('ORDER BY cs.last_updated DESC') && q.endsWith('LIMIT 50')
		}) >> [[id: 31L, name: 'web-01', instance_id: 410L], [id: 32L, name: 'db-01', instance_id: null]]
		0 * sql.rows(_, _)
		out.ok
		out.isMaster
		out.accountName == 'Provider'
		out.locale == Locale.GERMANY
		out.sources == [[id: 31L, name: 'web-01', instanceId: 410L], [id: 32L, name: 'db-01', instanceId: null]]
	}

	def "a sub-tenant gets only its own servers: the account id from the viewer's row is the only parameter"() {
		given:
		connectionAvailable()

		when:
		Map out = data.logSources([userId: 7], Locale.ENGLISH)

		then:
		1 * sql.firstRow(FinopsInsightsData.VIEWER_SQL, [7L]) >> viewer(0)
		1 * sql.rows({ String q -> q.contains('AND cs.account_id = ?') && q.endsWith('LIMIT 50') }, [22L]) >> [[id: 5L, name: 'app-01', instance_id: 9L]]
		0 * sql.rows(_)
		out.ok
		!out.isMaster
		out.sources*.id == [5L]
	}

	def "server, account and instance ids in the request options are never used"() {
		given:
		connectionAvailable()
		Map hostile = [userId: '7', servers: [999L, 1000L], serverIds: [1L], serverId: 5L, ids: [1L, 2L], objectId: 3L,
			instanceId: 8L, accountId: 99L, account: [id: 99L], 'servers[]': ['4'], query: "1; DROP TABLE compute_server"]

		when:
		Map out = data.logSources(hostile, Locale.ENGLISH)

		then:
		1 * sql.firstRow(FinopsInsightsData.VIEWER_SQL, [7L]) >> viewer(0)
		// the only parameter is the viewer's own account id, read from the database row
		1 * sql.rows({ String q -> !q.contains('999') && !q.contains('DROP') }, [22L]) >> []
		out.ok
		out.sources == []
	}

	def "the security context decides who the viewer is, the options only when there is no context"() {
		given:
		connectionAvailable()
		data.contextUser = 7L

		when:
		Map out = data.logSources([userId: '99', user: [id: 98], currentUserId: 97])

		then:
		1 * sql.firstRow(FinopsInsightsData.VIEWER_SQL, [7L]) >> viewer(0)
		1 * sql.rows(_, [22L]) >> []
		out.ok
	}

	def "an instance is linked only when the server carries exactly one"() {
		expect:
		FinopsInsightsData.LOG_SOURCES_MASTER_SQL.contains('CASE WHEN COUNT(DISTINCT c.instance_id) = 1 THEN MIN(c.instance_id) END')
		FinopsInsightsData.LOG_SOURCES_TENANT_SQL.contains('CASE WHEN COUNT(DISTINCT c.instance_id) = 1')
	}

	def "at most 50 sources are returned however many rows come back"() {
		given:
		connectionAvailable()

		when:
		Map out = data.logSources([userId: 7])

		then:
		1 * sql.firstRow(_, _) >> viewer(1)
		1 * sql.rows(_) >> (1..60).collect { [id: it as Long, name: "srv-${it}".toString(), instance_id: null] }
		out.sources.size() == 50
		out.sources[0].id == 1L
		FinopsInsightsData.LOG_SOURCE_LIMIT == 50
	}

	def "a nameless server is shown by its id"() {
		given:
		connectionAvailable()

		when:
		Map out = data.logSources([userId: 7])

		then:
		1 * sql.firstRow(_, _) >> viewer(0)
		1 * sql.rows(_, _) >> [[id: 12L, name: null, instance_id: null], [id: 13L, name: '  ', instance_id: null]]
		out.sources*.name == ['#12', '#13']
	}

	def "a viewer that cannot be resolved loads nothing and opens no connection"() {
		when:
		Map out = data.logSources([:], Locale.ENGLISH)

		then:
		0 * morpheus._
		0 * sql._
		!out.ok
		out.reason == 'noViewer'
		out.sources == []
	}

	def "an unknown user row is a noViewer state"() {
		given:
		connectionAvailable()

		when:
		Map out = data.logSources([userId: 7])

		then:
		1 * sql.firstRow(_, _) >> null
		0 * sql.rows(*_)
		!out.ok
		out.reason == 'noViewer'
	}

	def "a database failure is a loadFailed state and the connection is released"() {
		given:
		morpheus.report >> report
		report.getReadOnlyDatabaseConnection() >> Single.just(connection)

		when:
		Map out = data.logSources([userId: 7])

		then:
		1 * sql.firstRow(_, _) >> viewer(1)
		1 * sql.rows(_) >> { throw new java.sql.SQLException('boom') }
		1 * report.releaseDatabaseConnection(connection) >> Completable.complete()
		!out.ok
		out.reason == 'loadFailed'
		out.sources == []
	}
}
