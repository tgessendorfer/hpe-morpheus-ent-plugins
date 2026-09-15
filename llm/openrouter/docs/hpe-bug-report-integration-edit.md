# Morpheus 9.0.1: editing a plugin LLM integration ignores a credential switch and an emptied field

## Summary

Two changes made in the *Edit Integration* dialog of a plugin LLM integration are not saved, although
Morpheus reports *Update Successful*:

1. **Switching *Credentials* from a stored credential to *Local Credentials*.** The integration keeps
   the stored credential. The key typed into the local field is neither saved nor validated.
2. **Emptying a text field defined by the plugin.** The integration keeps the previous value.

In both cases the dialog shows the old value again when it is reopened. Neither problem shows up in
the log.

A related limitation, known from earlier plugins: the network proxy select renders no empty entry, so
a chosen proxy cannot be unselected either.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.0.1 |
| Plugin API | `morpheus-plugin-api` 1.4.1 |
| Integration | a plugin `LlmProvider` (OpenRouter) |

The credential option is declared exactly like in HPE's own `morpheus-copilot-plugin`:

```groovy
new OptionType(code: 'openrouter.credential', inputType: OptionType.InputType.CREDENTIAL,
    fieldName: 'type', fieldContext: 'credential', optionSource: 'credentials',
    defaultValue: 'local', config: '{"credentialTypes":["api-key"]}')
new OptionType(code: 'openrouter.servicePassword', inputType: OptionType.InputType.PASSWORD,
    fieldName: 'servicePassword', fieldContext: 'domain', localCredential: true)
```

## Issue 1: the credential switch is not applied

### Steps to reproduce

1. Create an *API Key* credential under *Infrastructure > Trust > Credentials*.
2. Create a plugin LLM integration and select that credential.
3. Edit the integration, change *Credentials* to *Local Credentials* and enter a different key.
4. Save.

**Expected:** the integration uses the local key; the plugin's `validate()` receives it.

**Actual:** *Update Successful*. `GET /api/integrations/<id>` still names the stored credential
(`credential.type: api-key`), the dialog shows it again, and `validate()` received the stored key.
A deliberately invalid local key (`sk-or-v1-0000000000`) was accepted, which it would not have been if
it had reached the plugin: the same key is rejected with `401 User not found.` when a new
integration is created with it.

**What the dialog sends:** the save request body carries `credential.type: "local"` and the new key
in `accountIntegration.servicePassword`, but it still includes the previously selected credential's
`credential.id`. The server keeps that stored credential. Either the dialog should drop
`credential.id` when *Local Credentials* is chosen, or the server should let `type: "local"` win.

### Workarounds

- Change the key inside the stored credential.
- Create a new integration with *Local Credentials* and point the agents at it.

## Issue 2: an emptied text field keeps its old value

### Steps to reproduce

1. Edit a plugin LLM integration and enter a value into an optional text field of type
   `OptionType.InputType.TEXT` with `fieldContext: 'config'`. Save; the value is stored.
2. Edit again, clear the field completely, save.

**Expected:** the config value is empty.

**Actual:** no error, and `GET /api/integrations/<id>` still returns the previous value in `config`.
Saving a different, non-empty value works.

**Cause, as far as it shows in the browser:** the dialog's controlled input falls back to the stored
value the moment it is empty. Setting the input `config.modelAllowList` to `""` (native value setter
plus an `input` event) made it display the stored `*` again immediately, while `" "` and `x` stayed
as entered. The request body sent on save carried the stored value, so the server never received an
empty one. The same probably applies to other plugin `TEXT` options; plugin API 1.4.2 offers no
`OptionType` attribute that changes it.

### Workaround

The plugin defaults the field to `*` (every model) and tells the user to enter `*` instead of
clearing it.

## Issue 3: the REST API update hands the provider the request instead of the credential

`PUT /api/integrations/<id>` with only `{"integration":{"refresh":false,"config":{...}}}` calls the
provider's `validate()` with an `AccountIntegration` whose `credentialData` holds the request's own
top-level keys (`config`, `refresh`) and whose `credentialLoaded` is `true`. The stored credential's
data is missing, so a provider that reads the key from `credentialData` falls back to the local
`servicePassword`. A config-only change of an integration with a stored credential then fails
validation (`401` from the upstream API).

The plugin now loads the credential itself through
`morpheusContext.services.accountCredential.loadCredentials(accountIntegration)`, which finds it.

A related observation: a text field emptied through the REST API (`""`, stored and returned as `''`)
is written back by the edit dialog as the two characters `""` on the next save.

## Suggested fix

- Send and apply the credential selection and the local credential fields on update, as on create.
- Save an emptied config field as empty instead of keeping the previous value.
- Render an empty entry in optional selects (`noBlank: false`, `noSelection`) in integration forms.
