# Third-Party Notices

## Sora typeface

- Upstream repository: <https://github.com/sora-xor/sora-font>
- Pinned source commit: `7f9a9c5d0ccd1c099cfac420aa27133df1c5fdc4`
- Google Fonts metadata: <https://github.com/google/fonts/tree/main/ofl/sora>
- License: SIL Open Font License 1.1
- Packaged static fonts: `Sora-Regular.ttf` (400), `Sora-Medium.ttf` (500),
  `Sora-SemiBold.ttf` (600), and `Sora-Bold.ttf` (700)

FitDesi bundles the unmodified static TTF files from the upstream
`fonts/ttf/v2.1beta/` directory at the pinned commit. The complete upstream
license is packaged at
`mobile/app/src/main/assets/licenses/sora-ofl-1.1.txt`.

Packaged font SHA-256 values:

- `sora_regular.ttf`: `a482dbc628113bfb4fa7af2fdd330f1f58ae968841e8683759971bad23f52f8e`
- `sora_medium.ttf`: `ec0901b96c516646e98d3a0e1d6f934b9700951a92f3757d1f606ce414576f68`
- `sora_semibold.ttf`: `d040b9f9dd116e181aef5f77c7fbee5415232dabb295e61a0c719fb2d268a01b`
- `sora_bold.ttf`: `2b96f271cffb5db2d16d7198ff3513b1f23699fb867a7e8e1fa25169bde92558`

## hasaneyldrm/exercises-dataset

- Repository: https://github.com/hasaneyldrm/exercises-dataset
- Pinned commit: `7455efae41b330c265e7cd4b78dfa848e7ce5ebd`
- Source file: `data/exercises.json`

FitDesi imports specifically approved non-media exercise record data and
instruction text fields in English, Italian, and Turkish. The accepted
sanitized 522-record legacy asset and the active 534-record canonical knowledge
pack contain no upstream image, GIF, video, media-ID, or media-path fields. No
upstream exercise media binary is tracked or redistributed by FitDesi.

### MIT License

Copyright (c) 2026 Hasan Emir Yıldırım

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation and data files (the "Software"),
to deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

### Upstream media exception

The MIT license above covers only the code, tooling, dataset structure, and
instruction text in the upstream repository. It does not cover exercise media
in the upstream `images/` and `videos/` directories. Cloning the upstream
repository does not grant a downstream license to that media. FitDesi does not
import or redistribute it.

## Historical Gym visual exercise media finding — not current content

Private historical audits found image, GIF, and media-ID path metadata
associated with Gym visual in the pre-sanitized legacy exercise JSON. Upstream
states that images, thumbnails, GIFs, animations, media IDs, media files, and
media-derived files are governed by separate terms. Cloning the exercise
repository does not grant FitDesi a media licence.

FitDesi has no separate Gym visual licence on record. Those historical paths
were removed from the accepted sanitized 522-record asset, were never media
binaries, and are not current packaged content. FitDesi does not claim
ownership of or redistribution rights for external exercise images or GIFs.

Gym visual attribution recorded by the upstream source: `© Gym visual — https://gymvisual.com/`

## Existing Pakistani food catalogue

The pre-existing Android project included 74 Pakistani food records. Repository evidence does not identify a complete original nutrition source or licence for every value. Build Week work preserved their stable IDs and reviewed app behavior while documenting the provenance limitation; FitDesi does not infer laboratory precision, household-serving evidence, or third-party ownership from their presence in the legacy app.

For the public-safe runtime these unresolved numeric records are **excluded**.
The historical numeric asset remains private audit/build evidence. It is not
packaged into the APK, read by runtime code, or included in the fresh-history
public export.

## Nourish Diet Planner food candidates

Source: <https://github.com/paradise-007/nourish-diet-planner>

The source repository declares the MIT licence. FitDesi's supplied 91-record curation states that underlying nutrition provenance is mixed and that the value basis remains unclear, though likely per 100 g. The repository licence is not treated as commercial verification of every nutrition value or underlying source.

