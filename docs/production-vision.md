# Production vision

Status: draft agreed in discussion on 2026-11-08 and extended on 2026-11-11 (projects, stockpiles as the one
concept), to be corrected by the owner. Supersedes the
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
- **What is built and what is bought is part of each project's definition;** the tool assumes nothing about any specific item.
  A project answers `builds(item)`: items it does not build are bought or otherwise obtained, never expanded into their
  own inputs. The answer is plain code, written with helpers such as `isGroup(item, "Composite")` and `isItem(item, name)`
  combined with `&&`, `||` and `!`, so rules by group, by name or both need no extra machinery. An item that is not built
  still counts as demand (see Stockpiles).
- **BPC runs** are stock: the owner sets an expected level and the plan flags when it runs low. Invention is the
  same replenish logic, only not triggered every cycle.

## Projects

A **project** is the aggregate root of planning. It is defined in code for now, with an enum entry as its name and id,
and the set of active projects is fixed in code. How projects are created and edited comes later.

- **Goals** (`ProjectGoal`): each goal states requirements, that is item levels (required, and optionally preferred) needed on hand at cycle start, and hides
  how it computed them. An output goal says "N of X"; a readiness goal (below) says "the direct inputs of any job in this
  selection". The planner never knows which kind it was given.
- **Build rule:** per project, see above.
- **Phases** are layers of the plan: the jobs making the goals' own inputs, the jobs making their inputs, and so on (v1
  chained `plan(...)` stages by hand the same way, sometimes filtering each stage by item group). They are numbered from 1 at
  the most upstream layer (reactions, intermediates) to N at the layer next to the goals, so the numbers read in the order the
  material flows. An item needed at several layers takes the most upstream one, so its job sits before every job consuming it.
  **Phases run in parallel,** all inside the cycle, each making the input the next consumes; a phase number is a grouping and
  never an order to run in. Grouping the to-do by phase, then by item group, is computed from the plan and needs no configuration.
- **Characters and stations** per phase: later. They matter for the to-do list and for where a phase's input stock should
  sit, not for the first calculation. Planning stays pooled across characters and stations.

## Stockpiles and aggregated demand

A stockpile is one item, optionally at one station, with levels: **required**, optional **preferred** (relax level) and
optional **overflow** (consider selling; a flag only, never acted on). There is one concept with two origins:

- **Manual:** the owner sets the levels (the Stockpiles tab, implemented). It is a reserve: wanted on top of whatever
  projects consume. It only adds demand; whether jobs result is decided by the projects (an item is built if any
  project builds it), so a manual stockpile never builds anything on its own.
- **Virtual:** derived from project requirements. Every intermediate is virtual: its demand comes from the inputs of the
  next phase.

**Contributions add up.** An item's required level is the sum of everything that asks for it, a manual level being one
contribution. Example, Tritanium with 1,400 on hand: manual 2,000 + project A 1,200 + project B 300 = required 3,500,
deficit 2,100. The UI shows the contributions per item, so the real, aggregated demand is visible.

**Preferred is for what the owner obtains elsewhere** (bought, mined, salvaged, harvested). Jobs are sized to **required**
only. Preferred adds up like required, but it only keeps an item that no job produces on the shopping list: below
required it is urgent, below preferred the owner keeps gathering, at preferred they can relax. A goal or a manual stockpile
may state a preferred level; for derived demand on such an item a coefficient (`required x (1 + coefficient)`, 0 by
default, optionally plus a flat buffer) can supply it. The coefficient is not implemented yet.

**Stock is subtracted once per item,** from the total demand, so two projects cannot both claim the same stock.
Planning therefore sums the demands of all active projects per item first. An item is built if any project builds it; a
project that stops at it still adds to its demand, and the jobs are sized to the total deficit.

**The shopping list is a view,** not a separate stockpile: the items no job produces (those without a recipe, and the stop
items of any phase) with a deficit. That is the input of the earliest phase plus the stop items of later ones.

- A **next-cycle estimate** shows what the goals will consume next cycle assuming intermediates start empty, minus what
  will be on hand after this cycle. Harvesting and hauling take time, so this shows what to start gathering now.
- A **cover scaler** per base item (default 0 extra cycles) sets how far ahead stock should reach: demand is the current
  cycle plus `cover` further cycles of the estimate. "Stock until I have at least two next cycles" is cover 2.
- The plan is frozen, but stockpiles compare the frozen requirement with **live stock**, so a deficit shrinks as materials arrive.
- Volume (m3) from the SDE is shown next to quantities as information (how many hauler loads). It is never a target.

## Readiness goals

Carried over from the v1 notebooks (`~/IdeaProjects/EVE`, `IndustryHelper.t2ShipResourcesByRace`), not invented here.

