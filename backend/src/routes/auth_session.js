export function authSessionRoute(principal) {
  return {
    authenticated: typeof principal.uid === "string" && principal.uid.length > 0,
    emailVerified: principal.emailVerified === true
  };
}
