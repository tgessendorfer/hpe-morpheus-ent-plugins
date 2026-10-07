package com.morpheusdata.budgetburn

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.model.User
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

import java.sql.Connection

class BudgetBurnAnalyticsProviderSpec extends Specification {

	def "the budget select returns owner_master for the spend rule in scopeCondition"() {
		expect:
		BudgetBurnAnalyticsProvider.BUDGET_SELECT.contains('AS owner_master')
		BudgetBurnAnalyticsProvider.BUDGET_SELECT.contains('a.currency AS owner_currency')
	}

	def "server invoices count only for servers that belong to no instance"() {
		given:
		String f = BudgetBurnAnalyticsProvider.INVOICE_FILTER

		expect: 'an instance VM has an instance invoice and a server invoice without instance_id; the container row links them'
		f.contains("i.ref_type = 'Instance'")
		f.contains("i.ref_type = 'ComputeServer' AND i.instance_id IS NULL")
		f.contains('NOT EXISTS (SELECT 1 FROM container ct WHERE ct.server_id = i.ref_id AND ct.instance_id IS NOT NULL)')
	}

	def "a failing connection release does not replace the page result"() {
		given:
		Connection connection = Mock(Connection)
		MorpheusReportService report = Mock(MorpheusReportService) {
			getReadOnlyDatabaseConnection() >> Single.just(connection)
		}
		MorpheusContext morpheus = Mock(MorpheusContext) {
			getReport() >> report
		}
		BudgetBurnAnalyticsProvider provider = new BudgetBurnAnalyticsProvider(null, morpheus)

		when:
		def response = provider.loadData(new User(id: 1L), [:])

		then:
		1 * report.releaseDatabaseConnection(connection) >> { throw new IllegalStateException('pool closed') }
		noExceptionThrown()
		!response.success
		response.errors.error == 'Budget data could not be loaded. See the appliance log for details.'
	}
}
