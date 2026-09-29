import { applicationDefault, getApps, initializeApp } from "firebase-admin/app";

const FIREBASE_APP_NAME = "fitdesi-backend-auth";
const SUPPORTED_FIREBASE_PROJECT_IDS = new Set([
  "fitdesi-ai",
  "fitdesi-ai-production"
]);

export function isSupportedFirebaseProjectId(projectId) {
  return SUPPORTED_FIREBASE_PROJECT_IDS.has(projectId);
}

export function createFirebaseAdminAppProvider({
  applicationDefaultImpl = applicationDefault,
  getAppsImpl = getApps,
  initializeAppImpl = initializeApp
} = {}) {
  let processApp = null;
  let processProjectId = null;

  return Object.freeze({
    getApp({ projectId }) {
      if (!isSupportedFirebaseProjectId(projectId)) {
        throw new Error("Firebase project configuration is unavailable.");
      }
      if (processApp) {
        if (processProjectId !== projectId) {
          throw new Error("Firebase project configuration changed after initialization.");
        }
        return processApp;
      }

      const existingApp = getAppsImpl().find((app) => app.name === FIREBASE_APP_NAME);
      const app = existingApp ?? initializeAppImpl(
        { credential: applicationDefaultImpl(), projectId },
        FIREBASE_APP_NAME
      );
      if (app.options.projectId !== projectId) {
        throw new Error("Firebase project configuration does not match the initialized app.");
      }

      processApp = app;
      processProjectId = projectId;
      return processApp;
    }
  });
}

export const firebaseAdminAppForProcess = createFirebaseAdminAppProvider();
