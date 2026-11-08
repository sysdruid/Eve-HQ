# 2026-11-08 - Blueprint recipes from the SDE

### Context
- **Objective:** Import each blueprint's manufacturing and reaction recipe (build time, materials, products per run) so the production planner has its input data.

### Architecture & Trade-offs
- **Key Choices:**
  - Only `manufacturing` and `reaction` are imported: the planner consumes those. The activity is a column, so invention, copying and research can be added without a schema change.
  - Three tables (activities, materials, products) keyed by blueprint and activity; materials and products reference their activity row.
  - No read API yet. The planner will define what it needs (recipe by product or by blueprint); the importer tests assert through SQL, as the blueprint-limit tests do.
  - The importer's record reading and batching were extracted (`forEachRecord`, `BatchedInsert`) because one blueprint now feeds four tables; parents are flushed before children so foreign keys hold at every batch.

### Friction & Gotchas
- **Real Issues Encountered:**
  - Looking at the real archive before writing the migration showed 1 material and 20 product type ids that `types.jsonl` does not define (removed Standup rigs, the old Clones blueprint). Foreign keys on those columns would have made every real import fail while all synthetic tests passed, so material and product type ids are plain columns.
  - Importing the real 99 MB archive into a throwaway data directory took about three seconds and produced exactly the expected counts (5010 activities, 27,523 materials, 4,987 products).
  - The first version of the material parser passed a dummy line number to its error messages; reading it back caught that before it was committed.

### Article Hook
- **Publication Takeaway:** Constraints you add for tidiness must survive the real data. Run the importer against the real archive before trusting a foreign key that synthetic fixtures never violate.
