package com.morpheusdata.costapproval

import com.morpheusdata.model.Account
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Policy
import com.morpheusdata.model.Request
import com.morpheusdata.model.RequestReference
import groovy.json.JsonOutput
import spock.lang.Specification

class CostApprovalProviderSpec extends Specification {

	CostApprovalProvider provider = new CostApprovalProvider(null, null)

	private static AccountIntegration integration(long id, Map cfg, String currency = 'EUR') {
		AccountIntegration ai = new AccountIntegration(account: new Account(currency: currency))
		ai.id = id
		ai.config = JsonOutput.toJson(cfg)
		ai
	}

	private static Request request(BigDecimal price, String currency, List<RequestReference> refs = null) {
		new Request(pricePerMonth: price, currency: currency,
			refs: refs ?: [new RequestReference(refId: '1', refType: 'instance', name: 'web-1', pricePerMonth: price, currency: currency)])
	}

	def "option codes, field names and i18n keys follow the plan"() {
		expect:
		provider.integrationOptionTypes()*.code == ['cost-threshold-approval-threshold', 'cost-threshold-approval-currency']
		provider.policyOptionTypes()*.code == ['cost-threshold-approval-policy-threshold', 'cost-threshold-approval-policy-currency']
		(provider.integrationOptionTypes() + provider.policyOptionTypes()).every { OptionType o ->
			o.fieldContext == 'config' && o.fieldName in ['costThreshold', 'thresholdCurrency'] &&
				o.fieldCode.startsWith('cost-threshold-approval.') && o.helpTextI18nCode.startsWith('cost-threshold-approval.')
		}
	}

	def "every option label and help key exists in both bundles"() {
		given:
		List<String> keys = (provider.integrationOptionTypes() + provider.policyOptionTypes()).collectMany { [it.fieldCode, it.helpTextI18nCode] }
		List<String> runtime = ['approved', 'overThreshold', 'currencyMismatch', 'noPrice'].collectMany {
			["cost-threshold-approval.request.${it}".toString(), "cost-threshold-approval.msg.${it}".toString()]
		}

		expect:
		['i18n/messages.properties', 'i18n/messages_de.properties'].every { String path ->
			Properties p = new Properties()
			getClass().classLoader.getResourceAsStream(path).withCloseable { p.load(it) }
			(keys + runtime).every { p.getProperty(it)?.trim() }
		}
	}

	def "a request under the threshold set in the form (cm.plugin) is approved"() {
		given:
		def ai = integration(7, [cm: [plugin: [costThreshold: '50']]])

		when:
		def rsp = provider.createApprovalRequest([], request(49.99G, 'EUR'), ai, null, [:])

		then:
		rsp.success
		rsp.externalRequestId.startsWith('ca-')
		rsp.references*.status == [RequestReference.ApprovalStatus.approved]
		rsp.references[0].externalId == "${rsp.externalRequestId}-0"
		rsp.externalRequestName == 'Approved automatically (49.99 EUR <= 50.00 EUR per month)'
	}

	def "the form threshold is honoured: 60 EUR against 50 is rejected with the reason"() {
		when:
		def rsp = provider.createApprovalRequest([], request(60G, 'EUR'), integration(7, [cm: [plugin: [costThreshold: '50']]]), null, [:])

		then:
		rsp.success
		rsp.references*.status == [RequestReference.ApprovalStatus.rejected]
		rsp.msg == 'Rejected: above cost threshold'
		rsp.externalRequestName == 'Above cost threshold of 50.00 EUR/month (requested 60.00 EUR). Please contact your provider for approval.'
		rsp.references*.externalName == [rsp.externalRequestName]
	}

	def "no answer is ever left requested: every outcome is approved or rejected"() {
		given:
		def ai = integration(7, [costThreshold: '50', thresholdCurrency: 'EUR'])
		def answers = [request(10G, 'EUR'), request(60G, 'EUR'), request(10G, 'USD'),
			new Request(refs: [new RequestReference(refId: '1', currency: 'EUR')])].collect {
			provider.createApprovalRequest([], it, ai, null, [:])
		}

		expect:
		answers*.references.flatten()*.status == [RequestReference.ApprovalStatus.approved,
			RequestReference.ApprovalStatus.rejected, RequestReference.ApprovalStatus.rejected,
			RequestReference.ApprovalStatus.rejected]
		answers[3].externalRequestName == 'No monthly price for this request (threshold 50.00 EUR/month). Please contact your provider for approval.'
	}

