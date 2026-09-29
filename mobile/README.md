# Run the FitDesi Android app

This directory is the Android/Gradle project root. Open this directory directly in Android Studio.

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio), JDK 17, and the Android SDK requested by Gradle sync.


1. Open Android Studio.
2. Select **Open** and choose this `mobile/` directory. Do not select `mobile/app/`.
3. Select Android Studio's bundled JDK 17 under Gradle settings if prompted.
4. Create a Firebase project under your control, register an Android client for
   `com.fitdesiai.app.qa`, and place its downloaded client file at
   `app/google-services.json`. The Google Services Gradle plugin requires this
   external, Git-ignored file even though the useful Basic core stays local/offline.
5. Allow Gradle sync to download Gradle 9.3.1 and required Android SDK components.
6. Keep the default safe-mode settings from `local.properties.example`.
7. Run the `app` configuration on an emulator or physical device.

Do not copy or publish FitDesi's private QA Firebase client. A release build uses
`com.fitdesiai.app` and therefore requires a separately registered matching client.
Firebase Admin service-account JSON is never an Android build input.

## Command-line build

Run commands from this `mobile/` directory:

```powershell
.\gradlew.bat tasks
.\gradlew.bat :app:assembleDebug
```

On macOS/Linux:

```bash
chmod +x gradlew
./gradlew tasks
./gradlew :app:assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. This ordinary
local debug build uses the local Android debug key and is not the privately signed
Shipaton QA APK. It cannot be used as evidence for that accepted APK's digest,
signature, Test Store configuration, or phone-validation result.

## Signed release builds

Release-producing Gradle tasks require all four values below from the process environment:

- `FITDESI_RELEASE_KEYSTORE_PATH`
- `FITDESI_RELEASE_KEYSTORE_PASSWORD`
- `FITDESI_RELEASE_KEY_ALIAS`
- `FITDESI_RELEASE_KEY_PASSWORD`

Use an operating-system or approved secret manager to inject these values into the current build process without placing them in shell history, `local.properties`, Gradle files, environment files, or the repository. The keystore path must resolve to an existing regular file outside the checkout. After configuring the environment, run `.\gradlew.bat :app:assembleRelease` on Windows or `./gradlew :app:assembleRelease` on macOS/Linux, then remove the variables from the process environment.

Release tasks fail before task execution when a required value is missing or blank, or when the keystore path is not a regular file. Debug builds, debug tests, and lint do not require release credentials. Never fall back to debug signing for a release artifact.

Keep the production update-signing key in a securely backed-up system outside Git. Losing the update-signing material can prevent future versions from updating existing installations. In CI, store each value in the platform's encrypted secret manager, materialize the keystore only in a restricted temporary runner location, and remove it with an always-run cleanup step. Never print, inspect, upload, or cache the signing values or keystore.

## Backend URLs

Copy `local.properties.example` to the ignored `local.properties` file, preserve any Android SDK entry created by Android Studio, and set the non-secret URL:

```properties
FITDESI_BACKEND_DEBUG_BASE_URL=http://10.0.2.2:3000/
FITDESI_BACKEND_RELEASE_BASE_URL=https://your-reviewed-backend.example/
```

URLs must end with `/`. Never place OpenAI, Gemini, RapidAPI, Firebase, or other provider credentials in this file or Android `BuildConfig`.

- Android emulator: `10.0.2.2` reaches the development computer; emulator `localhost` does not.
- Physical phone: use the computer's LAN IP while both devices are on a trusted network. The backend defaults to loopback, so LAN testing also requires an intentional development-only backend host/firewall configuration.
- Release builds: use HTTPS. The release default remains `https://example.invalid/`.

Debug builds allow cleartext HTTP solely for emulator/LAN development. Release builds do not enable cleartext traffic.

## Safe/mock-development mode

Keep these defaults while the backend is unfinished:

```properties
FITDESI_BUILD_WEEK_SAFE_MODE=true
FITDESI_AI_PROXY_ENABLED=false
FITDESI_EXERCISE_PROXY_ENABLED=false
FITDESI_FIRESTORE_SYNC_ENABLED=false
FITDESI_ALLOW_DESTRUCTIVE_MIGRATION=false
```

This prevents active remote calls and keeps bundled/offline functionality available. A typed deterministic-backend client exists for health and AI Coach integration testing, but the visible AI Coach remains local under these defaults. If backend-mock mode is later deliberately enabled, any backend failure falls back to the local Coach.

Remote AI, exercise API, and Firestore features are intentionally disabled by default. Provider secrets belong in the backend environment described in `../backend/README.md`.

## Commercial feature boundary

- **Basic:** useful local/offline core; no RevenueCat configuration is required.
- **Plus:** manager-supplied RevenueCat Test Store QA evidence records a Monthly
  purchase activating Plus, Profile/paywall presenting Plus, active-membership
  restart recovery from CustomerInfo, and the tested active-user Restore path
  returning Plus active. Public local builds start disabled unless the developer
  supplies their own public SDK key and matching external Firebase client.
- **Pro:** Coming Soon; no purchase path.
- **Boost:** rewarded access is limited to the QA/debug candidate. Release rewarded
  ads remain disabled.
- **Remote AI:** production remains OFF and unlaunched.

These statements do not claim production Google Play billing, cross-install or
store-history restore, production Firebase, production signing, or production
Remote AI validation.
