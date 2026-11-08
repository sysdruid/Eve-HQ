# Lessons from v1 (hive-mind)

Source: `~/IdeaProjects/hive-mind/` (read-only reference; do not copy code). Distilled from its
docs and the `eve-mcp` module (SSO, character sync, asset sync, production planning).
Status: draft, to be corrected by the owner.

## Product lessons

- v1 paired an LLM chat agent with a Compose client. The LLM mostly just interpreted requests;
  it was a UI in disguise and added little value.
- Eve-HQ is therefore a **dashboard first**. Agentic AI is an optional connector, added only
  where it shows real value. Core stays UI- and agent-independent.
- v1's production-planning code and specs hold the owner's vision of the EVE production
  workflow. Mine them for domain concepts when production is scoped (see below), not before.

## ESI / SSO

1. v1 used refresh tokens from a JSON file outside the repo and had no login flow. Eve-HQ adds
   SSO (PKCE) for "add a character".
2. Token endpoint and JWT carry many extra fields (`expires_in`, `token_type`, `scp`, `exp`,
   `iss`, `jti`, ...). DTOs must ignore unknown properties. This bit v1 twice.
3. Character id and name come from the access-token JWT: `sub` = `CHARACTER:EVE:<id>`, `name`.
   Signature verification was skipped because the token arrived over TLS directly from SSO;
   revisit if a token ever arrives by another route.
4. Refresh tokens may rotate: always persist the one returned.
5. `400 invalid_grant` (revoked/expired/wrong app) is ordinary per-character state, not a bug:
   mark that character invalid with the error and carry on. Network failures are different and
   should fail loudly.
6. Throttle syncs per character (v1: 1 hour, `force` escape hatch, injectable clock). Stamp the
   attempt time even on failure so a dead token is not retried on every call.
7. JDK `HttpClient` forbids setting the `Host` header; irrelevant if Ktor is used.

## Assets and locations (iteration 2)

- The assets endpoint is a full paginated snapshot (`X-Pages`): replace per character.
- `location_type` values seen: `station`, `other` (Upwell structure), `item` (inside a
  container), `solar_system` (e.g. jettisoned can).
- ESI sometimes reports an item in a structure as `item` with the structure id as
  `location_id`. Treat ids above 1_000_000_000_000 as structures.
- Containers nest: walk the chain with a depth bound (v1: 10).
- A character's own assets may sit in a corp container that is not in their asset list. Report
  "unresolved container" rather than dropping the asset. Resolving these needs the corp assets
  endpoint (Director role).
- Structure names need an authenticated call and can fail (ACL, scope, ESI error): cache them.
- Assets reference item types, so SDE data must be loaded before asset sync can work.

## SDE (CCP's Static Data Export)

Game reference data: item types, groups, market groups, blueprints and their activities,
regions/constellations/systems, NPC stations and corporations. v1 imported it into the same H2
file as dynamic data. Plan: separate tables, same database file. The SDE is in SQL mainly so
dynamic tables (assets, jobs, ...) can reference it with foreign keys, not just for fast search.
- Import by upsert on CCP's natural keys (`type_id`, ...), in one transaction with the metadata
  row, so referencing rows stay valid. Never replace wholesale.
- Do not hard-delete SDE rows dropped by a new build; mark them (`removed_in_build`).
- One metadata row (SDE build number + import time) records the version; no per-record stamps.
- Foreign keys: types, blueprints/activities, groups, NPC stations, solar systems. Not asset
  `location_id` (station, structure or item id) and not player structures (not in the SDE).
- Dynamic sync must refuse to run until the SDE is loaded.

## Production workflow concepts (v1 vocabulary, for later iterations)

- **Project**: a named production goal with a mandatory free-text intent and goal items
  (type, quantity).
- **Phase**: a stage derived backward from the goal by walking the blueprint/activity graph,
  grouped by activity kind (reaction, manufacturing). Position 0 is the most upstream.
  Each phase has derived target stock; raw materials terminate a branch.
- **Job**: one blueprint run with a status (not-started / in-progress / done). Recalculation
  regenerates only not-started jobs.
- **Phasing out**: a retired project drains downstream phases; its front phase target is zero.
- **Pooled netting**: demand across active projects is pooled so shared inputs are not
  double-counted.
- **Counting locations**: a configurable whitelist of stations/structures whose stock counts as
  on hand.
- **Import list**: unmet raw-material deficits, ideally with names and volume (m3).
- Jobs-to-run schedules as many full runs as stock supports instead of blocking on a shortage.
- Outputs should carry names alongside ids from the start (v1 had to patch this in).

## Engineering lessons

- Use an injectable "raw call" seam (or a port with a fake) for ESI and SSO. Tests run without
  network; real-network tests are separate and skipped without credentials.
- Fixed `Clock` injected for time-dependent logic.
- Avoid ORM-driven schema: v1 hit `ddl-auto=update` silently failing to add a column, missing
  transaction boundaries, and `Persistable` workarounds. Prefer explicit migrations.
- Project rules that worked: functionality must be tested; tests use interfaces and DTOs rather
  than raw JSON.
