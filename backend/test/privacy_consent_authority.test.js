import assert from "node:assert/strict";
import { test } from "node:test";
import { Timestamp } from "firebase-admin/firestore";
import { loadConfig } from "../src/config.js";
import {
  ConsentAuthorityConfigurationError,
  ConsentAuthorityInvalidRecordError,
  ConsentAuthorityTimeoutError,
  ConsentAuthorityUnavailableError,
  ConsentNoticeVersionMismatchError,
  ExperimentalAiConsentRequiredError,
  RemoteAiConsentRequiredError,
  ValidationError
} from "../src/errors.js";
import {
  createFirebaseAdminAppProvider,
  isSupportedFirebaseProjectId
} from "../src/firebase/firebase_admin_app.js";
import {
  CONSENT_SCHEMA_VERSION,
  consentPolicyFromConfig,
  PrivacyConsentAuthority,
  validateStoredConsentRecord
} from "../src/privacy/privacy_consent_authority.js";
import { FirestoreConsentStore } from "../src/privacy/firestore_consent_store.js";

const STANDARD_DECIDED_AT = "2026-09-14T10:00:00.000Z";
const EXPERIMENTAL_DECIDED_AT = "2026-09-14T10:01:00.000Z";
const CONSENT_UPDATED_AT = "2026-09-14T10:02:00.000Z";
const TEST_CONSENT_POLICY = Object.freeze({
  standardRemoteAi: "standard-v1",
  experimentalTraining: "experimental-v1"
});
const SENSITIVE_CONSENT_MARKERS = [
  "firebase-uid-marker",
  "remote_ai_consents_v1/firebase-uid-marker",
  "raw-store-marker",
  "fitdesi-ai-production",
  "token-marker",
  "email-marker",
  "claims-marker",
  "provider-marker"
];

function storedConsentRecord({
  standardGranted = true,
  standardNoticeVersion = "standard-v1",
  experimentalGranted = false,
  experimentalNoticeVersion = "experimental-v1"
} = {}) {
  return {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: standardGranted,
      noticeVersion: standardNoticeVersion,
      decidedAt: STANDARD_DECIDED_AT
    },
    experimentalTraining: {
      granted: experimentalGranted,
      noticeVersion: experimentalNoticeVersion,
      decidedAt: EXPERIMENTAL_DECIDED_AT
    },
    updatedAt: CONSENT_UPDATED_AT
  };
}

function changedStoredRecord(change) {
  const candidate = storedConsentRecord();
  change(candidate);
  return candidate;
}

async function captureRejection(promise) {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  assert.fail("expected promise to reject");
}

function captureThrow(action) {
  try {
    action();
  } catch (error) {
    return error;
  }
  assert.fail("expected action to throw");
}

function assertRedactedConsentError(error, code, context = "consent error") {
  assert.equal(error.code, code, context);
  const serialized = JSON.stringify(error);
  const rendered = String(error);
  for (const marker of SENSITIVE_CONSENT_MARKERS) {
    assert.equal(serialized.includes(marker), false, `${context}: serialized ${marker}`);
    assert.equal(rendered.includes(marker), false, `${context}: rendered ${marker}`);
  }
}

test("shared Firebase Admin app is lazy, named, reused, and project-immutable", () => {
  const calls = [];
  const app = {
    name: "fitdesi-backend-auth",
    options: { projectId: "fitdesi-ai" }
  };
  const provider = createFirebaseAdminAppProvider({
    applicationDefaultImpl: () => {
      calls.push("credential");
      return "adc";
    },
    getAppsImpl: () => {
      calls.push("getApps");
      return [];
    },
    initializeAppImpl: (options, name) => {
      calls.push({ options, name });
      return app;
    }
  });

  assert.deepEqual(calls, []);
  assert.equal(provider.getApp({ projectId: "fitdesi-ai" }), app);
  assert.equal(provider.getApp({ projectId: "fitdesi-ai" }), app);
  assert.deepEqual(calls, [
    "getApps",
    "credential",
    {
      options: { credential: "adc", projectId: "fitdesi-ai" },
      name: "fitdesi-backend-auth"
    }
  ]);
  assert.throws(
    () => provider.getApp({ projectId: "fitdesi-ai-production" }),
    /changed after initialization/
  );
});

test("shared Firebase Admin app reuses the matching named app without duplicate initialization", () => {
  const existingApp = {
    name: "fitdesi-backend-auth",
    options: { projectId: "fitdesi-ai-production" }
  };
  const provider = createFirebaseAdminAppProvider({
    applicationDefaultImpl: () => {
      throw new Error("must not request a second credential");
    },
    getAppsImpl: () => [
      { name: "other-app", options: { projectId: "other-project" } },
      existingApp
    ],
    initializeAppImpl: () => {
      throw new Error("must not initialize a duplicate app");
    }
  });

  assert.equal(
    provider.getApp({ projectId: "fitdesi-ai-production" }),
    existingApp
  );
});

test("shared Firebase Admin app accepts only the two approved projects", () => {
  assert.equal(isSupportedFirebaseProjectId("fitdesi-ai"), true);
  assert.equal(isSupportedFirebaseProjectId("fitdesi-ai-production"), true);
  assert.equal(isSupportedFirebaseProjectId("client-selected-project"), false);
  assert.equal(isSupportedFirebaseProjectId(""), false);
  assert.equal(isSupportedFirebaseProjectId(null), false);
});

test("loadConfig reads server-owned notice versions without enabling remote AI", () => {
  const originalRemoteNoticeVersion = process.env.REMOTE_AI_NOTICE_VERSION;
  const originalExperimentalNoticeVersion = process.env.EXPERIMENTAL_AI_NOTICE_VERSION;
  const originalRemoteAiEnabled = process.env.REMOTE_AI_ENABLED;

  try {
    process.env.REMOTE_AI_NOTICE_VERSION = " standard-v1 ";
    process.env.EXPERIMENTAL_AI_NOTICE_VERSION = " experimental-v1 ";
    delete process.env.REMOTE_AI_ENABLED;

    let config = loadConfig();

    assert.equal(config.remoteAiNoticeVersion, "standard-v1");
    assert.equal(config.experimentalAiNoticeVersion, "experimental-v1");
    assert.equal(config.remoteAiEnabled, false);

    process.env.REMOTE_AI_NOTICE_VERSION = "   ";
    process.env.EXPERIMENTAL_AI_NOTICE_VERSION = " unvalidated notice/value ";

    config = loadConfig();

    assert.equal(config.remoteAiNoticeVersion, "");
    assert.equal(config.experimentalAiNoticeVersion, "unvalidated notice/value");
    assert.equal(config.remoteAiEnabled, false);

    delete process.env.REMOTE_AI_NOTICE_VERSION;
    delete process.env.EXPERIMENTAL_AI_NOTICE_VERSION;

    config = loadConfig();

    assert.equal(config.remoteAiNoticeVersion, "");
    assert.equal(config.experimentalAiNoticeVersion, "");
    assert.equal(config.remoteAiEnabled, false);
  } finally {
    if (originalRemoteNoticeVersion === undefined) {
      delete process.env.REMOTE_AI_NOTICE_VERSION;
    } else {
      process.env.REMOTE_AI_NOTICE_VERSION = originalRemoteNoticeVersion;
    }
    if (originalExperimentalNoticeVersion === undefined) {
      delete process.env.EXPERIMENTAL_AI_NOTICE_VERSION;
    } else {
      process.env.EXPERIMENTAL_AI_NOTICE_VERSION = originalExperimentalNoticeVersion;
    }
    if (originalRemoteAiEnabled === undefined) {
      delete process.env.REMOTE_AI_ENABLED;
    } else {
      process.env.REMOTE_AI_ENABLED = originalRemoteAiEnabled;
    }
  }
});

