import { randomUUID } from "node:crypto";
import { healthRoute } from "./routes/health.js";
import { authSessionRoute } from "./routes/auth_session.js";
import { createAiCoachRoute } from "./routes/ai_coach.js";
import { workoutPlanRoute } from "./routes/workout_plan.js";
import { foodAnalyzeRoute } from "./routes/food_analyze.js";
import { dietPlanRoute } from "./routes/diet_plan.js";
import { yogaPlanRoute } from "./routes/yoga_plan.js";
import { progressReviewRoute } from "./routes/progress_review.js";
import {
  ApiError,
  AuthVerificationUnavailableError,
  ConsentAuthorityTimeoutError,
  EmailVerificationRequiredError,
  PayloadTooLargeError,
  RequestTimeoutError,
  ResponseValidationError,
  ValidationError
} from "./errors.js";
import { errorResponse, successResponse } from "./schemas/responses.js";
import { responseValidators } from "./services/response_validator.js";
import { corsHeaders } from "./middleware/cors.js";
import { clientIdentifier, createRateLimiter } from "./middleware/rate_limiter.js";
import { createFireworksGuard, readInstallationId } from "./middleware/fireworks_guard.js";
import { privacySafeLogger } from "./middleware/request_logger.js";
import { createAuthenticateRequest } from "./middleware/authenticate_request.js";
import { DEFAULT_CONFIG } from "./config.js";
import { FireworksProvider } from "./services/fireworks_provider.js";
import { ProviderRouter } from "./services/provider_router.js";
import { RevenueCatCommercialAuthority } from "./commercial/revenuecat_commercial_authority.js";
import { createFirebaseAdminIdTokenVerifier } from "./auth/firebase_admin_id_token_verifier.js";
import {
  consentPolicyFromConfig,
  PrivacyConsentAuthority
} from "./privacy/privacy_consent_authority.js";
import { FirestoreConsentStore } from "./privacy/firestore_consent_store.js";
import { RemoteAdmissionAuthority } from "./remote_admission/admission_authority.js";
import { createRemoteCapabilityPolicy } from "./remote_admission/capability_policy.js";
import { FirestoreRemoteAdmissionStore } from "./remote_admission/firestore_remote_admission_store.js";
import { DEFAULT_QUOTA_POLICY } from "./remote_admission/quota_policy.js";
import { RemoteExecutionAdmission } from "./remote_admission/remote_execution_admission.js";
import {
  assertNoConsentRequestBody,
  createPrivacyConsentRoutes,
  validateConsentMutationRequest,
  validateConsentStateResponse
} from "./routes/privacy_consent.js";

const STATIC_ROUTES = new Map([
  ["POST /api/ai/workout-plan", { handler: workoutPlanRoute, validateResponse: responseValidators.workout }],
  ["POST /api/ai/food-analyze", { handler: foodAnalyzeRoute, validateResponse: responseValidators.food }],
  ["POST /api/ai/diet-plan", { handler: dietPlanRoute, validateResponse: responseValidators.diet }],
  ["POST /api/ai/yoga-plan", { handler: yogaPlanRoute, validateResponse: responseValidators.yoga }],
  ["POST /api/ai/progress-review", { handler: progressReviewRoute, validateResponse: responseValidators.progress }]
]);

function createLazyRemoteAdmissionStore({ config }) {
  let store = null;
  const getStore = () => {
    store ??= new FirestoreRemoteAdmissionStore({
      projectId: config.firebaseProjectId,
      leaseDurationSeconds: config.remoteAdmissionLeaseSeconds
    });
    return store;
  };
  return Object.freeze({
    reserve(input) {
      return getStore().reserve(input);
    },
    recordProviderAttempt(input) {
      return getStore().recordProviderAttempt(input);
    },
    transition(input) {
      return getStore().transition(input);
    }
  });
}

function createDefaultRemoteExecutionAdmission({ config, consentAuthority }) {
  let admission = null;
  return Object.freeze({
    begin(input) {
      if (admission === null) {
        const commercialAuthority = new RevenueCatCommercialAuthority({ config });
        const capabilityPolicy = createRemoteCapabilityPolicy({ launchedCapabilities: [] });
        const admissionAuthority = new RemoteAdmissionAuthority({
          commercialAuthority,
          consentAuthority,
          capabilityPolicy
        });
        admission = new RemoteExecutionAdmission({
          admissionAuthority,
          firebaseProjectId: config.firebaseProjectId,
          hmacSecret: config.remoteAdmissionHmacSecret,
          quotaPolicy: DEFAULT_QUOTA_POLICY,
          store: createLazyRemoteAdmissionStore({ config })
        });
      }
      return admission.begin(input);
    }
  });
}

