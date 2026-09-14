package com.morpheusdata.openrouter.sync

import com.morpheusdata.core.BulkCreateResult
import com.morpheusdata.core.BulkRemoveResult
import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.integration.MorpheusLlmModelService
import com.morpheusdata.core.integration.MorpheusLlmService
import com.morpheusdata.model.llm.LlmIntegration
import com.morpheusdata.model.llm.LlmModel
import com.morpheusdata.openrouter.OpenRouterApiService
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

	private LlmModel model(Long id, String code, Boolean enabled = true) {
		return new LlmModel(id: id, code: code, name: code, enabled: enabled, llmIntegration: integration, metadata: [:])
	}

	private LlmModelsSync syncOver(List<LlmModel> existing, OpenRouterApiService apiService = null) {
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
			if (removeThrows) {
				throw new RuntimeException('Cannot delete or update a parent row: a foreign key constraint fails')
			}
			removed.addAll(args[0])
			Single.just(removeResult)
		}
		return new LlmModelsSync(context, integration, 'openrouter', apiService)
	}

	def "a model that is no longer listed is disabled, not deleted"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'openai/gpt-5.5'), model(2L, 'z-ai/glm-4.5')])

		when:
		sync.execute([model(null, 'openai/gpt-5.5')])

		then:
		saved*.id == [2L]
		saved*.enabled == [false]
		created.isEmpty()
		removed.isEmpty()
	}

	def "a new model is created and a known one is left alone"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'openai/gpt-5.5')])

		when:
		sync.execute([model(null, 'openai/gpt-5.5'), model(null, 'qwen/qwen3.5-9b')])

		then:
		created*.code == ['qwen/qwen3.5-9b']
		saved.isEmpty()
	}

	def "stored copies of one model collapse to the enabled one and the leftover is removed"() {
		given: 'what two concurrent refreshes can leave behind'
		LlmModelsSync sync = syncOver([model(16L, 'openai/gpt-5.5', false), model(37L, 'openai/gpt-5.5')])

		when:
		sync.execute([model(null, 'openai/gpt-5.5')])

		then:
		removed*.id == [16L]
		created.isEmpty()
		!saved.any { !it.enabled }
	}

	def "a copy that cannot be removed stays disabled"() {
		given: 'an agent still points at the leftover copy'
		removeThrows = true
		LlmModelsSync sync = syncOver([model(16L, 'openai/gpt-5.5'), model(37L, 'openai/gpt-5.5')])

		when:
		sync.execute([model(null, 'openai/gpt-5.5')])

		then: 'the newer copy is kept, the older one is disabled rather than left selectable'
		saved.find { it.id == 16L }?.enabled == false
		created.isEmpty()
	}

	def "a response without a model list leaves the stored models alone"() {
		given: 'a wrong base URL answering 200 with a web page'
		OpenRouterApiService api = Mock()
		api.listModels(_, _, _) >> [success: true, data: [data: '<!DOCTYPE html><html></html>']]
		LlmModelsSync sync = syncOver([model(1L, 'openai/gpt-5.5')], api)

		when:
		Map result = sync.execute('https://openrouter.ai/v1', 'sk-or-v1-test', [:]) { Map response -> [] }

		then: 'an empty catalog would have disabled every model'
		!result.success
		saved.isEmpty()
		created.isEmpty()
	}
}
