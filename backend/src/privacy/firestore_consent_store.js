import { createHash } from "node:crypto";
import {
  FieldValue,
  getFirestore,
  Timestamp
} from "firebase-admin/firestore";
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
import { firebaseAdminAppForProcess } from "../firebase/firebase_admin_app.js";
import {
  CONSENT_SCHEMA_VERSION,
  validateStoredConsentRecord
} from "./privacy_consent_authority.js";

const CONSENT_COLLECTION = "remote_ai_consents_v1";
const INVALID_CONSENT_MUTATION_MESSAGE = "Consent mutation is invalid.";
const FIRESTORE_DEADLINE_CODES = new Set([
  4,
  "4",
  "DEADLINE_EXCEEDED",
  "deadline-exceeded"
]);

function documentIdForFirebaseUid(firebaseUid) {
  if (
    typeof firebaseUid !== "string" ||
    firebaseUid.length < 1 ||
    firebaseUid.length > 128
  ) {
    throw new ConsentAuthorityUnavailableError();
  }
  return `uid_${createHash("sha256")
    .update(firebaseUid, "utf8")
    .digest("hex")}`;
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

function mapFirestoreError(error) {
  if (error instanceof ValidationError || isStableConsentError(error)) {
    return error;
  }
  try {
    if (FIRESTORE_DEADLINE_CODES.has(error?.code)) {
      return new ConsentAuthorityTimeoutError();
    }
  } catch {
    return new ConsentAuthorityUnavailableError();
  }
  return new ConsentAuthorityUnavailableError();
}

async function performFirestoreOperation(operation) {
  try {
    return await operation();
  } catch (error) {
    throw mapFirestoreError(error);
  }
}

function timestampIso(value) {
  if (!(value instanceof Timestamp)) {
    throw new ConsentAuthorityInvalidRecordError();
  }
  try {
    return value.toDate().toISOString();
  } catch {
    throw new ConsentAuthorityInvalidRecordError();
  }
}

function decodeStoredRecord(record) {
  try {
    const decoded = {
      ...record,
      standardRemoteAi: {
        ...record.standardRemoteAi,
        decidedAt: timestampIso(record.standardRemoteAi.decidedAt)
      },
      experimentalTraining: {
        ...record.experimentalTraining,
        decidedAt: timestampIso(record.experimentalTraining.decidedAt)
      },
      updatedAt: timestampIso(record.updatedAt)
    };
    const validated = validateStoredConsentRecord(decoded);
    return {
      validated,
      writable: {
        schemaVersion: CONSENT_SCHEMA_VERSION,
        standardRemoteAi: {
          granted: validated.standardRemoteAi.granted,
          noticeVersion: validated.standardRemoteAi.noticeVersion,
          decidedAt: record.standardRemoteAi.decidedAt
        },
        experimentalTraining: {
          granted: validated.experimentalTraining.granted,
          noticeVersion: validated.experimentalTraining.noticeVersion,
          decidedAt: record.experimentalTraining.decidedAt
        },
        updatedAt: record.updatedAt
      }
    };
  } catch (error) {
    if (error instanceof ConsentAuthorityInvalidRecordError) throw error;
    throw new ConsentAuthorityInvalidRecordError();
  }
}

function hasPositiveDecision(mutation) {
  return mutation?.standardRemoteAi?.granted === true ||
    mutation?.experimentalTraining?.granted === true;
}

function hasWithdrawalDecision(mutation) {
  return mutation?.standardRemoteAi?.granted === false ||
    mutation?.experimentalTraining?.granted === false;
}

function falseConsentRecord(policy, timestamp) {
  return {
    schemaVersion: CONSENT_SCHEMA_VERSION,
    standardRemoteAi: {
      granted: false,
      noticeVersion: policy.standardRemoteAi,
      decidedAt: timestamp
    },
    experimentalTraining: {
      granted: false,
      noticeVersion: policy.experimentalTraining,
      decidedAt: timestamp
    },
    updatedAt: timestamp
  };
}

function applyMutation(record, mutation, policy, timestamp) {
  if (mutation.standardRemoteAi) {
    if (mutation.standardRemoteAi.granted === true) {
      record.standardRemoteAi = {
        granted: true,
        noticeVersion: policy.standardRemoteAi,
        decidedAt: timestamp
      };
    } else {
      record.standardRemoteAi = {
        granted: false,
        noticeVersion: policy.standardRemoteAi,
        decidedAt: timestamp
      };
      record.experimentalTraining = {
        granted: false,
        noticeVersion: policy.experimentalTraining,
        decidedAt: timestamp
      };
    }
  }

  if (mutation.experimentalTraining) {
    if (mutation.experimentalTraining.granted === true) {
      const currentStandard = record.standardRemoteAi.granted === true &&
        record.standardRemoteAi.noticeVersion === policy.standardRemoteAi;
      if (!currentStandard) {
        throw new ValidationError(INVALID_CONSENT_MUTATION_MESSAGE);
      }
      record.experimentalTraining = {
        granted: true,
        noticeVersion: policy.experimentalTraining,
        decidedAt: timestamp
      };
    } else {
      record.experimentalTraining = {
        granted: false,
        noticeVersion: policy.experimentalTraining,
        decidedAt: timestamp
      };
    }
  }

  record.updatedAt = timestamp;
  return record;
}

export class FirestoreConsentStore {
  #firebaseAdminAppProvider;
  #firestore = null;
  #getFirestoreImpl;
  #projectId;
  #serverTimestampImpl;

  constructor({
    projectId,
    firebaseAdminAppProvider = firebaseAdminAppForProcess,
    getFirestoreImpl = getFirestore,
    serverTimestampImpl = () => FieldValue.serverTimestamp()
  }) {
    this.#projectId = projectId;
    this.#firebaseAdminAppProvider = firebaseAdminAppProvider;
    this.#getFirestoreImpl = getFirestoreImpl;
    this.#serverTimestampImpl = serverTimestampImpl;
  }

  #firestoreForOperation() {
    if (this.#firestore !== null) return this.#firestore;
    const app = this.#firebaseAdminAppProvider.getApp({
      projectId: this.#projectId
    });
    const firestore = this.#getFirestoreImpl(app);
    if (!firestore || typeof firestore.collection !== "function") {
      throw new ConsentAuthorityUnavailableError();
    }
    this.#firestore = firestore;
    return firestore;
  }

  #documentReference(firebaseUid) {
    const documentId = documentIdForFirebaseUid(firebaseUid);
    return this.#firestoreForOperation()
      .collection(CONSENT_COLLECTION)
      .doc(documentId);
  }

  async read({ firebaseUid }) {
    return performFirestoreOperation(async () => {
      const snapshot = await this.#documentReference(firebaseUid).get();
      if (snapshot.exists === false) return null;
      return decodeStoredRecord(snapshot.data()).validated;
    });
  }

  async mutate({ firebaseUid, mutation, policy }) {
    return performFirestoreOperation(async () => {
      const reference = this.#documentReference(firebaseUid);
      const firestore = this.#firestoreForOperation();
      await firestore.runTransaction(async (transaction) => {
        const snapshot = await transaction.get(reference);
        let existing = null;
        let malformed = false;

        if (snapshot.exists !== false) {
          try {
            existing = decodeStoredRecord(snapshot.data()).writable;
          } catch (error) {
            if (!(error instanceof ConsentAuthorityInvalidRecordError)) {
              throw error;
            }
            malformed = true;
          }
        }

        if (malformed && hasPositiveDecision(mutation)) {
          throw new ConsentAuthorityInvalidRecordError();
        }
        if (malformed && !hasWithdrawalDecision(mutation)) {
          throw new ConsentAuthorityInvalidRecordError();
        }

        const timestamp = this.#serverTimestampImpl();
        const base = existing ?? falseConsentRecord(policy, timestamp);
        const completeRecord = malformed
          ? falseConsentRecord(policy, timestamp)
          : applyMutation(base, mutation, policy, timestamp);
        transaction.set(reference, completeRecord);
      });
    });
  }

  async delete({ firebaseUid }) {
    return performFirestoreOperation(async () => {
      await this.#documentReference(firebaseUid).delete();
    });
  }
}
