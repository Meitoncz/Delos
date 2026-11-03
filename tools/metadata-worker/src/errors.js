/**
 * The only error type that reaches a client. `code` is a fixed identifier from this code base; upstream bodies,
 * upstream error text and exception messages are never copied into a response.
 */
export class HttpError extends Error {
  constructor(status, code, retryAfterSeconds = 0) {
    super(code);
    this.name = "HttpError";
    this.status = status;
    this.code = code;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Seconds a client should wait after a 429/503 caused by this Worker or by an upstream throttle. */
export const RETRY_AFTER_SECONDS = 30;

export const MAX_UPSTREAM_TEXT = 2_000_000;
export const UPSTREAM_TIMEOUT_MS = 8000;

/** fetch() to an upstream: no redirects (a 307/308 must never replay a credential-carrying POST), bounded time. */
export async function upstreamFetch(fetchFn, url, init) {
  let response;
  try {
    response = await fetchFn(url, { ...init, redirect: "manual", signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS) });
  } catch (error) {
    throw new HttpError(error && error.name === "TimeoutError" ? 504 : 502, error && error.name === "TimeoutError" ? "upstream_timeout" : "upstream_error");
  }
  return response;
}

/** Releases a response whose body is not needed. */
export async function discard(response) {
  try {
    await response.body?.cancel();
  } catch {
    // Nothing to release.
  }
}

/** Maps a non-2xx upstream status to the Worker's own generic error. Consumes nothing from the body. */
export async function rejectStatus(response) {
  await discard(response);
  if (response.status === 429 || response.status === 503) throw new HttpError(503, "upstream_busy", RETRY_AFTER_SECONDS);
  throw new HttpError(502, "upstream_error");
}

/** Body text of a successful upstream response, bounded. */
export async function readText(response) {
  if (!(response.status >= 200 && response.status < 300)) await rejectStatus(response);
  let text;
  try {
    text = await response.text();
  } catch {
    throw new HttpError(502, "upstream_error");
  }
  if (text.length > MAX_UPSTREAM_TEXT) throw new HttpError(502, "upstream_error");
  return text;
}
