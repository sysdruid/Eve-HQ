# 2026-11-08 - Project skeleton and persistence decisions

### Context
- **Objective:** Stand up a Kotlin Compose Desktop dashboard for EVE Online (12 characters, embedded H2),
  with a UI-independent `core`. Iteration 1 is "log in via SSO and list characters"; this entry covers
  the Gradle skeleton and the decisions made before any domain code.

### Architecture & Trade-offs
- **Key Choices:**
  - Three JVM modules (`core`, `data`, `app`). Kotlin Multiplatform was in the brief, but nothing needs
    multiple targets and jOOQ/H2 are JVM-only, so plain `kotlin("jvm")` keeps the build simple.
  - Schema owned by Flyway (versioned plain-SQL migrations), queries by jOOQ, generated from the migrated
    schema at build time. Hibernate rejected: JPA entities fight a pure-Kotlin domain, and ESI data is
    snapshot replacement plus reporting joins, which suits SQL-first jOOQ.
  - No design for MySQL/Postgres, but standard SQL only so the door stays open.
  - Forward-only migrations, DB file backup before migrating, DB in an OS user-data dir.
  - v1 (an LLM-chat client) taught us the LLM was a UI in disguise, so this one is dashboard first.

### Friction & Gotchas
- **Real Issues Encountered:**
  - Only JDK 17 on PATH; Gradle found a cached JDK 21 via toolchains, so the toolchain was pinned to 21.
  - Publishing: the first commit carried a personal email and tracked IDE files. Fixed by rewriting
    the single local commit (noreply email, untracked `.idea`) before the first push.
  - The project rule to journal on completion pointed at a `TASK.md` that never existed, so it could
    never fire; reworded to trigger on "plan step done, tests green".

### Article Hook
- **Publication Takeaway:** Decide the boring infrastructure (migrations, ORM-or-not, repo hygiene) before
  the first line of domain code. A repo with one unpushed commit is the cheapest it will ever be to fix.
