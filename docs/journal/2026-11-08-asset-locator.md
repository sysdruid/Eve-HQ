# 2026-11-08 - Asset locator

### Context
- **Objective:** Locate assets across characters with a searchable desktop list showing station, container, item type and quantity.

### Architecture & Trade-offs
- **Key Choices:**
  - Core resolves container ancestry and searches an in-memory snapshot; the desktop loads assets only when its tab is opened and refreshes on request.
  - ESI pages, type and custom container names are loaded by the data adapter. Missing names and inaccessible structures retain explicit ID or type fallbacks.
  - Expired tokens use the existing refresh use case. Asset and structure scopes are added to login configuration; older grants may require reauthorization.

### Friction & Gotchas
- **Real Issues Encountered:**
  - ESI's universe-name endpoint returns `404` for an entire batch containing one invalid ID. Bounded breadth-first splitting recovers names without an unbounded retry burst.
  - The first new-API test failed to compile because the API did not exist; several follow-up edge-case tests passed on their first run after the mapping was generalized.
  - The app launched with an isolated database, but the window capture did not reveal the tab content. A real authenticated ESI request and visual tab check remain outstanding.

### Verification
- `./gradlew test` and the IDE project build passed. A live public ESI name lookup resolved type `34` to `Tritanium`.

### Article Hook
- A name-lookup outage should degrade labels, not make already-fetched inventory disappear.