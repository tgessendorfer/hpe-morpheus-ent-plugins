package com.morpheusdata.costapproval

import com.morpheusdata.model.Request
import com.morpheusdata.model.RequestReference
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import spock.lang.Specification

class UserLocaleSpec extends Specification {

	def "the user's setting wins over the browser: en-US with a German browser gives English"() {
		expect:
		UserLocale.resolve('en-US', { Locale.GERMANY }) == Locale.US
		UserLocale.resolve('en-US', { Locale.GERMANY }).language == 'en'
	}

	def "a German setting gives German, also with an English browser and with an underscore"() {
		expect:
		UserLocale.resolve(setting, { Locale.US }).language == 'de'

		where:
		setting << ['de', 'de-DE', 'de_DE', ' de_AT ']
	}

	def "a blank, missing or unparsable setting falls back to the browser"() {
		expect:
		UserLocale.resolve(setting, { Locale.GERMANY }) == Locale.GERMANY

		where:
		setting << [null, '', '   ', '!!!', '-', '_', '12345']
	}

	def "without a web request the result is English"() {
		expect:
		UserLocale.resolve(null, { null }) == Locale.ENGLISH
		UserLocale.resolve('', { throw new IllegalStateException('no request bound to thread') }) == Locale.ENGLISH
		UserLocale.resolve(null, null) == Locale.ENGLISH
	}

	def "the setting is read with a parameterised query"() {
		given:
		Sql sql = Mock(Sql)

		when:
		String setting = UserLocale.setting(sql, 42L)

		then:
		1 * sql.firstRow('SELECT locale FROM user WHERE id = ?', [42L]) >> new GroovyRowResult([locale: 'de-DE'])
		0 * sql._
		setting == 'de-DE'
		!UserLocale.USER_LOCALE_SQL.contains('42')
	}

	def "no user id or no row gives no setting"() {
		given:
		Sql sql = Mock(Sql)

		expect:
		UserLocale.setting(sql, null) == null
		UserLocale.setting(null, 1L) == null

		when:
		String none = UserLocale.setting(sql, 7L)

		then:
		1 * sql.firstRow(UserLocale.USER_LOCALE_SQL, [7L]) >> null
		none == null
	}

	/** Shape of Morpheus' internal request and reference domain objects (not the model classes). */
	static class DomainRequest {
		Long requestByUserId
	}

	static class DomainRef {
		DomainRequest request
		String currency
	}

	def "the requesting user comes from the internal request behind the references"() {
		expect:
		UserLocale.requestingUserId(new Request(refs: [new DomainRef(request: new DomainRequest(requestByUserId: 5L))])) == 5L
		UserLocale.requestingUserId(new DomainRequest(requestByUserId: 9L)) == 9L
	}

	def "a request without a user gives no id"() {
		expect:
		UserLocale.requestingUserId(null) == null
		UserLocale.requestingUserId(new Request()) == null
		UserLocale.requestingUserId(new Request(refs: [new RequestReference(refId: '1')])) == null
		UserLocale.requestingUserId(new Request(refs: [new DomainRef(request: new DomainRequest())])) == null
	}
}
