import { getAuth } from "firebase-admin/auth";
import {
  firebaseAdminAppForProcess,
  isSupportedFirebaseProjectId
} from "../firebase/firebase_admin_app.js";

const INVALID_IDENTITY_CODES = new Set([
  "auth/argument-error",
  "auth/id-token-expired",
  "auth/id-token-revoked",
  "auth/invalid-argument",
  "auth/invalid-id-token",
  "auth/user-disabled",
  "auth/user-not-found"
]);

function providerErrorCode(error) {
  return error && typeof error === "object" && typeof error.code === "string"
    ? error.code
    : "";
}

export function createFirebaseAdminIdTokenVerifier({ projectId, firebaseAuth = null }) {
  return Object.freeze({
    async verify(token) {
      if (!isSupportedFirebaseProjectId(projectId)) {
        return { status: "unavailable" };
      }
      try {
        const decodedToken = await (firebaseAuth ?? getAuth(
          firebaseAdminAppForProcess.getApp({ projectId })
        ))
          .verifyIdToken(token, true);
        if (typeof decodedToken.uid !== "string" || decodedToken.uid.length === 0) {
          return { status: "invalid" };
        }
        return {
          status: "verified",
          principal: {
            uid: decodedToken.uid,
            emailVerified: decodedToken.email_verified === true
          }
        };
      } catch (error) {
        return INVALID_IDENTITY_CODES.has(providerErrorCode(error))
          ? { status: "invalid" }
          : { status: "unavailable" };
      }
    }
  });
}