test("consent errors expose stable status and redacted public contracts", () => {
  const unavailableMessage =
    "Privacy consent is temporarily unavailable. Please try again.";
  const sensitiveInput =
    "firebase-uid-marker fitdesi-ai firestore/doc token-marker secret-marker configuration-marker";
  const cases = [
    {
      error: new ConsentNoticeVersionMismatchError(sensitiveInput),
      statusCode: 409,
      code: "CONSENT_NOTICE_VERSION_MISMATCH",
      publicMessage:
        "The privacy notice has changed. Fetch the current consent state and try again."
    },
    {
      error: new ConsentAuthorityConfigurationError(sensitiveInput),
      statusCode: 503,
      code: "CONSENT_AUTHORITY_NOT_CONFIGURED",
      publicMessage: unavailableMessage
    },
    {
      error: new ConsentAuthorityInvalidRecordError(sensitiveInput),
      statusCode: 503,
      code: "CONSENT_AUTHORITY_INVALID_RECORD",
      publicMessage: unavailableMessage
    },
    {
      error: new ConsentAuthorityTimeoutError(sensitiveInput),
      statusCode: 504,
      code: "CONSENT_AUTHORITY_TIMEOUT",
      publicMessage: unavailableMessage
    },
    {
      error: new ConsentAuthorityUnavailableError(sensitiveInput),
      statusCode: 503,
      code: "CONSENT_AUTHORITY_UNAVAILABLE",
      publicMessage: unavailableMessage
    },
    {
      error: new RemoteAiConsentRequiredError(sensitiveInput),
      statusCode: 403,
      code: "REMOTE_AI_CONSENT_REQUIRED",
      publicMessage: "Current standard remote AI consent is required."
    },
    {
      error: new ExperimentalAiConsentRequiredError(sensitiveInput),
      statusCode: 403,
      code: "EXPERIMENTAL_AI_CONSENT_REQUIRED",
      publicMessage: "Current standard and experimental consent are required."
    }
  ];

  for (const { error, statusCode, code, publicMessage } of cases) {
    assert.equal(error.statusCode, statusCode);
    assert.equal(error.code, code);
    assert.equal(error.publicMessage, publicMessage);
    assert.equal(error.message, publicMessage);
    assert.deepEqual(error.headers, {});
    assert.equal(JSON.stringify(error).includes(sensitiveInput), false);
    assert.equal(String(error).includes(sensitiveInput), false);
  }
});

test("consent policy copies config without validating default-off startup values", () => {
  const policy = consentPolicyFromConfig({
    remoteAiNoticeVersion: "",
    experimentalAiNoticeVersion: " invalid/value "
  });

  assert.deepEqual(policy, {
    standardRemoteAi: "",
    experimentalTraining: " invalid/value "
  });
  assert.equal(Object.isFrozen(policy), true);
});

test("notice policy accepts exact one-to-128-character identifier boundaries", async () => {
  const policies = [
    { standardRemoteAi: "A", experimentalTraining: "9" },
    {
      standardRemoteAi: `A._-${"x".repeat(124)}`,
      experimentalTraining: `9${"z".repeat(127)}`
    }
  ];
  const store = {
    reads: 0,
    async read() {
      this.reads += 1;
      return null;
    }
  };

  for (const policy of policies) {
    const authority = new PrivacyConsentAuthority({ store, policy });
    const state = await authority.getConsentState({ firebaseUid: "trusted-uid" });
    assert.deepEqual(state.requiredNoticeVersions, policy);
  }
  assert.equal(store.reads, 2);
});

test("valid stored consent validation returns a detached frozen exact record", () => {
  const source = storedConsentRecord({ experimentalGranted: true });
  const validated = validateStoredConsentRecord(source);

  assert.deepEqual(validated, {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: true,
      noticeVersion: "standard-v1",
      decidedAt: STANDARD_DECIDED_AT
    },
    experimentalTraining: {
      granted: true,
      noticeVersion: "experimental-v1",
      decidedAt: EXPERIMENTAL_DECIDED_AT
    },
    updatedAt: CONSENT_UPDATED_AT
  });
  assert.notEqual(validated, source);
  assert.notEqual(validated.standardRemoteAi, source.standardRemoteAi);
  assert.notEqual(validated.experimentalTraining, source.experimentalTraining);
  assert.equal(Object.isFrozen(validated), true);
  assert.equal(Object.isFrozen(validated.standardRemoteAi), true);
  assert.equal(Object.isFrozen(validated.experimentalTraining), true);

  source.standardRemoteAi.granted = false;
  assert.equal(validated.standardRemoteAi.granted, true);
});

test("missing record normalizes to no consent and both authorizers deny", async () => {
  const reads = [];
  const store = {
    async read(input) {
      reads.push(input);
      return null;
    }
  };
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  assert.deepEqual(
    await authority.getConsentState({ firebaseUid: "firebase-uid-marker" }),
    {
      schemaVersion: 1,
      standardRemoteAi: {
        granted: false,
        current: false,
        noticeVersion: null,
        decidedAt: null
      },
      experimentalTraining: {
        granted: false,
        current: false,
        noticeVersion: null,
        decidedAt: null
      },
      requiredNoticeVersions: {
        standardRemoteAi: "standard-v1",
        experimentalTraining: "experimental-v1"
      },
      updatedAt: null
    }
  );
  assertRedactedConsentError(
    await captureRejection(
      authority.authorizeStandardRemoteProcessing({
        firebaseUid: "firebase-uid-marker"
      })
    ),
    "REMOTE_AI_CONSENT_REQUIRED"
  );
  assertRedactedConsentError(
    await captureRejection(
      authority.authorizeExperimentalProcessing({
        firebaseUid: "firebase-uid-marker"
      })
    ),
    "EXPERIMENTAL_AI_CONSENT_REQUIRED"
  );
  assert.deepEqual(reads, [
    { firebaseUid: "firebase-uid-marker" },
    { firebaseUid: "firebase-uid-marker" },
    { firebaseUid: "firebase-uid-marker" }
  ]);
});

test("stale standard notice stays visible but cannot authorize", async () => {
  const record = storedConsentRecord({ standardNoticeVersion: "standard-v0" });
  const store = { async read() { return record; } };
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  assert.deepEqual(
    await authority.getConsentState({ firebaseUid: "firebase-uid-marker" }),
    {
      schemaVersion: 1,
      standardRemoteAi: {
        granted: true,
        current: false,
        noticeVersion: "standard-v0",
        decidedAt: STANDARD_DECIDED_AT
      },
      experimentalTraining: {
        granted: false,
        current: false,
        noticeVersion: "experimental-v1",
        decidedAt: EXPERIMENTAL_DECIDED_AT
      },
      requiredNoticeVersions: {
        standardRemoteAi: "standard-v1",
        experimentalTraining: "experimental-v1"
      },
      updatedAt: CONSENT_UPDATED_AT
    }
  );
  assertRedactedConsentError(
    await captureRejection(
      authority.authorizeStandardRemoteProcessing({
        firebaseUid: "firebase-uid-marker"
      })
    ),
    "REMOTE_AI_CONSENT_REQUIRED"
  );
});

test("current standard notice authorizes standard processing", async () => {
  const store = { async read() { return storedConsentRecord(); } };
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  assert.deepEqual(
    await authority.getConsentState({ firebaseUid: "trusted-uid" }),
    {
      schemaVersion: 1,
      standardRemoteAi: {
        granted: true,
        current: true,
        noticeVersion: "standard-v1",
        decidedAt: STANDARD_DECIDED_AT
      },
      experimentalTraining: {
        granted: false,
        current: false,
        noticeVersion: "experimental-v1",
        decidedAt: EXPERIMENTAL_DECIDED_AT
      },
      requiredNoticeVersions: {
        standardRemoteAi: "standard-v1",
        experimentalTraining: "experimental-v1"
      },
      updatedAt: CONSENT_UPDATED_AT
    }
  );
  assert.equal(
    await authority.authorizeStandardRemoteProcessing({ firebaseUid: "trusted-uid" }),
    undefined
  );
});

test("experimental defaults false and is never inferred from standard", async () => {
  const authority = new PrivacyConsentAuthority({
    store: { async read() { return storedConsentRecord(); } },
    policy: TEST_CONSENT_POLICY
  });

  const state = await authority.getConsentState({ firebaseUid: "trusted-uid" });

  assert.deepEqual(state, {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: true,
      current: true,
      noticeVersion: "standard-v1",
      decidedAt: STANDARD_DECIDED_AT
    },
    experimentalTraining: {
      granted: false,
      current: false,
      noticeVersion: "experimental-v1",
      decidedAt: EXPERIMENTAL_DECIDED_AT
    },
    requiredNoticeVersions: {
      standardRemoteAi: "standard-v1",
      experimentalTraining: "experimental-v1"
    },
    updatedAt: CONSENT_UPDATED_AT
  });
  assertRedactedConsentError(
    await captureRejection(
      authority.authorizeExperimentalProcessing({ firebaseUid: "firebase-uid-marker" })
    ),
    "EXPERIMENTAL_AI_CONSENT_REQUIRED"
  );
});

