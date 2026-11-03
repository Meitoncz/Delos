import { createHandler } from "../src/handler.js";

// Sentinel values only; the real credentials never appear in this repository.
export const SECRETS = {
  IGDB_CLIENT_ID: "SENTINEL-IGDB-ID",
  IGDB_CLIENT_SECRET: "SENTINEL-IGDB-SECRET",
  PCGW_BOT_USERNAME: "SENTINEL-BOT-USER",
  PCGW_BOT_PASSWORD: "SENTINEL-BOT-PASSWORD",
};
export const FORBIDDEN_IN_RESPONSES = [...Object.values(SECRETS), "UPSTREAM-BODY", "UPSTREAM-ECHO", "tok-", "wikiSession", "wikiUser"];

export const BASE = "https://delos-meta.example.workers.dev";

const json = (value, status = 200, headers = []) =>
  new Response(typeof value === "string" ? value : JSON.stringify(value), { status, headers: new Headers(headers) });

/**
 * A fake world: Twitch, IGDB and PCGamingWiki behind one fetch function plus a manual clock. Nothing touches the
 * network and nothing really sleeps. Every upstream call is recorded in [calls].
 */
export function createWorld(options = {}) {
  const world = {
    clock: 1_000_000,
    calls: [],
    sleeps: [],
    tokenCalls: 0,
    loginCalls: 0,
    igdbBodies: [],
    // Behavior knobs; tests replace them.
    igdbResponse: (_call) => json([{ id: 1, name: "Game" }]),
    tokenResponse: (n) => json({ access_token: `tok-${n}`, expires_in: 3600 }),
    wikiResponse: (_params) => json({ cargoquery: [] }),
    loginResult: "Success",
    validSessions: new Set(),
    ...options,
  };

  world.now = () => world.clock;
  world.sleep = async (ms) => {
    world.sleeps.push(ms);
    world.clock += ms;
  };
  world.expireSessions = () => world.validSessions.clear();

  world.fetch = async (input, init = {}) => {
    const url = new URL(String(input));
    const headers = new Headers(init.headers ?? {});
    const call = { url, method: init.method ?? "GET", headers, body: init.body === undefined ? "" : String(init.body), redirect: init.redirect };
    world.calls.push(call);
    if (url.host === "id.twitch.tv") {
      world.tokenCalls++;
      return world.tokenResponse(world.tokenCalls, call);
    }
    if (url.host === "api.igdb.com") {
      world.igdbBodies.push(call.body);
      return world.igdbResponse(call);
    }
    if (url.host === "www.pcgamingwiki.com") return wiki(world, call);
    throw new Error(`unexpected upstream ${url.host}`);
  };
  return world;
}

function wiki(world, call) {
  const params = call.method === "POST" ? new URLSearchParams(call.body) : call.url.searchParams;
  const cookie = call.headers.get("cookie") ?? "";
  if (params.get("action") === "query" && params.get("meta") === "tokens") {
    return json({ query: { tokens: { logintoken: "login-token-1+\\" } } }, 200, [["set-cookie", "wikiSession=s1; path=/; HttpOnly"]]);
  }
  if (params.get("action") === "login") {
    world.loginCalls++;
    world.lastLogin = { params, cookie };
    if (world.loginResult !== "Success") return json({ login: { result: world.loginResult } });
    world.validSessions.add(`u${world.loginCalls}`);
    return json({ login: { result: "Success" } }, 200, [["set-cookie", `wikiUser=u${world.loginCalls}; path=/`]]);
  }
  const user = /wikiUser=(u\d+)/.exec(cookie)?.[1];
  if (!user || !world.validSessions.has(user)) {
    return json({ error: { code: "assertuserfailed", info: "UPSTREAM-ECHO " + SECRETS.PCGW_BOT_PASSWORD } });
  }
  return world.wikiResponse(params, call);
}

/** A Cache API stand-in keyed by URL. */
export function createCacheApi() {
  const store = new Map();
  return {
    store,
    async match(request) {
      const hit = store.get(request.url);
      return hit === undefined ? undefined : new Response(hit, { headers: { "Content-Type": "application/json" } });
    },
    async put(request, response) {
      store.set(request.url, await response.text());
    },
  };
}

/** A client of the Worker; asserts on every response that no secret or upstream text leaked. */
export function createClient(world, { env = SECRETS, cacheApi = null, handlerOptions = {} } = {}) {
  const handler = createHandler({ fetch: world.fetch, now: world.now, sleep: world.sleep, cacheApi, igdbIntervalMs: 260, pcgwIntervalMs: 550, ...handlerOptions });
  const waiting = [];
  const ctx = { waitUntil: (promise) => waiting.push(promise) };
  const client = {
    env,
    async request(path, init = {}) {
      const request = new Request(BASE + path, { method: "GET", ...init });
      const response = await handler(request, env, ctx);
      await Promise.all(waiting.splice(0));
      const text = await response.text();
      for (const forbidden of FORBIDDEN_IN_RESPONSES) {
        if (text.includes(forbidden)) throw new Error(`response leaks "${forbidden}"`);
        for (const [name, value] of response.headers) {
          if (`${name}: ${value}`.includes(forbidden)) throw new Error(`response header leaks "${forbidden}"`);
        }
      }
      return { status: response.status, headers: response.headers, text, json: () => JSON.parse(text) };
    },
  };
  return client;
}
