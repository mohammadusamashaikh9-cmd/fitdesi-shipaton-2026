import {
  ConsentAuthorityConfigurationError,
  ConsentAuthorityInvalidRecordError,
  ConsentAuthorityTimeoutError,
  ConsentAuthorityUnavailableError,
  ConsentNoticeVersionMismatchError,
  ExperimentalAiConsentRequiredError,
  RemoteAiConsentRequiredError,
  ValidationError
} from "../errors.js";

export const CONSENT_SCHEMA_VERSION = 1;

const NOTICE_VERSION_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const STORED_RECORD_KEYS = Object.freeze([
  "experimentalTraining",
  "schemaVersion",
  "standardRemoteAi",
  "updatedAt"
]);
const STORED_DECISION_KEYS = Object.freeze([
  "decidedAt",
  "granted",
  "noticeVersion"
]);
const CONSENT_MUTATION_KEYS = Object.freeze([
  "experimentalTraining",
  "standardRemoteAi"
]);
const CONSENT_DECISION_KEYS = Object.freeze([
  "granted",
  "noticeVersion"
]);
const INVALID_CONSENT_MUTATION_MESSAGE = "Consent mutation is invalid.";

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function hasExactKeys(value, expectedKeys) {
  const actualKeys = Object.keys(value).sort();
  return actualKeys.length === expectedKeys.length &&
    actualKeys.every((key, index) => key === expectedKeys[index]);
}

function isCanonicalIsoUtc(value) {
  if (typeof value !== "string") return false;
  try {
    const parsed = new Date(value);
    return Number.isFinite(parsed.getTime()) && parsed.toISOString() === value;
  } catch {
    return false;
  }
}

function isStoredDecision(value) {
  return isObject(value) &&
    hasExactKeys(value, STORED_DECISION_KEYS) &&
    typeof value.granted === "boolean" &&
    typeof value.noticeVersion === "string" &&
    value.noticeVersion.length > 0 &&
    isCanonicalIsoUtc(value.decidedAt);
}

function isConfiguredNoticeVersion(value) {
  return typeof value === "string" &&
    value === value.trim() &&
    value.length >= 1 &&
    value.length <= 128 &&
    NOTICE_VERSION_PATTERN.test(value);
}

function requireConfiguredPolicy(policy) {
  try {
    if (
      !isConfiguredNoticeVersion(policy?.standardRemoteAi) ||
      !isConfiguredNoticeVersion(policy?.experimentalTraining)
    ) {
      throw new ConsentAuthorityConfigurationError();
    }
  } catch (error) {
    if (error instanceof ConsentAuthorityConfigurationError) throw error;
    throw new ConsentAuthorityConfigurationError();
  }
}

function requireFirebaseUid(input) {
  let firebaseUid;
  try {
    firebaseUid = input?.firebaseUid;
  } catch {
    throw new ConsentAuthorityUnavailableError();
  }
  if (typeof firebaseUid !== "string" || firebaseUid.length === 0) {
    throw new ConsentAuthorityUnavailableError();
  }
  return firebaseUid;
}

function isStableConsentError(error) {
  return error instanceof ConsentNoticeVersionMismatchError ||
    error instanceof ConsentAuthorityConfigurationError ||
    error instanceof ConsentAuthorityInvalidRecordError ||
    error instanceof ConsentAuthorityTimeoutError ||
    error instanceof ConsentAuthorityUnavailableError ||
    error instanceof RemoteAiConsentRequiredError ||
    error instanceof ExperimentalAiConsentRequiredError;
}

function invalidConsentMutation() {
  return new ValidationError(INVALID_CONSENT_MUTATION_MESSAGE);
}

