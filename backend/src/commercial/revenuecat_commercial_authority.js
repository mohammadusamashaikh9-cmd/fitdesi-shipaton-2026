import {
  CommercialAuthorityConfigurationError,
  CommercialAuthorityInvalidResponseError,
  CommercialAuthorityRateLimitError,
  CommercialAuthorityTimeoutError,
  CommercialAuthorityUnauthorizedError,
  CommercialAuthorityUnavailableError
} from "../errors.js";

const REVENUECAT_V2_BASE_URL = "https://api.revenuecat.com/v2";
const MAX_REVENUECAT_CUSTOMER_ID_LENGTH = 1500;

export const CommercialTier = Object.freeze({
  BASIC: "BASIC",
  PLUS: "PLUS",
  PRO: "PRO"
});

function commercialState(tier = CommercialTier.BASIC, boostActive = false) {
  return Object.freeze({ tier, boostActive });
}

export function revenueCatCustomerIdForFirebaseUid(firebaseUid) {
  if (
    typeof firebaseUid !== "string" ||
    firebaseUid.length === 0 ||
    firebaseUid.length > MAX_REVENUECAT_CUSTOMER_ID_LENGTH - 3
  ) {
    throw new CommercialAuthorityUnavailableError();
  }
  return `fd_${firebaseUid}`;
}

function requireRevenueCatConfiguration(config) {
  const entitlementIds = [
    config?.revenueCatPlusEntitlementId,
    config?.revenueCatProEntitlementId,
    config?.revenueCatBoostEntitlementId
  ];
  const requiredValues = [
    config?.revenueCatV2SecretKey,
    config?.revenueCatProjectId,
    ...entitlementIds
  ];
  if (requiredValues.some((value) => typeof value !== "string" || value.trim().length === 0)) {
    throw new CommercialAuthorityConfigurationError();
  }
  if (new Set(entitlementIds).size !== entitlementIds.length) {
    throw new CommercialAuthorityConfigurationError();
  }
  if (!Number.isInteger(config.revenueCatTimeoutMs) || config.revenueCatTimeoutMs <= 0) {
    throw new CommercialAuthorityConfigurationError();
  }
}

function retryAfterSeconds(response) {
  const raw = response?.headers?.get?.("retry-after");
  if (typeof raw !== "string" || !/^\d+$/.test(raw)) return 30;
  return Number.parseInt(raw, 10);
}

function activeEntitlementIds(payload) {
  if (
    !payload ||
    typeof payload !== "object" ||
    Array.isArray(payload) ||
    payload.object !== "list" ||
    !Array.isArray(payload.items) ||
    (payload.next_page !== undefined && payload.next_page !== null)
  ) {
    throw new CommercialAuthorityInvalidResponseError();
  }
  const identifiers = new Set();
  for (const item of payload.items) {
    if (
      !item ||
      typeof item !== "object" ||
      Array.isArray(item) ||
      item.object !== "customer.active_entitlement" ||
      typeof item.entitlement_id !== "string" ||
      item.entitlement_id.length === 0
    ) {
      throw new CommercialAuthorityInvalidResponseError();
    }
    identifiers.add(item.entitlement_id);
  }
  return identifiers;
}

function normalizeCommercialState(entitlementIds, config) {
  const tier = entitlementIds.has(config.revenueCatProEntitlementId)
    ? CommercialTier.PRO
    : entitlementIds.has(config.revenueCatPlusEntitlementId)
      ? CommercialTier.PLUS
      : CommercialTier.BASIC;
  return commercialState(
    tier,
    entitlementIds.has(config.revenueCatBoostEntitlementId)
  );
}

export class RevenueCatCommercialAuthority {
  #config;
  #fetchImpl;

  constructor({ config, fetchImpl = globalThis.fetch }) {
    this.#config = config;
    this.#fetchImpl = fetchImpl;
  }

  async getCommercialState({ firebaseUid }) {
    requireRevenueCatConfiguration(this.#config);
    if (typeof this.#fetchImpl !== "function") {
      throw new CommercialAuthorityUnavailableError();
    }

    const customerId = revenueCatCustomerIdForFirebaseUid(firebaseUid);
    const projectId = encodeURIComponent(this.#config.revenueCatProjectId);
    const encodedCustomerId = encodeURIComponent(customerId);
    const url = `${REVENUECAT_V2_BASE_URL}/projects/${projectId}/customers/` +
      `${encodedCustomerId}/active_entitlements?limit=100`;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.#config.revenueCatTimeoutMs);

    try {
      let response;
      try {
        response = await this.#fetchImpl(url, {
          method: "GET",
          headers: {
            accept: "application/json",
            authorization: `Bearer ${this.#config.revenueCatV2SecretKey}`
          },
          signal: controller.signal
        });
      } catch {
        throw controller.signal.aborted
          ? new CommercialAuthorityTimeoutError()
          : new CommercialAuthorityUnavailableError();
      }

      if (controller.signal.aborted) throw new CommercialAuthorityTimeoutError();
      if (!response || !Number.isInteger(response.status)) {
        throw new CommercialAuthorityInvalidResponseError();
      }
      if (response.status === 404) return commercialState();
      if (response.status === 401 || response.status === 403) {
        throw new CommercialAuthorityUnauthorizedError();
      }
      if (response.status === 429) {
        throw new CommercialAuthorityRateLimitError(retryAfterSeconds(response));
      }
      if (response.status >= 500 && response.status <= 599) {
        throw new CommercialAuthorityUnavailableError();
      }
      if (response.status !== 200) throw new CommercialAuthorityUnavailableError();

      let payload;
      try {
        payload = await response.json();
      } catch {
        throw controller.signal.aborted
          ? new CommercialAuthorityTimeoutError()
          : new CommercialAuthorityInvalidResponseError();
      }
      if (controller.signal.aborted) throw new CommercialAuthorityTimeoutError();
      return normalizeCommercialState(activeEntitlementIds(payload), this.#config);
    } finally {
      clearTimeout(timeout);
    }
  }
}
