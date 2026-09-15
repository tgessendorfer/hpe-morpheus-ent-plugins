# Morpheus 9.0.1: an LLM integration's model list has no search or filter

## Summary

The detail page of an LLM integration (*Tools > AI Services > Integrations > (integration) >
Models*) lists every model of the integration, 25 per page, with no search box and no filter. For
an integration against a multi-vendor gateway such as OpenRouter that is several hundred models: an
administrator has to page through a dozen pages to find one.

The server side already supports a search. The page only lacks the input for it.

A second, related problem: **disabled models are shown and counted exactly like enabled ones**,
both on this page and in the model count of the integration list.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.0.1 |
| Plugin API | `morpheus-plugin-api` 1.4.1 |
| Integration | a plugin `LlmProvider` (OpenRouter), 280 models, 33 of them disabled |

## Steps to reproduce

1. Create an LLM integration whose provider syncs a large catalog.
2. Open the integration from *Tools > AI Services > Integrations*.
3. Try to find one model, for example a Gemini model.

**Expected:** a search box and filters above the table, as on other list pages such as
*Infrastructure > Clouds* (search, a status filter, a type filter, labels).

**Actual:** a paginated table (`Showing 1-25 of 280`, 12 pages) with the columns *Name* and *Type*
only. Nothing tells enabled and disabled models apart.

## Evidence

- The page loads its rows with
  `GET /integration/llm/<integrationId>/models?max=25&offset=0` (with
  `X-Requested-With: XMLHttpRequest`). The JSON answer is `{models: [{level, model: {...}}], total, max, offset}`.
- **That endpoint already filters server-side** with a `phrase` parameter:
  `phrase=gemini` returned `total: 15` of 280, `phrase=claude` returned 15.
- Each model in the answer carries `enabled`. In the integration above, 247 were `true` and 33
  `false`, and all 280 were listed and counted.
- The agent form loads its model choices from `/options/llmModels?integrationId=<integrationId>`.
- Plugin API 1.4.1 offers tab providers for instances, servers, clusters, networks and backup
  integrations, but none for LLM integrations, so a plugin cannot add the search itself.

## Suggested improvements

1. **A search box above the model table**, passing the text as `phrase` to the existing endpoint.
2. **A status filter** (all, enabled, disabled), and a visible enabled/disabled column or badge.
3. **A model count in the integration list that counts enabled models only**, or shows both.
4. **Hide disabled models from the agent form's model choice**, or mark them. Picking one today
   fails later with *The AI model is no longer available*.
5. Optionally, an `LlmIntegrationTabProvider` in the plugin API, so providers can add their own
   views to the integration page.

## Workaround in the OpenRouter plugin

The plugin keeps the list short instead: it lists only models that support tool calling, offers an
allow list of model ids with wildcards, and removes models that drop out of the list unless an agent
still uses them.