function validateMutationDecision(decision) {
  if (!isObject(decision)) throw invalidConsentMutation();
  const keys = Object.keys(decision);
  if (
    !Object.prototype.hasOwnProperty.call(decision, "granted") ||
    keys.length < 1 ||
    keys.length > 2 ||
    keys.some((key) => !CONSENT_DECISION_KEYS.includes(key)) ||
    typeof decision.granted !== "boolean"
  ) {
    throw invalidConsentMutation();
  }

  const hasNoticeVersion = Object.prototype.hasOwnProperty.call(
    decision,
    "noticeVersion"
  );
  if (
    hasNoticeVersion &&
    !isConfiguredNoticeVersion(decision.noticeVersion)
  ) {
    throw invalidConsentMutation();
  }

  if (!hasNoticeVersion) {
    if (decision.granted === true) throw invalidConsentMutation();
    return { granted: false };
  }
  return {
    granted: decision.granted,
    noticeVersion: decision.noticeVersion
  };
}

function normalizeMutationDecision(decision, policyNoticeVersion) {
  if (decision.granted === false) return { granted: false };
  if (decision.noticeVersion !== policyNoticeVersion) {
    throw new ConsentNoticeVersionMismatchError();
  }
  return {
    granted: true,
    noticeVersion: policyNoticeVersion
  };
}

function normalizeConsentMutation(mutation, policy) {
  try {
    if (!isObject(mutation)) throw invalidConsentMutation();
    const keys = Object.keys(mutation);
    if (
      keys.length < 1 ||
      keys.length > 2 ||
      keys.some((key) => !CONSENT_MUTATION_KEYS.includes(key))
    ) {
      throw invalidConsentMutation();
    }

    const validated = {};
    if (Object.prototype.hasOwnProperty.call(mutation, "standardRemoteAi")) {
      validated.standardRemoteAi = validateMutationDecision(
        mutation.standardRemoteAi
      );
    }
    if (Object.prototype.hasOwnProperty.call(mutation, "experimentalTraining")) {
      validated.experimentalTraining = validateMutationDecision(
        mutation.experimentalTraining
      );
    }
    if (
      validated.standardRemoteAi?.granted === false &&
      validated.experimentalTraining?.granted === true
    ) {
      throw invalidConsentMutation();
    }

    const normalized = {};
    if (validated.standardRemoteAi) {
      normalized.standardRemoteAi = normalizeMutationDecision(
        validated.standardRemoteAi,
        policy.standardRemoteAi
      );
    }
    if (validated.experimentalTraining) {
      normalized.experimentalTraining = normalizeMutationDecision(
        validated.experimentalTraining,
        policy.experimentalTraining
      );
    }
    return normalized;
  } catch (error) {
    if (
      error instanceof ValidationError ||
      error instanceof ConsentNoticeVersionMismatchError
    ) {
      throw error;
    }
    throw invalidConsentMutation();
  }
}

function policyForStore(policy) {
  return {
    standardRemoteAi: policy.standardRemoteAi,
    experimentalTraining: policy.experimentalTraining
  };
}

async function performConsentStoreOperation(
  operation,
  { preserveValidationError = false } = {}
) {
  try {
    await operation();
  } catch (error) {
    if (
      isStableConsentError(error) ||
      (preserveValidationError && error instanceof ValidationError)
    ) {
      throw error;
    }
    throw new ConsentAuthorityUnavailableError();
  }
}

function normalizedMissingConsent(policy) {
  return {
    schemaVersion: CONSENT_SCHEMA_VERSION,
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
      standardRemoteAi: policy.standardRemoteAi,
      experimentalTraining: policy.experimentalTraining
    },
    updatedAt: null
  };
}

function normalizedStoredConsent(record, policy) {
  const standardCurrent = record.standardRemoteAi.granted === true &&
    record.standardRemoteAi.noticeVersion === policy.standardRemoteAi;
  const experimentalCurrent = record.experimentalTraining.granted === true &&
    record.experimentalTraining.noticeVersion === policy.experimentalTraining &&
    standardCurrent;

  return {
    schemaVersion: CONSENT_SCHEMA_VERSION,
    standardRemoteAi: {
      granted: record.standardRemoteAi.granted,
      current: standardCurrent,
      noticeVersion: record.standardRemoteAi.noticeVersion,
      decidedAt: record.standardRemoteAi.decidedAt
    },
    experimentalTraining: {
      granted: record.experimentalTraining.granted,
      current: experimentalCurrent,
      noticeVersion: record.experimentalTraining.noticeVersion,
      decidedAt: record.experimentalTraining.decidedAt
    },
    requiredNoticeVersions: {
      standardRemoteAi: policy.standardRemoteAi,
      experimentalTraining: policy.experimentalTraining
    },
    updatedAt: record.updatedAt
  };
}

