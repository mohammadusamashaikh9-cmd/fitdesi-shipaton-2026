import { RateLimitError } from "../errors.js";

export function createRateLimiter({ windowMs, maxRequests, now = Date.now }) {
  const clients = new Map();

  return {
    check(clientId) {
      const timestamp = now();
      const current = clients.get(clientId);
      if (!current || timestamp >= current.resetAt) {
        clients.set(clientId, { count: 1, resetAt: timestamp + windowMs });
        return;
      }
      if (current.count >= maxRequests) {
        throw new RateLimitError(Math.max(1, Math.ceil((current.resetAt - timestamp) / 1000)));
      }
      current.count += 1;
    },
    reset() {
      clients.clear();
    }
  };
}

export function clientIdentifier(request, trustProxy) {
  if (trustProxy) {
    const forwarded = request.headers["x-forwarded-for"];
    if (typeof forwarded === "string" && forwarded.trim()) {
      return forwarded.split(",")[0].trim();
    }
  }
  return request.socket.remoteAddress ?? "unknown";
}
