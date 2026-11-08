# 2026-11-08 - EVE SSO login (PKCE) and token refresh

### Context
- **Objective:** "Add character" through the EVE SSO v2 browser login (PKCE, no client secret), plus refreshing a
  character's token, all testable without network or a registered developer app.

### Architecture & Trade-offs
- **Key Choices:**
  - Small pure pieces first: `Pkce` (RFC 7636 vector), `AuthorizeUrl`, `AccessToken` (JWT claims), then
    `SsoTokenClient` on Ktor tested with `MockEngine`, a loopback `CallbackServer` on the JDK's built-in
    `HttpServer`, and `EveSsoAuthenticator` tying them together. The browser is an injected `(URI) -> Unit`.
  - `core` owns two ports (`SsoAuthenticator`, `TokenRefresher`) and the rule that matters: a revoked refresh
    token marks the character invalid, a network failure stays loud and leaves the character untouched.
  - Every login problem surfaces as `LoginFailed` with the cause kept; refresh keeps `IOException` unwrapped.
  - The access-token signature is not verified (token arrives from the token endpoint over TLS); recorded as a
    decision to confirm, since CCP's docs recommend verification.

### Friction & Gotchas
- **Real Issues Encountered:**
  - `URLEncoder` writes a space as `+`; scopes go in the query as `%20`.
  - A wrong-state callback first failed the login; changed to answer 400 and keep waiting, because a stale
    browser tab from an earlier attempt would otherwise abort a good login.
  - The timeout test would have hung while red, so it runs under an outer `withTimeout`.
  - Ktor 3's `submitForm` and `MockEngine` needed the request body read as `OutgoingContent.ByteArrayContent`.
  - The CCP docs do not document `invalid_grant`'s status/format or refresh-token rotation; behaviour relies on
    the v1 lessons and a defensive error parse.
  - TDD honesty: the `%20` fix went in with the main implementation, so that quirk was never seen failing alone.

### Article Hook
- **Publication Takeaway:** An OAuth desktop login can be tested end to end with no network and no registered
  app if you inject the three real boundaries: the browser, the token endpoint and the clock.