test("stale experimental notice denies experimental processing", async () => {
  const authority = new PrivacyConsentAuthority({
    store: {
      async read() {
        return storedConsentRecord({
          experimentalGranted: true,
          experimentalNoticeVersion: "experimental-v0"
        });
      }
    },
    policy: TEST_CONSENT_POLICY
  });

  const state = await authority.getConsentState({ firebaseUid: "trusted-uid" });

  assert.deepEqual(state, {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: true,
      current: true,
      noticeVersion: "standard-v1",
      decidedAt: STANDARD_DECIDED_AT
    },
    experimentalTraining: {
      granted: true,
      current: false,
      noticeVersion: "experimental-v0",
      decidedAt: EXPERIMENTAL_DECIDED_AT
    },
    requiredNoticeVersions: {
      standardRemoteAi: "standard-v1",
      experimentalTraining: "experimental-v1"
    },
    updatedAt: CONSENT_UPDATED_AT
  });
  assertRedactedConsentError(
    await captureRejection(
      authority.authorizeExperimentalProcessing({ firebaseUid: "firebase-uid-marker" })
    ),
    "EXPERIMENTAL_AI_CONSENT_REQUIRED"
  );
});

test("current standard and experimental notices authorize experimental processing", async () => {
  const authority = new PrivacyConsentAuthority({
    store: {
      async read() {
        return storedConsentRecord({ experimentalGranted: true });
      }
    },
    policy: TEST_CONSENT_POLICY
  });

  assert.deepEqual(
    await authority.getConsentState({ firebaseUid: "trusted-uid" }),
    {
      schemaVersion: 1,
      standardRemoteAi: {
        granted: true,
        current: true,
        noticeVersion: "standard-v1",
        decidedAt: STANDARD_DECIDED_AT
      },
      experimentalTraining: {
        granted: true,
        current: true,
        noticeVersion: "experimental-v1",
        decidedAt: EXPERIMENTAL_DECIDED_AT
      },
      requiredNoticeVersions: {
        standardRemoteAi: "standard-v1",
        experimentalTraining: "experimental-v1"
      },
      updatedAt: CONSENT_UPDATED_AT
    }
  );
  assert.equal(
    await authority.authorizeExperimentalProcessing({ firebaseUid: "trusted-uid" }),
    undefined
  );
});

test("notice policy changes currentness without rewriting stored state", async () => {
  const record = storedConsentRecord({ experimentalGranted: true });
  const originalRecord = structuredClone(record);
  const store = {
    reads: 0,
    writes: 0,
    async read() {
      this.reads += 1;
      return record;
    },
    async mutate() {
      this.writes += 1;
    }
  };
  const currentAuthority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });
  const changedAuthority = new PrivacyConsentAuthority({
    store,
    policy: {
      standardRemoteAi: "standard-v2",
      experimentalTraining: "experimental-v2"
    }
  });

  const currentState = await currentAuthority.getConsentState({ firebaseUid: "trusted-uid" });
  const staleState = await changedAuthority.getConsentState({ firebaseUid: "trusted-uid" });

  assert.deepEqual(currentState, {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: true,
      current: true,
      noticeVersion: "standard-v1",
      decidedAt: STANDARD_DECIDED_AT
    },
    experimentalTraining: {
      granted: true,
      current: true,
      noticeVersion: "experimental-v1",
      decidedAt: EXPERIMENTAL_DECIDED_AT
    },
    requiredNoticeVersions: {
      standardRemoteAi: "standard-v1",
      experimentalTraining: "experimental-v1"
    },
    updatedAt: CONSENT_UPDATED_AT
  });
  assert.deepEqual(staleState, {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: true,
      current: false,
      noticeVersion: "standard-v1",
      decidedAt: STANDARD_DECIDED_AT
    },
    experimentalTraining: {
      granted: true,
      current: false,
      noticeVersion: "experimental-v1",
      decidedAt: EXPERIMENTAL_DECIDED_AT
    },
    requiredNoticeVersions: {
      standardRemoteAi: "standard-v2",
      experimentalTraining: "experimental-v2"
    },
    updatedAt: CONSENT_UPDATED_AT
  });
  assert.deepEqual(record, originalRecord);
  assert.equal(store.reads, 2);
  assert.equal(store.writes, 0);
});

test("experimental true with standard false is an impossible record", async () => {
  const impossible = storedConsentRecord({
    standardGranted: false,
    experimentalGranted: true
  });
  assertRedactedConsentError(
    captureThrow(() => validateStoredConsentRecord(impossible)),
    "CONSENT_AUTHORITY_INVALID_RECORD"
  );

  const authority = new PrivacyConsentAuthority({
    store: { async read() { return impossible; } },
    policy: TEST_CONSENT_POLICY
  });
  assertRedactedConsentError(
    await captureRejection(
      authority.authorizeExperimentalProcessing({ firebaseUid: "firebase-uid-marker" })
    ),
    "CONSENT_AUTHORITY_INVALID_RECORD"
  );
});

test("malformed stored records fail closed without repair or sensitive output", async () => {
  const malformedCases = [
    ["null record value", null],
    ["non-object", "raw-store-marker"],
    ["array", []],
    ["missing schemaVersion", changedStoredRecord((value) => { delete value.schemaVersion; })],
    ["missing standard decision", changedStoredRecord((value) => { delete value.standardRemoteAi; })],
    ["missing experimental decision", changedStoredRecord((value) => { delete value.experimentalTraining; })],
    ["missing updatedAt", changedStoredRecord((value) => { delete value.updatedAt; })],
    ["extra top-level field", { ...storedConsentRecord(), unexpected: true }],
    ["extra Firebase UID", { ...storedConsentRecord(), firebaseUid: "firebase-uid-marker" }],
    ["extra user field", { ...storedConsentRecord(), userId: "firebase-uid-marker" }],
    [
      "extra document path",
      {
        ...storedConsentRecord(),
        documentPath: "remote_ai_consents_v1/firebase-uid-marker"
      }
    ],
    ["standard decision is null", changedStoredRecord((value) => { value.standardRemoteAi = null; })],
    ["experimental decision is an array", changedStoredRecord((value) => { value.experimentalTraining = []; })],
    ["missing standard granted", changedStoredRecord((value) => { delete value.standardRemoteAi.granted; })],
    ["missing standard notice", changedStoredRecord((value) => { delete value.standardRemoteAi.noticeVersion; })],
    ["missing standard timestamp", changedStoredRecord((value) => { delete value.standardRemoteAi.decidedAt; })],
    ["extra standard field", changedStoredRecord((value) => { value.standardRemoteAi.uid = "firebase-uid-marker"; })],
    ["missing experimental granted", changedStoredRecord((value) => { delete value.experimentalTraining.granted; })],
    ["missing experimental notice", changedStoredRecord((value) => { delete value.experimentalTraining.noticeVersion; })],
    ["missing experimental timestamp", changedStoredRecord((value) => { delete value.experimentalTraining.decidedAt; })],
    ["extra experimental field", changedStoredRecord((value) => { value.experimentalTraining.path = "remote_ai_consents_v1/firebase-uid-marker"; })],
    ["wrong numeric schema", { ...storedConsentRecord(), schemaVersion: 2 }],
    ["wrong string schema", { ...storedConsentRecord(), schemaVersion: "1" }],
    ["non-boolean standard grant", changedStoredRecord((value) => { value.standardRemoteAi.granted = "true"; })],
    ["non-boolean experimental grant", changedStoredRecord((value) => { value.experimentalTraining.granted = 1; })],
    ["empty standard notice", changedStoredRecord((value) => { value.standardRemoteAi.noticeVersion = ""; })],
    ["non-string experimental notice", changedStoredRecord((value) => { value.experimentalTraining.noticeVersion = 1; })],
    ["invalid standard timestamp", changedStoredRecord((value) => { value.standardRemoteAi.decidedAt = "raw-store-marker"; })],
    ["non-canonical standard timestamp", changedStoredRecord((value) => { value.standardRemoteAi.decidedAt = "2026-09-14T10:00:00Z"; })],
    ["invalid experimental timestamp", changedStoredRecord((value) => { value.experimentalTraining.decidedAt = "not-a-date"; })],
    ["non-canonical experimental timestamp", changedStoredRecord((value) => { value.experimentalTraining.decidedAt = "2026-09-14T10:01:00.000+00:00"; })],
    ["invalid updated timestamp", { ...storedConsentRecord(), updatedAt: "invalid-date" }],
    ["non-canonical updated timestamp", { ...storedConsentRecord(), updatedAt: "2026-09-14T10:02:00Z" }]
  ];

  for (const [label, candidate] of malformedCases) {
    const validationError = captureThrow(() => validateStoredConsentRecord(candidate));
    assertRedactedConsentError(
      validationError,
      "CONSENT_AUTHORITY_INVALID_RECORD",
      label
    );

    if (candidate !== null) {
      const authority = new PrivacyConsentAuthority({
        store: { async read() { return candidate; } },
        policy: TEST_CONSENT_POLICY
      });
      const authorityError = await captureRejection(
        authority.authorizeStandardRemoteProcessing({
          firebaseUid: "firebase-uid-marker"
        })
      );
      assertRedactedConsentError(
        authorityError,
        "CONSENT_AUTHORITY_INVALID_RECORD",
        label
      );
    }
  }
});

