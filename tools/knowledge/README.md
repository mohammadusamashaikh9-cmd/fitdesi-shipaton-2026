# Public knowledge validation

The proposed public export intentionally includes only the self-contained
public-food validation slice from the private knowledge-tooling tree.

## Included public-food files

- `mobile/app/src/main/assets/pakistani_food_catalogue_public.json` — 39
  verified USDA nutrition records.
- `mobile/app/src/main/assets/south_asian_food_discovery.json` — 20
  FitDesi-authored identities with no nutrition values.
- `tools/knowledge/generated/public-food-projection.contract.json` — the
  existing public projection record and whole-file SHA-256 values.
- `tools/knowledge/validate/validate-public-food-projection.mjs` — a
  dependency-free validator that reads only public files.
- `tools/knowledge/test/public-food-projection.test.mjs` — a focused test that
  copies only the public inputs and validator to a temporary directory before
  execution, proving that no private catalogue is required.

## Commands

Run from the repository root with Node.js 22:

```powershell
node tools/knowledge/validate/validate-public-food-projection.mjs
node --test tools/knowledge/test/public-food-projection.test.mjs
```

The validator checks the existing checksum contract, 39-record count, unique
opaque IDs, eligibility fields, finite calorie/macro bounds, exact source-class
distribution, and the absence of unresolved legacy/Nourish nutrition. The test
also checks the separate 20-record nutrition-free discovery asset.

## Validation boundary

Public-only validation proves that the exported public assets match their
existing contract. It does not reproduce or prove derivation from the omitted
private 165-record engineering catalogue. Private-dependent generators,
historical food import/audit tools, raw/legacy food data, and their tests are
excluded from the proposed export.
