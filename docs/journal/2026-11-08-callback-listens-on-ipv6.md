# 2026-11-08 - The login callback must listen on IPv6 too

### Context
- **Objective:** Make the browser login finish by itself. The owner's browser showed "localhost refused to
  connect" on the redirect; copying the URL into `wget` inside the container worked, so every login so far had
  been completed by hand.

### Architecture & Trade-offs
- **Key Choices:**
  - `CallbackServer` now listens on both `127.0.0.1` and `::1` on the same port, sharing one handler. IPv4 is
    required (a taken port still fails the login with a clear message); IPv6 is best effort for machines without it.
  - The registered redirect stays `http://localhost:<port>/callback`; only the listening side changed.

### Friction & Gotchas
- **Real Issues Encountered:**
  - Misleading evidence first: a headless Chromium with a clean profile reached an IPv4-only listener fine, and the
    first login had "worked", so the cause was blamed on the browser profile (an ad-blocker) and then on the new
    all-scopes request. Both were wrong. A first redirect test also proved nothing (httpbin refuses to redirect to
    internal host names).
  - The deciding experiment was server-side: listen on both families with request logging, open a probe URL in the
    real browser, and read the client address. It was `::1`, so the real browser resolves `localhost` to IPv6 and did
    not fall back to IPv4 after a refusal (wget does fall back).
  - Verified at the end with the real server and the real browser (the server returned the code).

### Article Hook
- **Publication Takeaway:** "Works from wget" tells you the server is up, not that your browser can reach it. Log the
  client address on the server side and let the real client make the call before theorising about extensions.
