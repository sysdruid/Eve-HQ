# 2026-11-08 - Asset locator

### Context
- **Objective:** Locate assets across characters with a searchable desktop list showing station, container, item type and quantity.
- **Objective:** Persist each character's resolved inventory and revalidate ESI asset pages without hiding the previous snapshot during refresh.

### Architecture & Trade-offs
- **Key Choices:**
  - Core resolves container ancestry and searches an in-memory snapshot; the desktop loads assets only when its tab is opened and refreshes on request.
  - ESI pages, type and custom container names are loaded by the data adapter. Missing names and inaccessible structures retain explicit ID or type fallbacks.
  - `AssetSource` returns self-contained items with grouped type and immediate-location values; ESI keeps lookup joins internal while core follows parent IDs.
  - Expired tokens use the existing refresh use case. Asset and structure scopes are added to login configuration; older grants may require reauthorization.
  - `ListAssets` separates per-character aggregation, source loading/error conversion, and individual row conversion; sorting and search remain after aggregation.
  - H2 stores resolved item rows separately from raw ESI page bodies, with each page's own `Expires`, `ETag` and page count. A successful refresh replaces the pages and display snapshot in one transaction.
  - The persistent asset-cache repository uses generated jOOQ tables/records and a jOOQ transaction, consistent with the other database repositories; static-data archive loading retains JDBC for streaming batch inserts.
  - The UI reads the saved snapshot before network refresh and shows its update time; token refresh remains on the network path, not the cache-read path.
  - ESI mock assertions support both flexible request predicates and endpoint-specific method/path/page checks, with sanitized AssertJ diagnostics.
  - Endpoint page-sequence assertions preserve request order and duplicate counts; missing or malformed page values appear as redacted markers. AssertJ also expresses no-failure, empty-collection, substring, and unordered-collection checks directly.
  - Feature tests exercise `ListAssets` through migrated H2, the real jOOQ cache and ESI source; `EsiMock` is shared from the data module's test fixtures.
  - Repository-only snapshot and deletion tests were removed after feature flows covered persistence, replacement rollback, page cleanup and character removal through public behavior.

### Friction & Gotchas
- **Real Issues Encountered:**
  - ESI's universe-name endpoint returns `404` for an entire batch containing one invalid ID. Bounded breadth-first splitting recovers names without an unbounded retry burst.
  - The first new-API test failed to compile because the API did not exist; several follow-up edge-case tests passed on their first run after the mapping was generalized.
  - The source-contract test first failed against the old inventory-plus-maps shape, then passed after the grouped API and adapter mapping were introduced.
  - The `ListAssets` extraction initially used a suspend function reference with `map`, which Kotlin rejected; invoking it inside the inline lambda compiled and retained sequential loading.
  - The app launched with an isolated database, but the window capture did not reveal the tab content. A real authenticated ESI request and visual tab check remain outstanding.
  - A `304` response has no page body, so it is only usable when that exact page is cached; a missing `Expires` means the next load must revalidate immediately.
  - Page 1's `X-Pages` controls replacement: if pagination shrinks, obsolete cached pages are deleted only after all remaining pages and name lookups complete.
  - Captured requests include sensitive headers, query parameters and bodies; assertion diagnostics must summarize routes instead of rendering request objects.

### Verification
- `./gradlew test` and the IDE project build passed with the self-contained asset contract. A live public ESI name lookup resolved type `34` to `Tritanium`.
- After the `ListAssets` helper extraction, the focused `:core:test --tests eve.hq.core.asset.ListAssetsTest` suite passed, including cancellation, missing-message, and fallback-label cases.
- `EveAssetSourceTest` now uses an endpoint-named `EsiMock` DSL; the focused data test suite passed with paging, batching, authorization, error, and retry assertions preserved.
- `EsiMockTest` and `EveAssetSourceTest` passed with AssertJ predicate and endpoint assertions; the complete `./gradlew test` suite passed with sanitized failure diagnostics.
- `:data:test :core:test :features:test` passed after adding page-sequence assertions and applying the test readability updates; the final `./gradlew test` suite and `git diff --check` also passed.
- Persistent-cache core, H2, ESI and view-model tests passed, including stale display, per-page `304`, transaction rollback, page-count shrink, and character isolation; `./gradlew test` and the IDE build passed.
- The renamed `JooqAssetCacheRepositoryTest` and asset-cache deletion tests passed after the repository refactor, including ordered multi-item reads, nullable validators, rollback, and character isolation.
- `ListAssetsFeatureTest` passed for initial and reopened snapshots, page-specific ETag revalidation, page-count shrink/empty inventory, transactional rollback, and character removal/re-addition; `:data:test :features:test`, full `./gradlew test`, IDE build, and inspections passed.

### Article Hook
- A name-lookup outage should degrade labels, not make already-fetched inventory disappear.
- Small helper boundaries can clarify a suspend use case while keeping cancellation distinct from ordinary per-character failures.
- Endpoint-named HTTP test setup keeps request expectations visible without repeating response plumbing.
- ESI cache validators belong to each paginated representation, while the user-facing snapshot is replaced only after the complete asset/name resolution succeeds.
- Flexible request predicates and endpoint helpers can share test infrastructure while keeping assertion output free of captured credentials and payloads.