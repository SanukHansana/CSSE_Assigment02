# Custom JWT authentication prerequisite

This is the separately authorized authentication module, preceding ground-report Stage 2. No ground-reporting endpoints or frontend login screens are implemented in this change.

## Configuration

The backend now loads `backend/.env` as an optional **Java properties** file when started from `backend`; existing `application-local.properties` continues to load and takes precedence. Use `KEY=value` lines, without `export`. Matching outer single/double quotes are supported by an environment post-processor. This loader is not a general-purpose shell/dotenv parser. Shell variable expansion and multiline dotenv values are not supported. Operating-system environment variables take precedence over these files.

A signing key was added to the existing ignored `.env` only if `JWT_SECRET` was absent; existing contents and existing secrets were preserved. Keys are never returned or logged. `.env.example` contains placeholders only. Do not put a token or signing key in `frontend/.env` or an `EXPO_PUBLIC_*` variable.

```properties
JWT_SECRET=<Base64-encoded random key, at least 32 bytes>
JWT_TTL_SECONDS=900
WEB_ALLOWED_ORIGINS=http://localhost:8081
```

Generate a key with `openssl rand -base64 32`. Missing, malformed, or too-short keys prevent startup. Never use the all-zero test key outside tests. Tokens are signed with HS256 and validated with Spring Security's resource-server support; the issuer is `dmc-backend` and audience is `dmc-api`. The configurable access-token lifetime is 60–3600 seconds, default 15 minutes. Changing the signing key invalidates existing tokens. Do not share the key with clients or other untrusted services.

```sh
cd backend
./mvnw spring-boot:run
```

Use the existing MongoDB Atlas connection setup; do not place credentials in source code or pass them through the frontend. Apply `migrations/002-custom-auth-users.js` using the migration README's explicit mongosh workflow against the intended development database. No Atlas migration or account creation was performed by this implementation. Registration also ensures the unique email index before inserting, so database privileges must permit creating that index. The startup/public availability endpoint does not require a reachable database.

## API

| Method | Endpoint | Access |
| --- | --- | --- |
| POST | `/api/dmc/auth/register` | Public, creates CITIZEN only |
| POST | `/api/dmc/auth/login` | Public, returns a short-lived access token |
| GET | `/api/dmc/auth/me` | Valid bearer token and enabled database account |
| GET | `/api/dmc` | Existing public availability response |

Registration request:

```json
{
  "email": "citizen@example.com",
  "displayName": "Kasun Perera",
  "password": "your-long-unique-password"
}
```

Registration returns 201 with `id`, normalized lowercase `email`, `displayName`, and `roles: ["CITIZEN"]`. It does not log the user in automatically. The password must contain 12–72 UTF-16 code units and at most 72 UTF-8 bytes (bcrypt's limit). Passwords are hashed with bcrypt cost 12 and are never trimmed. Names are trimmed. Email uniqueness is enforced by MongoDB, including races; duplicates return 409 `ACCOUNT_ALREADY_EXISTS`.

Login request:

```json
{
  "email": "citizen@example.com",
  "password": "your-long-unique-password"
}
```

Login returns:

```json
{
  "accessToken": "<issued JWT>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "expiresAt": "2026-10-09T10:15:00Z",
  "user": {
    "id": "<server account ID>",
    "email": "citizen@example.com",
    "displayName": "Kasun Perera",
    "roles": ["CITIZEN"]
  }
}
```

Send `Authorization: Bearer <issued JWT>` to `/me` and future protected reporting endpoints. User projections never include password hashes. Login responses use `Cache-Control: no-store`. Unknown email, incorrect password, and disabled accounts return the same 401 `INVALID_CREDENTIALS`; unknown-email comparisons still run bcrypt. Malformed JSON/field validation uses 400 and structured `fieldErrors`. Database failures return 503 `AUTH_UNAVAILABLE` without internal exception details. Missing/invalid bearer authentication uses JSON 401 responses.

Tokens contain subject ID, issuer, audience, issue/expiry time, and unique token ID, not passwords or roles. Each authenticated request loads its enabled account from MongoDB and derives authorities from **current stored roles**, ignoring caller/token role claims. A disabled/deleted account is rejected immediately. MongoDB availability is therefore necessary for protected requests, even with a correctly signed token. The token ID is not a revocation list.

## Role administration and hazard-report integration

Account roles are `CITIZEN`, `COMMUNITY_VOLUNTEER`, `DUTY_OFFICER`, and `DMC_OFFICER`. Public registration always stores CITIZEN. No public promotion endpoint exists, and no officer credentials are seeded. A trusted administrator must assign volunteer/officer roles in the database through an approved administrative workflow; user-supplied registration fields cannot grant privileges. Roles from request bodies are not an authorization source.

Stage 2 must use the authenticated JWT subject and current account to populate `ReporterIdentity`/`UserReference` and restrict reporter functions to Citizen/Community Volunteer accounts. `@EnableMethodSecurity` is available for later role enforcement. Officer and assessment permissions belong to subsequent reporting stages; merely adding account-role constants does not implement those modules.

## Mobile and web clients

Both clients use the same bearer-token API. Native clients can use Expo SecureStore for sensitive token persistence. Web clients should keep access tokens in memory rather than persist them in localStorage; a browser reload requires login again in this initial implementation. Refresh tokens, HttpOnly refresh cookies, and cookie sessions are **not implemented**. CSRF is disabled because the API authenticates solely through explicitly supplied Authorization headers, not automatically attached cookies. Revisit CSRF protections before adding cookie authentication.

Configure a comma-separated list of exact browser origins in `WEB_ALLOWED_ORIGINS`, for example `http://localhost:8081,https://your-web-app.example`. Wildcards and URL paths are rejected. CORS allows Authorization/Content-Type and future report mutation headers, but no cookie credentials. Expo's actual development URL/port must match the configured origin. Native clients do not use browser CORS. Use HTTPS for deployed login and API traffic.

## Deliberate limits and validation

This is a basic custom-auth foundation. There are no refresh tokens, per-device sessions, immediate individual-token logout/revocation, email verification, password reset, MFA, or frontend auth flows. Logout presently means discarding the client token; a copied token remains usable until expiry unless the account is disabled or the signing key changes. Deployment needs login/registration rate limiting at the gateway or a separately implemented backend mechanism; none is claimed here. Account role edits are administrative, not a new public module.

Tests cover password hashing, server-assigned Citizen roles, unique-index setup/duplicate errors, normalized emails, invalid credentials, disabled accounts, multibyte password limits, JWT claims/signatures/expiry/issuer/audience, protected HTTP access, database-derived role handling, request validation, and browser CORS. Tests use mocked account persistence and do not demonstrate live Atlas writes. Run `./mvnw test` from `backend`.

Official implementation references: [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
