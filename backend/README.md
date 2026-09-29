# FitDesi AI Backend

This Node.js service is the server-side trust boundary for FitDesi AI. It provides strict feature-specific endpoints, deterministic mock responses, and an optional Fireworks serverless implementation for `POST /api/ai/coach`. It is not a generic prompt or completion proxy.

FitDesi AI was developed during OpenAI Build Week using Codex and GPT-5.6. Deployed model inference is designed to run through Fireworks. The OpenAI JavaScript package is used only as an OpenAI-compatible protocol client configured for the Fireworks base URL; the OpenAI API is not used at runtime.

## Architecture

```text
Android app
  -> feature-specific FitDesi endpoint
  -> route resolution and coarse IP rate limiting
  -> Firebase Bearer authentication
  -> AI-only verified-email authorization
  -> bounded JSON parsing, strict request validation, and data minimization
  -> bounded FitDesi knowledge grounding
  -> Fireworks provider (AI Coach only, when explicitly enabled)
  -> strict structured-output and deterministic safety validation
  -> existing Android-compatible Coach fields
  -> Android local fallback on any backend/provider failure
```

Provider credentials, model selection, base URL, temperature, output limit, and system instructions are server-controlled. Android must never contain a Fireworks credential or call Fireworks directly. The on-device AI Coach and Workout Generator remain the offline fallback.

One shared, lazy Firebase Admin app supplies both token verification and the backend-only Firestore consent store. Firebase Admin uses Application Default Credentials from the server deployment identity; it does not use an APK/client secret or a checked-in service-account JSON file. The privacy authority owns all consent access through the fixed `remote_ai_consents_v1` collection. Ownership is derived only from the verified `principal.uid`; internally, the backend derives an opaque deterministic document key in the form `uid_<64 lowercase SHA-256 hex characters>`, so the raw Firebase UID is not used as the Firestore document ID or stored in consent fields. Android Firestore remains retired and is not consent authority.

## Requirements and local use

- Node.js 22 LTS
- npm
- Application Default Credentials / deployment identity for Firebase Admin when using protected routes

From `backend/` on Windows:

```powershell
npm.cmd ci
npm.cmd run check
npm.cmd test
npm.cmd start
```

On macOS or Linux, use `npm` instead of `npm.cmd`. The server listens on `127.0.0.1:3000` by default.

Mock mode requires no key and is the committed default:

```text
AI_PROVIDER=mock
REMOTE_AI_ENABLED=false
FIREWORKS_API_KEY=
FIREWORKS_MODEL=
FIREBASE_PROJECT_ID=fitdesi-ai
```

All four conditions must hold before a Fireworks request can run: `AI_PROVIDER=fireworks`, `REMOTE_AI_ENABLED=true`, a non-empty backend-only `FIREWORKS_API_KEY`, and a non-empty backend-only `FIREWORKS_MODEL`. Missing configuration fails closed. Never commit a populated `.env` file.