- A **selector** names a set of items by filters: tech level, race (Amarr, Gallente, ORE ...) and an explicit list of
  ship groups. "Medium" is not an SDE attribute, so the owner lists the groups (cruisers, battlecruisers, haulers,
  exhumers). v1's 50000 m3 volume cap only existed to drop battleship-class hulls and jump freighters; explicit
  groups replace it. Quirky blueprints (absurd build times, not obtainable in game) are excluded by a sanity filter
  and an exclusion list.
- For the first version the selection is an explicit list of a few named hulls; the race and group filters follow once
  the type attributes are imported. The goal's requirements are the **direct inputs** of the hull job (components, and the
  T1 hull where a T2 recipe needs one); everything below follows from the replenish logic as virtual stockpiles.
- Each member's cycle job runs as many times as fit in the cycle at base time: `floor(cycle length / base build time)`,
  at least one run. (v1 used a 7-day period, dropped hulls whose single run outlasted it, and dropped exotic blueprints
  that run for seconds; an explicit hull list makes those filters unnecessary.)
- The level of each material is the **maximum over all members** of what one such job needs, taken per material, then
  multiplied by the cycle's **speed** (v1 used 1.4: the quantities grow because jobs run faster than base time) and by the
  **scale**, the number of such jobs the owner wants to be able to start (v1 used 10). Rounded up, with exact decimals. Averaging
  the bills (v1) is dropped: it understates the most demanding hull. Because the maximum is per material, the stockpile can
  exceed what any single hull needs; that is deliberate, it covers starting any hull's job.
- **A cycle limits job length:** a planned job may not run longer than the usable cycle: the cycle minus a **window** kept
  free at its end (12 hours for the owner), so there is time to get to the game and restart jobs. The same usable length
  sizes a hull's cycle job. Job length is runs x base time / speed. A longer one is
  split into jobs that each fit, the remainder last (three weeks of work in a two-week cycle becomes two weeks plus one). A
  single run longer than the cycle stays one run per job. Splitting does not change demand, only the number of jobs, which is
  where job slots will bite (see Deferred).
- The resulting levels feed the normal replenish logic, so the upstream phases are planned to refill them.
- **Running hull jobs do not reduce the requirement.** Readiness means "able to start N jobs". Starting them spends the
  materials, so afterwards the plan asks for the shortfall again, which is the point. A project's tab lists its running hull
  jobs for information only. What does count as stock is the output of running jobs of the **upstream** phases (components,
  reactions) that will land in the hangars within the cycle, never the hulls themselves.
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
- **Characters and stations on phases,** and stockpiles inside named containers (their item ids are per character and change
  on repack, so a stable key is needed first).
- **Profit** (actual ME, structure bonuses, prices) and then trading. Build cost comes from this plan.
- **Automatic buy-or-build:** manual stop flag first.

## Roadmap

1. Import blueprint data from the SDE. Done: max production limit per blueprint and the manufacturing and reaction recipes
   (build time, materials, products per run). Still to do: a read API for recipes, the type attributes selectors need
   (race, tech level; group is already imported), invention and copying data when needed.
2. Done: manual stockpiles (levels, status, optional station, tab), and `AssetService` for pooled assets.
3. Projects and the engine. Done: `Project` with requirements and a build rule, summing demand per item and netting stock
   once, contributions, the shopping list as a view, phases as depth, recipe and item adapters over the SDE (published
   blueprints only), a Projects tab with a project picker, two code-defined projects (1000 Amarr Shuttles; the inputs of five hundred
   Anathema jobs, built from the reusable `inputsOfJobs` goal: components, composites and intermediates are built in phases,
   everything else is bought) and the Anathema build rule written from the real groups in its tree. Next: the aggregated
   shopping list over all projects, copy buttons and the per-item contributions on click, then more projects.
3b. Readiness goals. Done: explicit hull list, runs that fit the cycle, max over members per material x speed x jobs, direct
   inputs as requirements, and job splitting by cycle length (the Medium T2 hulls project, configured in `Projects.kt`).
   To do: fixed-run copies (T3), selector filters after the type-attribute import, slot and blueprint max-run limits.
4. Done: phases as layers numbered from the most upstream, and per-project build rules. Next: names for phases, then the persisted cycle plan, to-do with copyable names (characters, stations), multibuy list.
5. Stock sources beyond assets. Done: the output of running and finished-but-undelivered manufacturing and reaction jobs counts as
   stock when it lands within the project's usable cycle (`runs x output per run`, all running jobs for a project without a
   cycle). To do: BPC runs.
6. Tick-off from the Jobs data. Started: a project declares the items it exists to produce (the hulls) and its tab lists the
   running ESI jobs making them, with a summary ("Legion 8, Tengu 8 in progress"). The hull jobs are informational and never change the plan. To do: match running jobs of the
   upstream phases to the planned jobs, and count their output finishing within the cycle as stock.
7. Exact job time from ESI, slot checks, job splitting, capacity warnings.
8. Profit, then trading.