export function consentPolicyFromConfig(config) {
  return Object.freeze({
    standardRemoteAi: config?.remoteAiNoticeVersion,
    experimentalTraining: config?.experimentalAiNoticeVersion
  });
}

export function validateStoredConsentRecord(record) {
  try {
    if (
      !isObject(record) ||
      !hasExactKeys(record, STORED_RECORD_KEYS) ||
      record.schemaVersion !== CONSENT_SCHEMA_VERSION ||
      !isStoredDecision(record.standardRemoteAi) ||
      !isStoredDecision(record.experimentalTraining) ||
      !isCanonicalIsoUtc(record.updatedAt) ||
      (
        record.experimentalTraining.granted === true &&
        record.standardRemoteAi.granted !== true
      )
    ) {
      throw new ConsentAuthorityInvalidRecordError();
    }

    const standardRemoteAi = Object.freeze({
      granted: record.standardRemoteAi.granted,
      noticeVersion: record.standardRemoteAi.noticeVersion,
      decidedAt: record.standardRemoteAi.decidedAt
    });
    const experimentalTraining = Object.freeze({
      granted: record.experimentalTraining.granted,
      noticeVersion: record.experimentalTraining.noticeVersion,
      decidedAt: record.experimentalTraining.decidedAt
    });
    return Object.freeze({
      schemaVersion: CONSENT_SCHEMA_VERSION,
      standardRemoteAi,
      experimentalTraining,
      updatedAt: record.updatedAt
    });
  } catch (error) {
    if (error instanceof ConsentAuthorityInvalidRecordError) throw error;
    throw new ConsentAuthorityInvalidRecordError();
  }
}

export class PrivacyConsentAuthority {
  #policy;
  #store;

  constructor({ store, policy }) {
    this.#store = store;
    this.#policy = policy;
  }

  async getConsentState(input = {}) {
    requireConfiguredPolicy(this.#policy);
    const firebaseUid = requireFirebaseUid(input);
    let record;
    try {
      record = await this.#store.read({ firebaseUid });
    } catch (error) {
      if (isStableConsentError(error)) throw error;
      throw new ConsentAuthorityUnavailableError();
    }

    if (record === null) return normalizedMissingConsent(this.#policy);
    return normalizedStoredConsent(
      validateStoredConsentRecord(record),
      this.#policy
    );
  }

  async authorizeStandardRemoteProcessing(input = {}) {
    const state = await this.getConsentState(input);
    if (state.standardRemoteAi.current !== true) {
      throw new RemoteAiConsentRequiredError();
    }
  }

  async authorizeExperimentalProcessing(input = {}) {
    const state = await this.getConsentState(input);
    if (
      state.standardRemoteAi.current !== true ||
      state.experimentalTraining.current !== true
    ) {
      throw new ExperimentalAiConsentRequiredError();
    }
  }

  async updateConsent(input = {}) {
    const firebaseUid = requireFirebaseUid(input);
    requireConfiguredPolicy(this.#policy);
    let rawMutation;
    try {
      rawMutation = input?.mutation;
    } catch {
      throw invalidConsentMutation();
    }
    const mutation = normalizeConsentMutation(rawMutation, this.#policy);
    const policy = policyForStore(this.#policy);
    await performConsentStoreOperation(() => this.#store.mutate({
      firebaseUid,
      mutation,
      policy
    }), { preserveValidationError: true });
  }

  async deleteConsent(input = {}) {
    const firebaseUid = requireFirebaseUid(input);
    requireConfiguredPolicy(this.#policy);
    await performConsentStoreOperation(() => this.#store.delete({ firebaseUid }));
  }
}