## Environment configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `HOST` | `127.0.0.1` | Listener address |
| `PORT` | `3000` | Listener port |
| `AI_PROVIDER` | `mock` | `mock` or `fireworks`; mock is safe by default |
| `REMOTE_AI_ENABLED` | `false` | Independent remote-execution gate |
| `FIREBASE_PROJECT_ID` | empty | Explicit trusted Firebase project for ID-token verification (`fitdesi-ai` QA or `fitdesi-ai-production`) |
| `REMOTE_AI_NOTICE_VERSION` | empty | Server-owned opaque standard privacy-notice identifier; required for consent endpoints/authorization, and positive grants must echo its current value |
| `EXPERIMENTAL_AI_NOTICE_VERSION` | empty | Server-owned opaque experimental/training privacy-notice identifier; required for consent endpoints/authorization, and positive grants must echo its current value |
| `REVENUECAT_V2_SECRET_KEY` | empty | Backend-only RevenueCat REST v2 secret; never expose it to clients |
| `REVENUECAT_PROJECT_ID` | empty | RevenueCat project used for server-side commercial-state reads |
| `REVENUECAT_PLUS_ENTITLEMENT_ID` | empty | Server-configured Plus entitlement identifier |
| `REVENUECAT_PRO_ENTITLEMENT_ID` | empty | Server-configured Pro entitlement identifier |
| `REVENUECAT_BOOST_ENTITLEMENT_ID` | empty | Server-configured Boost entitlement identifier; Boost is not a tier |
| `REVENUECAT_TIMEOUT_MS` | `5000` | RevenueCat request timeout |
| `FIREWORKS_API_KEY` | empty | Backend-only secret supplied by the deployment secret manager |
| `FIREWORKS_MODEL` | empty | Fixed server-side Fireworks model identifier |
| `FIREWORKS_TIMEOUT_MS` | `8000` | Shared provider-operation timeout; must remain below `REQUEST_TIMEOUT_MS` |
| `FIREWORKS_MAX_OUTPUT_TOKENS` | `1800` | Fixed maximum generated tokens |
| `FIREWORKS_IP_REQUESTS_PER_MINUTE` | `20` | Fireworks Coach requests per IP per minute |
| `FIREWORKS_INSTALLATION_REQUESTS_PER_MINUTE` | `12` | Requests per installation bucket per minute |
| `FIREWORKS_INSTALLATION_REQUESTS_PER_DAY` | `100` | Requests per installation bucket per rolling day |
| `FIREWORKS_MAX_CONCURRENT_REQUESTS` | `4` | Process-wide Fireworks concurrency |
| `FIREWORKS_MAX_CONCURRENT_PER_INSTALLATION` | `1` | Per-installation Fireworks concurrency |
| `FIREWORKS_CIRCUIT_FAILURE_THRESHOLD` | `3` | Counted failures before opening the circuit |
| `FIREWORKS_CIRCUIT_RESET_MS` | `30000` | Open-circuit cooldown |
| `CORS_ALLOWED_ORIGINS` | empty | Comma-separated exact browser origins; empty rejects browser Origin headers |
| `TRUST_PROXY` | `false` | Trust the first forwarded client IP only behind a reviewed proxy that overwrites the header |
| `REQUEST_BODY_LIMIT_BYTES` | `32768` | Maximum JSON request size |
| `REQUEST_TIMEOUT_MS` | `10000` | Application request deadline |
| `HEADERS_TIMEOUT_MS` | `15000` | Header receive deadline |
| `KEEP_ALIVE_TIMEOUT_MS` | `5000` | Idle keep-alive timeout |
| `RATE_LIMIT_WINDOW_MS` | `60000` | General in-process client-limit window |
| `RATE_LIMIT_MAX_REQUESTS` | `60` | General requests per client/window |

The Fireworks base URL is fixed in server configuration as `https://api.fireworks.ai/inference/v1`, and temperature is fixed at `0.2`. Neither is accepted from clients. The optional `x-fitdesi-installation-id` header is bounded and one-way hashed for rate buckets; when absent, the server uses an IP-derived fallback bucket. This identifier is an abuse-control hint, not authentication.

The two privacy-notice variables are server authority, not client configuration. Empty, malformed, or unreviewed values make consent operations fail closed. They are validated when consent authority is used rather than during general configuration loading, so empty values do not prevent health, default-off, or deterministic mock startup. A client making a positive consent decision must echo the corresponding current server-required value.

The backend derives the RevenueCat customer identity as `fd_<FirebaseUid>` from the authenticated Firebase UID; client-provided commercial identity cannot override it. RevenueCat authority is currently read-only and normalizes active entitlements into `BASIC`, `PLUS`, or `PRO` plus a separate Boost capability. This commercial authority is not yet wired into AI capability or quota enforcement. The secret stays server-side, and no real RevenueCat secret is committed.

## API endpoints

