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
- First real use: planning the Anathema project took minutes. Timing the planner on the real database showed it was not
  the algorithm: 150 recipe and 245 group lookups cost 0.3 to 0.5 s each, because H2 closes an embedded database when the
  last connection closes and every query used a short-lived connection, so each lookup reopened the file. A ten second idle
  delay (`DB_CLOSE_DELAY`) took planning from 145 s to 0.4 s and still frees the file lock for the import command. A global
  temporary table, which H2 drops on close, makes a cheap regression test for "stayed open". Tests that simulate an app
  restart by reopening the same directory need the delay switched off.
- The readiness project (any medium T2 hull, N jobs, a cycle) ran on the real recipes in under a second: 23 requirements, three
  phases, long jobs split to fit the cycle. Two lessons: round once at the end (a per-run time rounded to whole seconds made a
  job of 67,737 runs read 0.8% too long, so the job carries its exact total), and use exact decimals for the speed multiplier
  so 15 x 1.4 is 21, not 22. The probe also showed the real cost of N = 10: over two hundred jobs, which is where slots
  will become the limit.
- A project now knows what it exists to produce (`endProducts`), and its tab lists the running ESI jobs for those products
  next to the plan, reusing the Jobs tab's line and progress formatting (extracted, not copied). My first reading of the request
  ("the jobs that build the hulls") was the jobs still to be started; the owner meant the ones already running. Asking what the
  numbers should be used for, not just how to display them, would have saved a round trip.
- Running jobs now count as stock: the plan asks for less when components are already being built. The owner's correction
  shaped it: running *hull* jobs must not reduce the requirement (readiness is "able to start N jobs", and starting them spends the
  materials), only upstream output that will land in the hangars does. The plan loads the jobs once and hands them to the tab, which
  made a separate project-jobs use case redundant.
- Showing running jobs per phase exposed a flaw: phases were numbered from the jobs the plan still asked for, so once running
  output covered a whole layer its tab would vanish and the others renumber, exactly when its running jobs mattered. Phases now
  come from a structural pass without stock (planning twice, about 0.4 s each on the real trees), and the jobs and running jobs
  hang off those stable layers.
- "Shopping list" was the wrong word: some of what a plan does not produce the owner builds elsewhere (T1 hulls), some is bought
  (moon materials). It became "prerequisites", grouped by item group, with an EVE multibuy text per group, so the owner decides
  per group. The item group already in the data matched the owner's own examples; the market-group tree would have needed a new import.
- Job time: the planner's flat 1.4 "speed" was 40% off a real job (13d 11h against 8d 5h 19m in game). Chasing the gap with the
  owner found each factor in turn (TE, Industry, Advanced Industry, a 2% implant, a Raitaru) and one unexplained 4%, which a
  wrong guess (a second implant) did not survive: the SDE shows the blueprint's own required engineering skills give 1% a level
  (Electronic Engineering IV for electronic components). Lesson: the data states the rule, so import the numbers instead of
  recalling them. The formula is pinned by that real job; the importer now reads skills and time-bonus attributes.
- First run on the real recipes with the owner's real factors showed the plan wanting about 2.7 times the materials of base time,
  nearly double what the old 1.4 gave. That also exposed a modelling slip: the probe gave every blueprint TE 20, but T2 hulls are built
  from freshly invented copies at 2 to 4. The hull's blueprint TE became a setting of the project, and the phases below keep using the
  blueprints actually owned. Every factor has to describe the job that will really run.
- Icons and portraits come from EVE's image server and are cached on disk. The store works on bytes (testable with a mock
  engine: one download for many rows, no repeat after a restart, missing icons remembered, failures retried), a decoder turns them
  into bitmaps with a small LRU, and two composables draw them with a quiet placeholder. Rendering a screen offscreen to a PNG
  made it possible to check the look without a display.
- Stock against need became one reusable bar: `on hand / need` in compact numbers (2.78M, exact in the tooltip), four colour steps
  (covered, 85%, 50%, below), a pale green between required and preferred, blue above overflow. A render of the first version showed
  the most urgent rows looking grey: an almost empty red bar is invisible, so a short item's whole track is tinted instead. The
  shortfall left the line; the multibuy copy ("Copy missing materials") carries the exact amounts, as in the game.

### Article Hook
- **Publication Takeaway:** Hiding how requirements are produced behind one small interface let the engine be written
  and tested with a list of fixed numbers, before the interesting rules (readiness, max over members) exist.
