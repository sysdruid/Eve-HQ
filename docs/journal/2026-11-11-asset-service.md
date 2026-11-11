# 2026-11-11 - AssetService: hiding when assets are fetched

### Context
- **Objective:** Stop every consumer (Assets tab, Stockpiles tab, later the planner) from deciding when to hit ESI,
  falling back to cached data and fighting over the page cache. Callers should just ask for assets.

### Architecture & Trade-offs
- **Key Choices:** `AssetService` in core owns freshness (5 minute in-memory window), the last-known copy, the
  per-character lock and the fallback to the saved snapshot plus a reported failure. `ListAssets` and
  `ListStockpiles` became thin views over it and share one instance in the app.
- The window is deliberately not ESI's expiry. The ESI source already enforces the exact expiry, and now returns its
  saved snapshot with no requests at all while every page is fresh (before, every load still re-posted the item-name
  lookup, re-decoded all pages and rewrote the snapshot). That kept the `AssetSource` port unchanged, so no fakes changed.
- Resolution of station and container path moved into the service (`OwnedAsset`), so location queries become filters
  (`AssetQuery`) and the future per-location stockpiles need no new asset code.
- Opening a tab accepts what is fresh; the Refresh buttons force a fetch.

### Friction & Gotchas
- **Real Issues Encountered:** A fixed test clock never ages a time-window cache, so feature scenarios about ESI expiry
  needed a zero-window service; the window itself is tested with a movable clock in `AssetServiceTest`.
- Listings now show "inventory updated" for every loaded character, not only ones with a saved snapshot: a small,
  intended behaviour change that an existing test pinned down.

### Article Hook
- **Publication Takeaway:** A cache that every caller has to know about leaks; moving the policy behind one service
  deleted duplicated fallback code, a race and a repeated lookup in one go, and made the next consumer cheaper.