| Method | Path | Runtime behavior |
| --- | --- | --- |
| `GET` | `/api/health` | Reports mock, disabled, or Fireworks mode without exposing configuration |
| `GET` | `/api/auth/session` | Verifies a Firebase Bearer ID token with revocation checking and reports only authenticated/email-verification state |
| `GET` | `/api/privacy/consent` | Authenticated for verified or unverified email; returns normalized consent and currentness without identity, path, project, provider, or commercial state |
| `PUT` | `/api/privacy/consent` | Authenticated for verified or unverified email; grants or withdraws standard and/or experimental consent and returns `204` after durable mutation |
| `DELETE` | `/api/privacy/consent` | Authenticated for verified or unverified email; idempotently removes only the consent record and returns `204` |
| `POST` | `/api/ai/coach` | Deterministic mock by default; the only Fireworks-enabled vertical slice |
| `POST` | `/api/ai/workout-plan` | Deterministic mock only |
| `POST` | `/api/ai/food-analyze` | Deterministic mock only |
| `POST` | `/api/ai/diet-plan` | Deterministic mock only |
| `POST` | `/api/ai/yoga-plan` | Deterministic mock only |
| `POST` | `/api/ai/progress-review` | Deterministic mock only |

There is no generic completion route. Unknown input properties are rejected.

`GET /api/health` remains public. `GET /api/auth/session` requires exactly one
`Authorization: Bearer <Firebase-ID-token>` header and accepts authenticated
identities whether `emailVerified` is true or false. All six `POST /api/ai/*`
routes require the same Firebase authentication and additionally require
`emailVerified=true`. For AI requests, processing order is route resolution,
coarse IP rate limiting, Firebase authentication, verified-email authorization,
bounded JSON parsing, existing handler/provider execution, and response
validation. RevenueCat commercial state is not currently consulted by this AI
route gate.

Firebase Admin verifies tokens against the server-configured project with
revocation checking; request fields, custom UID headers, email, installation
IDs, and RevenueCat identifiers are not identity authority. The backend returns
neither UID, email, decoded claims, nor token metadata. Missing Firebase project
configuration, deployment identity, or verification infrastructure fails closed
without affecting public health checks.

### Privacy consent authority

`GET`, `PUT`, and `DELETE /api/privacy/consent` require the same strict Firebase Bearer authentication, but they intentionally do not require a verified email. An authenticated user with `emailVerified=false` may read consent state, grant consent, withdraw consent, and delete the consent record. These endpoints record privacy intent only. Positive consent never bypasses the separate Stage 12B `emailVerified=true` requirement on all six AI `POST` routes and does not by itself authorize remote processing.

The standard remote-AI and experimental/training decisions are separate. Experimental consent defaults to false and an experimental grant requires effective current standard consent. A standard withdrawal atomically revokes experimental consent. A stored positive grant whose notice version is stale remains visible as `granted=true` but `current=false`; authorization depends on `current=true`, not on `granted` alone. Positive mutations fail closed when stored state is malformed. A withdrawal may omit its notice-version echo, and a recovery mutation can replace malformed state only toward both decisions being false.

`PUT` accepts standard and/or experimental grant or withdrawal decisions. Positive grants must echo the corresponding current server-required notice version; a withdrawal may omit the echo or send a syntactically valid stale value. The route returns `204` only after the authoritative transactional mutation completes. The client can then use `GET` to retrieve normalized currentness. The GET response does not contain a Firebase UID, opaque document key/path, email or claims, Firebase project, provider state, or RevenueCat/commercial state.

`DELETE` is idempotent and removes only the backend consent record. It is not Firebase Authentication account deletion, local Room/DataStore/app-data deletion, RevenueCat deletion, provider-side data deletion, or complete account/data erasure.

The domain methods `authorizeStandardRemoteProcessing({ firebaseUid })` and `authorizeExperimentalProcessing({ firebaseUid })` exist, but Stage 12D does not wire them into AI providers. P0/P2 routing remains future Stage 12F work; these privacy routes perform no provider selection, and remote AI remains default off.

Current evidence is limited to local deterministic Node tests, injected/fake Firestore behavior, and route, authentication, normalization, and transaction contracts. It does not establish live Firestore success, deployed ADC/workload identity or IAM success, Firestore Security Rules validation, Cloud Run or Secret Manager deployment, production provider routing or remote AI, Android runtime privacy UI, or store/release readiness.

The Coach accepts a bounded question and optional allow-listed fitness context: goal, experience, equipment, compact recent-workout summary, calorie/macro targets, dietary preference, meal count, workout-day count, and limitations. It does not accept a provider, model, key, base URL, system prompt, token limit, temperature, or tool configuration. It also does not accept name, email, device data, or complete workout/food histories.

