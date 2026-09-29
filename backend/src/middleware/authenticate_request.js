import {
  AuthenticationRequiredError,
  AuthVerificationUnavailableError,
  InvalidSessionError
} from "../errors.js";

const BEARER_CREDENTIAL = /^Bearer ([^\s]+)$/;

export function createAuthenticateRequest({ idTokenVerifier }) {
  return async function authenticateRequest(request) {
    const authorizationHeaders = [];
    const rawHeaders = Array.isArray(request.rawHeaders) ? request.rawHeaders : [];
    for (let index = 0; index < rawHeaders.length; index += 2) {
      if (rawHeaders[index]?.toLowerCase() === "authorization") {
        authorizationHeaders.push(rawHeaders[index + 1]);
      }
    }
    const match = authorizationHeaders.length === 1 && typeof authorizationHeaders[0] === "string"
      ? BEARER_CREDENTIAL.exec(authorizationHeaders[0])
      : null;
    if (!match) throw new AuthenticationRequiredError();

    let verification;
    try {
      verification = await idTokenVerifier?.verify(match[1]);
    } catch {
      throw new AuthVerificationUnavailableError();
    }
    if (verification?.status === "invalid") throw new InvalidSessionError();
    if (verification?.status !== "verified") {
      throw new AuthVerificationUnavailableError();
    }

    const principal = verification.principal;
    if (!principal || typeof principal.uid !== "string" || principal.uid.length === 0) {
      throw new InvalidSessionError();
    }
    return Object.freeze({
      uid: principal.uid,
      emailVerified: principal.emailVerified === true
    });
  };
}
