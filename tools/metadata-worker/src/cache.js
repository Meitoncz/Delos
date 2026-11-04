/**
 * Response cache for validated lookups. Two layers: a small in-isolate LRU and the Workers Cache API.
 * The Cache API only stores on custom domains (not on *.workers.dev), so the in-isolate layer is what keeps
 * repeated lookups off the upstreams on a workers.dev deployment. Keys are built from the validated, normalized
 * parameters only.
 */
export function createResponseCache({ cacheApi, now, maxEntries = 256 }) {
  const memory = new Map(); // key -> { body, expires }

  function remember(key, body, ttlSeconds) {
    memory.delete(key);
    if (memory.size >= maxEntries) memory.delete(memory.keys().next().value);
    memory.set(key, { body, expires: now() + ttlSeconds * 1000 });
  }

  return {
    /** Cached JSON text for [key], or null. */
    async get(key) {
      const entry = memory.get(key);
      if (entry) {
        memory.delete(key);
        if (entry.expires > now()) {
          memory.set(key, entry);
          return entry.body;
        }
      }
      if (!cacheApi) return null;
      try {
        const hit = await cacheApi.match(new Request(key));
        if (hit && hit.ok) return await hit.text();
      } catch {
        // A broken cache is a miss, never a failure.
      }
      return null;
    },

    /** Stores [body] for [ttlSeconds]; the Cache API write may finish after the response via [waitUntil]. */
    put(key, body, ttlSeconds, waitUntil) {
      remember(key, body, ttlSeconds);
      if (!cacheApi) return;
      const write = (async () => {
        try {
          await cacheApi.put(new Request(key), new Response(body, {
            headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": `public, max-age=${ttlSeconds}` },
          }));
        } catch {
          // Not storing is acceptable.
        }
      })();
      if (waitUntil) waitUntil(write);
    },
  };
}
