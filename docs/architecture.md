# Architecture

## Current product boundary

FitDesi AI `0.2.0-alpha` is a single-module Kotlin/Jetpack Compose Android
application under `mobile/`, with an optional Node.js 22 backend under
`backend/`.

The useful Basic core is local/offline:

- Compose screens consume ViewModel state and explicit callbacks.
- Room stores calorie, workout, and exercise records.
- Preferences DataStore and SharedPreferences store profile, appearance,
  routine/plan, and other existing preferences.
- Deterministic local Coach and Workout Generator providers preserve useful
  behavior without a paid model key or network connection.
- Bundled exercise, programming, safety, and food assets provide the canonical
  local knowledge boundary.

No Room/DataStore schema, key, or persisted format changes are part of the
public-export preparation.

## Packaged data

- The sanitized legacy exercise asset has 522 unique stable IDs and no media
  properties or paths.
- The active canonical knowledge pack has 534 unique exercise records and is
  unchanged.
- The only nutrition/logging catalogue contains 39 verified USDA records.
- A separate 20-record South Asian discovery asset contains identities only:
  no calories, macros, serving nutrition, imported provenance, or logging flag.

Discovery records cannot enter calorie/macro calculations, deterministic diet
generation, or backend food grounding.

## Commercial boundary

- **Basic:** useful local/offline core.
- **Plus:** manager-supplied Test Store QA evidence records a Monthly purchase
  activating Plus, Profile/paywall presenting Plus, active-membership restart
  recovery from CustomerInfo, and the tested active-user Restore path returning
  Plus active.
- **Pro:** Coming Soon; no purchase path.
- **Boost:** temporary bounded access in the QA/debug candidate. Release
  rewarded ads remain disabled.

RevenueCat Test Store evidence is not production Google Play billing,
cross-install/store-history restore, or real-revenue evidence.

## Remote-service boundary

The Android app contains typed Firebase Authentication, backend transport,
commercial-state, consent, and remote-admission clients. The backend contains
Firebase ID-token verification, verified-email enforcement for protected AI
routes, consent and RevenueCat commercial authorities, quota/admission
controls, provider routing, strict structured response validation, bounded
timeouts, stable public errors, and privacy-safe logging.

Those components do not launch a service. Committed backend defaults remain
`AI_PROVIDER=mock` and `REMOTE_AI_ENABLED=false`; the launched-capability
set is empty. Production Remote AI is therefore OFF and unlaunched. Live
deployment, workload identity/ADC, Firestore, production Firebase and
RevenueCat, provider credentials/routing, privacy/legal approval, semantic
quality approval, quota operations, and production runtime validation remain
external gates.

Provider, Firebase Admin, RevenueCat server, admission, and signing secrets
must remain server-side or in approved secret managers. They never belong in
Android resources, `BuildConfig`, `local.properties`, tests, screenshots,
logs, or Git.

## Build boundary

- Android project root: `mobile/`
- Application module: `mobile/app/`
- Application IDs: `com.fitdesiai.app.qa` (debug) and
  `com.fitdesiai.app` (release)
- Version: `0.2.0-alpha`, `versionCode = 14`
- Android Gradle Plugin: 9.1.1
- Gradle wrapper: 9.3.1
- Required JDK: 17

The Google Services plugin is applied to the app module. A clean build therefore
requires an external, ignored `mobile/app/google-services.json` from the
builder's own Firebase project with a client matching the selected application
ID. This Android client configuration is not a Firebase Admin credential and
must not be copied from FitDesi's private QA environment.

An ordinary local debug APK uses the developer machine's Android debug key. It
is not byte-for-byte or signing-equivalent to the privately signed Shipaton QA
APK, and it does not inherit that artifact's digest, Test Store configuration,
or phone-validation evidence.

## Public repository boundary

The proposed public repository is a default-deny, fresh-history export. Private
Git history, internal context/audits, private food inputs and generators,
private CI configuration, credentials, signing material, ignored local files,
and build outputs are excluded. See `docs/PUBLIC_EXPORT_PLAN.md`.
