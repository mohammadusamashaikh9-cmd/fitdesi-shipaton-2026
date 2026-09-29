# Changelog

This public changelog records product-facing checkpoints. Private Build Week evidence
and internal audit history are intentionally omitted from the proposed fresh-history
export.

## [Unreleased] - public export preparation

- Kept Android runtime code, resources, packaged data, application identity, version,
  signing, and the accepted APK unchanged.
- Made the 39-record public-food validator and its focused Node test independent of
  the omitted private 165-record engineering catalogue.
- Recorded the 39 verified USDA nutrition records, 20 nutrition-free South Asian
  discovery identities, current asset provenance, and exact public-export boundary.
- Clarified that Basic is the local/offline core; the retained Plus claims are
  limited to the specifically recorded RevenueCat Test Store purchase,
  entitlement-presentation, restart-recovery, and active-user Restore scenarios;
  Boost is QA/debug-only, Pro is Coming Soon, and production Remote AI is OFF.

## [0.2.0-alpha] - 2026-07-26

### Product-development alpha

- Transitioned active Android version metadata from the archived `0.1.9-buildweek` checkpoint to `0.2.0-alpha`.
- Preserved Build Week checkpoints, APK evidence, release records, and tags as historical evidence.
- Continued product development under the existing offline-first, safe-default, non-medical preview boundaries.

## [0.1.0-buildweek] - 2026-07-16

### Stable checkpoint

- Removed production fake analytics, profile defaults, workout history, streaks, and demo fallback data.
- Persisted real completed workout sessions locally and derived analytics from those saved records.
- Fixed root-tab and Android hardware-back behavior for nested Explore, Tools, and Workout screens.
- Upgraded the local Macro Calculator and One Rep Max Calculator.
- Added the local multi-step Build Routine flow.
- Added safe offline AI Coach v1 with structured responses and medical-safety guardrails.
- Added traceable Android and GitHub Actions APK version metadata.

### Safety defaults

- Build Week safe mode remains enabled.
- OpenAI, Gemini, Firebase, Firestore, RapidAPI, backend providers, and remote AI calls remain disabled.
- No API keys or signing credentials are included in the repository.
