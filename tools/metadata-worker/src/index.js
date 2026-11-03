import { createHandler } from "./handler.js";

// One handler per isolate: the Twitch token, the PCGamingWiki session, the in-memory cache and the pacing
// state live in its closure and are reused across requests while the isolate stays warm.
let handler;

export default {
  fetch(request, env, ctx) {
    handler ??= createHandler({ cacheApi: typeof caches !== "undefined" ? caches.default : null });
    return handler(request, env, ctx);
  },
};
