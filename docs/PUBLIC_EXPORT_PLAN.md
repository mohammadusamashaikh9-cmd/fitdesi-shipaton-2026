# FitDesi Shipaton 2026 public export manifest

Status: **PLANNED — MANAGER REVIEW REQUIRED**. No export, public repository,
commit, push, or publication is performed by this preparation.

The eventual public repository must be created with fresh Git history from the
manager-approved tree. The private engineering repository, its `.git`
directory, history, refs, and visibility must remain private.

## Reconciliation

- Previous audit input: 586 tracked files before asset sanitization.
- Accepted sanitized commit:
  `ced40a37bab5b980765a4ff03a42960f7d318038`.
- Accepted sanitized tree: 585 tracked files.
- Exact reduction: the unreferenced fitness banner
  `mobile/app/src/main/res/drawable/img_fitness_banner_1782242408821.jpg`
  was removed because redistribution evidence was unresolved.
- This preparation adds `docs/ASSET_PROVENANCE.md`; the proposed public
  allowlist therefore resolves to 430 files: 429 accepted-tree files plus that
  new document.
- The accepted tree has 156 tracked exclusions under the rules below.

## Exact allowlist

The export is default-deny. A path is included only when it is a regular tracked
file at the accepted commit (or the explicitly named new provenance document),
matches one of these rules, and survives the exclusions below. All paths use
repository-relative forward slashes.

```text
.gitattributes
.gitignore
CHANGELOG.md
CONTRIBUTING.md
LICENSE
README.md
SECURITY.md
THIRD_PARTY_NOTICES.md
backend/**
mobile/**
docs/ASSET_PROVENANCE.md
docs/DATA_SOURCES.md
docs/PUBLIC_EXPORT_PLAN.md
docs/architecture.md
tools/knowledge/README.md
tools/knowledge/generated/public-food-projection.contract.json
tools/knowledge/test/public-food-projection.test.mjs
tools/knowledge/validate/validate-public-food-projection.mjs
```

`backend/**` and `mobile/**` mean regular tracked source files only. They do
not override the exclusions below and do not import ignored or untracked files.
The only exact exceptions to the `.env`-family exclusions are the reviewed,
tracked documentation-only examples `backend/.env.example` and
`mobile/.env.example`; neither contains a credential value. No directory or
wildcard `.env` exception exists.

## Exact tracked exclusions from the accepted 585-file tree

```text
.agents/**
.github/**
AGENTS.md
codemagic.yaml
mobile/app/screenshots/greeting.png
docs/** except:
  docs/ASSET_PROVENANCE.md
  docs/DATA_SOURCES.md
  docs/PUBLIC_EXPORT_PLAN.md
  docs/architecture.md
tools/** except:
  tools/knowledge/README.md
  tools/knowledge/generated/public-food-projection.contract.json
  tools/knowledge/test/public-food-projection.test.mjs
  tools/knowledge/validate/validate-public-food-projection.mjs
```

This excludes all 17 tracked `.agents/**` files, the one private GitHub QA
workflow, the root internal agent instructions, private Codemagic
configuration, the malformed and unreferenced
`mobile/app/screenshots/greeting.png`, 69 of the accepted tree's 72
documentation files, and 66 of its 70 tooling files: 156 accepted-tree files
total. The screenshot remains in the private source tree; this rule excludes it
from the proposed public export without deleting or replacing it.

The excluded documentation set includes all internal project context,
`docs/archive/openai-build-week-2026/**`, chronological build logs, audit
reports/JSON, internal release evidence, UI migration reports, orchestration
policy, and private plans. The excluded tooling set includes private/raw/legacy
food datasets, public-projection generators that depend on the private
catalogue, food import/promotion/audit tools, legacy-food tests, internal
orchestration tools, and non-public evidence.

## Always-excluded filesystem content

These items are excluded even if present locally. Exclusion wins over a broad
allowlist match; the two exact reviewed `.env.example` exceptions named above
are resolved explicitly, and every other unmatched or ambiguous path is denied:

