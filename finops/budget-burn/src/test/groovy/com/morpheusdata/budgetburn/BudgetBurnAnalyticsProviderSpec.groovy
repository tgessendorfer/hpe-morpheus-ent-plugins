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
