# 2026-11-08 - Core character use cases (TDD)

### Context
- **Objective:** Build the UI- and DB-independent `core` for iteration 1: add a character via SSO, list,
  mark invalid, remove. Fakes only, one red-green cycle per behaviour.

### Architecture & Trade-offs
- **Key Choices:**
  - Two ports: `CharacterRepository` (all/find/save/remove) and `SsoAuthenticator` (`suspend login()`,
    throws `LoginFailed`). Login waits on a browser, so it is `suspend` to make cancellation work.
  - `core` depends only on kotlinx-coroutines; tests use `runBlocking`, no test-coroutines library.
  - A character carries a status (`Valid` / `Invalid(reason)`): a revoked token is normal per-character
    state (a v1 lesson), not an exception.
  - Skipped a `Clock` port until something needs time (YAGNI).

### Friction & Gotchas
- **Real Issues Encountered:**
  - TDD honesty: three cases (upsert on re-add, failed login adds nothing, remove unknown) passed on the
    first run because the fake repository already behaves that way. They pin behaviour but did not
    drive code; the real guarantee belongs in a repository contract test against H2.
  - To get a meaningful red I stubbed with `TODO()`; for "unknown id" the first green used `!!` on
    purpose so the next test failed with a real NPE and forced the null handling.

### Article Hook
- **Publication Takeaway:** Tests that pass immediately are a signal: either the behaviour lives in a
  collaborator (test it there) or the test is only documentation. Say which.
