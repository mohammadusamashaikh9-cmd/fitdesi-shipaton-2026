import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";

// Validates only the files proposed for the public export: the public-safe runtime
// nutrition projection and its checksum contract. It deliberately does not read or
// claim to reproduce the unavailable private engineering catalogue.

const projectionPath = "mobile/app/src/main/assets/pakistani_food_catalogue_public.json";
const contractPath = "tools/knowledge/generated/public-food-projection.contract.json";

const projectionBytes = await readFile(projectionPath);
const projection = JSON.parse(projectionBytes.toString("utf8"));
const contract = JSON.parse(await readFile(contractPath, "utf8"));

const ACCEPTED_BASES = new Set(["VERIFIED_PER_SERVING", "VERIFIED_PER_100G", "VERIFIED_RECIPE_YIELD"]);
const isEligible = (r) =>
  r.isLoggable === true &&
  r.licenceStatus === "VERIFIED_COMMERCIAL" &&
  r.fitDesiReviewStatus === "FITDESI_NUTRITION_VERIFIED" &&
  ACCEPTED_BASES.has(r.nutritionBasis) &&
  Number.isInteger(r.calories) && r.calories >= 1 && r.calories <= 3000 &&
  Number.isFinite(r.proteinGrams) && r.proteinGrams >= 0 && r.proteinGrams <= 300 &&
  Number.isFinite(r.carbsGrams) && r.carbsGrams >= 0 && r.carbsGrams <= 500 &&
  Number.isFinite(r.fatGrams) && r.fatGrams >= 0 && r.fatGrams <= 250;

assert.equal(projection.schemaVersion, 1);
assert.equal(projection.records.length, 39);
assert.equal(projection.recordCount, projection.records.length);
assert.equal(projection.reviewedLoggableCount, 39);
assert.equal(projection.reviewRequiredCount, 0);
assert.equal(new Set(projection.records.map((r) => r.id)).size, 39);
assert.ok(projection.records.every((r) => typeof r.id === "string" && r.id.length > 0));
assert.ok(projection.records.every((r) => typeof r.name === "string" && r.name.trim().length > 0));
assert.ok(projection.records.every((r) => r.servingSize === "100 g"));
assert.ok(projection.records.every((r) => r.nutritionBasis === "VERIFIED_PER_100G"));
assert.ok(projection.records.every((r) => Array.isArray(r.importedProvenance) && r.importedProvenance.length > 0));

// Every projected record satisfies the commercial eligibility gate.
assert.ok(projection.records.every(isEligible), "A projected record fails FoodCommercialReleaseEligibilityPolicy.");

// No unresolved source classes survive.
assert.equal(projection.records.filter((r) => r.runtimeSource === "IMPORTED_REVIEW_REQUIRED").length, 0);
assert.equal(projection.records.filter((r) => r.licenceStatus === "UNKNOWN").length, 0);
assert.equal(projection.records.filter((r) => r.fitDesiReviewStatus === "NEEDS_FITDESI_NUTRITION_REVIEW").length, 0);
assert.equal(projection.records.filter((r) => r.nutritionBasis === "EXISTING_FITDESI_SERVING_ESTIMATE").length, 0);
assert.ok(projection.records.every((r) => r.id.startsWith("fd-food-usda-")));
assert.equal(
  projection.records.flatMap((r) => r.importedProvenance).filter((p) =>
    /nourish/i.test(p.sourceRepository ?? "") || /nourish/i.test(p.sourceFile ?? "")).length,
  0
);

// Exact source-class distribution.
const sourceClassCounts = Object.fromEntries([
  "USDA_FOUNDATION_VERIFIED",
  "USDA_FNDDS_VERIFIED",
  "USDA_SR_LEGACY_VERIFIED",
  "USDA_BRANDED_LABEL_VERIFIED"
].map((rs) => [rs, projection.records.filter((r) => r.runtimeSource === rs).length]));
assert.deepEqual(sourceClassCounts, {
  USDA_FOUNDATION_VERIFIED: 15,
  USDA_FNDDS_VERIFIED: 11,
  USDA_SR_LEGACY_VERIFIED: 12,
  USDA_BRANDED_LABEL_VERIFIED: 1
});

// Contract checksums reproduce.
const projectionRecordsSha256 = createHash("sha256").update(JSON.stringify(projection.records)).digest("hex");
const projectionFileSha256 = createHash("sha256").update(projectionBytes).digest("hex");
assert.equal(contract.contractName, "PUBLIC_SAFE_FOOD_PROJECTION_V1");
assert.equal(projectionRecordsSha256, contract.projectionRecordsSha256);
assert.equal(projectionFileSha256, contract.projectionFileSha256);
assert.equal(contract.recordCount, 39);
assert.equal(contract.reviewedLoggableCount, 39);
assert.equal(contract.reviewRequiredCount, 0);
assert.deepEqual(contract.sourceClassCounts, sourceClassCounts);
// The historical engineering checksum must remain untouched and distinct.
assert.notEqual(contract.projectionRecordsSha256, "dfd9d6ceb629e3f504b79f74ff1db63213f9810c560371b9677208106a56f5a4");

console.log(JSON.stringify({
  contract: contract.contractName,
  records: 39,
  sourceClassCounts,
  projectionRecordsSha256,
  projectionFileSha256,
  privateDerivationValidated: false
}, null, 2));
