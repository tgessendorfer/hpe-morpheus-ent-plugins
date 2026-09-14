# Morpheus OpenRouter LLM Plugin

**Work in progress — nothing to install yet.**

An HPE Morpheus Enterprise LLM integration for [OpenRouter](https://openrouter.ai)'s
OpenAI-compatible API, so Morpheus AI agents can use the models of every vendor OpenRouter serves.

- **Claude models:** use [morpheus-anthropic-plugin](https://github.com/tgessendorfer/morpheus-anthropic-plugin).
  It talks to OpenRouter's Anthropic-compatible endpoint and keeps prompt caching and Anthropic's
  server tools.
- **Why not the Local LLM plugin's "OpenAI Compatible" integration?** Version 1.0.0 cannot connect
  to OpenRouter or `api.openai.com`: it sends no SNI in the TLS handshake. See
  [docs/hpe-bug-report-local-llm-sni.md](docs/hpe-bug-report-local-llm-sni.md).

Target: HPE Morpheus Enterprise 9.0.1.

This is an independent, community-built plugin. It is not an official OpenRouter, Anthropic or
Hewlett Packard Enterprise product, and it is neither endorsed by nor affiliated with those
companies.

## License

[Apache License 2.0](LICENSE)
