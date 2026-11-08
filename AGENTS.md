# Eve-HQ

Kotlin Multiplatform (Compose Desktop) operations dashboard for managing EVE Online manufacturing pipelines, asset locations and market margins across 12 characters. It unifies multi-account ESI data into a single operational cockpit, backed by an embedded H2 database.

## Principles

- **Core is UI-independent.** Domain logic lives in a pure Kotlin `core` module with no UI, DB or HTTP imports. Connectors (Compose GUI, MCP server, future AI agent tools) are thin adapters over the same core use cases.
- **TDD is mandatory.** Write a failing test first, make it pass, then refactor. No production code without a test that demanded it. Prefer testing through core's public interface with fakes for ports (ESI, storage, clock).
- **Ports and adapters.** Core defines interfaces for external things (ESI, persistence); `data` implements them.

## Planned modules

- `core`: domain model and use cases (industry, assets, market, characters)
- `data`: ESI client (Ktor, OAuth2 PKCE) and H2 persistence
- `app`: Compose Desktop GUI
- `agent-tools` (later): MCP/agent connector

## Status

Greenfield. Nothing is built yet.
