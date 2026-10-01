package com.morpheusdata.msptenantoverview

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.web.MorpheusWebRequestService
import spock.lang.Specification

class MspTenantOverviewAnalyticsProviderSpec extends Specification {

	def "the provider is a master-tenant-only cost page with the planned code"() {
		given:
		def p = new MspTenantOverviewAnalyticsProvider(new MspTenantOverviewPlugin(), null)

		expect:
		p.code == 'msp-tenant-overview-analytics'
		p.masterTenantOnly
		!p.subTenantOnly
		p.category == 'cost'
		p.name == 'MSP Tenant Overview'
	}

	def "without a web request the locale is English and messages fall back to the English default"() {
		given:
		def p = new MspTenantOverviewAnalyticsProvider(new MspTenantOverviewPlugin(), null)

		expect:
		p.viewerLocale() == Locale.ENGLISH
		p.message('error.masterOnly', 'english text', Locale.ENGLISH) == 'english text'
	}

	def "locale and messages come from the web request when there is one"() {
		given:
		MorpheusWebRequestService web = Mock()
		MorpheusContext ctx = Mock() { getWebRequest() >> web }
		web.getLocale() >> Locale.GERMANY
		web.getMessage('msp-tenant-overview-analytics.error.masterOnly', null, 'fallback', Locale.GERMANY) >> 'deutsch'
		def p = new MspTenantOverviewAnalyticsProvider(new MspTenantOverviewPlugin(), ctx)

		expect:
		p.viewerLocale() == Locale.GERMANY
		p.message('error.masterOnly', 'fallback', Locale.GERMANY) == 'deutsch'
	}

	def "a failing web request service does not break the page"() {
		given:
		MorpheusWebRequestService web = Mock()
		MorpheusContext ctx = Mock() { getWebRequest() >> web }
		web.getLocale() >> { throw new IllegalStateException('no request') }
		web.getMessage(*_) >> { throw new IllegalStateException('no request') }
		def p = new MspTenantOverviewAnalyticsProvider(new MspTenantOverviewPlugin(), ctx)

		expect:
		p.viewerLocale() == Locale.ENGLISH
		p.message('error.loadFailed', 'fallback', Locale.ENGLISH) == 'fallback'
	}

	def "every i18n key used by the template and the provider exists in both bundles"() {
		given:
		String hbs = resource('renderer/hbs/mspTenantOverview.hbs')
		Set<String> used = (hbs =~ /\{\{i18n '([^']+)'\}\}/).collect { it[1] } as Set
		used += ['msp-tenant-overview-analytics.error.masterOnly', 'msp-tenant-overview-analytics.error.loadFailed']
		Properties en = bundle('i18n/messages.properties')
		Properties de = bundle('i18n/messages_de.properties')

		expect:
		used.size() > 10
		used.every { it.startsWith('msp-tenant-overview-analytics.') }
		(used - en.stringPropertyNames()).isEmpty()
		(used - de.stringPropertyNames()).isEmpty()
	}

	def "the template carries no hard-coded colors or vendor-specific CSS classes"() {
		given:
		String hbs = resource('renderer/hbs/mspTenantOverview.hbs')

		expect:
		!(hbs =~ /#[0-9A-Fa-f]{6}\b/)
		hbs.contains('class="finops-msp-tenant-overview"')
	}

	private static String resource(String path) {
		InputStream is = MspTenantOverviewAnalyticsProviderSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { it.getText('UTF-8') }
	}

	private static Properties bundle(String path) {
		Properties p = new Properties()
		InputStream is = MspTenantOverviewAnalyticsProviderSpec.classLoader.getResourceAsStream(path)
		assert is != null : "missing resource ${path}"
		is.withCloseable { p.load(it) }
		p
	}
}
