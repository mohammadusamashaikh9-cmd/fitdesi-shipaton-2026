export function buildProviderRequest(feature, validatedInput) {
  // Provider adapters translate this reviewed, feature-specific object into
  // their fixed server-side request. Client input never controls model or prompt configuration.
  return { schemaVersion: "1", feature, input: validatedInput };
}
