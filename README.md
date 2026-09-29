# FitDesi AI

Offline-first Pakistani fitness and nutrition companion for Android.

## Product overview

FitDesi AI is an early Android preview for people who want practical workout, nutrition, and fitness tools that remain useful without a paid model API or always-on connection. It combines locally persisted activity data, Pakistani food references, calculators, structured routine tools, and a deterministic offline Coach.

## Problem

Many fitness apps assume continuous connectivity, generic food catalogues, and one-size-fits-all recommendations. Pakistani users need familiar food references, locally usable planning tools, and honest guidance that works even when a remote service is unavailable.

## Solution

FitDesi AI keeps its core experience local-first. Profile, routine, workout, calorie, and preference data stay within the app's existing persistence layers. The current Coach uses deterministic FitDesi knowledge and bounded profile context to provide structured, general fitness guidance without claiming to be a medical or live-LLM service.

## Core features

- Home dashboard with real calorie, macro, hydration, activity, and workout state
- Workout dashboard, active session tracking, completion summaries, history, and analytics
- Build Routine and a persistent multi-routine library
- Deterministic offline AI Coach with structured workout and diet guidance
- Pakistani Food Tracker with 39 verified USDA nutrition records plus 20 nutrition-free South Asian discovery identities
- Macro Calculator and One-Rep-Max Calculator
- Persisted profile, units, appearance, calorie targets, and fitness context
- Tools dashboard and five main tabs: Home, Workout, AI Coach, Tools, and Profile
- Light, Dark, and System appearance support
- Adaptive launcher icon and native Android splash screen

## Why FitDesi is different

- **Pakistani context:** food references, search aliases, and planning language are designed around familiar local choices.
- **Offline-first:** the main preview experience does not require a paid model key or remote inference.
- **Truthful data states:** reviewed, estimated, and incomplete food data are presented differently rather than silently treated as equally precise.
- **Practical continuity:** routines, logs, and preferences persist locally across normal use and compatible signed APK updates.

## Offline-first architecture

Compose screens consume existing ViewModel state and callbacks. Domain logic stays in local engines and repositories, with Room used for workout/calorie records and DataStore used for profile, preferences, and plan/routine state. Bundled exercise and food knowledge supports the offline experience.

The repository also contains a backend under `backend/` (Node 22) with protected provider boundaries for future reviewed deployment. Production Remote AI is OFF and unlaunched. Backend secrets are environment/server-side only and never enter the APK. See [architecture](docs/architecture.md) and [security policy](SECURITY.md).

## Pakistani and South Asian food intelligence

The public-safe runtime uses two strictly separated food layers:

- **Verified nutrition (39 records)** — USDA FoodData Central records that satisfy FitDesi's commercial-verification gate (15 Foundation / 11 FNDDS / 12 SR Legacy / 1 branded-label-derived). These are searchable, loggable, and used in calorie and deterministic diet calculations. Source class: public domain / CC0-1.0.
- **South Asian discovery (20 records)** — FitDesi-authored cultural food identities (roti, biryani, karahi, daal, chole, kabab, lassi, kheer, and more). They are searchable and visible but carry **no** nutrition values, are **not** loggable, and always display “Nutrition verification in progress.” Nutrition verification of additional South Asian foods is ongoing.

