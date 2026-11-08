# 2026-11-08 - Industry jobs tab

### Context
- **Objective:** A Jobs tab listing every character's running industry jobs as `Alice-Rifter [10] M  ETL: 2h 14m  Active @ Jita IV`, ready jobs first.
- **Objective:** Request every ESI scope at login so new features never force a re-login of existing characters.

### Architecture & Trade-offs
- **Key Choices:**
  - `ListJobs` in core mirrors `ListAssets`: per-character failures are reported, not fatal, and cancellation is rethrown. Rows keep the end time and ESI status; `isReady(now)` and `remaining(now)` are derived, so the GUI can tick without calling ESI.
  - ESI keeps a finished job `active` until delivery, so "Ready" comes from the clock. Delivered, cancelled and reverted jobs are filtered in core and the status stays in the model for a later history view.
  - Activity is a full-name enum in core; the `M|TE|ME|C|I|RE|R` codes are a GUI concern in the ViewModel. The row shows the product name and falls back to the blueprint for research and copy jobs.
  - Two shared pieces were extracted as behaviour-preserving refactors before the new code: `FreshCharacter` (refresh an expired token or demand a re-login) and `EsiNames` (type names from static data then ESI, station/structure/system names). Facilities reuse the asset location lookup; ids at or above 1e12 are structures.
  - The page cache stores only the raw body, ETag and expiry (V6, cascade-deleted with the character); a fresh page is served from H2 after a restart.
  - `DEFAULT_SCOPES` is publicData plus all 72 scopes from ESI's OpenAPI security scheme; `sso.scopes` still narrows it for a public build.

### Friction & Gotchas
- **Real Issues Encountered:**
  - `= runBlocking { ... assertThat(...).isEqualTo(...) }` makes the JUnit method return a non-`Unit` value, so JUnit silently skips it. My first "green" ran 1 of 4 tests. `runBlocking<Unit>` fixes it; comparing `@Test` counts with executed counts showed no other test was affected.
  - I told the user character removal wasn't covered by a test. It was: the re-add feature test in `ListAssetsFeatureTest` proves the cascade. Check the existing tests before claiming a gap.
  - The Swagger endpoint 404s now; the scope list came from `esi.evetech.net/meta/openapi.json`, whose security scheme also lists scopes that no path references.
  - ESI documents reactions as activity 9 and some tooling reports 11. Both are accepted and unknown ids fail loudly.
  - The GUI was not opened against real ESI: the ViewModel is tested, the Compose screen is only compiled. Facility names are fetched again on every refresh and failed characters lose their rows until the next success.

### Article Hook
- **Publication Takeaway:** A test that silently never runs is worse than no test. In Kotlin, `= runBlocking { assertThat(...) }` skips itself without a warning, so count executed tests against declared ones whenever a new test style goes green on the first run.
