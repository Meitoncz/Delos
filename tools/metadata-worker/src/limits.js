import { HttpError, RETRY_AFTER_SECONDS } from "./errors.js";

/**
 * Spaces upstream calls at least [intervalMs] apart inside one isolate. A call that would have to wait longer than
 * [maxWaitMs] is refused with 503 so the app's per-provider backoff takes over instead of piling up requests.
 * Slots are reserved synchronously, so concurrent requests queue in order without any lock.
 */
export function createGate({ intervalMs, maxWaitMs, now, sleep }) {
  let nextFree = 0;
  return async function gate() {
    const current = now();
    const start = Math.max(current, nextFree);
    if (start - current > maxWaitMs) throw new HttpError(503, "busy", RETRY_AFTER_SECONDS);
    nextFree = start + intervalMs;
    if (start > current) await sleep(start - current);
  };
}

/**
 * Per-client token bucket, used when the Workers Rate Limiting binding is not configured (or errors).
 * In-isolate and therefore approximate: separate isolates keep separate buckets.
 */
export function createBucket({ capacity, refillPerSecond, now, maxClients = 2000 }) {
  const clients = new Map(); // key -> { tokens, time }
  return function take(key) {
    const current = now();
    const entry = clients.get(key) ?? { tokens: capacity, time: current };
    entry.tokens = Math.min(capacity, entry.tokens + ((current - entry.time) / 1000) * refillPerSecond);
    entry.time = current;
    clients.delete(key);
    if (clients.size >= maxClients) clients.delete(clients.keys().next().value);
    clients.set(key, entry);
    if (entry.tokens < 1) return false;
    entry.tokens -= 1;
    return true;
  };
}