function sendJson(response, statusCode, payload, headers = {}) {
  if (response.writableEnded) return statusCode;
  const body = JSON.stringify(payload);
  response.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    ...headers
  });
  response.end(body);
  return statusCode;
}

function sendEmpty(response, statusCode, headers = {}) {
  if (response.writableEnded) return statusCode;
  response.writeHead(statusCode, {
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    ...headers
  });
  response.end();
  return statusCode;
}

async function readJson(request, bodyLimitBytes) {
  const contentType = request.headers["content-type"] ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw new ValidationError("Content-Type must be application/json.");
  }

  const declaredLength = Number.parseInt(request.headers["content-length"] ?? "0", 10);
  if (Number.isFinite(declaredLength) && declaredLength > bodyLimitBytes) {
    throw new PayloadTooLargeError();
  }

  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > bodyLimitBytes) throw new PayloadTooLargeError();
    chunks.push(chunk);
  }
  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("not an object");
    }
    return parsed;
  } catch {
    throw new ValidationError("Request body must be a JSON object.");
  }
}

function withTimeout(promise, timeoutMs, timeoutError = () => new RequestTimeoutError()) {
  let timer;
  const deadline = new Promise((_, reject) => {
    timer = setTimeout(() => reject(timeoutError()), timeoutMs);
    timer.unref?.();
  });
  return Promise.race([promise, deadline]).finally(() => clearTimeout(timer));
}

