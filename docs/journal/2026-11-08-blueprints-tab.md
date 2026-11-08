# 2026-11-08 - Blueprints tab

### Context
- **Objective:** A Blueprints tab (one row per blueprint item, searchable, free-only filter) showing owner, blueprint name, original/copy, ME/TE, remaining runs on copies, location and whether a running job uses it. First visible result of the production roadmap.
- **Objective:** Keep the SDE max production limit in the model without showing it.

### Architecture & Trade-offs
- **Key Choices:**
  - The tab needs only `blueprints.jsonl` from the SDE (type id and max production limit). Materials, products and times were left out until the planner needs them. `blueprints.jsonl` became a required archive entry and `blueprints` references `types`, so parents import before dependants.
  - `ListBlueprints` takes a `BlueprintSource` and the existing `JobSource`. In-use matching uses the job's `blueprint_id` (the blueprint's item id), now carried on `IndustryJob`. Only ongoing jobs count; `JobStatus.ongoing` replaced a private set in `ListJobs`.
  - If jobs fail but blueprints load, rows are listed as free with a warning instead of hiding the character. `attempt {}` is one shared "capture failure, rethrow cancellation" helper.
  - `EsiNames.stationOrStructureName` and `bodyOrFail` (403/401 asks for a new login, other statuses fail with endpoint and status) are shared by the job and blueprint sources.
  - Blueprints are not cached in the database yet; ESI caches them and the tab loads on open and on refresh.
  - ETL and activity codes moved out of `JobsViewModel` into a small formatting file reused by the Blueprints ViewModel.

### Friction & Gotchas
- **Real Issues Encountered:**
  - An already-imported SDE keeps its saved ETag, so the next `import-static-data` would say "up to date" and never fill the new table. A new V8 migration clears the validators (V7 may already be applied, so it is not edited), guarded by a Flyway `target("6")` regression test.
  - Blueprint `location_id` can be a container item id; it is looked up as a structure and shows "Location <id>" when unresolved. Resolving containers through the asset snapshot is still open.
  - Whether ESI keeps listing a blueprint while a job uses it is unverified; the tab was only exercised against mocked ESI.
  - The test-only jdbscript schema needed a `blueprints` record, and the app test archive helper needed an empty `blueprints.jsonl` once it became required.

### Article Hook
- **Publication Takeaway:** When a feature adds a table that an earlier import already skipped, "unchanged upstream" caching turns into silent missing data. Make the schema change reset the freshness marker.
