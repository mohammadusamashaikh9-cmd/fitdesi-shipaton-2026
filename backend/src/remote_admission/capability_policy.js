import { CommercialTier } from "../commercial/revenuecat_commercial_authority.js";

export const RemoteCapability = Object.freeze({
  REMOTE_AI_COACH: "REMOTE_AI_COACH",
  AI_WORKOUT_GENERATION: "AI_WORKOUT_GENERATION",
  AI_PROGRESS_SUMMARIES: "AI_PROGRESS_SUMMARIES",
  ADVANCED_ANALYTICS: "ADVANCED_ANALYTICS"
});

export const RemoteCapabilityDecision = Object.freeze({
  ELIGIBLE: "ELIGIBLE",
  TIER_REQUIRED: "TIER_REQUIRED",
  NOT_LAUNCHED: "NOT_LAUNCHED",
  UNKNOWN_CAPABILITY: "UNKNOWN_CAPABILITY",
  COMMERCIAL_STATE_INVALID: "COMMERCIAL_STATE_INVALID"
});

export const RemoteCapabilityAccessBasis = Object.freeze({
  TIER: "TIER",
  BOOST: "BOOST"
});

const KNOWN_CAPABILITIES = new Set(Object.values(RemoteCapability));
const KNOWN_TIERS = new Set(Object.values(CommercialTier));
const BOOST_CAPABILITIES = new Set([
  RemoteCapability.AI_WORKOUT_GENERATION,
  RemoteCapability.ADVANCED_ANALYTICS
]);

function decision(capability, value, accessBasis = null) {
  return Object.freeze({
    eligible: value === RemoteCapabilityDecision.ELIGIBLE,
    capability,
    decision: value,
    accessBasis
  });
}

function validCommercialState(commercialState) {
  return commercialState !== null &&
    typeof commercialState === "object" &&
    KNOWN_TIERS.has(commercialState.tier) &&
    typeof commercialState.boostActive === "boolean";
}

export function createRemoteCapabilityPolicy({ launchedCapabilities = [] } = {}) {
  const launched = new Set(
    Array.isArray(launchedCapabilities)
      ? launchedCapabilities.filter((capability) => KNOWN_CAPABILITIES.has(capability))
      : []
  );

  return Object.freeze({
    evaluate({ capability, commercialState } = {}) {
      if (!KNOWN_CAPABILITIES.has(capability)) {
        return decision(null, RemoteCapabilityDecision.UNKNOWN_CAPABILITY);
      }
      if (!launched.has(capability)) {
        return decision(capability, RemoteCapabilityDecision.NOT_LAUNCHED);
      }
      if (!validCommercialState(commercialState)) {
        return decision(capability, RemoteCapabilityDecision.COMMERCIAL_STATE_INVALID);
      }
      if (
        commercialState.tier === CommercialTier.PLUS ||
        commercialState.tier === CommercialTier.PRO
      ) {
        return decision(
          capability,
          RemoteCapabilityDecision.ELIGIBLE,
          RemoteCapabilityAccessBasis.TIER
        );
      }
      if (commercialState.boostActive && BOOST_CAPABILITIES.has(capability)) {
        return decision(
          capability,
          RemoteCapabilityDecision.ELIGIBLE,
          RemoteCapabilityAccessBasis.BOOST
        );
      }
      return decision(capability, RemoteCapabilityDecision.TIER_REQUIRED);
    }
  });
}
