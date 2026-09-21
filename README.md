# HPE Morpheus Enterprise plugins

> **Independent community project.** Not an official product of Hewlett Packard Enterprise,
> Anthropic, OpenAI or OpenRouter, and neither endorsed by nor affiliated with any of them. See
> [Trademarks](#trademarks).

Plugins for HPE Morpheus Enterprise, one folder per plugin, grouped by provider type. Each plugin
builds, versions and releases on its own.

| Plugin | Provider type | Folder | Latest release | Status |
|---|---|---|---|---|
| Anthropic Claude | LLM | [`llm/anthropic`](llm/anthropic) | [1.5.1](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/anthropic-v1.5.1) | Claude over the native Messages API, direct or through OpenRouter. Verified on Morpheus 9.0.1. |
| OpenRouter | LLM | [`llm/openrouter`](llm/openrouter) | [0.1.2](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/openrouter-v0.1.2) | Every other model OpenRouter serves, over its OpenAI-compatible API. Verified on Morpheus 9.0.1. |
| OpenAI-Compatible API | LLM | [`llm/openai`](llm/openai) | [0.1.0](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/openai-v0.1.0) | Any OpenAI-compatible chat API — api.openai.com, OpenRouter, LiteLLM, vLLM, Ollama — over HTTP or HTTPS, where HPE's Local LLM plugin fails on SNI. Verified on Morpheus 9.0.2. |
| Proxmox VE | Cloud | [`cloud/proxmox-ve`](cloud/proxmox-ve) | [0.1.27-lab](https://github.com/tgessendorfer/hpe-morpheus-ent-plugins/releases/tag/proxmox-ve-v0.1.27-lab) | Lab build of HPE's community Proxmox VE plugin, with Proxmox VE 9 support and working provisioning. Verified on Morpheus 9.0.2 with Proxmox VE 9.2.20. Community code without support. |

## Installing

Download the plugin's shaded `-all.jar` from its release and upload it under *Administration >
Integrations > Plugins*. Each plugin's README covers the setup in Morpheus.

| Plugin | Jar | Plugin code |
|---|---|---|
| Anthropic Claude | `morpheus-anthropic-plugin-<version>-all.jar` | `morpheus-anthropic-plugin` |
| OpenRouter | `morpheus-openrouter-plugin-<version>-all.jar` | `morpheus-openrouter-plugin` |
| OpenAI-Compatible API | `morpheus-openai-plugin-<version>-all.jar` | `morpheus-openai-plugin` |
| Proxmox VE | `proxmox-ve-<version>-lab-all.jar` | `proxmox-ve` |

Morpheus identifies a plugin by its code, so a jar from this repository upgrades a plugin installed
from the plugins' earlier repositories.

## Building

Each plugin folder is a standalone Gradle project with its own wrapper. Use JDK 17: Groovy 3.0.9
does not run on JDK 21, and the Proxmox VE plugin's Gradle 8.3 stops at JDK 20.

```bash
(cd llm/anthropic && ./gradlew clean test shadowJar)    # build/libs/morpheus-anthropic-plugin-<version>-all.jar
(cd llm/openrouter && ./gradlew clean test shadowJar)   # build/libs/morpheus-openrouter-plugin-<version>-all.jar
(cd llm/openai && ./gradlew clean test shadowJar)       # build/libs/morpheus-openai-plugin-<version>-all.jar
(cd cloud/proxmox-ve && ./gradlew clean build)          # build/libs/proxmox-ve-<version>-all.jar
```

The Proxmox VE plugin has no `settings.gradle`, so its jar takes its name from the folder.

## Releases

Every plugin has its own tag prefix:

| Plugin | Tag |
|---|---|
| Anthropic Claude | `anthropic-v<version>` |
| OpenRouter | `openrouter-v<version>` |
| OpenAI-Compatible API | `openai-v<version>` |
| Proxmox VE | `proxmox-ve-v<version>-lab` |

A pushed tag runs [`.github/workflows/release.yml`](.github/workflows/release.yml). It checks the tag
against `version` in the plugin's `gradle.properties`, runs the tests, builds the plugin and creates
the release with the `-all.jar`, a `SHA256SUMS` file and the plugin's section of its
`RELEASE-NOTES.md`. The build workflows run only for the plugin whose folder changed.

## Repository layout

```text
llm/anthropic      Anthropic Claude LLM provider
llm/openrouter     OpenRouter LLM provider
llm/openai         OpenAI-compatible LLM provider
cloud/proxmox-ve   Proxmox VE cloud provider
docs/hpe           Bug reports and feature requests sent to HPE
```

## History

The Anthropic, OpenRouter and Proxmox VE plugins started in separate repositories and moved here
with their full history; the OpenAI-compatible plugin began here as a copy of the OpenRouter plugin.
`git log --follow` shows a file's commits from before the move, and the old release tags carry the
plugin's prefix. The Proxmox VE plugin was a GitHub fork of HPE's repository; its
[README](cloud/proxmox-ve/README.md) describes how to merge later upstream changes.

## License

[Apache License 2.0](LICENSE). Attributions for the work these plugins derive from are in
[NOTICE](NOTICE).

## Trademarks

"HPE", "Hewlett Packard Enterprise" and "Morpheus" are trademarks of Hewlett Packard Enterprise
Development LP. "Anthropic" and "Claude" are trademarks of Anthropic PBC. "OpenAI", "GPT" and "ChatGPT"
are trademarks of OpenAI. "OpenRouter" is a trademark of its owner. "Proxmox" is a trademark of Proxmox Server Solutions GmbH. Other model and vendor names
are trademarks of their respective owners. They are used here solely to identify the products these
plugins integrate with.
