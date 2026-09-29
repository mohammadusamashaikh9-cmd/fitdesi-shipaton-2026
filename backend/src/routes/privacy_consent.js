import { ValidationError } from "../errors.js";

const CONSENT_SCHEMA_VERSION = 1;
const NOTICE_VERSION_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const MUTATION_KEYS = Object.freeze([
  "experimentalTraining",
  "standardRemoteAi"
]);
const MUTATION_DECISION_KEYS = Object.freeze([
  "granted",
  "noticeVersion"
]);
const STATE_KEYS = Object.freeze([
  "experimentalTraining",
  "requiredNoticeVersions",
  "schemaVersion",
  "standardRemoteAi",
  "updatedAt"
]);
const STATE_DECISION_KEYS = Object.freeze([
  "current",
  "decidedAt",
  "granted",
  "noticeVersion"
]);
const REQUIRED_NOTICE_KEYS = Object.freeze([
  "experimentalTraining",
  "standardRemoteAi"
]);

function invalidConsentRequest() {
  return new ValidationError("Consent request is invalid.");
}

function isPlainJsonObject(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function hasExactKeys(value, expectedKeys) {
  const keys = Object.keys(value).sort();
  return keys.length === expectedKeys.length &&
    keys.every((key, index) => key === expectedKeys[index]);
}

function isNoticeVersion(value) {
  return typeof value === "string" &&
    value === value.trim() &&
    value.length >= 1 &&
    value.length <= 128 &&
    NOTICE_VERSION_PATTERN.test(value);
}

function validateMutationDecision(value) {
  if (!isPlainJsonObject(value)) throw invalidConsentRequest();
  const keys = Object.keys(value);
  if (
    !Object.prototype.hasOwnProperty.call(value, "granted") ||
    keys.length < 1 ||
    keys.length > 2 ||
    keys.some((key) => !MUTATION_DECISION_KEYS.includes(key)) ||
    typeof value.granted !== "boolean"
  ) {
    throw invalidConsentRequest();
  }

  const hasNoticeVersion = Object.prototype.hasOwnProperty.call(
    value,
    "noticeVersion"
  );
  if (hasNoticeVersion && !isNoticeVersion(value.noticeVersion)) {
    throw invalidConsentRequest();
  }
  if (value.granted === true && !hasNoticeVersion) {
    throw invalidConsentRequest();
  }

  return hasNoticeVersion
    ? { granted: value.granted, noticeVersion: value.noticeVersion }
    : { granted: false };
}

export function validateConsentMutationRequest(body) {
  try {
    if (!isPlainJsonObject(body)) throw invalidConsentRequest();
    const keys = Object.keys(body);
    if (
      keys.length < 1 ||
      keys.length > 2 ||
      keys.some((key) => !MUTATION_KEYS.includes(key))
    ) {
      throw invalidConsentRequest();
    }

    const mutation = {};
    if (Object.prototype.hasOwnProperty.call(body, "standardRemoteAi")) {
      mutation.standardRemoteAi = validateMutationDecision(
        body.standardRemoteAi
      );
    }
    if (Object.prototype.hasOwnProperty.call(body, "experimentalTraining")) {
      mutation.experimentalTraining = validateMutationDecision(
        body.experimentalTraining
      );
    }
    if (
      mutation.standardRemoteAi?.granted === false &&
      mutation.experimentalTraining?.granted === true
    ) {
      throw invalidConsentRequest();
    }
    return mutation;
  } catch (error) {
    if (error instanceof ValidationError) throw error;
    throw invalidConsentRequest();
  }
}

export function assertNoConsentRequestBody(request) {
  try {
    const contentLength = request?.headers?.["content-length"];
    const transferEncoding = request?.headers?.["transfer-encoding"];
    if (transferEncoding !== undefined) throw invalidConsentRequest();
    if (contentLength === undefined) return;
    if (
      typeof contentLength !== "string" ||
      !/^\d+$/.test(contentLength) ||
      BigInt(contentLength) !== 0n
    ) {
      throw invalidConsentRequest();
    }
  } catch (error) {
    if (error instanceof ValidationError) throw error;
    throw invalidConsentRequest();
  }
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

function isNormalizedDecision(value) {
  if (
    !isPlainJsonObject(value) ||
    !hasExactKeys(value, STATE_DECISION_KEYS) ||
    typeof value.granted !== "boolean" ||
    typeof value.current !== "boolean"
  ) {
    return false;
  }
  const missingDecision = value.noticeVersion === null && value.decidedAt === null;
  const storedDecision = typeof value.noticeVersion === "string" &&
    value.noticeVersion.length > 0 &&
    isCanonicalIsoUtc(value.decidedAt);
  if (!missingDecision && !storedDecision) return false;
  if (missingDecision && (value.granted || value.current)) return false;
  if (value.current && !value.granted) return false;
  return true;
}

export function validateConsentStateResponse(data) {
  try {
    if (
      !isPlainJsonObject(data) ||
      !hasExactKeys(data, STATE_KEYS) ||
      data.schemaVersion !== CONSENT_SCHEMA_VERSION ||
      !isNormalizedDecision(data.standardRemoteAi) ||
      !isNormalizedDecision(data.experimentalTraining) ||
      !isPlainJsonObject(data.requiredNoticeVersions) ||
      !hasExactKeys(data.requiredNoticeVersions, REQUIRED_NOTICE_KEYS) ||
      !isNoticeVersion(data.requiredNoticeVersions.standardRemoteAi) ||
      !isNoticeVersion(data.requiredNoticeVersions.experimentalTraining) ||
      (
        data.experimentalTraining.granted === true &&
        data.standardRemoteAi.granted !== true
      )
    ) {
      return false;
    }

    const standardCurrent = data.standardRemoteAi.granted === true &&
      data.standardRemoteAi.noticeVersion ===
        data.requiredNoticeVersions.standardRemoteAi;
    const experimentalCurrent = data.experimentalTraining.granted === true &&
      data.experimentalTraining.noticeVersion ===
        data.requiredNoticeVersions.experimentalTraining &&
      standardCurrent;
    if (
      data.standardRemoteAi.current !== standardCurrent ||
      data.experimentalTraining.current !== experimentalCurrent
    ) {
      return false;
    }

    if (data.updatedAt === null) {
      return data.standardRemoteAi.noticeVersion === null &&
        data.experimentalTraining.noticeVersion === null;
    }
    return isCanonicalIsoUtc(data.updatedAt) &&
      data.standardRemoteAi.noticeVersion !== null &&
      data.experimentalTraining.noticeVersion !== null;
  } catch {
    return false;
  }
}

export function createPrivacyConsentRoutes({ authority }) {
  return Object.freeze({
    async get({ firebaseUid }) {
      return authority.getConsentState({ firebaseUid });
    },
    async put(body, { firebaseUid }) {
      const mutation = validateConsentMutationRequest(body);
      await authority.updateConsent({ firebaseUid, mutation });
    },
    async delete({ firebaseUid }) {
      await authority.deleteConsent({ firebaseUid });
    }
  });
}
