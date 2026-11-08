# Production vision

Status: draft agreed in discussion on 2026-11-08, to be corrected by the owner. Supersedes the
Project/Phase/Job vocabulary of `lessons-from-v1.md` where they differ.

## How the owner produces

- Goals: T2 and T3C hulls to sell; hulls and modules for the owner and corpmates (cost is secondary there).
- Everything is built from basic materials (minerals, gases, salvage, PI), but cheap intermediates may be bought.
- All phases (reactions, components, hulls/modules) run in parallel inside a **cycle** with a hard
  boundary: two weeks now, a month later. The limit today is job slots, then ISK.
- Dedicated characters per phase (for example one runs T3 polymer reactions and T3 components, another the
  intermediate reactions, another T2 reaction products and T2 components). Assignment is by item group today
  and may become per blueprint.
- All production materials sit in the characters' personal hangars. Jobs run in two structures (a Raitaru and an Athanor).
- Today the plan is a Markdown file with hand-ticked jobs. The waste comes from intermediates built without
  exact numbers and never consumed downstream.
- The clicking in game is unavoidable; planning and bookkeeping should cost almost none.

## Model: replenish to a level

- Each item has a **level**: one cycle of downstream consumption, assuming downstream starts from zero input.
- At cycle start the plan runs only the jobs needed to bring each item back to its level, from the actual state.
  Overshooting is fine: it is already in stock next cycle, so the next top-up shrinks. This also absorbs
  invention's randomness and duplicate jobs.
- **Stock at cycle start** = items at the counting locations (personal hangars in the structures)
  + output of finished but unclaimed jobs + output of running jobs that finish within the cycle.
- ME and structure bonuses do not size jobs (sizing is conservative); they matter for cost and profit.
- **Standing goals** are stable but adjustable. There are two kinds that drive jobs:
  - **Output goal:** build N of item X; the plan lists the jobs.
  - **Readiness goal:** "have the materials to start cycle-long jobs for any item in a selection" (scaled) (for example any T2 medium
    hull of Amarr, ORE or Gallente origin). The hull jobs are not planned; the owner picks which hull to build at job
    start. The plan only guarantees the inputs exist.
- **Stop items** are never expanded into their own inputs: raw materials, PI, datacores, relics, decryptors and anything
  the owner chooses to buy. Their need goes to the shopping list. Fuel is a stop item with a manual stockpile signal:
  when reactions or structures need it, it is drawn from stock and never expanded.
- **BPC runs** are stock: the owner sets an expected level and the plan flags when it runs low. Invention is the
  same replenish logic, only not triggered every cycle.

## Readiness goals

Carried over from the v1 notebooks (`~/IdeaProjects/EVE`, `IndustryHelper.t2ShipResourcesByRace`), not invented here.

- A **selector** names a set of items by filters: tech level, race (Amarr, Gallente, ORE ...) and an explicit list of
  ship groups. "Medium" is not an SDE attribute, so the owner lists the groups (cruisers, battlecruisers, haulers,
  exhumers). v1's 50000 m3 volume cap only existed to drop battleship-class hulls and jump freighters; explicit
  groups replace it. Quirky blueprints (absurd build times, not obtainable in game) are excluded by a sanity filter
  and an exclusion list.
- Each member gets a per-job run count: how many runs fit in one cycle, `floor(cycle length / base build time)`,
  times a manual **speed** multiplier (v1 used 1.4). Long builds give small counts and short builds large ones.
- The level of each material is the **maximum over all members** of what one such job needs, times a **scale** factor:
  enough to start any potential hull job. v1 averaged the bills; that did not work as well as expected, so averaging is dropped.
  The scale stays a free number the owner tunes so the upstream phases come out balanced (v1 used 10).
- The resulting levels feed the normal replenish logic, so the upstream phases are planned to refill them.
- **Fixed-run blueprint copies:** invented copies (T3 hulls and subsystems) have a fixed run count (4 for the owner), so
  the job size is the copy's runs, not the cycle capacity. Goals for such items are explicit (N hulls plus a named
  list of subsystems) and are rounded to whole copies. BPC runs are stock (see above).

