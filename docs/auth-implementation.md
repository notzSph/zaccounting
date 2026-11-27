# Identity and authentication implementation

## Scope

The first auth slice ports FAExpo account and session behavior to Spring Security. Users may sign in with either normalized email or normalized username. MFA is deferred. Business membership enforcement and business-scoped authorization remain a separate slice.

## API

All routes are under `/api/v1/auth`.

| Method | Route | Behavior |
| --- | --- | --- |
| `GET` | `/csrf` | Issues the CSRF cookie and returns its token for the `X-XSRF-TOKEN` request header. |
| `POST` | `/register` | Creates an unverified account and sends an email confirmation link. |
| `POST` | `/verify-email` | Consumes a one-use confirmation token. |
| `POST` | `/resend-verification` | Sends a new confirmation link when the account needs verification. |
| `POST` | `/login` | Accepts `{ "identifier": "email-or-username", "password": "..." }`, then sets the session cookie. |
| `GET` | `/me` | Returns the current active, verified account. |
| `POST` | `/logout` | Revokes the current session and clears the cookie. |
| `POST` | `/forgot-password` | Requests a reset email with a generic response. |
| `POST` | `/reset-password` | Consumes a one-use reset token, changes the password and revokes all sessions. |

Every unsafe request requires the CSRF token. If an `Origin` or `Referer` header is present, it must match the configured origin. Call `GET /api/v1/auth/csrf` first, retain the `XSRF-TOKEN` cookie, then send its value in `X-XSRF-TOKEN` on POST requests. The application uses one same-origin web/API origin.

## API error contract

API errors use the shared `com.zaccounting.api.common.error` contract and RFC 9457 `application/problem+json` responses. Clients should branch on the stable `code`, not on human-readable text. Responses include an `X-Request-ID` header and the same `requestId` property in the body. Validation responses may include field names, constraint codes, and safe validation messages, never rejected values.

Common codes include `VALIDATION_FAILED` (400), `AUTHENTICATION_REQUIRED` and `AUTHENTICATION_FAILED` (401), `ACCESS_DENIED` and `ORIGIN_NOT_ALLOWED` (403), `RESOURCE_NOT_FOUND` (404), `ACCOUNT_IDENTIFIER_CONFLICT` and `REQUEST_CONFLICT` (409), `RATE_LIMITED` (429), `DEPENDENCY_UNAVAILABLE` (503), and `INTERNAL_ERROR` (500). Rate-limit responses also include `Retry-After`.

Expected application failures use `ApiException` with an `ApiErrorCode`. The global `ApiExceptionHandler` maps validation, framework, database, security, and unexpected failures to the same response shape. Unexpected errors return a generic `INTERNAL_ERROR`; server logs include the request ID and exception details. Authentication, origin, and CSRF failures raised before MVC use the same `ApiProblemWriter`.

Login failure text remains generic. Password recovery and verification resend keep their anti-enumeration responses when database operations fail, while recording the failure and request ID server-side. Tokens, passwords, and account identifiers must not be added to error responses or diagnostic log messages.

## Storage and controls

