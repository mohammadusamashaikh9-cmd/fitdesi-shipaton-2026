import assert from "node:assert/strict";
import { test } from "node:test";
import { RemoteAiConsentRequiredError } from "../src/errors.js";
import {
  createRemoteCapabilityPolicy,
  RemoteCapability
} from "../src/remote_admission/capability_policy.js";

async function authorityModule() {
  return import("../src/remote_admission/admission_authority.js");
}

async function captureError(operation) {
  try {
    await operation();
  } catch (error) {
    return error;
  }
  assert.fail("Expected operation to fail closed.");
}

function recordingAuthorities({ commercialState, consentError = null }) {
  const calls = [];
  return {
    calls,
    commercialAuthority: {
      async getCommercialState(input) {
        calls.push(["commercial", input]);
        return commercialState;
      }
    },
    consentAuthority: {
      async authorizeStandardRemoteProcessing(input) {
        calls.push(["consent", input]);
        if (consentError) throw consentError;
      }
    }
  };
}

test("admission consumes Stage 12C then Stage 12D interfaces with only Firebase UID", async () => {
  const { RemoteAdmissionAuthority } = await authorityModule();
  const collaborators = recordingAuthorities({
    commercialState: { tier: "PLUS", boostActive: false }
  });
  const authority = new RemoteAdmissionAuthority({
    ...collaborators,
    capabilityPolicy: createRemoteCapabilityPolicy({
      launchedCapabilities: [RemoteCapability.REMOTE_AI_COACH]
    })
  });
  const firebaseUid = "trusted-private-uid";

  const result = await authority.authorize({
    firebaseUid,
    capability: RemoteCapability.REMOTE_AI_COACH,
    tier: "PRO",
    boostActive: true,
    consentGranted: true,
    commercialState: { tier: "PRO", boostActive: true }
  });

  assert.deepEqual(collaborators.calls, [
    ["commercial", { firebaseUid }],
    ["consent", { firebaseUid }]
  ]);
  assert.deepEqual(result, {
    capability: RemoteCapability.REMOTE_AI_COACH,
    accessBasis: "TIER",
    commercialTier: "PLUS"
  });
  assert.equal(JSON.stringify(result).includes(firebaseUid), false);
  assert.equal("boostActive" in result, false);
  assert.equal("consent" in result, false);
});

test("positive commercial and consent authority are never cached", async () => {
  const { RemoteAdmissionAuthority } = await authorityModule();
  const collaborators = recordingAuthorities({
    commercialState: { tier: "PLUS", boostActive: false }
  });
  const authority = new RemoteAdmissionAuthority({
    ...collaborators,
    capabilityPolicy: createRemoteCapabilityPolicy({
      launchedCapabilities: [RemoteCapability.REMOTE_AI_COACH]
    })
  });

  await authority.authorize({
    firebaseUid: "private-uid",
    capability: RemoteCapability.REMOTE_AI_COACH
  });
  await authority.authorize({
    firebaseUid: "private-uid",
    capability: RemoteCapability.REMOTE_AI_COACH
  });

  assert.deepEqual(collaborators.calls.map(([name]) => name), [
    "commercial",
    "consent",
    "commercial",
    "consent"
  ]);
});

test("BASIC plus Boost cannot obtain REMOTE_AI_COACH through admission", async () => {
  const { RemoteAdmissionAuthority } = await authorityModule();
  const collaborators = recordingAuthorities({
    commercialState: { tier: "BASIC", boostActive: true }
  });
  const authority = new RemoteAdmissionAuthority({
    ...collaborators,
    capabilityPolicy: createRemoteCapabilityPolicy({
      launchedCapabilities: [RemoteCapability.REMOTE_AI_COACH]
    })
  });

  const error = await captureError(() => authority.authorize({
    firebaseUid: "private-uid",
    capability: RemoteCapability.REMOTE_AI_COACH
  }));

  assert.equal(error.statusCode, 403);
  assert.equal(error.code, "REMOTE_CAPABILITY_UNAVAILABLE");
  assert.deepEqual(collaborators.calls.map(([name]) => name), ["commercial"]);
});

