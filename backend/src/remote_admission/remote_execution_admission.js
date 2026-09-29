import { CommercialTier } from "../commercial/revenuecat_commercial_authority.js";
import {
  RemoteAdmissionUnavailableError,
  RemoteRequestCompletedError
} from "../errors.js";
import { deriveOpaqueAccountKey } from "./account_identity.js";
import { AdmissionState } from "./admission_contracts.js";
import { deriveRequestIdentity } from "./idempotency.js";

const KNOWN_COMMERCIAL_TIERS = new Set(Object.values(CommercialTier));

function validDependency(value, methods) {
  return value !== null &&
    typeof value === "object" &&
    methods.every((method) => typeof value[method] === "function");
}

function requireAuthorizedResult(result, capability) {
  if (
    result === null ||
    typeof result !== "object" ||
    result.capability !== capability ||
    typeof result.accessBasis !== "string" ||
    !KNOWN_COMMERCIAL_TIERS.has(result.commercialTier)
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  return result;
}

class RemoteExecutionSession {
  #providerAttempts = 0;
  #requestIdentity;
  #settled = false;
  #store;

  constructor({ requestIdentity, store }) {
    this.#requestIdentity = requestIdentity;
    this.#store = store;
    Object.freeze(this);
  }

  #requireActive() {
    if (this.#settled) throw new RemoteRequestCompletedError();
  }

  async beforeProviderAttempt() {
    this.#requireActive();
    const result = await this.#store.recordProviderAttempt({
      requestIdentity: this.#requestIdentity
    });
    if (result?.state !== AdmissionState.DISPATCHED) {
      this.#settled = result?.state === AdmissionState.EXPIRED;
      throw result?.state === AdmissionState.EXPIRED
        ? new RemoteRequestCompletedError()
        : new RemoteAdmissionUnavailableError();
    }
    this.#providerAttempts += 1;
  }

  async succeed() {
    this.#requireActive();
    if (this.#providerAttempts < 1) throw new RemoteAdmissionUnavailableError();
    const result = await this.#store.transition({
      requestIdentity: this.#requestIdentity,
      toState: AdmissionState.SUCCEEDED
    });
    if (result?.state !== AdmissionState.SUCCEEDED) {
      this.#settled = result?.state === AdmissionState.EXPIRED;
      throw result?.state === AdmissionState.EXPIRED
        ? new RemoteRequestCompletedError()
        : new RemoteAdmissionUnavailableError();
    }
    this.#settled = true;
  }

  async fail() {
    this.#requireActive();
    const toState = this.#providerAttempts === 0
      ? AdmissionState.FAILED_PRE_DISPATCH
      : AdmissionState.FAILED_POST_DISPATCH;
    const result = await this.#store.transition({
      requestIdentity: this.#requestIdentity,
      toState
    });
    if (result?.state !== toState) {
      this.#settled = result?.state === AdmissionState.EXPIRED;
      throw result?.state === AdmissionState.EXPIRED
        ? new RemoteRequestCompletedError()
        : new RemoteAdmissionUnavailableError();
    }
    this.#settled = true;
  }
}

export class RemoteExecutionAdmission {
  #admissionAuthority;
  #firebaseProjectId;
  #hmacSecret;
  #quotaPolicy;
  #store;

  constructor({
    admissionAuthority,
    firebaseProjectId,
    hmacSecret,
    quotaPolicy,
    store
  } = {}) {
    if (
      !validDependency(admissionAuthority, ["authorize"]) ||
      !validDependency(quotaPolicy, ["getPolicy"]) ||
      !validDependency(store, ["reserve", "recordProviderAttempt", "transition"])
    ) {
      throw new RemoteAdmissionUnavailableError();
    }
    this.#admissionAuthority = admissionAuthority;
    this.#firebaseProjectId = firebaseProjectId;
    this.#hmacSecret = hmacSecret;
    this.#quotaPolicy = quotaPolicy;
    this.#store = store;
  }

  async begin({ firebaseUid, idempotencyKey, capability, validatedRequest } = {}) {
    const authorized = requireAuthorizedResult(await this.#admissionAuthority.authorize({
      firebaseUid,
      capability
    }), capability);
    const accountKey = deriveOpaqueAccountKey({
      firebaseProjectId: this.#firebaseProjectId,
      firebaseUid,
      hmacSecret: this.#hmacSecret
    });
    const requestIdentity = deriveRequestIdentity({
      accountKey,
      idempotencyKey,
      capability: authorized.capability,
      validatedRequest,
      hmacSecret: this.#hmacSecret
    });
    const policy = this.#quotaPolicy.getPolicy({
      capability: authorized.capability,
      tier: authorized.commercialTier
    });
    const reservation = await this.#store.reserve({
      accountKey,
      requestIdentity,
      capability: authorized.capability,
      policy
    });
    if (reservation?.state !== AdmissionState.RESERVED) {
      throw new RemoteAdmissionUnavailableError();
    }
    return new RemoteExecutionSession({ requestIdentity, store: this.#store });
  }
}
