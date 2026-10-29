package com.morpheusdata.anthropic.sync

import com.morpheusdata.anthropic.AnthropicApiService
import com.morpheusdata.core.BulkCreateResult
import com.morpheusdata.core.BulkRemoveResult
import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.integration.MorpheusLlmModelService
import com.morpheusdata.core.integration.MorpheusLlmService
import com.morpheusdata.model.llm.LlmIntegration
import com.morpheusdata.model.llm.LlmModel
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * Covers how the catalog sync treats the models already stored for an integration.
 */
class LlmModelsSyncSpec extends Specification {

	LlmIntegration integration = new LlmIntegration(id: 7L)
	MorpheusLlmModelService modelService = Mock()
	List<LlmModel> saved = []
	List<LlmModel> created = []
	List<LlmModel> removed = []
	boolean removeThrows = false
	// Models an agent points at: a bulk removal that holds one of them fails as a whole.
	Set<Long> inUse = []
	List<List<Long>> removeAttempts = []

	private LlmModel model(Long id, String code, Boolean enabled = true) {
		return new LlmModel(id: id, code: code, name: code, enabled: enabled, llmIntegration: integration, metadata: [:])
	}

	private LlmModelsSync syncOver(List<LlmModel> existing, AnthropicApiService apiService = null) {
		MorpheusLlmService llm = Mock()
		MorpheusContext context = Mock()
		BulkRemoveResult removeResult = Mock()
		removeResult.getSuccess() >> true
		context.getLlm() >> llm
		llm.getModel() >> modelService
		modelService.list(_) >> Observable.fromIterable(existing)
		modelService.bulkSave(_) >> { List<List<LlmModel>> args -> saved.addAll(args[0]); Single.just(Mock(BulkSaveResult)) }
		modelService.bulkCreate(_) >> { List<List<LlmModel>> args -> created.addAll(args[0]); Single.just(Mock(BulkCreateResult)) }
		modelService.bulkRemove(_) >> { List<List<LlmModel>> args ->
			removeAttempts << args[0]*.id
			if (removeThrows || args[0].any { LlmModel model -> model.id in inUse }) {
				throw new RuntimeException('Cannot delete or update a parent row: a foreign key constraint fails')
			}
			removed.addAll(args[0])
			Single.just(removeResult)
		}
		return new LlmModelsSync(context, integration, 'anthropic-claude', apiService)
	}

	private static AnthropicApiService listing(Map result) {
		return new AnthropicApiService() {
			@Override
			Map listModels(String baseUrl, String apiKey, String apiVersion, Map opts) {
				return result
			}
		}
	}

	def "a model that left the catalog is removed, since Morpheus still offers a disabled one in the agent form"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'claude-haiku-4-5'), model(2L, 'claude-3-haiku')])

		when:
		sync.execute([model(null, 'claude-haiku-4-5')])

		then:
		removed*.id == [2L]
		!saved.any { !it.enabled }
		created.isEmpty()
	}

	def "a model that left the catalog but an agent still uses is disabled instead"() {
		given: 'the foreign key from the agent refuses the removal'
		removeThrows = true
		LlmModelsSync sync = syncOver([model(1L, 'claude-haiku-4-5'), model(2L, 'claude-3-haiku')])

		when:
		sync.execute([model(null, 'claude-haiku-4-5')])

		then:
		saved*.id == [2L]
		saved*.enabled == [false]
		created.isEmpty()
	}

	def "a bulk removal that fails on one model in use is retried model by model"() {
		given: 'two models left the catalog, and an agent still points at one of them'
		inUse << 2L
		LlmModelsSync sync = syncOver([model(1L, 'claude-haiku-4-5'), model(2L, 'claude-3-haiku'), model(3L, 'claude-3-opus')])

		when:
		sync.execute([model(null, 'claude-haiku-4-5')])

		then: 'the batch fails, then each model is tried on its own'
		removeAttempts.size() == 3
		removeAttempts[0].sort() == [2L, 3L]
		removeAttempts.drop(1).flatten().sort() == [2L, 3L]

		and: 'the free model is removed, the one in use is kept and disabled'
		removed*.id == [3L]
		saved*.id == [2L]
		saved*.enabled == [false]
		created.isEmpty()
	}

	def "a catalog without a model list, or without any Claude model, leaves the stored models alone"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'claude-haiku-4-5')], listing(response))

		when:
		Map result = sync.execute('https://api.example.com', 'sk-ant-test', null, [:]) { Map apiResponse ->
			(apiResponse.data ?: []).findAll { it.id?.startsWith('claude') }.collect { model(null, it.id as String) }
		}

		then:
		result.success == succeeds
		removed.isEmpty()
		saved.isEmpty()
		created.isEmpty()

		where:
		response                                                   || succeeds
		[success: true, data: [data: '<html>sign in</html>']]      || false
		[success: true, data: [:]]                                 || false
		[success: true, data: [data: [[id: 'gpt-5.4-mini']]]]      || true
		[success: true, data: [data: []]]                          || true
	}

	def "a full catalog still adds and removes through the API path"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'claude-3-haiku')],
			listing([success: true, data: [data: [[id: 'claude-haiku-4-5']]]]))

		when:
		sync.execute('https://api.example.com', 'sk-ant-test', null, [:]) { Map apiResponse ->
			apiResponse.data.collect { model(null, it.id as String) }
		}

		then:
		created*.code == ['claude-haiku-4-5']
		removed*.id == [1L]
	}

	def "a listing that switches to the [1m] spelling keeps the stored model instead of adding a copy"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'anthropic/claude-sonnet-4.6')])

		when:
		sync.execute([model(null, 'anthropic/claude-sonnet-4.6[1m]')])

		then: 'the agent that points at model 1 keeps working'
		created.isEmpty()
		!saved.any { !it.enabled }
		removed.isEmpty()
	}

	def "stored copies of one model collapse to the enabled one and the leftover is removed"() {
		given: 'what two refreshes that saw both OpenRouter spellings left behind'
		LlmModelsSync sync = syncOver([model(16L, 'anthropic/claude-sonnet-4.6', false), model(37L, 'anthropic/claude-sonnet-4.6[1m]')])

		when:
		sync.execute([model(null, 'anthropic/claude-sonnet-4.6[1m]')])

		then:
		removed*.id == [16L]
		created.isEmpty()
		!saved.any { !it.enabled }
	}

	def "a copy that cannot be removed stays disabled"() {
		given: 'an agent still points at the leftover copy'
		removeThrows = true
		LlmModelsSync sync = syncOver([model(16L, 'anthropic/claude-sonnet-4.6'), model(37L, 'anthropic/claude-sonnet-4.6[1m]')])

		when:
		sync.execute([model(null, 'anthropic/claude-sonnet-4.6[1m]')])

		then: 'the newer copy is kept, the older one is disabled rather than left selectable and working by accident'
		saved.find { it.id == 16L }?.enabled == false
		created.isEmpty()
	}
}