test("not-launched capability fails before consent and without provider work", async () => {
  const { RemoteAdmissionAuthority } = await authorityModule();
  const collaborators = recordingAuthorities({
    commercialState: { tier: "PRO", boostActive: false }
  });
  const authority = new RemoteAdmissionAuthority({
    ...collaborators,
    capabilityPolicy: createRemoteCapabilityPolicy()
  });

  const error = await captureError(() => authority.authorize({
    firebaseUid: "private-uid",
    capability: RemoteCapability.AI_PROGRESS_SUMMARIES
  }));

  assert.equal(error.statusCode, 403);
  assert.equal(error.code, "REMOTE_CAPABILITY_NOT_LAUNCHED");
  assert.deepEqual(collaborators.calls.map(([name]) => name), ["commercial"]);
});

test("existing Stage 12D consent denial crosses the composition seam unchanged", async () => {
  const { RemoteAdmissionAuthority } = await authorityModule();
  const consentError = new RemoteAiConsentRequiredError();
  const collaborators = recordingAuthorities({
    commercialState: { tier: "PLUS", boostActive: false },
    consentError
  });
  const authority = new RemoteAdmissionAuthority({
    ...collaborators,
    capabilityPolicy: createRemoteCapabilityPolicy({
      launchedCapabilities: [RemoteCapability.REMOTE_AI_COACH]
    })
  });

  const error = await captureError(() => authority.authorize({
    firebaseUid: "private-uid",
    capability: RemoteCapability.REMOTE_AI_COACH
  }));

  assert.equal(error, consentError);
  assert.deepEqual(collaborators.calls.map(([name]) => name), ["commercial", "consent"]);
});

test("invalid composition or invalid commercial state fails remote admission closed", async () => {
  const { RemoteAdmissionAuthority } = await authorityModule();
  const validPolicy = createRemoteCapabilityPolicy({
    launchedCapabilities: [RemoteCapability.REMOTE_AI_COACH]
  });

  const invalidDependencyError = await captureError(() => new RemoteAdmissionAuthority({
    commercialAuthority: {},
    consentAuthority: {},
    capabilityPolicy: validPolicy
  }));
  assert.equal(invalidDependencyError.code, "REMOTE_ADMISSION_UNAVAILABLE");

  const collaborators = recordingAuthorities({
    commercialState: { tier: "VIP", boostActive: true }
  });
  const authority = new RemoteAdmissionAuthority({
    ...collaborators,
    capabilityPolicy: validPolicy
  });
  const invalidStateError = await captureError(() => authority.authorize({
    firebaseUid: "private-uid",
    capability: RemoteCapability.REMOTE_AI_COACH
  }));
  assert.equal(invalidStateError.code, "REMOTE_ADMISSION_UNAVAILABLE");
  assert.deepEqual(collaborators.calls.map(([name]) => name), ["commercial"]);
});

test("missing authenticated Firebase identity fails before authority calls", async () => {
  const { RemoteAdmissionAuthority } = await authorityModule();
  const collaborators = recordingAuthorities({
    commercialState: { tier: "PLUS", boostActive: false }
  });
  const authority = new RemoteAdmissionAuthority({
    ...collaborators,
    capabilityPolicy: createRemoteCapabilityPolicy({
      launchedCapabilities: [RemoteCapability.REMOTE_AI_COACH]
    })
  });

  for (const firebaseUid of [undefined, null, ""]) {
    const error = await captureError(() => authority.authorize({
      firebaseUid,
      capability: RemoteCapability.REMOTE_AI_COACH
    }));
    assert.equal(error.code, "REMOTE_ADMISSION_UNAVAILABLE");
  }
  assert.deepEqual(collaborators.calls, []);
});
