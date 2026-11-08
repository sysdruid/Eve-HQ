# 2026-11-08 - Feature scenarios on real H2 with jOOQ

### Context
- **Objective:** Replace the fake repository with scenarios ("given this DB state, the feature behaves this way")
  running the real use cases against real in-memory H2, Flyway migrations and a jOOQ repository.

### Architecture & Trade-offs
- **Key Choices:**
  - jOOQ classes are generated from the Flyway SQL scripts (`DDLDatabase`, `sort=flyway`): no live DB at build
    time, migrations stay the single source of truth.
  - Names rendered unquoted (`RenderQuotedNames.NEVER`) so H2 (upper) and Postgres (lower) each fold case.
  - Sorting by name lives in `ListCharacters` (core), so the repository's row order is never relied on.
  - Each test builds its own database (random in-memory name): isolated, parallel-safe, ~ms each.
  - The old core unit tests and `FakeCharacterRepository` were deleted; 15 scenarios replaced 8 tests.

### Friction & Gotchas
- **Real Issues Encountered:**
  - jOOQ 3.21's Gradle plugin needs a Java 21 JVM to run Gradle itself; pinned the daemon with
    `gradle-daemon-jvm.properties` (generated once under JDK 21 to escape the chicken-and-egg).
  - The plugin only generates: sources must be added to the source set and compile tasks wired to depend on it.
  - Generated lowercase names + jOOQ quoting did not match H2's uppercase folding ("Table characters not found").
  - TDD honesty: the status mapping was written before its test, and six scenarios passed on first run because
    the use cases already behaved that way. The test for a tie-break was dropped (EVE names are unique).

### Article Hook
- **Publication Takeaway:** Testing "through the real database" cost almost nothing in speed and found real
  bugs (name casing, upsert, wiring) that a fake repository would have hidden.
