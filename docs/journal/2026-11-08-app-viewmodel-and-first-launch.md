# 2026-11-08 - Characters ViewModel, wiring and first launch

### Context
- **Objective:** Finish iteration 1 end to end: a Compose Desktop window that lists characters, adds one through
  the EVE login and removes one, wired to the real config, database and SSO.

### Architecture & Trade-offs
- **Key Choices:**
  - `CharactersViewModel` is plain Kotlin (no Compose) with a `StateFlow`; blocking JDBC runs on an injected
    dispatcher. It is tested against real in-memory H2 with a hand-completed fake login, so the "waiting for
    browser" state is observable. The Compose screen and browser launcher are thin edges and are not unit-tested.
  - Composition root (`AppServices`) loads config, opens the file database, builds the SSO stack via a factory in
    `data`, so `app` needs no HTTP dependencies. Config or database failures render on screen, not as a crash.
  - Scopes are configuration (`sso.scopes` in config.properties, default `publicData`): the consent screen showed
    only "public data" because that is all we asked for; the client secret is irrelevant to PKCE and to scopes.
    The owner requests all 73 (`publicData` plus ESI's 72, from esi.evetech.net/meta/openapi.json) to avoid
    re-authorising per feature; that includes write scopes while tokens are still stored unencrypted.
  - Portraits deferred: they need an HTTP and image-decoding layer for little iteration-1 value.

### Friction & Gotchas
- **Real Issues Encountered:**
  - Name shadowing: a constructor property `addCharacter` and a method `addCharacter()` made the method call itself
    forever; the test run hung and wrote a 290 MB heap dump. Fixed by renaming the use-case properties; now every
    Gradle run is wrapped in `timeout`.
  - A "does not start a second login" test passed for the wrong reason (my fake let both logins share one
    result). Counting started logins made it discriminating.
  - Design error caught late: when the browser could not open, my message told the user to open the URL by hand,
    but the failed login had already closed the callback server. The message now states only the reason.
  - First launch printed a Skiko "Cannot create Linux GL context" and fell back to another renderer; the window
    still rendered. Checked with a Java `Robot` screen capture (cropped to the window, then deleted).
  - First real run: the app waited forever and no browser tab opened. `xdg-open` 1.1.3 on KDE (here with no
    `kfmclient`/`kde-config`) runs `kfmclient exec`, fails, and still exits 0, so nothing is reported. Launching it
    without the desktop-detection variables (XDG_CURRENT_DESKTOP etc.) makes it use the default https handler.
    Found by reading the script, then confirmed by running it plain (exit 0, no tab) and sanitised (tab opened).
  - TDD honesty: the invalid-status row mapping was written before its test, again.

### Article Hook
- **Publication Takeaway:** A test that passes the first time proves nothing until you have made it fail on
  purpose; and a hung test run is worth a timeout wrapper before it is worth debugging.
