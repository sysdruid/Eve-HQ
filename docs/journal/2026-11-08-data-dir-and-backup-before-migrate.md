# 2026-11-08 - Data dir resolver and backup-before-migrate

### Context
- **Objective:** Give the app a durable home for its H2 file (per-OS data dir with overrides) and make schema
  migrations safe for the user's only copy of their data: back the file up before migrating.

### Architecture & Trade-offs
- **Key Choices:**
  - `Platform` (OS name, home, env, properties) is passed into `DataDir.resolve`, so per-OS rules are tested
    without touching the real environment; that boundary is the one fake in these tests.
  - Override order: system property, then env var, then the platform default (Linux XDG, macOS
    Application Support, Windows LOCALAPPDATA).
  - Backup only when an existing file has pending migrations: `Migrations.migrate` takes a `beforeMigrating`
    hook that Flyway's `info().pending()` gates. A new database or an up-to-date one is never copied.
  - Backups are plain file copies named by UTC timestamp from an injected `Clock`.

### Friction & Gotchas
- **Real Issues Encountered:**
  - Connecting to a file H2 creates the file, so "did it exist?" must be decided before the first connection.
  - H2 auto-commits DDL, so a migration script that fails midway can leave a half-applied schema. The plan's
    "failed migration leaves the database unchanged" is not achievable; the backup is the safety net, and the
    test now asserts that instead.
  - TDD honesty: four tests (backup contents, no backup for new/up-to-date DB, failing migration) passed on first
    run because the implementation already behaved that way; the contents test inspects the backup read-only
    so a stale or partial copy would fail it.

### Article Hook
- **Publication Takeaway:** Don't promise rollback you can't deliver: find out whether your database makes DDL
  transactional, and if not, make the backup, and a test that opens it, the real guarantee.