The omitted 165-record private engineering catalogue preserves source IDs,
repository references, nutrition-basis warnings, confidence, and review status
for private tooling and historical evidence. It is **not** the public runtime
nutrition source. The public-safe runtime nutrition catalogue contains **zero**
unresolved Nourish numeric records, **zero** Nourish `importedProvenance`
blocks, and **zero** Nourish-derived nutrition values. No calorie, macro,
serving size, precision, or licence claim is invented.

Commercial release of any Nourish-derived value still requires further provenance and serving review.

## USDA FoodData Central verified nutrition (public-safe runtime catalogue)

Source: U.S. Department of Agriculture, Agricultural Research Service,
USDA FoodData Central. Releases used: Foundation Foods (April 2026), Survey/FNDDS
(2021-2023, October 2024), SR Legacy (April 2018 final), and Branded (April 2026).

- FoodData Central: <https://fdc.nal.usda.gov/>
- Dataset downloads: <https://fdc.nal.usda.gov/download-datasets/>
- Foundation Foods documentation: <https://fdc.nal.usda.gov/Foundation_Foods_Documentation/>
- Status: United States public-domain data, made available under CC0 1.0 Universal

USDA states that FoodData Central data are public domain and not copyrighted.
Permission is not required. Source citation is requested; it is not a licence
requirement.

The public-safe runtime nutrition catalogue (`mobile/app/src/main/assets/pakistani_food_catalogue_public.json`)
contains exactly **39** verified records — **15 Foundation / 11 FNDDS / 12 SR Legacy /
1 branded-label-derived** — derived deterministically as the records satisfying
`FoodCommercialReleaseEligibilityPolicy`. FitDesi retains the immutable FDC IDs and
exact per-100 g source nutrition in provenance and review metadata, maps exact USDA
nutrient identities into the existing Tracker fields, and rounds displayed calories to
the nearest whole kcal for the existing Android catalogue schema. The April 2026 raw
Foundation CSV dump is not tracked in the release branch; maintainers reacquire it from
the USDA download page and verify the hash-pinned source archives, selection evidence,
and per-record `sourceRecordSha256` recorded in provenance. FitDesi does not imply USDA
endorsement and does not describe its deterministic data/source review as dietitian,
clinical, medical, or laboratory certification.

## FitDesi South Asian discovery foods

The 20 South Asian discovery records (`mobile/app/src/main/assets/south_asian_food_discovery.json`,
IDs prefixed `fd-discovery-`) are original FitDesi-authored cultural food identity
metadata covered by this repository's `LICENSE`. They contain no third-party content and
no nutrition values, copy no external provenance, and are not derived from the legacy or
Nourish datasets. They exist for product discovery and cultural coverage only and always
display “Nutrition verification in progress.”

## FitDesi-owned content and asset assertions

The structured FitDesi-authored guidance in the packaged knowledge assets is
covered by this repository's `LICENSE`. It provides general fitness and
nutrition education only and does not diagnose, treat, or guarantee outcomes.

The project owner reports that the retained FitDesi logo was generated with
ChatGPT. That report is recorded without asserting unverified third-party
ownership, exclusivity, trademark clearance, or generation terms. The removed
fitness banner and historical exercise-media paths are not current packaged
content. See `docs/ASSET_PROVENANCE.md`.

## Software dependency notice coverage

This section is a technical inventory, not a legal opinion. It distinguishes a
missing metadata field or notice copy from missing licence rights.

### Public source and backend lockfile

The public export vendors the Gradle wrapper, whose JAR contains its own complete
Apache License 2.0 text. Package-manager caches and `node_modules/` are excluded;
the backend source redistributes dependency declarations and its lockfile, not
installed dependency source trees.

The backend lockfile has 191 resolved non-root package entries: 108 MIT, 47
Apache-2.0, 17 ISC, 11 BSD-3-Clause, five BlueOak-1.0.0, one 0BSD, one
BSD-2-Clause, and one entry without a `license` field. The sole missing field is
`limiter@1.1.5`. The installed package's `package.json` declares MIT and its
bundled `LICENSE.txt` records `Copyright (C) 2011 by John Hurliman` followed by
the MIT permission and warranty terms. Therefore the lockfile omission is
missing metadata, not evidence of missing licence rights. If installed backend
dependencies are redistributed later, their applicable copyright and licence
notices must accompany that distribution.

