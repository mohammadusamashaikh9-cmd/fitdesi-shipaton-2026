import { createServer } from "node:http";
import { handleRequest } from "./app.js";
import { DEFAULT_CONFIG } from "./config.js";

const server = createServer(handleRequest);
server.requestTimeout = DEFAULT_CONFIG.requestTimeoutMs;
server.headersTimeout = DEFAULT_CONFIG.headersTimeoutMs;
server.keepAliveTimeout = DEFAULT_CONFIG.keepAliveTimeoutMs;

server.listen(DEFAULT_CONFIG.port, DEFAULT_CONFIG.host, () => {
  console.log(`FitDesi backend listening on http://${DEFAULT_CONFIG.host}:${DEFAULT_CONFIG.port}`);
});
