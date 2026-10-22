package com.morpheusdata.costapproval

import com.morpheusdata.model.RequestReference
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import spock.lang.Specification

class StoredDecisionsSpec extends Specification {

	private static GroovyRowResult row(Map m) {
		new GroovyRowResult([request_external_id: null, request_external_name: null, ref_id: null, ref_type: null,
			name: null, external_id: null, external_name: null] + m)
	}

	def "a new request id carries the decision and a full UUID"() {
		when:
		String approved = StoredDecisions.newRequestId('ca-', true)
		String rejected = StoredDecisions.newRequestId('ca-', false)

		then:
		approved ==~ /ca-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-a/
		rejected ==~ /ca-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r/
		StoredDecisions.statusOf(approved) == RequestReference.ApprovalStatus.approved
		StoredDecisions.statusOf(rejected) == RequestReference.ApprovalStatus.rejected
	}

	def "an id without a decision (before 1.2.1) or no id carries none"() {
		expect:
		StoredDecisions.statusOf(id) == null

		where:
		id << [null, '', 'ca-1a2b3c4d', 'ca-1a2b3c4d-0']
	}

	def "open decisions are read with a parameterised query, only for this integration"() {
		given:
		Sql sql = Mock(Sql)

		when:
		def reports = StoredDecisions.load(sql, 7L)

		then:
		1 * sql.rows(StoredDecisions.OPEN_DECISIONS_SQL, [7L]) >> []
		0 * sql._
		reports == []
		StoredDecisions.OPEN_DECISIONS_SQL.contains('r.account_integration_id = ?')
		StoredDecisions.OPEN_DECISIONS_SQL.contains("rr.status IN ('requesting', 'requested')")
		StoredDecisions.OPEN_DECISIONS_SQL.endsWith('LIMIT 1000')
	}

	def "no connection or no integration id reads nothing"() {
		given:
		Sql sql = Mock(Sql)

		expect:
		StoredDecisions.load(null, 7L) == []
		StoredDecisions.load(sql, null) == []
	}

	def "stored rows are rebuilt as the original reports, grouped per request in order"() {
		given:
		Sql sql = Stub(Sql) {
			rows(_ as String, _ as List) >> [
				row(request_external_id: 'ca-1111-a', request_external_name: 'Approved automatically', ref_id: 47, ref_type: 'instance',
					name: 'web-1', external_id: 'ca-1111-a-0', external_name: 'Approved automatically'),
				row(request_external_id: 'ca-2222-r', request_external_name: null, ref_id: 48, ref_type: 'instance',
					name: 'db-1', external_id: null, external_name: 'Above cost threshold'),
				row(request_external_id: 'ca-2222-r', request_external_name: null, ref_id: 49, ref_type: 'instance',
					name: 'db-2', external_id: null, external_name: null),
				row(request_external_id: 'ca-3333', ref_id: 50, external_id: 'ca-3333-0')
			]
		}

		when:
		def reports = StoredDecisions.load(sql, 7L)

		then: 'the id without a decision is left alone'
		reports*.externalId == ['ca-1111-a', 'ca-2222-r']
		reports[0].externalName == 'Approved automatically'
		reports[0].refs*.status == [RequestReference.ApprovalStatus.approved]
		reports[0].refs*.refId == ['47']
		reports[0].refs*.externalId == ['ca-1111-a-0']

		and: 'missing external ids and names fall back to the ones of the response'
		reports[1].externalName == 'Above cost threshold'
		reports[1].refs*.status == [RequestReference.ApprovalStatus.rejected] * 2
		reports[1].refs*.refId == ['48', '49']
		reports[1].refs*.externalId == ['ca-2222-r-0', 'ca-2222-r-1']
		reports[1].refs*.externalName == ['Above cost threshold'] * 2
	}
}