### Separate Android runtime and APK review

On 2026-09-29, Gradle successfully resolved the current
`debugRuntimeClasspath` to 213 unique coordinates. That number includes BOMs,
metadata variants, and transitive coordinates and is not a count of distinct APK
binaries. Of those coordinates, 47 had locally retained POMs: 21 identify the
Android SDK License, 20 identify Apache-2.0, three identify MIT, one identifies
Play Core SDK Terms, one identifies Play Integrity API Terms, and Guava's cached
POM omits licence metadata. The other 166 cached artifacts had no retained POM;
that cache state is missing metadata, not evidence of missing licence rights.

The resolved graph, retained POMs, and embedded archive notices identify these
relevant licence/terms families. These are requirements for distributing the
compiled Android artifact, not a source-publication gate created merely by
Gradle dependency declarations:

- Apache License 2.0: AndroidX/Jetpack and Compose components, Kotlin/kotlinx,
  Square's Retrofit/Moshi/OkHttp/Okio family, Gson, Material Components, and
  multiple Firebase/Tink/Guava support components. Redistribution requires an
  Apache-2.0 licence copy and preservation of applicable upstream NOTICE text.
- MIT: RevenueCat Purchases and Purchases AdMob `10.19.0`, plus Checker
  Framework annotations in the resolved graph. Redistribution requires the
  applicable copyright and permission notices.
- Google SDK/service terms rather than an open-source notice: Android Billing,
  Google Play services including Mobile Ads, User Messaging Platform,
  reCAPTCHA, Play Core, and Play Integrity components. Distribution must comply
  with the applicable SDK/service terms; an SPDX-style open-source label is not
  inferred.
- Mozilla Public License 2.0: OkHttp's compiled Public Suffix List data. The
  inspected APK retains OkHttp's embedded notice identifying that source and
  licence.

#### RevenueCat Purchases Android 10.19.0

The resolved `purchases` and `purchases-admob` POMs declare MIT. The official
`10.19.0` source notice is at
<https://github.com/RevenueCat/purchases-android/blob/10.19.0/LICENSE> and its
retrieved UTF-8 content has SHA-256
`9faf71b90e66087e350718c8b2f9390589603eea7a32bb8dfe6ba19a6a9b8fb1`.

MIT License

Copyright (c) 2018 RevenueCat, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

#### Checker Framework qualifiers 3.12.0

The resolved JAR contains this MIT notice:

Checker Framework qualifiers

Copyright 2004-present by the Checker Framework developers

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

The inspected local debug APK from the accepted sanitized source contains full
Apache-2.0 texts under the AndroidX annotation and Room metadata, OkHttp's Public
Suffix List notice, and the packaged Sora/exercise/USDA files listed above. It
does not contain the RevenueCat MIT copyright/permission notice, and the
inspected RevenueCat AARs do not contain one even though their POMs declare MIT.
This document now supplies that authoritative upstream notice for an
accompanying public distribution. The exact privately signed Shipaton APK was
not available for this focused audit, so its notice inventory is not claimed.

For the source-only repository, the actually vendored Gradle wrapper, Sora
fonts, exercise data, USDA data, and accompanying notice files have the licence
and notice coverage identified above. The backend lockfile and Gradle build
files reference dependencies; they do not redistribute installed dependency
trees or compiled AAR/JAR contents. Missing cached POM or lockfile metadata is
not proof that licence rights are absent.

Public distribution of the privately signed APK remains separately blocked.
Before that artifact is distributed, complete authoritative notice collection
for its compiled redistributed components, preserve applicable Apache NOTICE
material, review the Google SDK/Play terms, and obtain manager/legal approval
for the notice bundle and delivery method. The present evidence does not
establish that the APK binary itself must change because notices may be supplied
with the artifact. The accepted signed APK was not rebuilt and retains the
earlier packaged exercise-notice wording; the corrected source notice therefore
differs from the notice inside that APK. Its complete binary-notice review
remains unverified. No dependency was upgraded and no APK was changed by this
source correction.
