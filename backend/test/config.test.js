import assert from "node:assert/strict";
import test from "node:test";
import { loadConfig } from "../src/config.js";

const evaluationVariables = ["OPENROUTER_EVALUATION_TIMEOUT_MS", "OPENROUTER_EVALUATION_MAX_OUTPUT_TOKENS",
  "FIREWORKS_EVALUATION_TIMEOUT_MS", "FIREWORKS_EVALUATION_MAX_OUTPUT_TOKENS"];
function withEvaluationEnvironment(values, check) {
  const previous = evaluationVariables.map((name) => process.env[name]);
  try {
    for (const name of evaluationVariables) {
      if (values[name] === undefined) delete process.env[name];
      else process.env[name] = values[name];
    }
    check();
  } finally {
    evaluationVariables.forEach((name, index) => {
      if (previous[index] === undefined) delete process.env[name];
      else process.env[name] = previous[index];
    });
  }
}

test("evaluation runtime defaults are independent of unchanged Fireworks and safe provider defaults", () => {
  withEvaluationEnvironment({}, () => {
    const config = loadConfig();
    assert.equal(config.openrouterEvaluationTimeoutMs, 30000);
    assert.equal(config.openrouterEvaluationMaxOutputTokens, 4096);
    assert.equal(config.fireworksEvaluationTimeoutMs, 30000);
    assert.equal(config.fireworksEvaluationMaxOutputTokens, 4096);
    assert.equal(config.fireworksTimeoutMs, 8000);
    assert.equal(config.fireworksMaxOutputTokens, 1800);
    assert.equal(config.provider, "mock");
    assert.equal(config.remoteAiEnabled, false);
    const changed = loadConfig({ fireworksTimeoutMs: 5000, fireworksMaxOutputTokens: 512 });
    assert.equal(changed.openrouterEvaluationTimeoutMs, 30000);
    assert.equal(changed.openrouterEvaluationMaxOutputTokens, 4096);
    assert.equal(changed.fireworksEvaluationTimeoutMs, 30000);
    assert.equal(changed.fireworksEvaluationMaxOutputTokens, 4096);
  });
});

test("evaluation environment accepts only bounded integers including both range endpoints", () => {
  for (const [timeout, tokens] of [["1000", "256"], ["120000", "4096"], ["45000", "3072"]]) {
    withEvaluationEnvironment({ OPENROUTER_EVALUATION_TIMEOUT_MS: timeout,
      OPENROUTER_EVALUATION_MAX_OUTPUT_TOKENS: tokens, FIREWORKS_EVALUATION_TIMEOUT_MS: timeout,
      FIREWORKS_EVALUATION_MAX_OUTPUT_TOKENS: tokens }, () => {
      const config = loadConfig();
      assert.equal(config.openrouterEvaluationTimeoutMs, Number(timeout));
      assert.equal(config.openrouterEvaluationMaxOutputTokens, Number(tokens));
      assert.equal(config.fireworksEvaluationTimeoutMs, Number(timeout));
      assert.equal(config.fireworksEvaluationMaxOutputTokens, Number(tokens));
      assert.equal(config.fireworksTimeoutMs, 8000);
      assert.equal(config.fireworksMaxOutputTokens, 1800);
    });
  }
});

test("invalid evaluation environment values fail closed without parseInt truncation", () => {
  for (const name of evaluationVariables) {
    const outOfRange = name.endsWith("_TIMEOUT_MS") ? ["999", "120001"] : ["255", "4097"];
    for (const value of [...outOfRange, "0", "-1", "1.5", "3000junk", "1e3", "Infinity", "NaN", " 3000 "]) {
      withEvaluationEnvironment({ [name]: value }, () => assert.throws(() => loadConfig(), new RegExp(name)));
    }
  }
});

test("evaluation server overrides remain bounded and cannot change Fireworks settings", () => {
  withEvaluationEnvironment({}, () => {
    const config = loadConfig({ openrouterEvaluationTimeoutMs: 45000, openrouterEvaluationMaxOutputTokens: 3072 });
    assert.equal(config.fireworksTimeoutMs, 8000);
    assert.equal(config.fireworksMaxOutputTokens, 1800);
    for (const [field, invalid] of [
      ["openrouterEvaluationTimeoutMs", [999, 120001, 1000.5, "30000", null, NaN, Infinity]],
      ["openrouterEvaluationMaxOutputTokens", [255, 4097, 256.5, "4096", null, NaN, Infinity]],
      ["fireworksEvaluationTimeoutMs", [999, 120001, 1000.5, "30000", null, NaN, Infinity]],
      ["fireworksEvaluationMaxOutputTokens", [255, 4097, 256.5, "4096", null, NaN, Infinity]]
    ]) for (const value of invalid) assert.throws(() => loadConfig({ [field]: value }));
  });
});
