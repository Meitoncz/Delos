import { describe, expect, it, vi } from "vitest";
import { createBucket, createGate } from "../src/limits.js";
import { BASE, createClient, createWorld, SECRETS } from "./harness.js";

describe("routing and method policy", () => {
  it("answers anything but GET with 405 and an Allow header", async () => {
    const client = createClient(createWorld());
    for (const method of ["POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"]) {
      const init = method === "POST" || method === "PUT" || method === "PATCH" ? { method, body: "x" } : { method };
      const response = await client.request("/v1/igdb/games?search=Game", init);
      expect(response.status).toBe(405);
      expect(response.headers.get("allow")).toBe("GET");
      expect(response.json()).toEqual({ error: "method_not_allowed" });
    }
  });

  it("answers unknown paths with 404", async () => {
    const client = createClient(createWorld());
    for (const path of ["/", "/v1", "/v1/igdb", "/v1/igdb/games/", "/v1/igdb/search", "/v4/games", "/v1/pcgw", "/v1/pcgw/api", "/v1/IGDB/games", "/v1/igdb/games%2F", "/__proto__", "/constructor"]) {
      const response = await client.request(path);
      expect(response.status, path).toBe(404);
      expect(response.json()).toEqual({ error: "not_found" });
    }
  });

  it("rejects oversize URLs and headers and bodies before doing any work", async () => {
    const world = createWorld();
    const client = createClient(world);
    expect((await client.request(`/v1/igdb/games?search=${"a".repeat(3000)}`)).status).toBe(414);
    expect((await client.request("/v1/igdb/games?search=Game", { headers: { "x-big": "a".repeat(9000) } })).status).toBe(431);
    expect((await client.request("/v1/igdb/games?search=Game", { headers: { "content-length": "5" } })).status).toBe(413);
    expect(world.calls).toHaveLength(0);
  });

  it("sets Cache-Control and a JSON content type on every response and enables no CORS", async () => {
    const world = createWorld({ igdbResponse: () => new Response("[]") });
    const client = createClient(world, { handlerOptions: {} });
    const responses = [
      await client.request("/v1/igdb/games?search=Game"),
      await client.request("/v1/igdb/games?search=Game"),
      await client.request("/v1/igdb/games?search="),
      await client.request("/nope"),
      await client.request("/v1/igdb/games?search=Game", { method: "POST", body: "x" }),
      await client.request("/v1/igdb/games?search=Game", { method: "OPTIONS", headers: { origin: "https://evil.example", "access-control-request-method": "GET" } }),
      await client.request("/v1/igdb/games?search=Game", { headers: { origin: "https://evil.example" } }),
    ];
    for (const response of responses) {
      expect(response.headers.get("cache-control")).toBeTruthy();
      expect(response.headers.get("content-type")).toContain("application/json");
      expect(response.headers.get("x-content-type-options")).toBe("nosniff");
      expect([...response.headers.keys()].filter((name) => name.startsWith("access-control-"))).toEqual([]);
    }
  });
});

describe("per-client rate limit", () => {
  it("returns 429 with Retry-After when the Rate Limiting binding refuses", async () => {
    const world = createWorld({ igdbResponse: () => new Response("[]") });
    const keys = [];
    let allowed = 2;
    const env = { ...SECRETS, RATE_LIMITER: { limit: async ({ key }) => { keys.push(key); return { success: allowed-- > 0 }; } } };
    const client = createClient(world, { env });
    const headers = { "cf-connecting-ip": "203.0.113.7" };
    expect((await client.request("/v1/igdb/games?search=A", { headers })).status).toBe(200);
    expect((await client.request("/v1/igdb/games?search=B", { headers })).status).toBe(200);
    const limited = await client.request("/v1/igdb/games?search=C", { headers });
    expect(limited.status).toBe(429);
    expect(limited.json()).toEqual({ error: "rate_limited" });
    expect(limited.headers.get("retry-after")).toBe("30");
    expect(limited.headers.get("cache-control")).toBe("no-store");
    expect(keys).toEqual(["203.0.113.7", "203.0.113.7", "203.0.113.7"]);
    expect(world.igdbBodies).toHaveLength(2);
  });

  it("falls back to an in-isolate bucket without the binding: a burst of 60, then 429", async () => {
    const world = createWorld();
    const client = createClient(world);
    const headers = { "cf-connecting-ip": "198.51.100.1" };
    const statuses = [];
    for (let i = 0; i < 61; i++) statuses.push((await client.request("/v1/igdb/games?id=0", { headers })).status);
    expect(statuses.slice(0, 60).every((status) => status === 400)).toBe(true);
    expect(statuses[60]).toBe(429);
    // Another client is unaffected; the first recovers as time passes.
    expect((await client.request("/v1/igdb/games?id=0", { headers: { "cf-connecting-ip": "198.51.100.2" } })).status).toBe(400);
    world.clock += 1000;
    expect((await client.request("/v1/igdb/games?id=0", { headers })).status).toBe(400);
  });

  it("falls back to the bucket when the binding throws", async () => {
    const world = createWorld({ igdbResponse: () => new Response("[]") });
    const env = { ...SECRETS, RATE_LIMITER: { limit: async () => { throw new Error("binding down"); } } };
    expect((await createClient(world, { env }).request("/v1/igdb/games?search=A")).status).toBe(200);
  });

  it("bucket refills at the configured rate and bounds its memory", () => {
    let now = 0;
    const take = createBucket({ capacity: 2, refillPerSecond: 1, now: () => now, maxClients: 2 });
    expect([take("a"), take("a"), take("a")]).toEqual([true, true, false]);
    now += 1000;
    expect(take("a")).toBe(true);
    take("b");
    take("c");
    // "a" was evicted and starts fresh with a full bucket.
    expect([take("a"), take("a")]).toEqual([true, true]);
  });
});

