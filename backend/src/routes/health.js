export function healthRoute(config) {
  return {
    status: "ok",
    service: "fitdesi-ai-backend",
    providerMode: config.provider === "mock"
      ? "mock"
      : (config.remoteAiEnabled ? "fireworks" : "disabled"),
    timestamp: new Date().toISOString()
  };
}