- Passwords use Spring Security's Argon2 encoder with FAExpo's parameters: Argon2id, 65,536 KiB memory, three iterations, parallelism four, 16-byte salt and 32-byte hash. Bouncy Castle is pinned because Spring Security's Argon2 implementation requires it. A process-wide two-permit guard bounds concurrent password-hash work, including unknown-account dummy checks.
- Passwords are accepted at 12 to 256 Unicode code points and at most 512 UTF-8 bytes. Passwords and submitted tokens are never logged.
- Session and reset/verification tokens contain 32 random bytes and are stored only as SHA-256 digests. Sessions expire after 30 days. Reset links expire after 30 minutes; verification links expire after 24 hours. Each token is single-use.
- Five failed password attempts lock an account for 15 minutes. The failure counter resets after the lock expires. Login is limited to 10 attempts per identifier and 100 attempts per source network per 15 minutes, with a shared 1,000-attempt ceiling per 15 minutes. Password hashing for registration and reset has a shared 1,000-operation ceiling per 15 minutes and a two-operation per-process concurrency cap. Registration is limited to three attempts per normalized email and username plus 30 per source network per hour; verification resend is limited to three per email and 20 per source network per hour; email-token verification is limited to 30 per source network per hour; password recovery is limited to three per email and 20 per source network per 15 minutes. Shared buckets use PostgreSQL and HMAC-derived bucket keys. The limiter policy runs before the authentication service transaction, so each request does not need a second connection while holding auth locks.
- Source network buckets group IPv4 addresses by `/24` and IPv6 addresses by `/64`. `X-Forwarded-For` is honored only when the immediate peer matches `TRUSTED_PROXY_CIDRS`; configure only the reverse proxy's actual network. Otherwise the socket peer address is used.
- Verification and recovery email uses a PostgreSQL outbox inserted in the same transaction as the challenge. Unknown-address recovery requests insert a no-op job to keep the request path similar. The worker claims jobs with a time-limited lease, retries delivery with bounded backoff, and clears encrypted payloads after success or permanent failure. Delivery is at-least-once, so a crash after SMTP acceptance can produce a duplicate email. Links remain single-use. SMTP work never runs in the request. Terminal job metadata is retained for 30 days.
- Outbox recipient and token payloads are encrypted with Spring Security AES-256-GCM. Keep `AUTH_OUTBOX_ENCRYPTION_KEY` outside PostgreSQL and source control, stable across restarts and replicas. Losing or rotating this key makes pending messages undecryptable; successful and permanently failed jobs have their ciphertext cleared.
- Password reset changes the credential, consumes reset tokens, revokes active sessions and records an audit event in one database transaction.
- Session cookies are `HttpOnly`, `SameSite=Lax`, path `/`, and `Secure` according to `AUTH_COOKIE_SECURE`. Keep Secure enabled in production.
- TOTP, recovery codes, password/email change, and business-scoped authorization are not part of this first slice.

## Runtime configuration

Set these values in the ignored local `infra/secrets/.env` or in the deployment secret store. Do not commit real values.

- `AUTH_RATE_LIMIT_PEPPER`: random secret with at least 32 UTF-8 bytes. Generate one with `openssl rand -hex 32`. Keep it stable across API replicas; rotating it resets existing rate-limit bucket identities.
- `AUTH_OUTBOX_ENCRYPTION_KEY`: Base64 encoding of exactly 32 random bytes. Generate one with `openssl rand -base64 32`. Keep it in the deployment secret store, back it up securely, and use the same value across API replicas.
- `TRUSTED_PROXY_CIDRS`: comma-separated CIDRs for reverse proxies allowed to supply `X-Forwarded-For`. Set this to the actual proxy source range; do not trust all networks. If empty, forwarded headers are ignored.
- `AUTH_MAIL_POLL_INTERVAL`: outbox polling delay in milliseconds, default `2000`.
- `AUTH_COOKIE_SECURE`: `true` for HTTPS production. The example uses `false` for local HTTP development.
- `PUBLIC_ORIGIN`: exact browser origin used for CSRF defense, such as `https://accounting.example.com`.
- `FRONTEND_URL`: public web base URL used to construct verification and reset links.
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, and `MAIL_FROM`: required before registration and recovery are available. The application returns a service-unavailable response when mail delivery is not configured.

## Remaining work

- Build and exercise the Flyway migrations against both an empty database and a database with pre-existing V1 users. V2 leaves the three login identifier columns null together for legacy users until an explicit identity-mapping path is chosen.
- Exercise parallel outbox workers, expired leases, SMTP retry behavior, encryption-key rotation failures, and account-existence timing under PostgreSQL.
- Add the corresponding Next.js auth screens so emailed verification/reset links and session actions have a user-facing flow.
- Add focused tests for Argon2 parameters, identifier normalization, lockout, rate-limit concurrency, CSRF/origin behavior, cookie attributes, session expiry/revocation, token single-use, reset concurrency and account status.
- Add password and email change flows if required by the preserved product behavior.
- Implement business membership authorization as a separate slice. Do not treat a valid user session as permission to access a business.
- Add TOTP enrollment, confirmation, encryption-key management and one-use recovery codes in a later MFA slice.