describe("upstream pacing", () => {
  it("spaces upstream calls and sleeps instead of bursting", async () => {
    let now = 0;
    const sleeps = [];
    const gate = createGate({ intervalMs: 260, maxWaitMs: 2500, now: () => now, sleep: async (ms) => { sleeps.push(ms); now += ms; } });
    await gate();
    await gate();
    await gate();
    expect(sleeps).toEqual([260, 260]);
    now += 10_000;
    await gate();
    expect(sleeps).toEqual([260, 260]);
  });

  it("refuses with 503 and Retry-After when the queue would wait too long", async () => {
    const gate = createGate({ intervalMs: 1000, maxWaitMs: 2500, now: () => 0, sleep: () => new Promise(() => {}) });
    gate();
    gate();
    gate();
    await expect(gate()).rejects.toMatchObject({ status: 503, code: "busy", retryAfterSeconds: 30 });
  });

  it("IGDB lookups reach the upstream at most four per second", async () => {
    const world = createWorld({ igdbResponse: () => new Response("[]") });
    const client = createClient(world);
    for (const name of ["A", "B", "C", "D"]) await client.request(`/v1/igdb/games?search=${name}`);
    expect(world.sleeps.length).toBeGreaterThanOrEqual(3);
    expect(world.sleeps.every((ms) => ms <= 260)).toBe(true);
  });
});

describe("no secrets, upstream text or query values in output", () => {
  it("keeps secrets out of every failure response and log line, and logs only a hash of the query", async () => {
    const logs = [];
    const spy = vi.spyOn(console, "warn").mockImplementation((line) => logs.push(String(line)));
    try {
      const world = createWorld({
        tokenResponse: () => new Response(`UPSTREAM-BODY ${SECRETS.IGDB_CLIENT_SECRET}`, { status: 400 }),
        loginResult: "Failed",
      });
      const client = createClient(world);
      const term = "Some Very Private Game Name";
      const igdb = await client.request(`/v1/igdb/games?search=${encodeURIComponent(term)}`);
      const pcgw = await client.request(`/v1/pcgw/cargo?page=${encodeURIComponent(term)}`);
      expect(igdb.status).toBe(502);
      expect(pcgw.status).toBe(502);
      expect(logs.length).toBe(2);
      for (const line of logs) {
        expect(line).not.toContain("Private");
        for (const secret of Object.values(SECRETS)) expect(line).not.toContain(secret);
        expect(JSON.parse(line)).toMatchObject({ status: 502, code: "upstream_auth" });
        expect(JSON.parse(line).q).toMatch(/^[0-9a-f]{12}$/);
      }
    } finally {
      spy.mockRestore();
    }
  });

  it("turns an unexpected exception into a generic 500", async () => {
    const spy = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      const world = createWorld();
      const env = new Proxy({}, { get(_target, name) { if (name === "IGDB_CLIENT_ID") throw new Error(SECRETS.IGDB_CLIENT_SECRET); return undefined; } });
      const client = createClient(world, { env });
      const response = await client.request("/v1/igdb/games?search=A");
      expect(response.status).toBe(500);
      expect(response.json()).toEqual({ error: "internal" });
    } finally {
      spy.mockRestore();
    }
  });

  it("builds the cache key from the request origin and normalized parameters only", async () => {
    const world = createWorld({ igdbResponse: () => new Response("[]") });
    const client = createClient(world);
    expect(BASE).toContain("workers.dev");
    await client.request("/v1/igdb/games?search=A&", { headers: { authorization: "Bearer x" } });
    const hit = await client.request("/v1/igdb/games?search=A", { headers: { "user-agent": "other" } });
    expect(hit.headers.get("x-delos-cache")).toBe("HIT");
  });
});