test("authorizers perform a fresh store read on every call", async () => {
  const records = [
    storedConsentRecord(),
    null,
    storedConsentRecord({ experimentalGranted: true }),
    storedConsentRecord({
      experimentalGranted: true,
      experimentalNoticeVersion: "experimental-v0"
    })
  ];
  const reads = [];
  const store = {
    async read(input) {
      reads.push(input);
      return records.shift();
    }
  };
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  assert.equal(
    await authority.authorizeStandardRemoteProcessing({ firebaseUid: "trusted-uid" }),
    undefined
  );
  assertRedactedConsentError(
    await captureRejection(
      authority.authorizeStandardRemoteProcessing({ firebaseUid: "firebase-uid-marker" })
    ),
    "REMOTE_AI_CONSENT_REQUIRED"
  );
  assert.equal(
    await authority.authorizeExperimentalProcessing({ firebaseUid: "trusted-uid" }),
    undefined
  );
  assertRedactedConsentError(
    await captureRejection(
      authority.authorizeExperimentalProcessing({ firebaseUid: "firebase-uid-marker" })
    ),
    "EXPERIMENTAL_AI_CONSENT_REQUIRED"
  );
  assert.deepEqual(reads, [
    { firebaseUid: "trusted-uid" },
    { firebaseUid: "firebase-uid-marker" },
    { firebaseUid: "trusted-uid" },
    { firebaseUid: "firebase-uid-marker" }
  ]);
});

test("invalid notice policy configuration fails before store read", async () => {
  const invalidValues = [
    undefined,
    null,
    1,
    "",
    " ",
    " standard-v1",
    "standard-v1 ",
    ".standard-v1",
    "_standard-v1",
    "-standard-v1",
    "standard/v1",
    "standard v1",
    "a".repeat(129)
  ];

  for (const field of ["standardRemoteAi", "experimentalTraining"]) {
    for (const value of invalidValues) {
      const store = {
        reads: 0,
        async read() {
          this.reads += 1;
          throw new Error("store must not be read");
        }
      };
      const policy = {
        standardRemoteAi: "standard-v1",
        experimentalTraining: "experimental-v1",
        [field]: value
      };
      const authority = new PrivacyConsentAuthority({ store, policy });
      assertRedactedConsentError(
        await captureRejection(
          authority.getConsentState({ firebaseUid: "firebase-uid-marker" })
        ),
        "CONSENT_AUTHORITY_NOT_CONFIGURED"
      );
      assert.equal(store.reads, 0);
    }
  }

  for (const method of [
    "getConsentState",
    "authorizeStandardRemoteProcessing",
    "authorizeExperimentalProcessing"
  ]) {
    const store = {
      reads: 0,
      async read() {
        this.reads += 1;
        return null;
      }
    };
    const authority = new PrivacyConsentAuthority({
      store,
      policy: { standardRemoteAi: "", experimentalTraining: "experimental-v1" }
    });
    assertRedactedConsentError(
      await captureRejection(authority[method]({ firebaseUid: "" })),
      "CONSENT_AUTHORITY_NOT_CONFIGURED"
    );
    assert.equal(store.reads, 0);
  }
});

test("unexpected store failures are unavailable and cannot become consent-required", async () => {
  for (const method of [
    "getConsentState",
    "authorizeStandardRemoteProcessing",
    "authorizeExperimentalProcessing"
  ]) {
    const authority = new PrivacyConsentAuthority({
      store: {
        async read() {
          throw new Error(
            "raw-store-marker firebase-uid-marker fitdesi-ai-production token-marker"
          );
        }
      },
      policy: TEST_CONSENT_POLICY
    });
    assertRedactedConsentError(
      await captureRejection(
        authority[method]({ firebaseUid: "firebase-uid-marker" })
      ),
      "CONSENT_AUTHORITY_UNAVAILABLE"
    );
  }
});

test("stable consent store errors are preserved", async () => {
  for (const stableError of [
    new ConsentNoticeVersionMismatchError(),
    new ConsentAuthorityConfigurationError(),
    new ConsentAuthorityInvalidRecordError(),
    new ConsentAuthorityTimeoutError(),
    new ConsentAuthorityUnavailableError(),
    new RemoteAiConsentRequiredError(),
    new ExperimentalAiConsentRequiredError()
  ]) {
    const authority = new PrivacyConsentAuthority({
      store: { async read() { throw stableError; } },
      policy: TEST_CONSENT_POLICY
    });
    const caught = await captureRejection(
      authority.getConsentState({ firebaseUid: "firebase-uid-marker" })
    );
    assert.equal(caught, stableError);
    assertRedactedConsentError(caught, stableError.code);
  }
});

function recordingMutationStore({ mutateError = null, deleteError = null } = {}) {
  const mutateCalls = [];
  const deleteCalls = [];
  return {
    mutateCalls,
    deleteCalls,
    async mutate(input) {
      mutateCalls.push(input);
      if (mutateError) throw mutateError;
    },
    async delete(input) {
      deleteCalls.push(input);
      if (deleteError) throw deleteError;
    }
  };
}

test("matching standard grant passes only the server policy to the store", async () => {
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });
  const clientMutation = {
    standardRemoteAi: { granted: true, noticeVersion: "standard-v1" }
  };

  await authority.updateConsent({
    firebaseUid: "trusted-uid",
    mutation: clientMutation
  });

  assert.deepEqual(store.mutateCalls, [{
    firebaseUid: "trusted-uid",
    mutation: {
      standardRemoteAi: { granted: true, noticeVersion: "standard-v1" }
    },
    policy: {
      standardRemoteAi: "standard-v1",
      experimentalTraining: "experimental-v1"
    }
  }]);
  assert.notEqual(store.mutateCalls[0].mutation, clientMutation);
  assert.notEqual(
    store.mutateCalls[0].mutation.standardRemoteAi,
    clientMutation.standardRemoteAi
  );
});

test("matching experimental grant passes only the server policy to the store", async () => {
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });
  const clientMutation = {
    experimentalTraining: {
      granted: true,
      noticeVersion: "experimental-v1"
    }
  };

  await authority.updateConsent({
    firebaseUid: "trusted-uid",
    mutation: clientMutation
  });

  assert.deepEqual(store.mutateCalls, [{
    firebaseUid: "trusted-uid",
    mutation: {
      experimentalTraining: {
        granted: true,
        noticeVersion: "experimental-v1"
      }
    },
    policy: {
      standardRemoteAi: "standard-v1",
      experimentalTraining: "experimental-v1"
    }
  }]);
  assert.notEqual(store.mutateCalls[0].mutation, clientMutation);
  assert.notEqual(
    store.mutateCalls[0].mutation.experimentalTraining,
    clientMutation.experimentalTraining
  );
});

test("grant with missing or stale notice version rejects before store mutation", async () => {
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });
  const cases = [
    [
      "missing standard notice",
      { standardRemoteAi: { granted: true } },
      "VALIDATION_ERROR"
    ],
    [
      "missing experimental notice",
      { experimentalTraining: { granted: true } },
      "VALIDATION_ERROR"
    ],
    [
      "stale standard notice",
      { standardRemoteAi: { granted: true, noticeVersion: "standard-v0" } },
      "CONSENT_NOTICE_VERSION_MISMATCH"
    ],
    [
      "stale experimental notice",
      {
        experimentalTraining: {
          granted: true,
          noticeVersion: "experimental-v0"
        }
      },
      "CONSENT_NOTICE_VERSION_MISMATCH"
    ]
  ];

  for (const [label, mutation, code] of cases) {
    assertRedactedConsentError(
      await captureRejection(
        authority.updateConsent({
          firebaseUid: "firebase-uid-marker",
          mutation
        })
      ),
      code,
      label
    );
  }
  assert.deepEqual(store.mutateCalls, []);
});

