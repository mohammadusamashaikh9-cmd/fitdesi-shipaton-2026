import { createHmac } from "node:crypto";
import { RemoteAdmissionUnavailableError } from "../errors.js";

const ACCOUNT_KEY_VERSION = "v1";
const MIN_HMAC_SECRET_BYTES = 32;
const MAX_HMAC_SECRET_BYTES = 256;
const MAX_FIREBASE_PROJECT_ID_LENGTH = 128;
const MAX_FIREBASE_UID_LENGTH = 128;
const HMAC_PURPOSE_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;

function requireHmacSecret(hmacSecret) {
  if (
    typeof hmacSecret !== "string" ||
    hmacSecret !== hmacSecret.trim() ||
    /[\u0000-\u001f\u007f]/.test(hmacSecret) ||
    Buffer.byteLength(hmacSecret, "utf8") < MIN_HMAC_SECRET_BYTES ||
    Buffer.byteLength(hmacSecret, "utf8") > MAX_HMAC_SECRET_BYTES
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  return hmacSecret;
}

function requireFirebaseUid(firebaseUid) {
  if (
    typeof firebaseUid !== "string" ||
    firebaseUid.length === 0 ||
    firebaseUid.length > MAX_FIREBASE_UID_LENGTH ||
    /[\u0000-\u001f\u007f]/.test(firebaseUid)
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  return firebaseUid;
}

function requireFirebaseProjectId(firebaseProjectId) {
  if (
    typeof firebaseProjectId !== "string" ||
    firebaseProjectId.length === 0 ||
    firebaseProjectId.length > MAX_FIREBASE_PROJECT_ID_LENGTH ||
    firebaseProjectId !== firebaseProjectId.trim() ||
    !/^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(firebaseProjectId)
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  return firebaseProjectId;
}

export function deriveServerHmac({ hmacSecret, purpose, value }) {
  const secret = requireHmacSecret(hmacSecret);
  if (
    typeof purpose !== "string" ||
    !HMAC_PURPOSE_PATTERN.test(purpose) ||
    typeof value !== "string" ||
    value.length === 0
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  return createHmac("sha256", secret)
    .update(`fitdesi-remote-admission:${ACCOUNT_KEY_VERSION}:${purpose}\0`, "utf8")
    .update(value, "utf8")
    .digest("base64url");
}

export function deriveOpaqueAccountKey({ firebaseProjectId, firebaseUid, hmacSecret } = {}) {
  const projectId = requireFirebaseProjectId(firebaseProjectId);
  const uid = requireFirebaseUid(firebaseUid);
  const digest = deriveServerHmac({
    hmacSecret,
    purpose: "account-key",
    value: `${projectId}\0${uid}`
  });
  return `acct_${ACCOUNT_KEY_VERSION}_${digest}`;
}
