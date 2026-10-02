package com.morpheusdata.costapproval

import com.morpheusdata.model.Account
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Policy
import com.morpheusdata.model.Request
import com.morpheusdata.model.RequestReference
import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

	def "a policy threshold with a decimal comma is honoured"() {
		given:
		Policy policy = new Policy()
		policy.config = JsonOutput.toJson([cm: [plugin: [costThreshold: '10,50']]])

		when:
		def rsp = provider.createApprovalRequest([], request(10.50G, 'EUR'), integration(7, [costThreshold: '5']), policy, [:])

		then:
		rsp.references*.status == [RequestReference.ApprovalStatus.approved]
		rsp.externalRequestName == 'Approved automatically (10.50 EUR <= 10.50 EUR per month)'
	}

	/** Holds the first put of a decision until the test releases it. */
	static class SlowPutProvider extends CostApprovalProvider {
		final CountDownLatch putStarted = new CountDownLatch(1)
		final CountDownLatch releasePut = new CountDownLatch(1)

		SlowPutProvider() { super(null, null) }

		@Override
		protected Map<String, Request> newReportMap() {
			// Local copies: inside the anonymous map, a bare name would resolve as a map key.
			CountDownLatch started = putStarted
			CountDownLatch release = releasePut
			return new ConcurrentHashMap<String, Request>() {
				@Override
				Request put(String key, Request value) {
					started.countDown()
					release.await(5, TimeUnit.SECONDS)
					return super.put(key, value)
				}
			}
		}
	}

	@Timeout(20)
	def "a decision stored while a monitor run takes the pending reports is not lost"() {
		given:
		SlowPutProvider slow = new SlowPutProvider()
		def ai = integration(3, [costThreshold: '50'])
		List<Request> firstRun = []
		def rsp = null
		Thread create = Thread.start { rsp = slow.createApprovalRequest([], request(1G, 'EUR'), ai, null, [:]) }

		when: 'a monitor run starts while the decision is being stored'
		assert slow.putStarted.await(5, TimeUnit.SECONDS)
		Thread monitor = Thread.start { firstRun.addAll(slow.monitorApproval(ai)) }
		monitor.join(300)
		slow.releasePut.countDown()
		create.join()
		monitor.join()
		List<Request> secondRun = slow.monitorApproval(ai)

		then: 'the decision is reported exactly once over both runs'
		(firstRun + secondRun)*.externalId == [rsp.externalRequestId]
		slow.monitorApproval(ai) == []
	}

	@Timeout(60)
	def "concurrent decisions and monitor runs report every decision exactly once"() {
		given:
		def ai = integration(4, [costThreshold: '50'])
		int writers = 4
		int perWriter = 250
		Set<String> created = ConcurrentHashMap.newKeySet()
		List<String> reported = Collections.synchronizedList([])
		CountDownLatch start = new CountDownLatch(1)
		AtomicBoolean done = new AtomicBoolean(false)

		when:
		List<Thread> threads = (1..writers).collect {
			Thread.start {
				start.await()
				perWriter.times { created << provider.createApprovalRequest([], request(1G, 'EUR'), ai, null, [:]).externalRequestId }
			}
		}
		Thread monitor = Thread.start {
			start.await()
			while (!done.get()) {
				reported.addAll(provider.monitorApproval(ai)*.externalId)
			}
		}
		start.countDown()
		threads*.join()
		done.set(true)
		monitor.join()
		reported.addAll(provider.monitorApproval(ai)*.externalId)

		then:
		created.size() == writers * perWriter
		reported.size() == created.size()
		reported as Set == created
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

	/** Provider whose user setting and browser locale are fixed; records the user ids looked up. */
	static class LocaleProvider extends CostApprovalProvider {
		Map<Long, String> settings = [:]
		Locale browser
		List<Long> looked = []

		LocaleProvider() { super(null, null) }

		@Override
		protected String userLocaleSetting(Long userId) {
			looked << userId
			return settings[userId]
		}

		@Override
		protected Locale browserLocale() { browser }
	}

	static class DomainRequest {
		Long requestByUserId
	}

	static class UserRef extends DomainRef {
		DomainRequest request
	}

	private static Request userRequest(Long userId, Double price) {
		new Request(refs: [new UserRef(id: 1, refId: 47L, refType: 'instance', name: 'web-1', pricePerMonth: price,
			currency: 'EUR', request: new DomainRequest(requestByUserId: userId))])
	}

	def "the request name follows the requesting user's setting, not the browser"() {
		given:
		LocaleProvider p = new LocaleProvider(settings: [5L: 'en-US', 6L: 'de-DE'], browser: Locale.GERMANY)
		def ai = integration(7, [costThreshold: '1000'])

		when:
		def english = p.createApprovalRequest([], userRequest(5L, 1234.5d), ai, null, [:])
		def german = p.createApprovalRequest([], userRequest(6L, 1234.5d), ai, null, [:])

		then:
		p.looked == [5L, 6L]
		english.externalRequestName == 'Above cost threshold of 1,000.00 EUR/month (requested 1,234.50 EUR). Please contact your provider for approval.'
		english.msg == 'Rejected: above cost threshold'
		german.externalRequestName == '\u00dcber der Kostenschwelle von 1.000,00 EUR/Monat (angefragt 1.234,50 EUR). Bitte wenden Sie sich f\u00fcr eine Freigabe an Ihren Provider.'
		german.msg == 'Abgelehnt: Kostenschwelle \u00fcberschritten'
		german.references*.externalName == [german.externalRequestName]
	}

	def "a user without a setting gets the browser language, without a browser English"() {
		given:
		def ai = integration(7, [costThreshold: '50'])

		expect:
		new LocaleProvider(browser: Locale.GERMANY).createApprovalRequest([], userRequest(8L, 10d), ai, null, [:])
			.externalRequestName == 'Automatisch freigegeben (10,00 EUR <= 50,00 EUR pro Monat)'
		new LocaleProvider(browser: null).createApprovalRequest([], userRequest(8L, 10d), ai, null, [:])
			.externalRequestName == 'Approved automatically (10.00 EUR <= 50.00 EUR per month)'
	}

	def "without a requesting user the texts stay English and no setting is looked up"() {
		given:
		LocaleProvider p = new LocaleProvider(browser: Locale.GERMANY)

		when:
		def rsp = p.createApprovalRequest([], request(10G, 'EUR'), integration(7, [costThreshold: '50']), null, [:])

		then:
		p.looked == []
		rsp.externalRequestName == 'Approved automatically (10.00 EUR <= 50.00 EUR per month)'
	}

	def "an unreadable language setting falls back quietly and never breaks the decision"() {
		given: 'no Morpheus context: the report connection and the web request are not available'
		def rsp = provider.createApprovalRequest([], userRequest(5L, 10d), integration(7, [costThreshold: '50']), null, [:])

		expect:
		provider.userLocaleSetting(5L) == null
		rsp.references*.status == [RequestReference.ApprovalStatus.approved]
		rsp.externalRequestName == 'Approved automatically (10.00 EUR <= 50.00 EUR per month)'
	}
}
