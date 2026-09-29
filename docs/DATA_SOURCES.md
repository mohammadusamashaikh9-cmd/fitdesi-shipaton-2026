# FitDesi AI public data sources

This inventory describes content proposed for the fresh-history public export.
Public availability alone is not treated as licence or redistribution evidence.

## Current packaged content

| Dataset or field group | Public path | Evidence/classification | Current use |
|---|---|---|---|
| Legacy exercise catalogue, 522 records | `mobile/app/src/main/assets/exercises/exercises.json` | Non-media data and matched instruction text from `hasaneyldrm/exercises-dataset`, pinned commit `7455efae41b330c265e7cd4b78dfa848e7ce5ebd`; MIT notice retained | Compatibility catalogue. All legacy image/GIF/media path properties were removed in the accepted sanitized commit. |
| Active canonical exercise pack, 534 records | `mobile/app/src/main/assets/knowledge/v1/exercises.json` | Reviewed FitDesi-authored records plus verified non-media upstream data; stable opaque IDs preserved | Authoritative current knowledge-pack exercise catalogue. Unchanged by public-export preparation. |
| Public-safe nutrition, 39 records | `mobile/app/src/main/assets/pakistani_food_catalogue_public.json` | USDA FoodData Central; public domain / CC0-1.0. Exact FDC IDs and record-level provenance remain in the asset | Only nutrition catalogue loaded for search, logging, deterministic diet calculations, and backend grounding. All records are loggable, `VERIFIED_COMMERCIAL`, `FITDESI_NUTRITION_VERIFIED`, and `VERIFIED_PER_100G`. |
| South Asian discovery, 20 records | `mobile/app/src/main/assets/south_asian_food_discovery.json` | FitDesi-authored identity metadata; no external nutrition source | Searchable identities only. No calories, macros, serving nutrition, imported provenance, or loggable field; excluded from calculations and AI grounding. |
| FitDesi logo | Existing Android logo/launcher resources | Project-owner-reported ChatGPT-generated artwork; no broader third-party ownership claim | Retained unchanged. See `docs/ASSET_PROVENANCE.md`. |
| Sora static fonts | `mobile/app/src/main/res/font/` | SIL Open Font License 1.1; complete licence packaged in Android assets | App typography. |

The 39 verified nutrition records have the exact source-class distribution
**15 Foundation / 11 FNDDS / 12 SR Legacy / 1 branded-label-derived**.
Together, the two food layers surface 59 identities, but only 39 contain
nutrition.

## Historical private findings

Private engineering audits retained 74 legacy estimates, 52 review-required
Nourish-derived references, and the same 39 USDA records in a 165-record
catalogue. That catalogue, its raw inputs, generators, and legacy-food tests are
not part of the proposed public export or Android runtime. Their absence means
the public-only validator does not prove the unavailable private derivation.
No unresolved private numeric nutrition is redistributed as verified.

Historical exercise audits found 521 image paths and 521 GIF paths in the
pre-sanitized 522-record legacy catalogue. Those paths are findings about
private history, not current packaged content. The accepted sanitized legacy
asset contains no media properties or paths; the active 534-record canonical
pack remains unchanged.

## Public-food validation

The existing contract remains
`tools/knowledge/generated/public-food-projection.contract.json`:

- records SHA-256:
  `55d39db92859f1cefbaeec79ab52749d38ec957dde594148d205b258ee1859b1`
- whole-file SHA-256:
  `1a2c0d6d3ea007edec1526c67fa06ed6b9be4ecb5a6784c84d58ae99bca6fb5c`

Run the dependency-free validator and focused isolated test from the repository
root:

```powershell
node tools/knowledge/validate/validate-public-food-projection.mjs
node --test tools/knowledge/test/public-food-projection.test.mjs
```

## USDA citation

U.S. Department of Agriculture, Agricultural Research Service, FoodData
Central. Releases represented in the public catalogue: Foundation Foods
(April 2026), Survey/FNDDS 2021–2023 (October 2024), SR Legacy (April 2018),
and Branded (April 2026). See <https://fdc.nal.usda.gov/> and the packaged
`mobile/app/src/main/assets/licenses/usda-fooddata-central.txt`.

FitDesi does not imply USDA endorsement or dietitian, clinical, medical, or
laboratory certification.
