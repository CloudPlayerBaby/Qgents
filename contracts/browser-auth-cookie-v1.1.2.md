# Browser Authentication Cookie Contract v1.1.2

## Authentication Transport

- Browser access and refresh tokens are sent only in `qgents_access_token` and `qgents_refresh_token` HttpOnly, Secure, SameSite=Strict, host-only cookies. Their paths are `/api/v1` and `/api/v1/auth` respectively.
- `GET /api/v1/auth/csrf` returns `204` and exposes the CSRF value through `X-XSRF-TOKEN`. Browser clients return that header on every unsafe request.
- Successful login, registration, and refresh set both authentication cookies and return a CSRF header. Refresh failure and logout clear authentication and CSRF cookies.
- SSE, WebSocket, and attachment preview URLs use cookies and must not carry a query token.

## Migration

- With `AUTH_LEGACY_TOKEN_COMPATIBILITY=true`, legacy JSON token responses, Bearer access input, and JSON refresh input remain temporarily supported. Cookie authentication takes precedence.
- With the flag set to `false`, login responses contain only the session user, refresh/logout read only cookies, and all unsafe requests require CSRF validation. Remove obsolete DTOs and Bearer compatibility only after that deployment is stable.
- Internal service credentials and third-party OAuth tokens are outside this browser contract.
