# 2026-11-08 - Name, blueprint-page and startup caches

### Context
- **Objective:** Stop refetching station/structure names on every refresh, cache blueprint pages like job pages, and show the last known jobs and blueprints at startup before the network answers.

### Architecture & Trade-offs
- **Key Choices:**
  - A shared `location_names` table (V9) behind `EsiNames`, used by assets, jobs and blueprints. It is not per character, so character removal does not touch it.
  - Only a refusal (403/404) is remembered as "unresolved" and retried after a day. Server errors and network failures are never remembered: they say nothing about the location, and remembering them would hide names for a day after a short outage.
  - Blueprint pages (V10) follow the asset-page pattern: per-page body, ETag, expiry and page count, replaced as a set so pages the list no longer has disappear, cascade-deleted with the character. Only expired pages are requested again.
  - The startup view is built from cached pages plus names that need no network: type names from the imported static data, location names from the name cache. `JobSource.cached` and `BlueprintSource.cached` default to null; `ListJobs.cached` and `ListBlueprints.cached` build rows, and blueprints get in-use marks from cached jobs. Both ViewModels show cached rows while the refresh runs.
  - The mapping from ESI records to domain objects is shared by the live and cached paths, so they cannot drift apart.
  - Resolved snapshots (as for assets) were not added; page cache plus name cache gives the same startup view with less stored data.

### Friction & Gotchas
- **Real Issues Encountered:**
  - While sharing the blueprint mapping I moved the static max-runs lookup inside the `map` lambda, which would have queried the database once per blueprint. Tests passed; reading the diff caught it.
  - At startup a type missing from the static data shows as "Unknown type (id)" until the live load finishes. That is accepted: the static import is a prerequisite for assets already.
  - Feature tests needed a small helper that inserts static types, since the feature database has none.
  - The job and blueprint page caches still live in separate tables (and assets in a third); merging them was left alone because it would mean migrating working data.

### Article Hook
- **Publication Takeaway:** When caching lookups, decide what a failure means. A refusal is a fact about the thing you asked for; a timeout is a fact about your network. Cache the first, never the second.