Conversation history is not accepted in this first vertical slice, so its effective server-side limit is zero. A future history contract must define bounded typed turns and pass a separate privacy review.

Successes use this envelope, with `mode` set to `mock` or `fireworks`:

```json
{
  "success": true,
  "requestId": "correlation-id",
  "mode": "mock",
  "data": {}
}
```

Errors contain a stable code, friendly message, and request ID. Raw provider bodies, stack traces, prompts, credentials, model identifiers, and configuration are never returned.

## Fireworks request and validation flow

1. The HTTP layer enforces content type, the 32 KiB body cap, exact request fields, field/list limits, general rate limiting, and the 10-second request deadline.
2. Mock mode returns the existing deterministic response without constructing a Fireworks client.
3. Genuine medical red flags are handled by deterministic escalation before provider invocation, so that health-adjacent prompt is not transmitted.
4. The provider gate checks provider selection, the independent remote flag, key, and model.
5. The server retrieves at most 20 exercises, 10 Pakistani foods, and 10 general knowledge records. It sends only bounded, relevant fields.
6. Per-IP/per-installation limits, daily limits, concurrency, and the circuit breaker are applied.
7. The Fireworks response must satisfy the strict AI Coach JSON schema. Workout plans are checked for requested day count, unique days, stable exercise IDs, names, roles, and equipment compatibility. Diet plans are checked for target safety, known food IDs/nutrition, and bounded totals. Deterministic medical escalation is checked again.
8. A maximum of one additional provider call is allowed for either repair or transient retry. Both attempts share one eight-second abort deadline. Provider 401/403 and 429 responses are not retried; Fireworks `Retry-After` is preserved for client-safe throttling.
9. Failed validation or provider execution returns a controlled error so Android can use its local fallback.

Privacy-safe logs contain request ID, endpoint, status, duration, provider alias, input character count, token usage when supplied, validation result, and fallback status. They exclude prompts, responses, profile details, health/diet details, authorization headers, keys, and installation identifiers.

## Current Android compatibility limitation

The backend validates the full structured Fireworks result internally, then projects the response to the five existing Android Coach fields: `summary`, `recommendedAction`, `nutritionNote`, `workoutNote`, and `safetyDisclaimer`. This avoids sending unknown response properties to the current strict Moshi DTO.

However, the current Android transport accepts only success envelope mode `mock`, while a real provider success is honestly labelled `fireworks`. The current `CoachRequestDto` also sends only `question`. Therefore a Fireworks success will currently be rejected by the Android boundary and activate the local fallback, and optional profile context or structured plan payloads are not yet delivered to Android. A separate reviewed mobile-contract task is required before remote mode is useful in the app. Committed Android safe-mode flags remain unchanged.

## Deployment

1. Deploy behind HTTPS using a Node 22-compatible service.
2. Run `npm ci`, `npm run check`, and `npm test` in the build stage.
3. Supply `FIREWORKS_API_KEY` through the platform secret manager, not source control.
4. Supply an explicitly reviewed Fireworks serverless model identifier as `FIREWORKS_MODEL`.
5. Set `AI_PROVIDER=fireworks` and `REMOTE_AI_ENABLED=true` only in the reviewed deployment environment.
6. Keep CORS restricted. Set `TRUST_PROXY=true` only when the platform overwrites forwarded client-IP headers.
7. Verify health, mock fallback behavior, stable client errors, provider timeouts, throttling, and redacted logs before enabling Android backend mode.

Firebase authentication, AI-route verified-email authorization, and the backend privacy-consent authority are implemented. Consent authorizers are not yet wired into provider routing. The abuse controls and circuit breaker remain in-process: they reset on restart/cold start and do not coordinate across serverless instances. Before public production exposure, add reviewed AI capability and consent enforcement at provider routing, durable quotas/accounting and idempotency, a shared rate-limit/circuit store, durable privacy-safe observability, retention/deletion controls, domain evaluations, cost alerts, and deployment-specific egress/TLS protections. Cloud Run and Secret Manager deployment, production secrets, real RevenueCat QA, provider routing, and production remote-AI enablement are not complete. Do not embed a permanent authentication secret in the APK.
