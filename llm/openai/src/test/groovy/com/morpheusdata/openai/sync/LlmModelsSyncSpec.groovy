package com.morpheusdata.openai.sync

import com.morpheusdata.core.BulkCreateResult
import com.morpheusdata.core.BulkRemoveResult
import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.integration.MorpheusLlmModelService
import com.morpheusdata.core.integration.MorpheusLlmService
import com.morpheusdata.model.llm.LlmIntegration
import com.morpheusdata.model.llm.LlmModel
import com.morpheusdata.openai.OpenAiApiService
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
	// Ids of models an agent points at. Removing one fails the whole call, like the
	// foreign key from ai_agent.model_id does.
	Set<Long> usedByAgent = [] as Set

	private LlmModel model(Long id, String code, Boolean enabled = true) {
		return new LlmModel(id: id, code: code, name: code, enabled: enabled, llmIntegration: integration, metadata: [:])
	}

	private LlmModelsSync syncOver(List<LlmModel> existing, OpenAiApiService apiService = null) {
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
			if (args[0].any { it.id in usedByAgent }) {
				throw new RuntimeException('Cannot delete or update a parent row: a foreign key constraint fails (fk_ai_agent_model)')
			}
			removed.addAll(args[0])
			Single.just(removeResult)
		}
		return new LlmModelsSync(context, integration, 'openai-llm', apiService)
	}

	def "a model that is no longer listed is removed"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'openai/gpt-5.5'), model(2L, 'z-ai/glm-4.5')])

		when:
		sync.execute([model(null, 'openai/gpt-5.5')])

		then:
		removed*.id == [2L]
		saved.isEmpty()
		created.isEmpty()
		sync.removedCount == 1
		sync.disabledCount == 0
	}

	def "a model an agent still uses stays disabled, and the rest of the batch is removed all the same"() {
		given: 'unticking a checkbox drops three models, one of which an agent uses'
		usedByAgent << 3L
		LlmModelsSync sync = syncOver([model(1L, 'openai/gpt-5.5'), model(2L, 'anthropic/claude-haiku-4.5'),
									   model(3L, 'anthropic/claude-sonnet-5'), model(4L, 'google/gemma-4-31b-it:free')])

		when:
		sync.execute([model(null, 'openai/gpt-5.5')])

		then:
		removed*.id as Set == [2L, 4L] as Set
		saved*.id == [3L]
		saved*.enabled == [false]
		sync.removedCount == 2
		sync.disabledCount == 1
	}

	def "a leftover that was already disabled is removed too"() {
		given: 'what version 0.1.0-SNAPSHOT left behind, which only disabled such models'
		LlmModelsSync sync = syncOver([model(1L, 'openai/gpt-5.5'), model(2L, 'anthropic/claude-opus-4', false)])

		when:
		sync.execute([model(null, 'openai/gpt-5.5')])

		then:
		removed*.id == [2L]
		saved.isEmpty()
	}

	def "a new model is created and a known one is left alone"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'openai/gpt-5.5')])

		when:
		sync.execute([model(null, 'openai/gpt-5.5'), model(null, 'qwen/qwen3.5-9b')])

		then:
		created*.code == ['qwen/qwen3.5-9b']
		saved.isEmpty()
		removed.isEmpty()
		sync.addedCount == 1
		sync.updatedCount == 0
	}

	def "a stored model that comes back without metadata is not saved again"() {
		given: 'what Morpheus hands back for a model the plugin stored with metadata'
		LlmModel stored = model(1L, 'openai/gpt-5.5')
		stored.metadata = null
		LlmModel fresh = model(null, 'openai/gpt-5.5')
		fresh.metadata = [supportsToolUse: true, apiFormat: 'openai-chat-completions']
		LlmModelsSync sync = syncOver([stored])

		when:
		sync.execute([fresh])

		then:
		saved.isEmpty()
		sync.updatedCount == 0
	}

	def "a changed name is still saved"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'dots-studio/dots-3-note-preview')])
		LlmModel fresh = model(null, 'dots-studio/dots-3-note-preview')
		fresh.name = 'Dots 3 (expires 2026-09-30)'

		when:
		sync.execute([fresh])

		then:
		saved*.name == ['Dots 3 (expires 2026-09-30)']
		sync.updatedCount == 1
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
		usedByAgent << 16L
		LlmModelsSync sync = syncOver([model(16L, 'openai/gpt-5.5'), model(37L, 'openai/gpt-5.5')])

		when:
		sync.execute([model(null, 'openai/gpt-5.5')])

		then: 'the newer copy is kept, the older one is disabled rather than left selectable'
		saved.find { it.id == 16L }?.enabled == false
		created.isEmpty()
	}

	def "a response without a model list leaves the stored models alone"() {
		given: 'a wrong base URL answering 200 with a web page'
		OpenAiApiService api = Mock()
		api.listModels(_, _, _) >> [success: true, data: [data: '<!DOCTYPE html><html></html>']]
		LlmModelsSync sync = syncOver([model(1L, 'openai/gpt-5.5')], api)

		when:
		Map result = sync.execute('https://openrouter.ai/v1', 'sk-or-v1-test', [:]) { Map response -> [] }

		then: 'an empty catalog would have removed every model'
		!result.success
		saved.isEmpty()
		created.isEmpty()
		removed.isEmpty()
	}
}
