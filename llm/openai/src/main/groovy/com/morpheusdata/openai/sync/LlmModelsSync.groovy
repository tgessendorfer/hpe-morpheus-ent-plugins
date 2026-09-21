/*
 * Copyright 2026 Thomas Gessendorfer.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Derived from the Apache 2.0 licensed HPE morpheus-copilot-plugin.
 */
package com.morpheusdata.openai.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.llm.LlmIntegration
import com.morpheusdata.model.llm.LlmModel
import com.morpheusdata.openai.OpenAiApiService
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

import java.util.concurrent.ConcurrentHashMap

/**
 * Syncs the model catalog returned by GET /models into Morpheus.
 *
 * Models that are no longer listed - dropped from the endpoint's catalog, or filtered
 * out by the integration's settings - are removed. Morpheus 9.0.1 shows disabled
 * models exactly like enabled ones, in the integration's model tab and in the agent
 * form, where picking one fails. The foreign key from ai_agent.model_id to llm_model
 * has no cascade, so a model an agent still uses cannot be removed; that one is
 * disabled instead and logged.
 */
@Slf4j
class LlmModelsSync {

	protected final MorpheusContext morpheusContext
	protected final LlmIntegration llmIntegration
	protected final String providerCode
	protected final OpenAiApiService apiService

	// What one sync did, for its log line.
	protected int addedCount = 0
	protected int updatedCount = 0
	protected int removedCount = 0
	protected int disabledCount = 0

	private static final ConcurrentHashMap<Long, Object> SYNC_LOCKS = new ConcurrentHashMap<>()

	LlmModelsSync(MorpheusContext morpheusContext, LlmIntegration llmIntegration, String providerCode, OpenAiApiService apiService) {
		this.morpheusContext = morpheusContext
		this.llmIntegration = llmIntegration
		this.providerCode = providerCode
		this.apiService = apiService ?: new OpenAiApiService()
	}

	Map execute(String baseUrl, String apiKey, Map opts = [:], Closure<Collection<LlmModel>> modelBuilder) {
		Map result = apiService.listModels(baseUrl, apiKey, opts) ?: [success: false, msg: 'No response from the models API']
		if (!result.success) {
			log.warn("Failed to refresh models: ${result.msg}")
			return result
		}
		Map apiResponse = result.data instanceof Map ? result.data as Map : [:]
		// A wrong base URL can answer 200 with a web page instead of the catalog. Synced
		// as an empty catalog, that would remove every model the integration has.
		if (!(apiResponse.data instanceof List)) {
			log.warn("Skipping model sync: ${baseUrl}${OpenAiApiService.MODELS_PATH} returned no model list")
			return [success: false, msg: "${baseUrl}${OpenAiApiService.MODELS_PATH} returned no model list".toString()]
		}
		Collection<LlmModel> freshModels = []
		if (modelBuilder) {
			def builtModels = modelBuilder.call(apiResponse)
			if (builtModels instanceof Collection) {
				freshModels = builtModels as Collection<LlmModel>
			}
		}
		execute(freshModels)
		return result
	}

	void execute(Collection<LlmModel> freshModels) {
		if (!morpheusContext || !providerCode) {
			log.warn('Skipping model sync: sync context is incomplete')
			return
		}
		if (!llmIntegration?.id) {
			log.warn('Skipping model sync: LLM integration is not persisted')
			return
		}
		// Two refreshes of one integration that both find no stored models each create
		// the whole catalog - one way for an integration to end up listing every model
		// twice. Serialised per integration.
		synchronized (SYNC_LOCKS.computeIfAbsent(llmIntegration.id) { Long id -> new Object() }) {
			sync(freshModels ?: [])
		}
	}

