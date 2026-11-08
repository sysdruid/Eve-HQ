# [2026-11-08] - Static data download

### Context
- **Objective:** Make the existing static-data import download CCP's latest JSONL archive on demand.

### Architecture & Trade-offs
- **Key Choices:** Follow CCP's latest-archive redirect, stream the download to a temporary ZIP, and send
  saved ETag and Last-Modified validators on later runs. Commit validators with the imported dataset in
  the same H2 transaction; local imports clear them, and a 304 skips both the transfer and import.

### Friction & Gotchas
- **Real Issues Encountered:** The archive link had to be checked against CCP's page rather than inferred.
  The latest link redirects to a versioned archive, so conditional headers must survive redirects. A failed
  download or invalid ZIP must not advance the validator and mask a needed retry.
- The tests were not run in a failing state before the implementation, a missed TDD red phase; the full
  suite and a live initial import followed by an unchanged check passed afterwards.

### Article Hook
- **Publication Takeaway:** An HTTP cache validator describes the data actually committed, not merely the
  last response received; coupling it transactionally to the import keeps retries and local overrides safe.