	def "a policy threshold overrides the integration threshold"() {
		given:
		Policy policy = new Policy()
		policy.config = JsonOutput.toJson([cm: [plugin: [costThreshold: '10']]])

		when:
		def rsp = provider.createApprovalRequest([], request(20G, 'EUR'), integration(7, [costThreshold: '50']), policy, [:])

		then:
		rsp.references*.status == [RequestReference.ApprovalStatus.rejected]
	}

	def "a request in another currency than the threshold is never approved"() {
		when:
		def rsp = provider.createApprovalRequest([], request(1G, 'USD'),
			integration(7, [cm: [plugin: [costThreshold: '50', thresholdCurrency: 'EUR']]]), null, [:])

		then:
		rsp.references*.status == [RequestReference.ApprovalStatus.rejected]
		rsp.msg == 'Rejected: currency differs from the cost threshold'
		rsp.externalRequestName == 'Currency differs from the cost threshold (requested 1.00 USD, threshold 50.00 EUR/month). Please contact your provider for approval.'
	}

	def "without request or reference currency the tenant currency of the integration applies"() {
		given:
		def refs = [new RequestReference(refId: '1', pricePerMonth: 5G)]

		when:
		def rsp = provider.createApprovalRequest([], new Request(refs: refs), integration(7, [thresholdCurrency: 'CHF'], 'CHF'), null, [:])

		then:
		rsp.references*.status == [RequestReference.ApprovalStatus.approved]
		rsp.externalRequestName.contains('5.00 CHF')
	}

	def "without any currency source the rule ends at USD"() {
		given:
		def ai = integration(7, [:], null)

		expect:
		provider.fallbackCurrency(ai) == 'USD'
	}

	def "monitorApproval reports every decision once, unchanged, and only to its own integration"() {
		given:
		def a = integration(1, [costThreshold: '50'])
		def b = integration(2, [costThreshold: '50'])
		def ra = provider.createApprovalRequest([], request(1G, 'EUR'), a, null, [:])
		def rr = provider.createApprovalRequest([], request(99G, 'EUR'), a, null, [:])

		expect:
		provider.monitorApproval(b) == []

		when:
		Map<String, Request> first = provider.monitorApproval(a).collectEntries { [(it.externalId): it] }

		then:
		first.keySet() == [ra.externalRequestId, rr.externalRequestId] as Set
		first[ra.externalRequestId].refs*.status == [RequestReference.ApprovalStatus.approved]
		first[rr.externalRequestId].refs*.status == [RequestReference.ApprovalStatus.rejected]
		first[rr.externalRequestId].externalName == rr.externalRequestName
		first[rr.externalRequestId].refs*.externalId == rr.references*.externalId
		provider.monitorApproval(a) == []
	}

	/** Shape of the internal domain object Morpheus 9.0.2 puts into Request.refs (not the model class). */
	static class DomainRef {
		Long id
		Long refId
		String refType
		String name
		Double pricePerMonth
		String currency
	}

	def "references arrive as Morpheus domain objects, not as the model class"() {
		given:
		def refs = [new DomainRef(id: 13, refId: 47L, refType: 'instance', name: 'web-1', pricePerMonth: 16.0d, currency: 'EUR')]

		when:
		def under = provider.createApprovalRequest([], new Request(refs: refs), integration(7, [cm: [plugin: [costThreshold: '20']]]), null, [:])
		def over = provider.createApprovalRequest([], new Request(refs: refs), integration(7, [cm: [plugin: [costThreshold: '10']]]), null, [:])

		then:
		under.success
		under.references*.status == [RequestReference.ApprovalStatus.approved]
		under.references*.refId == ['47']
		under.references*.pricePerMonth == [16.00G]
		under.externalRequestName.contains('16.00 EUR')
		over.references*.status == [RequestReference.ApprovalStatus.rejected]
		over.externalRequestName == 'Above cost threshold of 10.00 EUR/month (requested 16.00 EUR). Please contact your provider for approval.'
	}
}
