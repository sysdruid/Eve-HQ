# 2026-11-08 - Spike: jdbscript + Kotlin + H2 + Flyway

### Context
- **Objective:** Before building persistence around "given this DB state, the feature behaves this way",
  check that jdbscript (author's own fixture library) works from Kotlin on in-memory H2 with a real
  Flyway migration. Also create the test-only `features` module and the V1 `characters` migration.

### Architecture & Trade-offs
- **Key Choices:**
  - Feature scenarios live in a test-only `features` module that depends on `core` and `data`; the arrows
    stay `data -> core`. No fake repository: real use cases, real H2, fakes only at SSO/ESI and the clock.
  - jdbscript schema interfaces are hand-written in Kotlin (method name == column name, verbatim).
    Upstream Kotlin receiver-lambda extensions are copied in; they have no assert variants, so two
    small `assertHas` / `assertHasNot` extensions were added next to them.
  - V1 uses plain portable SQL only, so MySQL/Postgres stay possible.

### Friction & Gotchas
- **Real Issues Encountered:**
  - H2 2.4.240 bug: a `CHECK` on a column (`status IN (...)`, also `a OR b`; a single `<>` was fine) breaks
    every insert once the session that created the table has closed, with a misleading
    "Check constraint invalid ... The database has been closed". Any migrate-then-close flow hits it.
  - Diagnosis path: passed on 2.3.232, failed with plain JDBC (so not jdbscript), failed without Flyway
    (so not Flyway), passed in standalone probes (the creating connection was still open). Closing that
    connection before the insert reproduced it. H2 2.5.252 fixes it; we pin 2.5.252.
  - Kotlin worked without `-Xjvm-default=all`: `defaults()` with a body in a Kotlin interface ran fine.

### Article Hook
- **Publication Takeaway:** A spike that runs the full chain early finds what unit tests never will. Bisect
  by removing one layer at a time (library, migration tool, driver version), and keep the minimal repro
  as a regression test so the next upgrade can't reintroduce it.

### Follow-up
- Data adapter tests now use jdbscript schemas and typed inserts for static type fixtures, plaintext-token
  compatibility, and the post-migration CHECK-constraint regression. The existing behavior assertions
  remain unchanged, and the full Gradle test suite passes.

### Follow-up: shared static-type fixture
- Added a reusable jdbscript baseline for canonical Material/Mineral data and Tritanium/Pyerite, with a
  repository test proving the real names resolve. The asset-source scenario reuses the fixture and
  overrides only the record needed to distinguish static lookup from ESI fallback.
- Added contributor guidance for extending static imports, including migrations, parent-before-child
  order, streamed transactional replacement, and success/rollback tests.
