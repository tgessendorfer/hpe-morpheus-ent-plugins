package com.morpheusdata.socketreport

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Content language: the Morpheus user setting first, then the browser, then English.
 */
class ContentLocaleSpec extends Specification {

	@Unroll
	def "the setting '#raw' parses to #expected"() {
		expect:
		ContentLocale.parse(raw) == expected

		where:
		raw          | expected
		'en-US'      | Locale.forLanguageTag('en-US')
		'de'         | Locale.GERMAN
		'de_DE'      | Locale.GERMANY
		' de-AT '    | Locale.forLanguageTag('de-AT')
		null         | null
		''           | null
		'   '        | null
		'garbage'    | null
		'12-34'      | null
		'x' * 100    | null
	}

	@Unroll
	def "setting #setting with browser #browser resolves to #expected"() {
		expect:
		ContentLocale.resolve(ContentLocale.parse(setting), browser) == expected

		where:
		setting   | browser        | expected
		'en-US'   | Locale.GERMANY | Locale.forLanguageTag('en-US')
		'de'      | Locale.US      | Locale.GERMAN
		null      | Locale.GERMANY | Locale.GERMANY
		''        | Locale.GERMANY | Locale.GERMANY
		'garbage' | Locale.GERMANY | Locale.GERMANY
		null      | null           | Locale.ENGLISH
	}

	def "an en-US setting gives English texts even when the browser sends German"() {
		given:
		Locale locale = ContentLocale.resolve(ContentLocale.parse('en-US'), Locale.GERMANY)

		expect:
		ContentLocale.texts(locale).title == 'Socket Usage'
		ContentLocale.texts(locale).column.tenant == 'Tenant'
	}

	def "a German setting gives German texts"() {
		expect:
		ContentLocale.texts(Locale.GERMAN).title == 'Socket-Verbrauch'
		ContentLocale.texts(Locale.GERMANY).stat.total == 'Sockets gesamt'
	}

	def "only the bundle languages are used for texts; any other language gets English"() {
		expect:
		ContentLocale.messageLocale(Locale.FRANCE) == Locale.ENGLISH
		ContentLocale.messageLocale(Locale.forLanguageTag('de-CH')) == Locale.GERMAN
		ContentLocale.messageLocale(null) == Locale.ENGLISH
		ContentLocale.texts(Locale.FRANCE).title == 'Socket Usage'
	}

	def "texts hold every report key of the English bundle, nested by its parts"() {
		given:
		Properties p = new Properties()
		getClass().getResourceAsStream('/i18n/messages.properties').withStream { p.load(it) }
		Map texts = ContentLocale.texts(Locale.GERMAN)

		expect:
		p.stringPropertyNames().findAll { it.startsWith('socket-usage-report.') }.every { String key ->
			Object node = texts
			key.substring('socket-usage-report.'.length()).tokenize('.').each { node = ((Map) node)[it] }
			node instanceof String && node
		}
	}

	def "the user lookup is one parameterised query"() {
		given:
		Sql sql = Mock(Sql)

		when:
		Object raw = ContentLocale.lookupSetting(sql, 42L)

		then:
		1 * sql.firstRow('SELECT locale FROM user WHERE id = ?', [42L]) >> new GroovyRowResult([locale: 'en-US'])
		0 * sql._
		raw == 'en-US'
		!ContentLocale.USER_LOCALE_SQL.contains('42')
		ContentLocale.USER_LOCALE_SQL.count('?') == 1
	}

	def "the user lookup without a user or a row returns null"() {
		given:
		Sql sql = Mock(Sql)

		expect:
		ContentLocale.lookupSetting(sql, null) == null
		ContentLocale.lookupSetting(null, 1L) == null

		when:
		Object raw = ContentLocale.lookupSetting(sql, 7L)

		then:
		1 * sql.firstRow(_ as String, [7L]) >> null
		raw == null
	}
}
