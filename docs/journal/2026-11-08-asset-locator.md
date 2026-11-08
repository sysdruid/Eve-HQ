# 2026-11-08 - Asset locator

### Context
- **Objective:** Locate assets across characters with a searchable desktop list showing station, container, item type and quantity.

### Architecture & Trade-offs
- **Key Choices:**
  - Core resolves container ancestry and searches an in-memory snapshot; the desktop loads assets only when its tab is opened and refreshes on request.
  - ESI pages, type and custom container names are loaded by the data adapter. Missing names and inaccessible structures retain explicit ID or type fallbacks.
  - `AssetSource` returns self-contained items with grouped type and immediate-location values; ESI keeps lookup joins internal while core follows parent IDs.
  - Expired tokens use the existing refresh use case. Asset and structure scopes are added to login configuration; older grants may require reauthorization.
  - `ListAssets` separates per-character aggregation, source loading/error conversion, and individual row conversion; sorting and search remain after aggregation.

### Friction & Gotchas
- **Real Issues Encountered:**
  - ESI's universe-name endpoint returns `404` for an entire batch containing one invalid ID. Bounded breadth-first splitting recovers names without an unbounded retry burst.
  - The first new-API test failed to compile because the API did not exist; several follow-up edge-case tests passed on their first run after the mapping was generalized.
  - The source-contract test first failed against the old inventory-plus-maps shape, then passed after the grouped API and adapter mapping were introduced.
  - The `ListAssets` extraction initially used a suspend function reference with `map`, which Kotlin rejected; invoking it inside the inline lambda compiled and retained sequential loading.
  - The app launched with an isolated database, but the window capture did not reveal the tab content. A real authenticated ESI request and visual tab check remain outstanding.

### Verification
- `./gradlew test` and the IDE project build passed with the self-contained asset contract. A live public ESI name lookup resolved type `34` to `Tritanium`.
- After the `ListAssets` helper extraction, the focused `:core:test --tests eve.hq.core.asset.ListAssetsTest` suite passed, including cancellation, missing-message, and fallback-label cases.
- `EveAssetSourceTest` now uses an endpoint-named `EsiMock` DSL; the focused data test suite passed with paging, batching, authorization, error, and retry assertions preserved.

### Article Hook
- A name-lookup outage should degrade labels, not make already-fetched inventory disappear.
- Small helper boundaries can clarify a suspend use case while keeping cancellation distinct from ordinary per-character failures.
- Endpoint-named HTTP test setup keeps request expectations visible without repeating response plumbing.