test("withdrawal permits missing or stale notice echo and strips it before storage", async () => {
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  await authority.updateConsent({
    firebaseUid: "trusted-uid",
    mutation: { standardRemoteAi: { granted: false } }
  });
  await authority.updateConsent({
    firebaseUid: "trusted-uid",
    mutation: {
      experimentalTraining: {
        granted: false,
        noticeVersion: "experimental-v0"
      }
    }
  });

  assert.deepEqual(store.mutateCalls, [
    {
      firebaseUid: "trusted-uid",
      mutation: { standardRemoteAi: { granted: false } },
      policy: {
        standardRemoteAi: "standard-v1",
        experimentalTraining: "experimental-v1"
      }
    },
    {
      firebaseUid: "trusted-uid",
      mutation: { experimentalTraining: { granted: false } },
      policy: {
        standardRemoteAi: "standard-v1",
        experimentalTraining: "experimental-v1"
      }
    }
  ]);
});

test("client notice version syntax is exact for grants and withdrawals", async () => {
  const invalidVersions = [
    ["non-string null", null],
    ["non-string number", 1],
    ["non-string boolean", false],
    ["non-string object", {}],
    ["non-string array", []],
    ["non-string undefined", undefined],
    ["leading whitespace", " standard-v1"],
    ["trailing whitespace", "standard-v1 "],
    ["empty", ""],
    ["over 128 characters", `A${"x".repeat(128)}`],
    ["leading period", ".standard-v1"],
    ["leading underscore", "_standard-v1"],
    ["leading hyphen", "-standard-v1"],
    ["embedded space", "standard v1"],
    ["slash", "standard/v1"],
    ["other character", "standard@v1"]
  ];
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  for (const [label, noticeVersion] of invalidVersions) {
    for (const granted of [true, false]) {
      assertRedactedConsentError(
        await captureRejection(
          authority.updateConsent({
            firebaseUid: "firebase-uid-marker",
            mutation: {
              standardRemoteAi: { granted, noticeVersion }
            }
          })
        ),
        "VALIDATION_ERROR",
        `${label}, granted=${granted}`
      );
    }
  }
  assert.deepEqual(store.mutateCalls, []);
});

test("invalid mutation fields and types reject before store mutation", async () => {
  const invalidMutations = [
    ["undefined", undefined],
    ["null", null],
    ["array", []],
    ["non-object", "raw-store-marker"],
    ["empty", {}],
    ["unknown top-level", { unexpected: true }],
    ["uid", { uid: "firebase-uid-marker" }],
    ["firebaseUid", { firebaseUid: "firebase-uid-marker" }],
    ["userId", { userId: "firebase-uid-marker" }],
    [
      "document path",
      { documentPath: "remote_ai_consents_v1/firebase-uid-marker" }
    ],
    ["schemaVersion", { schemaVersion: 1 }],
    ["top-level decidedAt", { decidedAt: STANDARD_DECIDED_AT }],
    ["top-level updatedAt", { updatedAt: CONSENT_UPDATED_AT }],
    ["privacy class", { privacyClass: "P0" }],
    ["email state", { emailVerified: true }],
    ["provider field", { provider: "provider-marker" }],
    ["commercial field", { commercialTier: "PRO" }],
    ["null decision", { standardRemoteAi: null }],
    ["array decision", { standardRemoteAi: [] }],
    [
      "missing granted",
      { standardRemoteAi: { noticeVersion: "standard-v1" } }
    ],
    [
      "non-boolean granted",
      {
        standardRemoteAi: {
          granted: "true",
          noticeVersion: "standard-v1"
        }
      }
    ],
    [
      "extra nested uid",
      {
        standardRemoteAi: {
          granted: true,
          noticeVersion: "standard-v1",
          uid: "firebase-uid-marker"
        }
      }
    ],
    [
      "extra nested timestamp",
      {
        experimentalTraining: {
          granted: false,
          decidedAt: EXPERIMENTAL_DECIDED_AT
        }
      }
    ],
    [
      "valid decision plus unknown top-level",
      {
        standardRemoteAi: {
          granted: true,
          noticeVersion: "standard-v1"
        },
        token: "token-marker"
      }
    ]
  ];
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  for (const [label, mutation] of invalidMutations) {
    assertRedactedConsentError(
      await captureRejection(
        authority.updateConsent({
          firebaseUid: "firebase-uid-marker",
          mutation
        })
      ),
      "VALIDATION_ERROR",
      label
    );
  }
  assert.deepEqual(store.mutateCalls, []);
});

test("simultaneous standard false and experimental true rejects before store mutation", async () => {
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  assertRedactedConsentError(
    await captureRejection(
      authority.updateConsent({
        firebaseUid: "firebase-uid-marker",
        mutation: {
          standardRemoteAi: { granted: false },
          experimentalTraining: {
            granted: true,
            noticeVersion: "experimental-v1"
          }
        }
      })
    ),
    "VALIDATION_ERROR"
  );
  assert.deepEqual(store.mutateCalls, []);
});

test("simultaneous standard false and stale experimental true keeps validation precedence", async () => {
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  const error = await captureRejection(
    authority.updateConsent({
      firebaseUid: "firebase-uid-marker",
      mutation: {
        standardRemoteAi: { granted: false },
        experimentalTraining: {
          granted: true,
          noticeVersion: "experimental-v0"
        }
      }
    })
  );

  assertRedactedConsentError(error, "VALIDATION_ERROR");
  assert.equal(store.mutateCalls.length, 0);
});

test("delete delegates only the validated server-derived UID and is idempotent", async () => {
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: TEST_CONSENT_POLICY
  });

  assert.equal(
    await authority.deleteConsent({ firebaseUid: "trusted-uid" }),
    undefined
  );
  assert.equal(
    await authority.deleteConsent({ firebaseUid: "trusted-uid" }),
    undefined
  );

  assert.deepEqual(store.deleteCalls, [
    { firebaseUid: "trusted-uid" },
    { firebaseUid: "trusted-uid" }
  ]);
  assert.deepEqual(store.mutateCalls, []);
});

test("mutation and delete failures remain fail closed and redacted", async () => {
  const rawMutationStore = recordingMutationStore({
    mutateError: new Error(
      "raw-store-marker firebase-uid-marker fitdesi-ai-production token-marker"
    )
  });
  const rawDeleteStore = recordingMutationStore({
    deleteError: new Error(
      "raw-store-marker remote_ai_consents_v1/firebase-uid-marker provider-marker"
    )
  });
  const mutationAuthority = new PrivacyConsentAuthority({
    store: rawMutationStore,
    policy: TEST_CONSENT_POLICY
  });
  const deleteAuthority = new PrivacyConsentAuthority({
    store: rawDeleteStore,
    policy: TEST_CONSENT_POLICY
  });

  assertRedactedConsentError(
    await captureRejection(
      mutationAuthority.updateConsent({
        firebaseUid: "firebase-uid-marker",
        mutation: {
          standardRemoteAi: {
            granted: true,
            noticeVersion: "standard-v1"
          }
        }
      })
    ),
    "CONSENT_AUTHORITY_UNAVAILABLE"
  );
  assertRedactedConsentError(
    await captureRejection(
      deleteAuthority.deleteConsent({ firebaseUid: "firebase-uid-marker" })
    ),
    "CONSENT_AUTHORITY_UNAVAILABLE"
  );

  const mutationTimeout = new ConsentAuthorityTimeoutError();
  const deleteInvalidRecord = new ConsentAuthorityInvalidRecordError();
  const stableMutationAuthority = new PrivacyConsentAuthority({
    store: recordingMutationStore({ mutateError: mutationTimeout }),
    policy: TEST_CONSENT_POLICY
  });
  const stableDeleteAuthority = new PrivacyConsentAuthority({
    store: recordingMutationStore({ deleteError: deleteInvalidRecord }),
    policy: TEST_CONSENT_POLICY
  });
  assert.equal(
    await captureRejection(
      stableMutationAuthority.updateConsent({
        firebaseUid: "trusted-uid",
        mutation: { standardRemoteAi: { granted: false } }
      })
    ),
    mutationTimeout
  );
  assert.equal(
    await captureRejection(
      stableDeleteAuthority.deleteConsent({ firebaseUid: "trusted-uid" })
    ),
    deleteInvalidRecord
  );
});

