import { CorsError } from "../errors.js";

export function corsHeaders(request, allowedOrigins) {
  const origin = request.headers.origin;
  if (!origin) return {};
  if (!allowedOrigins.includes(origin)) throw new CorsError();
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-methods": "GET, POST, OPTIONS",
    "access-control-allow-headers": "Content-Type",
    "access-control-max-age": "600",
    vary: "Origin"
  };
}
