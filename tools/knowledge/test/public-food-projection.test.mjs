import assert from "node:assert/strict";
import test from "node:test";
import { execFileSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

test("public-safe nutrition projection validator passes from public files only", async (t) => {
  const publicRoot = await mkdtemp(join(tmpdir(), "fitdesi-public-food-"));
  t.after(() => rm(publicRoot, { recursive: true, force: true }));

  const publicFiles = [
    "mobile/app/src/main/assets/pakistani_food_catalogue_public.json",
    "tools/knowledge/generated/public-food-projection.contract.json",
    "tools/knowledge/validate/validate-public-food-projection.mjs"
  ];
  for (const relativePath of publicFiles) {
    const destination = join(publicRoot, relativePath);
    await mkdir(dirname(destination), { recursive: true });
    await copyFile(relativePath, destination);
  }

  const output = execFileSync(
    process.execPath,
    ["tools/knowledge/validate/validate-public-food-projection.mjs"],
    { cwd: publicRoot, encoding: "utf8" }
  );
  const summary = JSON.parse(output);
  assert.equal(summary.records, 39);
  assert.equal(summary.projectionRecordsSha256, "55d39db92859f1cefbaeec79ab52749d38ec957dde594148d205b258ee1859b1");
  assert.equal(summary.projectionFileSha256, "1a2c0d6d3ea007edec1526c67fa06ed6b9be4ecb5a6784c84d58ae99bca6fb5c");
  assert.equal(summary.privateDerivationValidated, false);
  assert.deepEqual(summary.sourceClassCounts, {
    USDA_FOUNDATION_VERIFIED: 15,
    USDA_FNDDS_VERIFIED: 11,
    USDA_SR_LEGACY_VERIFIED: 12,
    USDA_BRANDED_LABEL_VERIFIED: 1
  });
});

test("South Asian discovery dataset is 20 nutrition-free FitDesi identities", async () => {
  const raw = await readFile("mobile/app/src/main/assets/south_asian_food_discovery.json", "utf8");
  const catalogue = JSON.parse(raw);

  assert.equal(catalogue.records.length, 20);
  assert.equal(catalogue.recordCount, 20);

  const ids = catalogue.records.map((r) => r.id);
  assert.equal(new Set(ids).size, 20);
  assert.ok(ids.every((id) => id.startsWith("fd-discovery-")));
  assert.ok(ids.every((id) => Number.isNaN(Number(id))));

  const names = catalogue.records.map((r) => r.name);
  assert.equal(new Set(names).size, 20);
  assert.ok(names.every((name) => name.trim().length > 0));
  assert.ok(catalogue.records.every((r) => r.nutritionStatus === "NUTRITION_VERIFICATION_IN_PROGRESS"));

  // Absence of nutrition is asserted on the raw bytes so no key can hide.
  const forbidden = [
    "calories", "caloriesPerServing", "protein", "proteinGrams",
    "carbs", "carbsGrams", "fat", "fatGrams", "nutritionBasis",
    "importedProvenance", "servingSize", "isLoggable"
  ];
  for (const key of forbidden) {
    assert.ok(!raw.includes(`"${key}"`), `Discovery asset must not contain "${key}"`);
  }
  for (const record of catalogue.records) {
    assert.deepEqual(Object.keys(record).sort(), ["category", "cuisine", "id", "name", "nutritionStatus"]);
  }
});