test("mutation preserves bounded store ValidationError without changing delete mapping", async () => {
  const mutationValidationError = new ValidationError(
    "Consent mutation is invalid."
  );
  const deleteValidationError = new ValidationError(
    "Consent mutation is invalid."
  );
  const mutationStore = recordingMutationStore({
    mutateError: mutationValidationError
  });
  const deleteStore = recordingMutationStore({
    deleteError: deleteValidationError
  });
  const mutationAuthority = new PrivacyConsentAuthority({
    store: mutationStore,
    policy: TEST_CONSENT_POLICY
  });
  const deleteAuthority = new PrivacyConsentAuthority({
    store: deleteStore,
    policy: TEST_CONSENT_POLICY
  });

  const mutationError = await captureRejection(
    mutationAuthority.updateConsent({
      firebaseUid: "trusted-uid",
      mutation: { standardRemoteAi: { granted: false } }
    })
  );
  const deleteError = await captureRejection(
    deleteAuthority.deleteConsent({ firebaseUid: "trusted-uid" })
  );

  assert.equal(mutationError, mutationValidationError);
  assert.equal(mutationError.code, "VALIDATION_ERROR");
  assert.equal(mutationError.publicMessage, "Consent mutation is invalid.");
  assertRedactedConsentError(mutationError, "VALIDATION_ERROR");
  assertRedactedConsentError(deleteError, "CONSENT_AUTHORITY_UNAVAILABLE");
  assert.notEqual(deleteError, deleteValidationError);
});

test("invalid mutation policy prevents update and delete store access", async () => {
  const store = recordingMutationStore();
  const authority = new PrivacyConsentAuthority({
    store,
    policy: { standardRemoteAi: "", experimentalTraining: "experimental-v1" }
  });

  assertRedactedConsentError(
    await captureRejection(
      authority.updateConsent({
        firebaseUid: "firebase-uid-marker",
        mutation: { standardRemoteAi: { granted: false } }
      })
    ),
    "CONSENT_AUTHORITY_NOT_CONFIGURED"
  );
  assertRedactedConsentError(
    await captureRejection(
      authority.deleteConsent({ firebaseUid: "firebase-uid-marker" })
    ),
    "CONSENT_AUTHORITY_NOT_CONFIGURED"
  );
  assert.deepEqual(store.mutateCalls, []);
  assert.deepEqual(store.deleteCalls, []);
});

test("invalid mutation UID prevents update and delete store access before policy validation", async () => {
  const invalidUids = [undefined, null, 1, ""];

  for (const firebaseUid of invalidUids) {
    const store = recordingMutationStore();
    const authority = new PrivacyConsentAuthority({
      store,
      policy: { standardRemoteAi: "", experimentalTraining: "" }
    });
    assertRedactedConsentError(
      await captureRejection(
        authority.updateConsent({
          firebaseUid,
          mutation: { standardRemoteAi: { granted: false } }
        })
      ),
      "CONSENT_AUTHORITY_UNAVAILABLE"
    );
    assertRedactedConsentError(
      await captureRejection(authority.deleteConsent({ firebaseUid })),
      "CONSENT_AUTHORITY_UNAVAILABLE"
    );
    assert.deepEqual(store.mutateCalls, []);
    assert.deepEqual(store.deleteCalls, []);
  }
});

function persistedConsentRecord({
  standardGranted = true,
  standardNoticeVersion = "standard-v1",
  experimentalGranted = false,
  experimentalNoticeVersion = "experimental-v1"
} = {}) {
  return {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: standardGranted,
      noticeVersion: standardNoticeVersion,
      decidedAt: Timestamp.fromDate(new Date(STANDARD_DECIDED_AT))
    },
    experimentalTraining: {
      granted: experimentalGranted,
      noticeVersion: experimentalNoticeVersion,
      decidedAt: Timestamp.fromDate(new Date(EXPERIMENTAL_DECIDED_AT))
    },
    updatedAt: Timestamp.fromDate(new Date(CONSENT_UPDATED_AT))
  };
}

const TRUSTED_SERVER_DOCUMENT_ID =
  "uid_8a9d36b6d7cdea490c58883c9d2b5f2bf90d11a7ffb2730a8cc739850f5d2740";

function firestoreSnapshot(data = null) {
  return data === null
    ? { exists: false, data: () => undefined }
    : { exists: true, data: () => data };
}

function firestoreStoreHarness({
  snapshot = firestoreSnapshot(),
  readError = null,
  transactionError = null,
  transactionGetError = null,
  deleteError = null,
  appError = null,
  initializationError = null,
  projectId = "fitdesi-ai"
} = {}) {
  const calls = {
    app: [],
    getFirestore: [],
    collections: [],
    documents: [],
    reads: 0,
    runTransactions: 0,
    transactionGets: [],
    transactionSets: [],
    deletes: 0,
    serverTimestamps: 0
  };
  const app = Object.freeze({ name: "injected-admin-app" });
  const serverTimestamp = Object.freeze({
    type: "unique-server-timestamp-sentinel"
  });
  const documentReference = {
    async get() {
      calls.reads += 1;
      if (readError) throw readError;
      return snapshot;
    },
    async delete() {
      calls.deletes += 1;
      if (deleteError) throw deleteError;
    }
  };
  const firestore = {
    collection(collectionName) {
      calls.collections.push(collectionName);
      return {
        doc(firebaseUid) {
          calls.documents.push(firebaseUid);
          return documentReference;
        }
      };
    },
    async runTransaction(operation) {
      calls.runTransactions += 1;
      if (transactionError) throw transactionError;
      const transaction = {
        async get(reference) {
          calls.transactionGets.push(reference);
          if (transactionGetError) throw transactionGetError;
          return snapshot;
        },
        set(reference, value, ...options) {
          calls.transactionSets.push({ reference, value, options });
        }
      };
      return operation(transaction);
    }
  };
  const firebaseAdminAppProvider = {
    getApp(input) {
      calls.app.push(input);
      if (appError) throw appError;
      return app;
    }
  };
  const getFirestoreImpl = (receivedApp) => {
    calls.getFirestore.push(receivedApp);
    if (initializationError) throw initializationError;
    return firestore;
  };
  const serverTimestampImpl = () => {
    calls.serverTimestamps += 1;
    return serverTimestamp;
  };
  const store = new FirestoreConsentStore({
    projectId,
    firebaseAdminAppProvider,
    getFirestoreImpl,
    serverTimestampImpl,
    collectionName: "client-selected-collection"
  });

  return {
    app,
    calls,
    documentReference,
    firestore,
    serverTimestamp,
    store
  };
}

function onlyTransactionWrite(harness) {
  assert.equal(harness.calls.runTransactions, 1);
  assert.equal(harness.calls.transactionGets.length, 1);
  assert.equal(harness.calls.transactionGets[0], harness.documentReference);
  assert.equal(harness.calls.transactionSets.length, 1);
  const write = harness.calls.transactionSets[0];
  assert.equal(write.reference, harness.documentReference);
  assert.deepEqual(write.options, []);
  return write.value;
}

test("Firestore store is lazy and always selects fixed collection with server UID", async () => {
  const harness = firestoreStoreHarness();

  assert.deepEqual(harness.calls.app, []);
  assert.deepEqual(harness.calls.getFirestore, []);
  assert.deepEqual(harness.calls.collections, []);
  assert.deepEqual(harness.calls.documents, []);

  assert.equal(
    await harness.store.read({
      firebaseUid: "trusted-server-uid",
      documentPath: "client-selected/path"
    }),
    null
  );
  assert.equal(
    await harness.store.read({ firebaseUid: "trusted-server-uid" }),
    null
  );

  assert.deepEqual(harness.calls.app, [{ projectId: "fitdesi-ai" }]);
  assert.deepEqual(harness.calls.getFirestore, [harness.app]);
  assert.deepEqual(harness.calls.collections, [
    "remote_ai_consents_v1",
    "remote_ai_consents_v1"
  ]);
  assert.deepEqual(harness.calls.documents, [
    TRUSTED_SERVER_DOCUMENT_ID,
    TRUSTED_SERVER_DOCUMENT_ID
  ]);
  assert.equal(harness.calls.reads, 2);
});

test("Firestore document key is deterministic and safe for a normal trusted UID", async () => {
  const firstHarness = firestoreStoreHarness();
  const secondHarness = firestoreStoreHarness();

  await firstHarness.store.read({ firebaseUid: "trusted-server-uid" });
  await secondHarness.store.read({ firebaseUid: "trusted-server-uid" });

  assert.deepEqual(firstHarness.calls.documents, [TRUSTED_SERVER_DOCUMENT_ID]);
  assert.deepEqual(secondHarness.calls.documents, [TRUSTED_SERVER_DOCUMENT_ID]);
  assert.match(TRUSTED_SERVER_DOCUMENT_ID, /^uid_[0-9a-f]{64}$/);
  assert.equal(TRUSTED_SERVER_DOCUMENT_ID.includes("trusted-server-uid"), false);
});