```text
.git/**
**/build/**
.gradle/**
**/.gradle/**
node_modules/**
output/**
outputs/**
captures/**
.idea/**
.vscode/**
.mimosa/**
backend/.evaluation/**
local.properties
**/local.properties
google-services.json
**/google-services.json
.env
.env.*
*.env
*.env.*
secrets.properties
apikeys.properties
signing.properties
keystore.properties
*.jks
*.keystore
*.p12
*.pfx
*.pem
*.key
*.apk
*.aab
*.log
*.tmp
```

The allowlist includes reviewed example configuration files already tracked in
source (`backend/.env.example`, `mobile/.env.example`, and
`mobile/local.properties.example`) because they contain documentation,
names, and default-safe or empty values rather than private values. Real `.env`
files remain denied. The export operation must still scan the exact staged
public tree before creating fresh history.

## Public-food boundary

The public validator and test use only the four allowlisted knowledge-tool
files plus the two public Android food assets. The private 165-record catalogue
and all private-dependent generators/tests remain excluded. Public validation
proves the exported snapshot, counts, eligibility fields, bounds, source-class
distribution, unresolved-private-data absence, and existing checksums; it does
not prove private derivation.

## Licensing gate

The proposed public source repository actually redistributes the Gradle wrapper
JAR, the four Sora font files, the sanitized non-media exercise data, the USDA
public nutrition data, FitDesi-authored content, and their accompanying notice
files. The wrapper JAR contains the complete Apache License 2.0 text; the Sora
OFL 1.1, exercise-dataset MIT notice, and USDA public-domain/CC0 notice are also
included. The focused source audit found no unresolved technical notice omission
for those actually redistributed source files, subject to manager/legal review.

The backend source redistributes `package.json` and `package-lock.json`, not
installed `node_modules`. The lockfile has 191 resolved non-root package
entries. Its sole missing `license` field is `limiter@1.1.5`; the installed
package metadata declares MIT through its older `licenses` field and its
`LICENSE.txt` contains the MIT copyright and permission notice. This is missing
lockfile metadata, not evidence of missing licence rights. Likewise, Gradle
dependency coordinates reference components but do not redistribute their
AAR/JAR binaries in the public source repository.

Public distribution of the privately signed Shipaton APK is a separate gate.
Its exact binary notice inventory has not passed a complete review. Before that
APK is distributed publicly, complete authoritative notice collection for its
compiled redistributed components, preserve applicable Apache NOTICE material,
review the Google SDK/Play terms, and obtain manager/legal approval for the
notice bundle and delivery method. Missing cached POM metadata is not treated as
proof of missing rights, and this preparation does not establish that the APK
itself must change. See `THIRD_PARTY_NOTICES.md` for the technical
reconciliation.

## Dry-run manifest result

The corrected rules resolve 430 proposed public files: eight root files, 88
tracked backend files, 326 tracked mobile files, four documentation files
(including the new `docs/ASSET_PROVENANCE.md`), and four focused knowledge-tool
files. The accepted 585-file tree contributes 429 included files and 156 denied
files; the new provenance document is the 430th include. This is a path-only
dry run, not an export.

For a reproducible manifest, paths are repository-relative with forward
slashes, sorted by case-sensitive ordinal comparison, encoded as UTF-8 without
a byte-order mark, and serialized with one LF byte (`0A`) after every path,
including exactly one final LF after the last path. That 430-path, 25,378-byte
manifest has SHA-256
`b93836352e10bfa4bc5c1e44c488e1a07db805d4665ceb6b949b14331b29208c`.
The dry run found no included private/archive paths, credential or signing
paths, populated credential shapes, symlinks, missing files, real `.env` files,
APK/AAB files, build output, malformed screenshot, or excluded food datasets.

## Export procedure (not executed)

1. Manager approves this diff and the exact allowlist.
2. Build the export into a new empty directory from the approved commit and
   allowlisted working-tree documents only.
3. Verify the path count and confirm every output path matches the allowlist.
4. Run secret, licence, documentation-link, and public-food validation against
   that exact output.
5. Initialize fresh history only after those checks pass.
6. Obtain explicit authorization before any remote creation or publication.
