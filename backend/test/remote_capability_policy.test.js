import assert from "node:assert/strict";
import { test } from "node:test";

async function capabilityModule() {
  return import("../src/remote_admission/capability_policy.js");
}

test("BASIC cannot obtain REMOTE_AI_COACH, including with Boost", async () => {
  const {
    createRemoteCapabilityPolicy,
    RemoteCapability,
    RemoteCapabilityDecision
  } = await capabilityModule();
  const policy = createRemoteCapabilityPolicy({
    launchedCapabilities: [RemoteCapability.REMOTE_AI_COACH]
  });

  for (const boostActive of [false, true]) {
    assert.deepEqual(
      policy.evaluate({
        capability: RemoteCapability.REMOTE_AI_COACH,
        commercialState: { tier: "BASIC", boostActive }
      }),
      {
        eligible: false,
        capability: RemoteCapability.REMOTE_AI_COACH,
        decision: RemoteCapabilityDecision.TIER_REQUIRED,
        accessBasis: null
      }
    );
  }
});

test("PLUS and PRO are equally policy-eligible for REMOTE_AI_COACH", async () => {
  const {
    createRemoteCapabilityPolicy,
    RemoteCapability,
    RemoteCapabilityDecision,
    RemoteCapabilityAccessBasis
  } = await capabilityModule();
  const policy = createRemoteCapabilityPolicy({
    launchedCapabilities: [RemoteCapability.REMOTE_AI_COACH]
  });

  for (const tier of ["PLUS", "PRO"]) {
    assert.deepEqual(
      policy.evaluate({
        capability: RemoteCapability.REMOTE_AI_COACH,
        commercialState: { tier, boostActive: false }
      }),
      {
        eligible: true,
        capability: RemoteCapability.REMOTE_AI_COACH,
        decision: RemoteCapabilityDecision.ELIGIBLE,
        accessBasis: RemoteCapabilityAccessBasis.TIER
      }
    );
  }
});

test("Boost is limited to workout generation and advanced analytics", async () => {
  const {
    createRemoteCapabilityPolicy,
    RemoteCapability,
    RemoteCapabilityDecision,
    RemoteCapabilityAccessBasis
  } = await capabilityModule();
  const policy = createRemoteCapabilityPolicy({
    launchedCapabilities: Object.values(RemoteCapability)
  });
  const basicWithBoost = { tier: "BASIC", boostActive: true };

  for (const capability of [
    RemoteCapability.AI_WORKOUT_GENERATION,
    RemoteCapability.ADVANCED_ANALYTICS
  ]) {
    assert.deepEqual(policy.evaluate({ capability, commercialState: basicWithBoost }), {
      eligible: true,
      capability,
      decision: RemoteCapabilityDecision.ELIGIBLE,
      accessBasis: RemoteCapabilityAccessBasis.BOOST
    });
  }

  for (const capability of [
    RemoteCapability.REMOTE_AI_COACH,
    RemoteCapability.AI_PROGRESS_SUMMARIES
  ]) {
    assert.equal(
      policy.evaluate({ capability, commercialState: basicWithBoost }).eligible,
      false
    );
  }
});

test("unknown capabilities fail closed without echoing the unknown value", async () => {
  const {
    createRemoteCapabilityPolicy,
    RemoteCapabilityDecision
  } = await capabilityModule();
  const policy = createRemoteCapabilityPolicy({ launchedCapabilities: [] });

  assert.deepEqual(
    policy.evaluate({
      capability: "CLIENT_SELECTED_UNLIMITED_AI",
      commercialState: { tier: "PRO", boostActive: true }
    }),
    {
      eligible: false,
      capability: null,
      decision: RemoteCapabilityDecision.UNKNOWN_CAPABILITY,
      accessBasis: null
    }
  );
});

test("known but unlaunched capabilities fail closed before tier eligibility", async () => {
  const {
    createRemoteCapabilityPolicy,
    RemoteCapability,
    RemoteCapabilityDecision
  } = await capabilityModule();
  const policy = createRemoteCapabilityPolicy();

  assert.deepEqual(
    policy.evaluate({
      capability: RemoteCapability.REMOTE_AI_COACH,
      commercialState: { tier: "PLUS", boostActive: false }
    }),
    {
      eligible: false,
      capability: RemoteCapability.REMOTE_AI_COACH,
      decision: RemoteCapabilityDecision.NOT_LAUNCHED,
      accessBasis: null
    }
  );
});

test("invalid commercial state fails closed", async () => {
  const {
    createRemoteCapabilityPolicy,
    RemoteCapability,
    RemoteCapabilityDecision
  } = await capabilityModule();
  const policy = createRemoteCapabilityPolicy({
    launchedCapabilities: [RemoteCapability.REMOTE_AI_COACH]
  });

  for (const commercialState of [
    { tier: "VIP", boostActive: false },
    { tier: "PLUS", boostActive: "yes" },
    null
  ]) {
    assert.deepEqual(
      policy.evaluate({
        capability: RemoteCapability.REMOTE_AI_COACH,
        commercialState
      }),
      {
        eligible: false,
        capability: RemoteCapability.REMOTE_AI_COACH,
        decision: RemoteCapabilityDecision.COMMERCIAL_STATE_INVALID,
        accessBasis: null
      }
    );
  }
});