test("Firestore document key is identical across read mutate and delete", async () => {
  const harness = firestoreStoreHarness();

  await harness.store.read({ firebaseUid: "trusted-server-uid" });
  await harness.store.mutate({
    firebaseUid: "trusted-server-uid",
    mutation: { standardRemoteAi: { granted: false } },
    policy: TEST_CONSENT_POLICY
  });
  await harness.store.delete({ firebaseUid: "trusted-server-uid" });

  assert.deepEqual(harness.calls.documents, [
    TRUSTED_SERVER_DOCUMENT_ID,
    TRUSTED_SERVER_DOCUMENT_ID,
    TRUSTED_SERVER_DOCUMENT_ID
  ]);
  assert.deepEqual(harness.calls.collections, [
    "remote_ai_consents_v1",
    "remote_ai_consents_v1",
    "remote_ai_consents_v1"
  ]);
  assert.deepEqual(harness.calls.app, [{ projectId: "fitdesi-ai" }]);
  assert.deepEqual(harness.calls.getFirestore, [harness.app]);
});

test("Firestore document keys differ for different trusted UIDs", async () => {
  const harness = firestoreStoreHarness();

  await harness.store.read({ firebaseUid: "trusted-server-uid" });
  await harness.store.read({ firebaseUid: "different-server-uid" });

  assert.equal(harness.calls.documents.length, 2);
  assert.equal(harness.calls.documents[0], TRUSTED_SERVER_DOCUMENT_ID);
  assert.match(harness.calls.documents[1], /^uid_[0-9a-f]{64}$/);
  assert.notEqual(harness.calls.documents[0], harness.calls.documents[1]);
});

test("Firestore document key prevents path and reserved document ID forms", async () => {
  for (const firebaseUid of ["user/a", ".", "..", "__reserved__"]) {
    const harness = firestoreStoreHarness();

    await harness.store.read({ firebaseUid });

    assert.equal(harness.calls.documents.length, 1);
    assert.match(harness.calls.documents[0], /^uid_[0-9a-f]{64}$/);
    assert.notEqual(harness.calls.documents[0], firebaseUid);
    assert.equal(harness.calls.documents[0].includes(firebaseUid), false);
    assert.deepEqual(harness.calls.collections, ["remote_ai_consents_v1"]);
  }
});

test("invalid internal Firebase UID fails before Firestore access", async () => {
  const invalidUids = ["", null, 7, {}, "x".repeat(129)];
  const operations = [
    (store, firebaseUid) => store.read({ firebaseUid }),
    (store, firebaseUid) => store.mutate({
      firebaseUid,
      mutation: { standardRemoteAi: { granted: false } },
      policy: TEST_CONSENT_POLICY
    }),
    (store, firebaseUid) => store.delete({ firebaseUid })
  ];

  for (const firebaseUid of invalidUids) {
    for (const operation of operations) {
      const harness = firestoreStoreHarness();
      assertRedactedConsentError(
        await captureRejection(operation(harness.store, firebaseUid)),
        "CONSENT_AUTHORITY_UNAVAILABLE"
      );
      assert.deepEqual(harness.calls.app, []);
      assert.deepEqual(harness.calls.getFirestore, []);
      assert.deepEqual(harness.calls.collections, []);
      assert.deepEqual(harness.calls.documents, []);
    }
  }
});

test("Firestore read converts real Timestamp values to canonical ISO strings", async () => {
  const record = persistedConsentRecord({ experimentalGranted: true });
  const harness = firestoreStoreHarness({ snapshot: firestoreSnapshot(record) });

  assert.equal(record.standardRemoteAi.decidedAt instanceof Timestamp, true);
  assert.deepEqual(
    await harness.store.read({ firebaseUid: "trusted-server-uid" }),
    storedConsentRecord({ experimentalGranted: true })
  );
  assert.deepEqual(harness.calls.collections, ["remote_ai_consents_v1"]);
  assert.deepEqual(harness.calls.documents, [TRUSTED_SERVER_DOCUMENT_ID]);
});

test("Firestore rejects non-Timestamp persisted timestamp representations", async () => {
  const invalidRepresentations = [
    ["ISO string", STANDARD_DECIDED_AT],
    ["JavaScript Date", new Date(STANDARD_DECIDED_AT)],
    ["number", 1_757_808_000_000],
    ["null", null],
    [
      "duck-typed object",
      { toDate() { return new Date(STANDARD_DECIDED_AT); } }
    ]
  ];
  const fields = [
    ["standardRemoteAi.decidedAt", (record, value) => {
      record.standardRemoteAi.decidedAt = value;
    }],
    ["experimentalTraining.decidedAt", (record, value) => {
      record.experimentalTraining.decidedAt = value;
    }],
    ["updatedAt", (record, value) => {
      record.updatedAt = value;
    }]
  ];

  for (const [field, assign] of fields) {
    for (const [representation, value] of invalidRepresentations) {
      const record = persistedConsentRecord();
      assign(record, value);
      const harness = firestoreStoreHarness({
        snapshot: firestoreSnapshot(record)
      });
      const error = await captureRejection(
        harness.store.read({ firebaseUid: "firebase-uid-marker" })
      );
      assertRedactedConsentError(
        error,
        "CONSENT_AUTHORITY_INVALID_RECORD",
        `${field}: ${representation}`
      );
    }
  }
});

test("standard grant writes a complete schemaVersion 1 replacement with server timestamps", async () => {
  const existing = persistedConsentRecord({ standardGranted: false });
  const harness = firestoreStoreHarness({ snapshot: firestoreSnapshot(existing) });

  await harness.store.mutate({
    firebaseUid: "trusted-server-uid",
    mutation: {
      standardRemoteAi: {
        granted: true,
        noticeVersion: "client-notice-marker",
        decidedAt: "client-timestamp-marker"
      },
      documentPath: "client-selected/path"
    },
    policy: TEST_CONSENT_POLICY
  });

  const written = onlyTransactionWrite(harness);
  assert.deepEqual(written, {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: true,
      noticeVersion: "standard-v1",
      decidedAt: harness.serverTimestamp
    },
    experimentalTraining: {
      granted: false,
      noticeVersion: "experimental-v1",
      decidedAt: existing.experimentalTraining.decidedAt
    },
    updatedAt: harness.serverTimestamp
  });
  assert.equal(harness.calls.serverTimestamps, 1);
  assert.equal(JSON.stringify(written).includes("client-"), false);
});

test("standard withdrawal atomically writes standard false and experimental false", async () => {
  const harness = firestoreStoreHarness({
    snapshot: firestoreSnapshot(
      persistedConsentRecord({ experimentalGranted: true })
    )
  });

  await harness.store.mutate({
    firebaseUid: "trusted-server-uid",
    mutation: { standardRemoteAi: { granted: false } },
    policy: TEST_CONSENT_POLICY
  });

  assert.deepEqual(onlyTransactionWrite(harness), {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: false,
      noticeVersion: "standard-v1",
      decidedAt: harness.serverTimestamp
    },
    experimentalTraining: {
      granted: false,
      noticeVersion: "experimental-v1",
      decidedAt: harness.serverTimestamp
    },
    updatedAt: harness.serverTimestamp
  });
  assert.equal(harness.calls.serverTimestamps, 1);
});

test("experimental grant requires effective current standard inside transaction", async () => {
  for (const existing of [
    persistedConsentRecord({ standardGranted: false }),
    persistedConsentRecord({ standardNoticeVersion: "standard-v0" })
  ]) {
    const deniedHarness = firestoreStoreHarness({
      snapshot: firestoreSnapshot(existing)
    });
    const error = await captureRejection(
      deniedHarness.store.mutate({
        firebaseUid: "trusted-server-uid",
        mutation: { experimentalTraining: { granted: true } },
        policy: TEST_CONSENT_POLICY
      })
    );
    assert.equal(error instanceof ValidationError, true);
    assert.equal(error.code, "VALIDATION_ERROR");
    assert.deepEqual(deniedHarness.calls.transactionSets, []);
    assert.equal(deniedHarness.calls.runTransactions, 1);
  }

  const allowedHarness = firestoreStoreHarness({
    snapshot: firestoreSnapshot(
      persistedConsentRecord({ standardGranted: false })
    )
  });
  await allowedHarness.store.mutate({
    firebaseUid: "trusted-server-uid",
    mutation: {
      standardRemoteAi: { granted: true },
      experimentalTraining: { granted: true }
    },
    policy: TEST_CONSENT_POLICY
  });
  const written = onlyTransactionWrite(allowedHarness);
  assert.equal(written.standardRemoteAi.granted, true);
  assert.equal(written.experimentalTraining.granted, true);
  assert.equal(written.standardRemoteAi.decidedAt, allowedHarness.serverTimestamp);
  assert.equal(
    written.experimentalTraining.decidedAt,
    allowedHarness.serverTimestamp
  );
});

