export function privacySafeLogger(entry) {
  const output = {
    requestId: entry.requestId,
    route: entry.route,
    status: entry.status,
    durationMs: entry.durationMs
  };
  if (entry.providerAlias) output.providerAlias = entry.providerAlias;
  if (Number.isInteger(entry.inputCharacterCount)) {
    output.inputCharacterCount = entry.inputCharacterCount;
  }
  if (Number.isInteger(entry.tokenUsage)) output.tokenUsage = entry.tokenUsage;
  if (entry.validationResult) output.validationResult = entry.validationResult;
  if (entry.fallbackStatus) output.fallbackStatus = entry.fallbackStatus;
  console.log(JSON.stringify(output));
}