export function createApp({
  config = DEFAULT_CONFIG,
  logger = privacySafeLogger,
  idTokenVerifier = createFirebaseAdminIdTokenVerifier({
    projectId: config.firebaseProjectId
  }),
  consentAuthority = new PrivacyConsentAuthority({
    store: new FirestoreConsentStore({
      projectId: config.firebaseProjectId
    }),
    policy: consentPolicyFromConfig(config)
  }),
  fireworksProvider = new FireworksProvider({ config }),
  providerRouter = new ProviderRouter({ fireworksProvider }),
  remoteExecutionAdmission = createDefaultRemoteExecutionAdmission({ config, consentAuthority }),
  fireworksGuard = createFireworksGuard({
    ipRequestsPerMinute: config.fireworksIpRequestsPerMinute,
    installationRequestsPerMinute: config.fireworksInstallationRequestsPerMinute,
    installationRequestsPerDay: config.fireworksInstallationRequestsPerDay,
    maxConcurrentRequests: config.fireworksMaxConcurrentRequests,
    maxConcurrentPerInstallation: config.fireworksMaxConcurrentPerInstallation,
    circuitFailureThreshold: config.fireworksCircuitFailureThreshold,
    circuitResetMs: config.fireworksCircuitResetMs
  }),
  rateLimiter = createRateLimiter({
    windowMs: config.rateLimitWindowMs,
    maxRequests: config.rateLimitMaxRequests
  })
} = {}) {
  const authenticateRequest = createAuthenticateRequest({ idTokenVerifier });
  const privacyRoutes = createPrivacyConsentRoutes({ authority: consentAuthority });
  const coachHandler = createAiCoachRoute({
    config,
    provider: providerRouter,
    guard: fireworksGuard,
    remoteExecutionAdmission
  });
  const routes = new Map(STATIC_ROUTES);
  routes.set("POST /api/ai/coach", {
    handler: coachHandler,
    validateResponse: responseValidators.coach,
    wrappedResult: true
  });

  return async function handleRequest(request, response) {
    const requestId = randomUUID();
    const startedAt = performance.now();
    const url = new URL(request.url, "http://localhost");
    const route = url.pathname;
    let status = 500;
    let cors = {};
    const telemetry = {};

    response.once("finish", () => {
      logger({
        requestId,
        route,
        status: response.statusCode || status,
        durationMs: Math.max(0, Math.round(performance.now() - startedAt)),
        ...telemetry
      });
    });

    try {
      cors = corsHeaders(request, config.corsAllowedOrigins);
      if (request.method === "OPTIONS") {
        status = sendEmpty(response, 204, cors);
        return;
      }

      if (request.method === "GET" && route === "/api/health") {
        const data = healthRoute(config);
        if (!responseValidators.health(data)) throw new ResponseValidationError();
        status = sendJson(response, 200, successResponse(requestId, data), cors);
        return;
      }

      if (request.method === "GET" && route === "/api/auth/session") {
        const ipAddress = clientIdentifier(request, config.trustProxy);
        rateLimiter.check(ipAddress);
        const principal = await withTimeout(
          authenticateRequest(request),
          config.requestTimeoutMs,
          () => new AuthVerificationUnavailableError()
        );
        const data = authSessionRoute(principal);
        status = sendJson(response, 200, { success: true, requestId, data }, cors);
        return;
      }

      const isPrivacyRequest = route === "/api/privacy/consent" &&
        ["GET", "PUT", "DELETE"].includes(request.method);
      if (isPrivacyRequest) {
        if (request.url.includes("?")) {
          throw new ValidationError("Query parameters are not allowed.");
        }
        const ipAddress = clientIdentifier(request, config.trustProxy);
        rateLimiter.check(ipAddress);
        const principal = await withTimeout(
          authenticateRequest(request),
          config.requestTimeoutMs,
          () => new AuthVerificationUnavailableError()
        );

        if (request.method === "GET") {
          assertNoConsentRequestBody(request);
          const data = await withTimeout(
            privacyRoutes.get({ firebaseUid: principal.uid }),
            config.requestTimeoutMs,
            () => new ConsentAuthorityTimeoutError()
          );
          if (!validateConsentStateResponse(data)) {
            throw new ResponseValidationError();
          }
          status = sendJson(response, 200, {
            success: true,
            requestId,
            data
          }, cors);
          return;
        }

        if (request.method === "PUT") {
          const body = await withTimeout(
            readJson(request, config.requestBodyLimitBytes),
            config.requestTimeoutMs
          );
          const mutation = validateConsentMutationRequest(body);
          await privacyRoutes.put(mutation, { firebaseUid: principal.uid });
          status = sendEmpty(response, 204, cors);
          return;
        }

        assertNoConsentRequestBody(request);
        await privacyRoutes.delete({ firebaseUid: principal.uid });
        status = sendEmpty(response, 204, cors);
        return;
      }

      const routeDefinition = routes.get(`${request.method} ${route}`);
      if (!routeDefinition) {
        throw new ApiError(404, "NOT_FOUND", "Route not found.");
      }

      const ipAddress = clientIdentifier(request, config.trustProxy);
      rateLimiter.check(ipAddress);
      const principal = await withTimeout(
        authenticateRequest(request),
        config.requestTimeoutMs,
        () => new AuthVerificationUnavailableError()
      );
      if (!principal.emailVerified) throw new EmailVerificationRequiredError();
      let result;
      if (route === "/api/ai/coach") {
        const controller = new AbortController();
        const onAborted = () => controller.abort();
        const onClosed = () => {
          if (!response.writableFinished) controller.abort();
        };
        request.once("aborted", onAborted);
        response.once("close", onClosed);
        if (request.aborted || response.destroyed) controller.abort();
        try {
          const body = await withTimeout(
            readJson(request, config.requestBodyLimitBytes),
            config.requestTimeoutMs
          );
          result = await routeDefinition.handler(body, {
            firebaseUid: principal.uid,
            idempotencyKey: request.headers["idempotency-key"],
            ipAddress,
            installationId: readInstallationId(request),
            telemetry,
            signal: controller.signal
          });
        } finally {
          request.removeListener("aborted", onAborted);
          response.removeListener("close", onClosed);
        }
      } else {
        result = await withTimeout(
          (async () => {
            const body = await readJson(request, config.requestBodyLimitBytes);
            return routeDefinition.handler(body, {
              ipAddress,
              installationId: null,
              telemetry
            });
          })(),
          config.requestTimeoutMs
        );
      }
      const data = routeDefinition.wrappedResult ? result.data : result;
      const mode = routeDefinition.wrappedResult ? result.mode : "mock";
      if (!routeDefinition.validateResponse(data)) throw new ResponseValidationError();
      status = sendJson(response, 200, successResponse(requestId, data, mode), cors);
    } catch (error) {
      const apiError = error instanceof ApiError
        ? error
        : new ApiError(500, "INTERNAL_ERROR", "The service could not process this request.");
      status = sendJson(
        response,
        apiError.statusCode,
        errorResponse(requestId, apiError.code, apiError.publicMessage),
        { ...cors, ...apiError.headers }
      );
    }
  };
}

export const handleRequest = createApp();