## v1 behaviours kept and dropped

- Kept: explosion layer by layer; layers grouped by item group (construction components, composites, intermediate materials),
  which is how phases map to characters; reactions and manufacturing handled by one blueprint mechanism; round runs up
  (`ceil(need / product quantity)`); items without a blueprint, and stop items, go to the acquire list and a multibuy export.
- Kept: failing loudly when a product has several blueprints or a blueprint has several products, until a rule exists.
- Dropped: ignoring stock (v1 had netting commented out), which is the source of today's surplus; averaging the hull
  bills; the volume cap.
- Fuel: only a stockpile signal. How it is built is handled outside the tool.

## Stockpile signals

Two kinds, with different jobs:

1. **Manual stockpile signal:** the owner sets a level for any item and only wants to be told when stock drops below it
   (fuel is the example). Monitor only: it never creates jobs. What to do about it is the owner's call.
2. **Derived requirement:** computed from the goals down. For items the plan manufactures it drives the jobs. For stop
   items (gases, minerals, salvage, PI, datacores ...) it is the signal: what the frozen plan's jobs need, minus what is on hand.
   The owner does not maintain levels for these by hand.

For derived requirements:
- A signal reads "need N more of X": required for the cycle, on hand, deficit. One list; the owner decides whether to
  harvest or import. Harvest-or-buy labels are not needed for now.
- A **next-cycle estimate** shows what the goals will consume next cycle assuming intermediates start empty, minus what will
  be on hand after this cycle. Harvesting and hauling take time, so this shows what to start gathering now.
- A **cover scaler** per base item (default 0 extra cycles) sets how far ahead stock should reach: the signal fires on
  the current cycle's requirement plus `cover` further cycles of the next-cycle estimate. "Stock until I have at least two
  next cycles" is cover 2. An optional flat **buffer** quantity can be added on top (buying and hauling take time).
- The plan is frozen, but signals compare the frozen requirement with **live stock**, so a deficit shrinks as materials arrive.
- Volume (m3) from the SDE is shown next to quantities as information (how many hauler loads). It is never a target.

## Plan lifecycle and output

- The plan is generated at cycle start and then **frozen**; only job statuses change. Anything bought mid-cycle counts at the next cycle start.
- Output: a to-do grouped by character, structure and blueprint, with runs and a copyable blueprint name;
  a shopping list in EVE multibuy paste format.
- Jobs that appear in ESI tick themselves off.

## Deferred, deliberately

- **Multi-cycle jobs** (run several cycles' worth when a job takes a fraction of the cycle): model the level as min/max
  (trigger below one cycle of consumption, refill to N cycles). N is 1 for now.
- **Exact job time and slot checks:** job time depends on TE, skills and structure bonus. The manual multiplier per
  structure and activity (for example 0.9) is used first, with an optional max runs per job; later read blueprints
  (TE) and skills from ESI and check against each character's slots. The readiness scale is a number the owner tunes first, and
  could be derived from free slots later.
- **Per-character stock and transfers** between hangars: stock is pooled across characters (agreed); a "move X from A to B" list comes later.
- **Profit** (actual ME, structure bonuses, prices) and then trading. Build cost comes from this plan.
- **Automatic buy-or-build:** manual stop flag first.

## Roadmap

1. Import blueprint data from the SDE. Done so far: max production limit per blueprint (kept for planning, with the
   Blueprints tab) and the manufacturing and reaction recipes (build time, materials, products per run). Still to do: the
   type attributes selectors need (race, tech level; group is already imported); invention and copying data when needed.
2. Core planner: output goals, stop items, stock to jobs per phase (pure logic, test-first).
2b. Readiness goals: selectors, per-job run counts from the speed multiplier, max basket x scale; fixed-run copies.
3. Stock sources: assets at counting locations, unclaimed and running job output, BPC runs, with manual stockpile signals and derived base-material signals.
4. Persisted cycle plan, to-do with copyable names, multibuy list.
5. Tick-off from the Jobs data.
6. Exact job time from ESI, slot checks, job splitting, capacity warnings.
7. Profit, then trading.
