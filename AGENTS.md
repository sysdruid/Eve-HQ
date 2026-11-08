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

Iteration 1 in progress: log in via EVE SSO and list characters. The Gradle skeleton
(`core`, `data`, `app`) builds; domain logic, persistence and UI are being added step by step.
The plan lives in `TESTLIST.md`.

## Additional rules

- **Journal on completion.** When a plan step or feature is finished (its tests are green), invoke
  the `journal` skill before the final wrap-up. Append to the day's file for that topic in
  `docs/journal/` if one exists. Journal entries are committed with the work.
