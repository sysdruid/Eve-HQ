# 2026-11-11 - Manual stockpiles

### Context
- **Objective:** Start the production work with the simplest piece of the stockpile idea: items the owner wants to keep
  at a level (required / preferred / overflow), with a tab that shows on-hand stock against them.

### Architecture & Trade-offs
- **Key Choices:** The design came from a discussion first. A stockpile is one item with up to three levels; stock is
  pooled over characters and containers, assets only. Location, dates and planner-derived stockpiles are deliberately
  later: a planner that was already half written was deleted until the stockpile concept settles.
- Status is a pure function of the levels (`Stockpile.statusFor`); the enum order is the display order, so "needs
  attention first" is one `sortedWith`.
- On hand reuses the asset source's load-or-cached-snapshot behaviour, so one ESI outage does not make every
  stockpile look empty. The failure is shown next to the list.
- The name is stored with the stockpile so an item nobody owns still has a label; type search is a core port
  (`TypeCatalog`) implemented over the imported static types.

### Friction & Gotchas
- **Real Issues Encountered:** Deleting the planner file also deleted `TypeId`, which lived in it, and a `;` in the
  command chain committed the broken state anyway; fixed by amending the unpushed commit. A stray `cat >` waiting
  on stdin hung a tool call for minutes.
- The search field was first written with a constant empty value, so typed text never showed. Compose screens have no
  unit test here, so only a run of the app shows that kind of bug.
- A second consumer exposed a latent race: the Assets and Stockpiles tabs loaded the same character at once, both
  replaced its cached pages (delete, then insert) and the second hit a primary-key violation. The failure was only
  visible because logging had just been added; the stack trace named the table and the character. Fix: loads are
  serialised per character in the three refreshing sources, so the second caller finds a fresh cache.
  Still open: token refresh can race the same way across the three sources.
- Follow-ups from first use: clicking a stockpile jumps to the Assets tab with the item name in the search box (the
  existing text search already matches type names), and the per-character "inventory updated" lines became one line
  with the oldest time, which is the useful answer to how stale the list can be.

### Article Hook
- **Publication Takeaway:** Discussing the domain concept before coding removed a whole speculative module: the
  smallest useful version (manual levels over real assets) was cheap to build test-first, and it fixed the vocabulary
  the planner will reuse.
