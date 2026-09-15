package com.morpheusdata.anthropic.sync

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

	private LlmModel model(Long id, String code, Boolean enabled = true) {
		return new LlmModel(id: id, code: code, name: code, enabled: enabled, llmIntegration: integration, metadata: [:])
	}

	private LlmModelsSync syncOver(List<LlmModel> existing) {
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
		return new LlmModelsSync(context, integration, 'anthropic-claude', null)
	}

	def "a model that left the catalog is disabled"() {
		given:
		LlmModelsSync sync = syncOver([model(1L, 'claude-haiku-4-5'), model(2L, 'claude-3-haiku')])

		when:
		sync.execute([model(null, 'claude-haiku-4-5')])

		then:
		saved*.id == [2L]
		saved*.enabled == [false]
		created.isEmpty()
		removed.isEmpty()
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