test("experimental withdrawal preserves a valid standard decision", async () => {
  const existing = persistedConsentRecord({ experimentalGranted: true });
  const harness = firestoreStoreHarness({ snapshot: firestoreSnapshot(existing) });

  await harness.store.mutate({
    firebaseUid: "trusted-server-uid",
    mutation: { experimentalTraining: { granted: false } },
    policy: TEST_CONSENT_POLICY
  });

  assert.deepEqual(onlyTransactionWrite(harness), {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: true,
      noticeVersion: "standard-v1",
      decidedAt: existing.standardRemoteAi.decidedAt
    },
    experimentalTraining: {
      granted: false,
      noticeVersion: "experimental-v1",
      decidedAt: harness.serverTimestamp
    },
    updatedAt: harness.serverTimestamp
  });
});

test("missing record initializes both decisions false before applying mutation", async () => {
  const harness = firestoreStoreHarness();

  await harness.store.mutate({
    firebaseUid: "trusted-server-uid",
    mutation: { experimentalTraining: { granted: false } },
    policy: TEST_CONSENT_POLICY
  });

  assert.deepEqual(onlyTransactionWrite(harness), {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: false,
      noticeVersion: "standard-v1",
      decidedAt: harness.serverTimestamp
    },
    experimentalTraining: {
      granted: false,
      noticeVersion: "experimental-v1",
      decidedAt: harness.serverTimestamp
    },
    updatedAt: harness.serverTimestamp
  });
  assert.equal(harness.calls.serverTimestamps, 1);
});

test("withdrawal repairs malformed state to both decisions false", async () => {
  const malformed = {
    ...persistedConsentRecord({ experimentalGranted: true }),
    legacyUid: "firebase-uid-marker"
  };
  const harness = firestoreStoreHarness({
    snapshot: firestoreSnapshot(malformed)
  });

  await harness.store.mutate({
    firebaseUid: "trusted-server-uid",
    mutation: { experimentalTraining: { granted: false } },
    policy: TEST_CONSENT_POLICY
  });

  const written = onlyTransactionWrite(harness);
  assert.equal(written.standardRemoteAi.granted, false);
  assert.equal(written.experimentalTraining.granted, false);
  assert.equal(written.standardRemoteAi.decidedAt, harness.serverTimestamp);
  assert.equal(written.experimentalTraining.decidedAt, harness.serverTimestamp);
  assert.equal(Object.hasOwn(written, "legacyUid"), false);
});

test("positive mutation against malformed state fails without transaction.set", async () => {
  const malformed = {
    ...persistedConsentRecord(),
    documentPath: "remote_ai_consents_v1/firebase-uid-marker"
  };
  const harness = firestoreStoreHarness({
    snapshot: firestoreSnapshot(malformed)
  });

  const error = await captureRejection(
    harness.store.mutate({
      firebaseUid: "trusted-server-uid",
      mutation: { standardRemoteAi: { granted: true } },
      policy: TEST_CONSENT_POLICY
    })
  );

  assertRedactedConsentError(error, "CONSENT_AUTHORITY_INVALID_RECORD");
  assert.deepEqual(harness.calls.transactionSets, []);
  assert.equal(harness.calls.runTransactions, 1);
});

test("successful mutation replaces the complete document without unknown fields or merge", async () => {
  const malformed = {
    ...persistedConsentRecord(),
    unknownLegacyField: "raw-store-marker"
  };
  const harness = firestoreStoreHarness({
    snapshot: firestoreSnapshot(malformed)
  });

  await harness.store.mutate({
    firebaseUid: "trusted-server-uid",
    mutation: { standardRemoteAi: { granted: false } },
    policy: TEST_CONSENT_POLICY
  });

  const written = onlyTransactionWrite(harness);
  assert.deepEqual(Object.keys(written).sort(), [
    "experimentalTraining",
    "schemaVersion",
    "standardRemoteAi",
    "updatedAt"
  ]);
  assert.deepEqual(Object.keys(written.standardRemoteAi).sort(), [
    "decidedAt",
    "granted",
    "noticeVersion"
  ]);
  assert.deepEqual(Object.keys(written.experimentalTraining).sort(), [
    "decidedAt",
    "granted",
    "noticeVersion"
  ]);
  assert.equal(JSON.stringify(written).includes("raw-store-marker"), false);
});

test("delete commits only server UID document and remains idempotent", async () => {
  const harness = firestoreStoreHarness();

  await harness.store.delete({
    firebaseUid: "trusted-server-uid",
    documentPath: "client-selected/path"
  });
  await harness.store.delete({ firebaseUid: "trusted-server-uid" });

  assert.deepEqual(harness.calls.collections, [
    "remote_ai_consents_v1",
    "remote_ai_consents_v1"
  ]);
  assert.deepEqual(harness.calls.documents, [
    TRUSTED_SERVER_DOCUMENT_ID,
    TRUSTED_SERVER_DOCUMENT_ID
  ]);
  assert.equal(harness.calls.deletes, 2);
  assert.equal(harness.calls.runTransactions, 0);
  assert.deepEqual(harness.calls.app, [{ projectId: "fitdesi-ai" }]);
  assert.deepEqual(harness.calls.getFirestore, [harness.app]);
});

test("Firestore DEADLINE_EXCEEDED maps to timeout without application retry", async () => {
  for (const code of [4, "4", "DEADLINE_EXCEEDED", "deadline-exceeded"]) {
    const rawError = Object.assign(
      new Error("raw-store-marker firebase-uid-marker"),
      { code }
    );
    const harness = firestoreStoreHarness({ readError: rawError });
    const error = await captureRejection(
      harness.store.read({ firebaseUid: "firebase-uid-marker" })
    );
    assertRedactedConsentError(error, "CONSENT_AUTHORITY_TIMEOUT");
    assert.equal(harness.calls.reads, 1);
  }

  const transactionError = Object.assign(new Error("raw-store-marker"), {
    code: "DEADLINE_EXCEEDED"
  });
  const harness = firestoreStoreHarness({ transactionError });
  assertRedactedConsentError(
    await captureRejection(
      harness.store.mutate({
        firebaseUid: "firebase-uid-marker",
        mutation: { standardRemoteAi: { granted: false } },
        policy: TEST_CONSENT_POLICY
      })
    ),
    "CONSENT_AUTHORITY_TIMEOUT"
  );
  assert.equal(harness.calls.runTransactions, 1);
});

test("other Firestore initialization and operation failures map to unavailable", async () => {
  const cases = [
    {
      label: "Admin app initialization",
      harness: firestoreStoreHarness({
        appError: new Error("raw-store-marker fitdesi-ai-production")
      }),
      operation(store) {
        return store.read({ firebaseUid: "firebase-uid-marker" });
      }
    },
    {
      label: "Firestore initialization",
      harness: firestoreStoreHarness({
        initializationError: new Error("raw-store-marker fitdesi-ai-production")
      }),
      operation(store) {
        return store.read({ firebaseUid: "firebase-uid-marker" });
      }
    },
    {
      label: "document read",
      harness: firestoreStoreHarness({
        readError: new Error("raw-store-marker firebase-uid-marker")
      }),
      operation(store) {
        return store.read({ firebaseUid: "firebase-uid-marker" });
      }
    },
    {
      label: "transaction get",
      harness: firestoreStoreHarness({
        transactionGetError: new Error("raw-store-marker firebase-uid-marker")
      }),
      operation(store) {
        return store.mutate({
          firebaseUid: "firebase-uid-marker",
          mutation: { standardRemoteAi: { granted: false } },
          policy: TEST_CONSENT_POLICY
        });
      }
    },
    {
      label: "document delete",
      harness: firestoreStoreHarness({
        deleteError: new Error("raw-store-marker firebase-uid-marker")
      }),
      operation(store) {
        return store.delete({ firebaseUid: "firebase-uid-marker" });
      }
    }
  ];

  for (const { label, harness, operation } of cases) {
    assertRedactedConsentError(
      await captureRejection(operation(harness.store)),
      "CONSENT_AUTHORITY_UNAVAILABLE",
      label
    );
  }
});
