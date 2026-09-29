function integerEnvironment(name, fallback, { min, max, strict = false }) {
  const raw = process.env[name];
  if (raw == null || raw === "") return fallback;
  const value = Number.parseInt(raw, 10);
  if ((strict && !/^\d+$/.test(raw)) || !Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}.`);
  }
  return value;
}

function booleanEnvironment(name, fallback) {
  const raw = process.env[name];
  if (raw == null || raw === "") return fallback;
  if (raw === "true") return true;
  if (raw === "false") return false;
  throw new Error(`${name} must be true or false.`);
}

function originsEnvironment() {
  const raw = process.env.CORS_ALLOWED_ORIGINS ?? "";
  return raw
    .split(",")
    .map((origin) => origin.trim())
    .filter(Boolean);
}

export function loadConfig(overrides = {}) {
  const config = {
    host: process.env.HOST ?? "127.0.0.1",
    port: integerEnvironment("PORT", 3000, { min: 1, max: 65535 }),
    provider: process.env.AI_PROVIDER ?? "mock",
    remoteAiEnabled: booleanEnvironment("REMOTE_AI_ENABLED", false),
    remoteAdmissionHmacSecret: process.env.REMOTE_ADMISSION_HMAC_SECRET ?? "",
    remoteAdmissionLeaseSeconds: integerEnvironment(
      "REMOTE_ADMISSION_LEASE_SECONDS",
      60,
      { min: 30, max: 600 }
    ),
    firebaseProjectId: process.env.FIREBASE_PROJECT_ID?.trim() ?? "",
    remoteAiNoticeVersion: process.env.REMOTE_AI_NOTICE_VERSION?.trim() ?? "",
    experimentalAiNoticeVersion:
      process.env.EXPERIMENTAL_AI_NOTICE_VERSION?.trim() ?? "",
    revenueCatV2SecretKey: process.env.REVENUECAT_V2_SECRET_KEY?.trim() ?? "",
    revenueCatProjectId: process.env.REVENUECAT_PROJECT_ID?.trim() ?? "",
    revenueCatPlusEntitlementId: process.env.REVENUECAT_PLUS_ENTITLEMENT_ID?.trim() ?? "",
    revenueCatProEntitlementId: process.env.REVENUECAT_PRO_ENTITLEMENT_ID?.trim() ?? "",
    revenueCatBoostEntitlementId: process.env.REVENUECAT_BOOST_ENTITLEMENT_ID?.trim() ?? "",
    revenueCatTimeoutMs: integerEnvironment("REVENUECAT_TIMEOUT_MS", 5000, {
      min: 250,
      max: 30000
    }),
    fireworksApiKey: process.env.FIREWORKS_API_KEY ?? "",
    openrouterApiKey: process.env.OPENROUTER_API_KEY ?? "",
    openrouterEvaluationTimeoutMs: integerEnvironment("OPENROUTER_EVALUATION_TIMEOUT_MS", 30000, {
      min: 1000, max: 120000, strict: true
    }),
    openrouterEvaluationMaxOutputTokens: integerEnvironment("OPENROUTER_EVALUATION_MAX_OUTPUT_TOKENS", 4096, {
      min: 256, max: 4096, strict: true
    }),
    fireworksModel: process.env.FIREWORKS_MODEL ?? "",
    fireworksEvaluationTimeoutMs: integerEnvironment("FIREWORKS_EVALUATION_TIMEOUT_MS", 30000, {
      min: 1000, max: 120000, strict: true
    }),
    fireworksEvaluationMaxOutputTokens: integerEnvironment("FIREWORKS_EVALUATION_MAX_OUTPUT_TOKENS", 4096, {
      min: 256, max: 4096, strict: true
    }),
    fireworksTimeoutMs: integerEnvironment("FIREWORKS_TIMEOUT_MS", 8000, {
      min: 1000,
      max: 30000
    }),
    fireworksMaxOutputTokens: integerEnvironment("FIREWORKS_MAX_OUTPUT_TOKENS", 1800, {
      min: 256,
      max: 4096
    }),
    requestBodyLimitBytes: integerEnvironment("REQUEST_BODY_LIMIT_BYTES", 32768, {
      min: 1024,
      max: 1048576
    }),
    requestTimeoutMs: integerEnvironment("REQUEST_TIMEOUT_MS", 10000, {
      min: 1000,
      max: 120000
    }),
    headersTimeoutMs: integerEnvironment("HEADERS_TIMEOUT_MS", 15000, {
      min: 1000,
      max: 120000
    }),
    keepAliveTimeoutMs: integerEnvironment("KEEP_ALIVE_TIMEOUT_MS", 5000, {
      min: 1000,
      max: 120000
    }),
    rateLimitWindowMs: integerEnvironment("RATE_LIMIT_WINDOW_MS", 60000, {
      min: 1000,
      max: 3600000
    }),
    rateLimitMaxRequests: integerEnvironment("RATE_LIMIT_MAX_REQUESTS", 60, {
      min: 1,
      max: 10000
    }),
    fireworksIpRequestsPerMinute: integerEnvironment("FIREWORKS_IP_REQUESTS_PER_MINUTE", 20, {
      min: 1,
      max: 1000
    }),
    fireworksInstallationRequestsPerMinute: integerEnvironment("FIREWORKS_INSTALLATION_REQUESTS_PER_MINUTE", 12, {
      min: 1,
      max: 1000
    }),
    fireworksInstallationRequestsPerDay: integerEnvironment("FIREWORKS_INSTALLATION_REQUESTS_PER_DAY", 100, {
      min: 1,
      max: 10000
    }),
    fireworksMaxConcurrentRequests: integerEnvironment("FIREWORKS_MAX_CONCURRENT_REQUESTS", 4, {
      min: 1,
      max: 100
    }),
    fireworksMaxConcurrentPerInstallation: integerEnvironment("FIREWORKS_MAX_CONCURRENT_PER_INSTALLATION", 1, {
      min: 1,
      max: 10
    }),
    fireworksCircuitFailureThreshold: integerEnvironment("FIREWORKS_CIRCUIT_FAILURE_THRESHOLD", 3, {
      min: 1,
      max: 100
    }),
    fireworksCircuitResetMs: integerEnvironment("FIREWORKS_CIRCUIT_RESET_MS", 30000, {
      min: 1000,
      max: 3600000
    }),
    corsAllowedOrigins: originsEnvironment(),
    trustProxy: booleanEnvironment("TRUST_PROXY", false),
    ...overrides,
    // These values are intentionally not environment/client configurable.
    fireworksBaseUrl: "https://api.fireworks.ai/inference/v1",
    fireworksTemperature: 0.2
  };
  if (config.provider !== "mock" && config.provider !== "fireworks") {
    throw new Error("AI_PROVIDER must be mock or fireworks.");
  }
  for (const [field, name, min, max] of [
    ["openrouterEvaluationTimeoutMs", "OPENROUTER_EVALUATION_TIMEOUT_MS", 1000, 120000],
    ["openrouterEvaluationMaxOutputTokens", "OPENROUTER_EVALUATION_MAX_OUTPUT_TOKENS", 256, 4096],
    ["fireworksEvaluationTimeoutMs", "FIREWORKS_EVALUATION_TIMEOUT_MS", 1000, 120000],
    ["fireworksEvaluationMaxOutputTokens", "FIREWORKS_EVALUATION_MAX_OUTPUT_TOKENS", 256, 4096]
  ]) {
    if (!Number.isInteger(config[field]) || config[field] < min || config[field] > max) {
      throw new Error(`${name} must be an integer between ${min} and ${max}.`);
    }
  }
  if (
    !Number.isInteger(config.remoteAdmissionLeaseSeconds) ||
    config.remoteAdmissionLeaseSeconds < 30 ||
    config.remoteAdmissionLeaseSeconds > 600
  ) {
    throw new Error("REMOTE_ADMISSION_LEASE_SECONDS must be an integer between 30 and 600.");
  }
  if (config.remoteAdmissionLeaseSeconds * 1000 < config.fireworksTimeoutMs + 15000) {
    throw new Error(
      "REMOTE_ADMISSION_LEASE_SECONDS must exceed FIREWORKS_TIMEOUT_MS by at least 15000ms."
    );
  }
  if (config.fireworksTimeoutMs >= config.requestTimeoutMs) {
    throw new Error("FIREWORKS_TIMEOUT_MS must be lower than REQUEST_TIMEOUT_MS.");
  }
  return Object.freeze(config);
}

export const DEFAULT_CONFIG = loadConfig();
