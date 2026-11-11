# 2026-11-11 - Projects and the production engine

### Context
- **Objective:** First code for the production vision: a pure-core engine that turns project requirements, manual
  stockpiles and pooled stock into jobs and a shopping list, with a trace of who asked for each item.

### Architecture & Trade-offs
- **Key Choices:** A `Project` only states requirements and its own stop items; how they arise (a fixed list now,
  a readiness rule later) is hidden. The engine never knows the kind. Manual stockpiles are one more contribution.
- All demand on an item is summed first and stock is subtracted once, so two projects cannot claim the same stock.
  Contributions simply add (a manual level is a reserve on top, not a floor), which removed a special combining rule.
- Stop items are per project. An item is built when some project reaches it without stopping at it; a project that stops
  there still adds its demand, and the job is sized to the total deficit. This needs a per-item "reached by" set, but no
  per-project quantities.
- The shopping list is a view over the demands (no job, deficit above zero), not a second structure.

### Friction & Gotchas
- **Real Issues Encountered:** The engine was written as the general solution, so every test after the first passed
  immediately. To trust them, three deliberate breakages (ignore stop items, ignore stock when sizing, ignore manual
  stockpiles) were each confirmed to fail the tests that should catch them.
- The topological walk cannot know which project reaches an item before it has walked, so ordering uses a conservative
  "could any project expand this" test; only the real expansion decides jobs.
- Reading the v1 notebooks settled two modelling questions: v1 stages were depth levels from the goal (optionally filtered
  by item group), so a phase is just the depth in the plan, with the deepest depth winning, and the group filter was really
  a build-or-buy rule. Stop items became a per-project `builds(item)` predicate written with plain booleans over
  `isGroup`/`isItem`, instead of a combinator type. Phases run in parallel by design; the plan never implies an order.
- Meeting in the middle (a simple shuttle project and a complex Anathema one, plus the screen they need) exposed a real
  bug before any UI existed: the owner's database held blueprints but no recipes, because an import marked current before
  the recipe tables existed kept answering "up to date". A migration now forgets the saved validators. The SDE also made a
  product ambiguous (a development blueprint makes Tungsten Carbide too); only published blueprints count, which resolved all
  five ambiguous products, and anything still ambiguous fails loudly.
- The probe on the real data also showed the unmodified engine already plans the whole Anathema tree to raw materials in
  five phases.

### Article Hook
- **Publication Takeaway:** Hiding how requirements are produced behind one small interface let the engine be written
  and tested with a list of fixed numbers, before the interesting rules (readiness, max over members) exist.