	protected void sync(Collection<LlmModel> freshModels) {
		addedCount = 0
		updatedCount = 0
		removedCount = 0
		disabledCount = 0
		Collection<LlmModel> listed = uniqueByCode(freshModels)
		DataQuery query = new DataQuery().withFilter('providerCode', providerCode).withFilter('llmIntegration.id', llmIntegration.id)
		List<LlmModel> existingModels = removeDuplicateModels(morpheusContext.llm.model.list(query).toList().blockingGet())
		SyncTask<LlmModel, LlmModel, LlmModel> syncTask = new SyncTask<>(Observable.fromIterable(existingModels), listed)
		syncTask.addMatchFunction { LlmModel existingModel, LlmModel freshModel ->
			existingModel.code == freshModel.code
		}.onDelete { List<LlmModel> removeList ->
			retireMissingModels(removeList)
		}.onAdd { List<LlmModel> addList ->
			addMissingModels(addList)
		}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<LlmModel, LlmModel>> updateItems ->
			Observable.fromIterable(updateItems.collect { SyncTask.UpdateItemDto<LlmModel, LlmModel> updateItem ->
				new SyncTask.UpdateItem<LlmModel, LlmModel>(existingItem: updateItem.existingItem, masterItem: updateItem.masterItem)
			})
		}.onUpdate { List<SyncTask.UpdateItem<LlmModel, LlmModel>> updateList ->
			updateMatchedModels(updateList)
		}.start()
		// The debug lines per model are off on an appliance; this one says what the
		// integration's settings did to its model list.
		log.info("Model sync for integration ${llmIntegration.id}: ${listed.size()} listed, ${addedCount} added, " +
			"${updatedCount} updated, ${removedCount} removed, ${disabledCount} disabled because an agent still uses them")
	}

	/** One fresh entry per model code; the first one wins. */
	protected static Collection<LlmModel> uniqueByCode(Collection<LlmModel> models) {
		Map<String, LlmModel> unique = new LinkedHashMap<>()
		models?.each { LlmModel model -> unique.putIfAbsent(model.code ?: '', model) }
		return unique.values()
	}

	/**
	 * Collapses stored copies of one model to a single one - enabled first, newest
	 * after that - and removes the rest. A copy that cannot be removed, because an
	 * agent still points at it, stays disabled and is logged so that agent can be
	 * given a model again.
	 */
	protected List<LlmModel> removeDuplicateModels(List<LlmModel> existingModels) {
		List<LlmModel> keep = []
		List<LlmModel> extras = []
		(existingModels ?: []).groupBy { LlmModel model -> model.code ?: '' }.each { String code, List<LlmModel> copies ->
			List<LlmModel> ranked = copies.sort(false) { LlmModel a, LlmModel b ->
				((b.enabled ? 1 : 0) <=> (a.enabled ? 1 : 0)) ?: ((b.id ?: 0L) <=> (a.id ?: 0L))
			}
			keep << ranked[0]
			extras.addAll(ranked.drop(1))
		}
		if (!extras) {
			return keep
		}
		List<LlmModel> failed = removeModels(extras)
		List<LlmModel> removed = extras - failed
		if (removed) {
			log.info("Removed duplicate models: ${removed*.code}")
		}
		if (failed) {
			log.warn("Could not remove duplicate models ${failed*.code}; left disabled - an agent may still point at them")
			disable(failed)
		}
		return keep
	}

	/** Removes models that are no longer listed; one an agent still uses is disabled instead. */
	protected void retireMissingModels(List<LlmModel> missing) {
		if (!missing) {
			return
		}
		List<LlmModel> failed = removeModels(missing)
		removedCount += missing.size() - failed.size()
		if (failed) {
			disabledCount += failed.size()
			log.info("Models no longer listed but still used by an agent, left disabled: ${failed*.code}")
			disable(failed)
		}
	}

	/**
	 * Removes the given models and returns the ones that could not be removed. A single
	 * model an agent still points at fails a whole bulk removal, so a batch that fails
	 * as a whole is retried one model at a time.
	 */
	protected List<LlmModel> removeModels(List<LlmModel> models) {
		if (!models) {
			return []
		}
		try {
			def result = morpheusContext.llm.model.bulkRemove(models).blockingGet()
			List<LlmModel> failedItems = (result?.failedItems ?: []) as List<LlmModel>
			if (failedItems) {
				return failedItems
			}
			if (result?.success != false) {
				return []
			}
		} catch (Exception e) {
			log.debug("Could not remove models ${models*.code}: ${e.message}")
		}
		if (models.size() == 1) {
			return models
		}
		return models.findAll { LlmModel model -> removeModels([model]) }
	}

	protected void disable(List<LlmModel> models) {
		List<LlmModel> saveList = models.findAll { it.enabled != false }
		saveList.each { it.enabled = false }
		if (saveList) {
			morpheusContext.llm.model.bulkSave(saveList).blockingGet()
		}
	}

	protected void addMissingModels(List<LlmModel> addList) {
		if (!addList) {
			return
		}
		addList.each { LlmModel model ->
			model.llmIntegration = llmIntegration
			model.enabled = model.enabled != false
			log.debug("Adding new model: ${model.code}")
		}
		morpheusContext.llm.model.bulkCreate(addList).blockingGet()
		addedCount += addList.size()
	}

	protected void updateMatchedModels(List<SyncTask.UpdateItem<LlmModel, LlmModel>> updateList) {
		List<LlmModel> saveList = []
		updateList?.each { SyncTask.UpdateItem<LlmModel, LlmModel> updateItem ->
			LlmModel existingModel = updateItem.existingItem
			LlmModel freshModel = updateItem.masterItem
			boolean changed = false

			if (existingModel.llmIntegration?.id != llmIntegration.id) {
				existingModel.llmIntegration = llmIntegration
				changed = true
			}
			if (!existingModel.enabled) {
				existingModel.enabled = true
				changed = true
			}
			if (existingModel.contextWindow != freshModel.contextWindow) {
				existingModel.contextWindow = freshModel.contextWindow
				changed = true
			}
			if (existingModel.name != freshModel.name) {
				existingModel.name = freshModel.name
				changed = true
			}
			if (existingModel.maxOutputTokens != freshModel.maxOutputTokens) {
				existingModel.maxOutputTokens = freshModel.maxOutputTokens
				changed = true
			}
			// Morpheus 9.0.1 hands stored models back without their metadata, so comparing
			// against it saved every model on every refresh ("247 updated" with nothing changed).
			Map existingMetadata = existingModel.metadata ?: [:]
			Map freshMetadata = freshModel.metadata ?: [:]
			if (existingMetadata && existingMetadata != freshMetadata) {
				existingModel.metadata = freshMetadata
				changed = true
			}
			if (changed) {
				saveList << existingModel
			}
		}
		if (saveList) {
			morpheusContext.llm.model.bulkSave(saveList).blockingGet()
			updatedCount += saveList.size()
		}
	}
}
