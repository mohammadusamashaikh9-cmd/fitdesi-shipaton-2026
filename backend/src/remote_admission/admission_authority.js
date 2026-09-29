import {
  RemoteAdmissionUnavailableError,
  RemoteCapabilityNotLaunchedError,
  RemoteCapabilityUnavailableError
} from "../errors.js";
import { CommercialTier } from "../commercial/revenuecat_commercial_authority.js";
import { RemoteCapabilityDecision } from "./capability_policy.js";

const KNOWN_COMMERCIAL_TIERS = new Set(Object.values(CommercialTier));

function validAuthority(authority, methodName) {
  return authority !== null &&
    typeof authority === "object" &&
    typeof authority[methodName] === "function";
}

function requireFirebaseUid(firebaseUid) {
  if (typeof firebaseUid !== "string" || firebaseUid.length === 0) {
    throw new RemoteAdmissionUnavailableError();
  }
  return firebaseUid;
}

function requireEligibleDecision(result) {
  if (result?.decision === RemoteCapabilityDecision.NOT_LAUNCHED) {
    throw new RemoteCapabilityNotLaunchedError();
  }
  if (
    result?.decision === RemoteCapabilityDecision.TIER_REQUIRED ||
    result?.decision === RemoteCapabilityDecision.UNKNOWN_CAPABILITY
  ) {
    throw new RemoteCapabilityUnavailableError();
  }
  if (
    result?.decision !== RemoteCapabilityDecision.ELIGIBLE ||
    result.eligible !== true ||
    typeof result.capability !== "string" ||
    typeof result.accessBasis !== "string"
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  return result;
}

function requireCommercialTier(commercialState) {
  if (!KNOWN_COMMERCIAL_TIERS.has(commercialState?.tier)) {
    throw new RemoteAdmissionUnavailableError();
  }
  return commercialState.tier;
}

export class RemoteAdmissionAuthority {
  #capabilityPolicy;
  #commercialAuthority;
  #consentAuthority;

  constructor({ commercialAuthority, consentAuthority, capabilityPolicy } = {}) {
    if (
      !validAuthority(commercialAuthority, "getCommercialState") ||
      !validAuthority(consentAuthority, "authorizeStandardRemoteProcessing") ||
      !validAuthority(capabilityPolicy, "evaluate")
    ) {
      throw new RemoteAdmissionUnavailableError();
    }
    this.#commercialAuthority = commercialAuthority;
    this.#consentAuthority = consentAuthority;
    this.#capabilityPolicy = capabilityPolicy;
  }

  async authorize({ firebaseUid, capability } = {}) {
    const trustedFirebaseUid = requireFirebaseUid(firebaseUid);
    const commercialState = await this.#commercialAuthority.getCommercialState({
      firebaseUid: trustedFirebaseUid
    });
    const commercialTier = requireCommercialTier(commercialState);
    const capabilityDecision = requireEligibleDecision(this.#capabilityPolicy.evaluate({
      capability,
      commercialState
    }));
    await this.#consentAuthority.authorizeStandardRemoteProcessing({
      firebaseUid: trustedFirebaseUid
    });
    return Object.freeze({
      capability: capabilityDecision.capability,
      accessBasis: capabilityDecision.accessBasis,
      commercialTier
    });
  }
}
