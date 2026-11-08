---
name: tdd
description: Kent Beck-style Test-Driven Development: keep a test list of behaviours, then grow the code one small red → green → refactor cycle at a time. Use this by default whenever implementing a feature, adding or changing behaviour, or fixing a bug in code that has (or can reasonably have) automated tests — even if the user doesn't say "TDD" or "tests". Skip only for trivial non-behavioural edits (typos, formatting, config values), throwaway spikes, or when the user says not to.
---

# TDD

TDD is not "write the tests first, then the code". It is designing the code through
a sequence of tiny steps, each driven by one failing test. The tests are a by-product;
the real output is code that grew only as much as the behaviour demanded. Writing a
batch of tests and then implementing everything at once is test-first, not TDD — avoid it.

## The loop

### 0. Write the test list

Before touching code, write a list of test cases in a file `TESTLIST.md` at the project
root (reuse it if one exists for this task). The user may read or edit it while you work,
so re-read it at the start of each cycle.

Describe cases as **observable behaviour of the API** — inputs, outputs, errors, side
effects visible to a caller — never implementation ("returns 0 for an empty cart", not
"calls `sum()` on items").

```markdown
# Test list: <feature>
- [ ] empty cart totals 0
- [ ] single item totals its price
- [ ] quantity multiplies price
- [ ] negative quantity is rejected
```

The list doesn't need to be complete — it will grow. Don't ask for approval; proceed.

### 1. Pick one case

Prefer, in roughly this order:
- the simplest / degenerate case (empty, zero, one element) to get the API shape in place;
- the case that teaches the most while needing only a small code step;
- error and edge cases once the main shape has settled.

If a case would need a big leap, pick a smaller one first or split it.

### 2. Write one test — red

Write a single, small test for that case, through the public API. Run it and **watch it
fail for the expected reason** (an assertion about the behaviour, not an import error,
typo or missing fixture). A test you never saw fail may not be testing anything.

### 3. Make it pass — green

Make the simplest change that passes the test. Hardcoding a constant ("fake it") is
legitimate; the next test will force the generalisation ("triangulation"). If the right
general solution is obvious and small, just write it.

Never weaken, delete, or edit a test to get green. If a test seems wrong, say so and fix
it as a deliberate, visible step.

Run the tests for the part of the system you changed — the new test plus the existing
tests of that module/package/component. Not just the new test (too narrow), not the
whole suite every cycle (too slow; that's for the end).

### 4. Refactor

With everything green, clean up: remove duplication (including between test and code —
e.g. a hardcoded constant), improve names, simplify. Behaviour must not change; rerun
the same tests after.

Keep refactoring within the scope of the task — the code you just wrote and what it
directly touches. Note wider cleanups in `TESTLIST.md` under a "Notes" section
instead of doing them.

### 5. Update the list and repeat

Tick the case off. Add any new cases you thought of along the way (edge cases, errors,
interactions) — this is expected, not a failure of planning. Go back to step 1 until
the list is empty.

## Finishing

- Run the **full** test suite once at the end and fix any regressions.
- Report briefly: cases covered, cases added along the way, anything left in Notes.
- Leave `TESTLIST.md` in place and mention it; don't delete it or commit it unless asked.
- Don't commit at all unless the user asks.

## Bug fixes

The first test case is a test that reproduces the bug. See it fail, then fix. Add
neighbouring cases the bug suggests to the list.

## Common failure modes to avoid

- Writing several tests (or the whole implementation) in one step.
- Skipping the red run, or accepting a failure for the wrong reason.
- Testing internals: private methods, mock call counts, data structure layout. Mocks are
  fine at real boundaries (network, clock, filesystem), not between your own classes.
- Writing more code than the current test requires "because it'll be needed later" —
  add a test case to the list instead.
- Weakening a test to make it pass.

## No test setup yet?

If the project has no test runner, set up the minimal idiomatic one for the language
before step 2, and mention it to the user.