Together the food experience surfaces 59 food concepts, but only the 39 verified records contain nutrition — FitDesi does not claim all 59 are nutritionally verified. A separate private engineering catalogue (165 records) retains unresolved legacy and Nourish-derived estimates for historical tooling; those values are excluded from the public runtime and are not redistributed as verified. FitDesi does not claim laboratory precision or perfect household-serving accuracy. See [data sources](docs/DATA_SOURCES.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

## AI Coach architecture accuracy

The Coach uses deterministic offline-first guidance with bounded local persistent conversation history (Coach Chat V2). It classifies bounded requests and returns locally generated fitness, workout, diet, food, progress, and safety guidance. An authenticated remote Coach transport architecture exists behind the backend boundary, but production Remote AI remains intentionally default-off and unlaunched (`AI_PROVIDER=mock`, `REMOTE_AI_ENABLED=false`). No provider key is required for the core demo.

The Coach is not a medical professional. It does not diagnose, treat, or replace qualified clinical advice.

## Screenshots

Product screenshots and the final demo are separate submission artifacts and are not represented as release evidence by this source export.

## Current development status

FitDesi AI `0.2.0-alpha` is an **early product-development preview**. The Android application is actively evolving, and the public build should be treated as a tester preview rather than a Play Store-ready release. The core local workflow is available; data provenance review, broader device coverage, and production-service decisions remain ongoing.

Basic is the useful local/offline core. Manager-supplied RevenueCat Test Store QA evidence records that a Monthly purchase activated Plus, Profile and paywall presented Plus, restart while the membership remained active resolved Plus again from CustomerInfo, and the tested active-user Restore path returned Plus active. That evidence is not production Google Play billing, cross-install/store-history restore, or real-revenue evidence. Pro is **Coming Soon** and cannot be purchased. Rewarded Boost exists only in the QA/debug candidate and grants bounded temporary access; release rewarded ads remain disabled. Production Remote AI is OFF and unlaunched. Google Play production publication is deferred.

## Installation for testers

The privately signed Shipaton QA APK is maintained separately from this public-source export. An ordinary local `:app:assembleDebug` build uses the developer machine's normal Android debug key, has application ID `com.fitdesiai.app.qa`, and is not byte-for-byte or signing-equivalent to that accepted APK. Updating an existing installation requires a matching application ID and a compatible signing identity. Android may accept the same or a higher `versionCode`, depending on the install/update path and platform policy.

The core preview does not require a paid model API key. Do not share personal health data, credentials, or keystores in issue reports or screenshots.

## Building locally

Requirements:

- JDK 17
- Android SDK compatible with the versions declared in the project
- Android project root: `mobile/`
- Backend (optional, for remote AI development): Node 22, project root: `backend/`
- A Firebase Android client file from your own Firebase project at `mobile/app/google-services.json`. It is ignored by Git and must include a client matching `com.fitdesiai.app.qa` for an ordinary debug build. Do not reuse or publish FitDesi's private QA configuration.

Windows PowerShell:

```powershell
cd mobile
.\gradlew.bat :app:assembleDebug
```

Linux or macOS:

```bash
cd mobile
./gradlew :app:assembleDebug
```

The debug APK is produced under `mobile/app/build/outputs/apk/debug/`. Local preview builds use the normal local debug signature; the core offline demo does not need provider secrets. The Firebase Android client file is build configuration, not a provider or Firebase Admin credential, and must remain outside Git.

Provider credentials and other secrets remain server-side and are supplied by the deployment/secret-management boundary. Firebase Admin uses Application Default Credentials (ADC) / deployment identity; a service-account JSON must never be committed. Service-account JSON, provider keys, and signing material never belong in Git or the APK. See `backend/.env.example` for variable names.

## Technology stack

- Kotlin, Jetpack Compose, and Material 3
- Android ViewModels, Kotlin coroutines, and Flow
- Room and DataStore
- Bundled exercise and Pakistani-food knowledge assets
- Retrofit/OkHttp provider boundary, disabled for the current offline runtime
- Gradle and JDK 17; private CI workflow configuration is omitted from the
  proposed public source export

## Data privacy

Core preview data is stored in the app's existing local persistence layers. Provider credentials and signing material must never be committed to source, resources, screenshots, or logs. Remote services require separate review and are not needed for the current offline preview.

## Safety disclaimer

FitDesi AI provides general fitness and nutrition information only. It does not diagnose, treat, or replace professional medical care, emergency services, or individualized clinical advice. Users should seek qualified help for symptoms, injury, or medical concerns.

## Roadmap

- Expand provenance and serving-basis review for food data
- Improve device coverage and tester feedback loops
- Continue accessibility, localization, and offline-quality improvements
- Evaluate optional remote capabilities only through the existing protected provider boundaries

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening an issue or pull request.

## Security reporting

Please read [SECURITY.md](SECURITY.md). Do not disclose vulnerabilities, credentials, or personal fitness data in public issues.

## License

FitDesi AI is released under the [MIT License](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for exercise-data, food-data, and media restrictions.

## Public-source boundary

This proposed export intentionally omits private engineering history, internal project context, historical audit archives, private food inputs, private CI configuration, credentials, signing material, and build outputs. See the [public export plan](docs/PUBLIC_EXPORT_PLAN.md) and [asset provenance record](docs/ASSET_PROVENANCE.md). Public-only food validation proves the exported snapshot and checksum contract; it does not prove the omitted private derivation.
