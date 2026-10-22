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

import com.morpheusdata.model.Request
import com.morpheusdata.model.RequestReference
import groovy.sql.Sql

/**
 * Decisions that Morpheus has stored but not yet taken over, rebuilt from its own tables. The
 * external request id carries the decision as its last part ({@code ca-<uuid>-a} approved,
 * {@code ca-<uuid>-r} rejected), so a monitor run can report it again without any state in the
 * plugin: after a jar upload, a restart or on another appliance node. Morpheus keeps approvals in
 * the internal tables {@code request} and {@code request_reference} (domain classes Request and
 * RequestReference); they are read through the read-only report connection.
 */
class StoredDecisions {

	static final String APPROVED_SUFFIX = '-a'
	static final String REJECTED_SUFFIX = '-r'

	/**
	 * Bounds one monitor run to the newest open requests; Morpheus takes a decision over within
	 * a run or two, so only requests it never settles could pile up, and they never crowd out
	 * newer ones.
	 */
	static final int MAX_REQUESTS = 500

	/**
	 * Parameterised. The newest requests of the integration that still have a reference Morpheus
	 * has not decided ('requesting', 'requested'), then every reference of each, in the order of
	 * the original answer, so each one gets back the external id the answer gave it.
	 */
	static final String OPEN_DECISIONS_SQL = '''SELECT r.external_id AS request_external_id, r.external_name AS request_external_name,
		rr.ref_id, rr.ref_type, rr.name, rr.external_id, rr.external_name
		FROM (SELECT DISTINCT o.id FROM request o JOIN request_reference orr ON orr.request_id = o.id
			WHERE o.account_integration_id = ? AND o.external_id LIKE 'ca-%'
			AND orr.status IN ('requesting', 'requested')
			ORDER BY o.id DESC LIMIT ''' + MAX_REQUESTS + ''') recent
		JOIN request r ON r.id = recent.id
		JOIN request_reference rr ON rr.request_id = r.id
		ORDER BY r.id, rr.id'''

	/** The external request id for a new decision: prefix, a full random UUID, decision suffix. */
	static String newRequestId(String prefix, boolean approved) {
		return "${prefix}${UUID.randomUUID()}${approved ? APPROVED_SUFFIX : REJECTED_SUFFIX}".toString()
	}

	/** The decision a request id carries, or null for an id without one (written before 1.2.1). */
	static RequestReference.ApprovalStatus statusOf(String requestId) {
		if (requestId?.endsWith(APPROVED_SUFFIX)) {
			return RequestReference.ApprovalStatus.approved
		}
		if (requestId?.endsWith(REJECTED_SUFFIX)) {
			return RequestReference.ApprovalStatus.rejected
		}
		return null
	}

	/**
	 * The open requests of one integration that carry a decision, in the shape monitorApproval
	 * reports: the same external ids, names and references as the createApprovalRequest response.
	 * A reference stored without an external id gets the one the response gave it (request id and
	 * position among all references of the request).
	 */
	static List<Request> load(Sql sql, Long integrationId) {
		if (sql == null || integrationId == null) {
			return []
		}
		Map<String, Request> byId = new LinkedHashMap<>()
		sql.rows(OPEN_DECISIONS_SQL, [integrationId]).each { row ->
			String reqId = row.request_external_id?.toString()
			RequestReference.ApprovalStatus status = statusOf(reqId)
			if (status == null) {
				return
			}
			Request report = byId.computeIfAbsent(reqId) {
				new Request(externalId: reqId, externalName: row.request_external_name?.toString(), refs: [])
			}
			String name = report.externalName ?: row.external_name?.toString()
			report.externalName = name
			int position = report.refs.size()
			report.refs << new RequestReference(refId: row.ref_id?.toString(), refType: row.ref_type?.toString(),
				name: row.name?.toString(), externalId: row.external_id?.toString() ?: "${reqId}-${position}".toString(),
				externalName: row.external_name?.toString() ?: name, status: status)
		}
		return new ArrayList<Request>(byId.values())
	}
}
