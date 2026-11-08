# 2026-11-08 - Token encryption at rest, and a silent truncation

### Context
- **Objective:** After asking for every ESI scope, encrypt stored tokens before iteration 2 puts real data behind them.

### Architecture & Trade-offs
- **Key Choices:**
  - `TokenCipher`: AES-256-GCM, random nonce per value, stored as `v1:<base64>`. The 32-byte key lives in
    `token.key` in the data dir (created 0600, never replaced if damaged), so the database file and its backups
    do not contain the key. It does not protect against someone who can read the whole data folder.
  - A value without the `v1:` marker is read as it is. That keeps rows written as plain values working (the
    jdbscript scenarios, an old test character) without a migration pass.
  - Built then removed at the owner's request: an upgrade-on-startup pass and a database-file residue check. One
    test character did not justify them.

### Friction & Gotchas
- **Real Issues Encountered:**
  - H2 did not reject a 6,000-character token for a `VARCHAR(4096)` column: it silently truncated it. With all 73
    scopes the access token JWT is about 4.3 KB, so every stored access token would have been cut off. Found by a
    scenario with a long token; fixed by V2 widening both token columns (kept NOT NULL, existing rows preserved).
  - An unexpected exception during login left the screen on "waiting" forever; the ViewModel now catches it. The
    test fake also had to reset after a failure, or the next login re-threw the stale one.
  - A "callback does not work" report first looked like an all-scopes regression, but it was transport (see the
    callback-ipv6 entry); every earlier login had been finished by pasting the URL into wget.
  - Running the GUI against a copy of the data on the owner's screen invited a click on Remove, which looked like
    a migration losing a row until applying the migration by hand to the backup kept it.
  - The pre-migration backup holds the old plain tokens; it is a snapshot, not encrypted.

### Article Hook
- **Publication Takeaway:** A database that truncates instead of failing turns a config change (more scopes) into
  corrupted data with no error. Test with the largest realistic value, not a typical one.
