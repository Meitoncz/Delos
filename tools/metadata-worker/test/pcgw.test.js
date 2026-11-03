import { describe, expect, it } from "vitest";
import { createClient, createWorld, SECRETS } from "./harness.js";

const WIKI_JSON = '{"cargoquery":[{"title":{"Page":"Elden Ring","Steam":"1245620"}}]}';
const wikiCalls = (world) => world.calls.filter((c) => c.url.host === "www.pcgamingwiki.com");
const dataCalls = (world) => wikiCalls(world).filter((c) => !["login"].includes(c.url.searchParams.get("action") ?? new URLSearchParams(c.body).get("action")) && c.url.searchParams.get("meta") !== "tokens");
const pairs = (call) => [...call.url.searchParams];

describe("PCGW fixed endpoints", () => {
  it("cargo by Steam ids sends the app's exact join, distinct ascending ids", async () => {
    const world = createWorld({ wikiResponse: () => new Response(WIKI_JSON) });
    const response = await createClient(world).request("/v1/pcgw/cargo?steam=20,10,10");
    expect(response.status).toBe(200);
    expect(response.text).toBe(WIKI_JSON);
    expect(pairs(dataCalls(world)[0])).toEqual([
      ["action", "cargoquery"], ["tables", "Game,Video"], ["fields", "Game._pageName=Page,Game.Steam_AppID=Steam"],
      ["join_on", "Game._pageID=Video._pageID"], ["where", 'Game.Steam_AppID HOLDS "10" OR Game.Steam_AppID HOLDS "20"'],
      ["limit", "10"], ["assert", "user"], ["format", "json"],
    ]);
  });

  it("cargo by page title escapes the Cargo literal", async () => {
    const world = createWorld({ wikiResponse: () => new Response(WIKI_JSON) });
    await createClient(world).request("/v1/pcgw/cargo?page=" + encodeURIComponent('Say "Hi" \\ now'));
    expect(dataCalls(world)[0].url.searchParams.get("where")).toBe('Game._pageName = "Say \\"Hi\\" \\\\ now"');
  });

  it("page, redirect and search send the app's exact parameters", async () => {
    const world = createWorld({ wikiResponse: () => new Response("{}") });
    const client = createClient(world);
    await client.request("/v1/pcgw/page?title=Sea%20of%20Stars");
    await client.request("/v1/pcgw/redirect?title=Sea%20of%20Stars%3A%20Sunset%20Edition");
    await client.request("/v1/pcgw/search?q=Sea%20of%20Stars");
    const [page, redirect, search] = dataCalls(world).map(pairs);
    expect(page).toEqual([["action", "parse"], ["page", "Sea of Stars"], ["prop", "wikitext"], ["assert", "user"], ["format", "json"]]);
    expect(redirect).toEqual([["action", "query"], ["titles", "Sea of Stars: Sunset Edition"], ["redirects", "1"], ["assert", "user"], ["format", "json"]]);
    expect(search).toEqual([
      ["action", "query"], ["list", "search"], ["srsearch", "Sea of Stars"], ["srnamespace", "0"], ["srlimit", "5"], ["assert", "user"], ["format", "json"],
    ]);
  });

  const bad = [
    "/v1/pcgw/cargo", "/v1/pcgw/cargo?steam=", "/v1/pcgw/cargo?steam=0", "/v1/pcgw/cargo?steam=1,a", "/v1/pcgw/cargo?steam=1&page=A",
    "/v1/pcgw/cargo?page=", "/v1/pcgw/cargo?page=a|b", "/v1/pcgw/cargo?where=1%3D1", "/v1/pcgw/cargo?steam=1&where=1",
    `/v1/pcgw/cargo?steam=${Array.from({ length: 21 }, (_, i) => i + 1).join(",")}`,
    "/v1/pcgw/page", "/v1/pcgw/page?title=", "/v1/pcgw/page?title=a%5Bb", "/v1/pcgw/page?page=A", "/v1/pcgw/page?title=A&title=B",
    "/v1/pcgw/page?title=" + "a".repeat(201), "/v1/pcgw/redirect?title=A%7CB", "/v1/pcgw/redirect?title=A%0AB",
    "/v1/pcgw/search?q=", "/v1/pcgw/search?q=a%23b", "/v1/pcgw/search?q=a&srlimit=500", "/v1/pcgw/search?action=query",
  ];
  for (const path of bad) {
    it(`rejects ${path.slice(0, 60)}`, async () => {
      const world = createWorld();
      const response = await createClient(world).request(path);
      expect(response.status).toBe(400);
      expect(response.json()).toEqual({ error: "invalid_request" });
      expect(world.calls).toHaveLength(0);
    });
  }
});

describe("PCGW bot session", () => {
  it("logs in once with the session cookie, reuses the session and sets no secret in responses", async () => {
    const world = createWorld({ wikiResponse: () => new Response(WIKI_JSON) });
    const client = createClient(world);
    expect((await client.request("/v1/pcgw/cargo?steam=1")).status).toBe(200);
    expect((await client.request("/v1/pcgw/cargo?steam=2")).status).toBe(200);
    expect(world.loginCalls).toBe(1);
    const calls = wikiCalls(world);
    expect(calls.map((c) => c.method)).toEqual(["GET", "POST", "GET", "GET"]);
    expect(calls.every((c) => c.redirect === "manual")).toBe(true);
    // The login token request's session cookie travels with the login POST; the data calls carry both cookies.
    expect(world.lastLogin.cookie).toBe("wikiSession=s1");
    expect(world.lastLogin.params.get("lgname")).toBe(SECRETS.PCGW_BOT_USERNAME);
    expect(world.lastLogin.params.get("lgpassword")).toBe(SECRETS.PCGW_BOT_PASSWORD);
    expect(world.lastLogin.params.get("lgtoken")).toBe("login-token-1+\\");
    expect(calls[2].headers.get("cookie")).toBe("wikiSession=s1; wikiUser=u1");
    expect(calls[2].headers.get("user-agent")).toContain("Delos");
  });

  it("re-logs in exactly once when the wiki reports a lost session, then retries", async () => {
    const world = createWorld({ wikiResponse: () => new Response(WIKI_JSON) });
    const client = createClient(world);
    await client.request("/v1/pcgw/cargo?steam=1");
    world.expireSessions();
    const response = await client.request("/v1/pcgw/cargo?steam=2");
    expect(response.status).toBe(200);
    expect(response.text).toBe(WIKI_JSON);
    expect(world.loginCalls).toBe(2);
  });

  it("does not loop when the fresh session is rejected too", async () => {
    const world = createWorld({
      wikiResponse: () => new Response(`{"error":{"code":"assertuserfailed","info":"UPSTREAM-ECHO"}}`),
    });
    const response = await createClient(world).request("/v1/pcgw/cargo?steam=1");
    expect(response.status).toBe(502);
    expect(response.json()).toEqual({ error: "upstream_error" });
    expect(world.loginCalls).toBe(2);
    expect(dataCalls(world)).toHaveLength(2);
  });

  it("does not re-login for an ordinary wiki error and never echoes it", async () => {
    const world = createWorld({ wikiResponse: () => new Response(`{"error":{"code":"badquery","info":"UPSTREAM-ECHO"}}`) });
    const response = await createClient(world).request("/v1/pcgw/cargo?steam=1");
    expect(response.status).toBe(502);
    expect(response.json()).toEqual({ error: "upstream_error" });
    expect(world.loginCalls).toBe(1);
    expect(dataCalls(world)).toHaveLength(1);
  });

  it("concurrent first requests share one login", async () => {
    const world = createWorld({ wikiResponse: () => new Response(WIKI_JSON) });
    const client = createClient(world);
    const results = await Promise.all([1, 2, 3].map((n) => client.request(`/v1/pcgw/cargo?steam=${n}`)));
    expect(results.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(world.loginCalls).toBe(1);
  });

  it("a refused login is a generic 502 and is latched for ten minutes", async () => {
    const world = createWorld({ loginResult: "Failed" });
    const client = createClient(world);
    const first = await client.request("/v1/pcgw/cargo?steam=1");
    expect(first.status).toBe(502);
    expect(first.json()).toEqual({ error: "upstream_auth" });
    const after = wikiCalls(world).length;
    expect((await client.request("/v1/pcgw/cargo?steam=2")).status).toBe(502);
    expect(wikiCalls(world)).toHaveLength(after);
    world.clock += 11 * 60 * 1000;
    world.loginResult = "Success";
    world.wikiResponse = () => new Response(WIKI_JSON);
    expect((await client.request("/v1/pcgw/cargo?steam=3")).status).toBe(200);
  });

  it("a throttled login is retried by the next request", async () => {
    const world = createWorld({ loginResult: "Throttled" });
    const client = createClient(world);
    expect((await client.request("/v1/pcgw/cargo?steam=1")).status).toBe(502);
    const after = world.loginCalls;
    expect((await client.request("/v1/pcgw/cargo?steam=2")).status).toBe(502);
    expect(world.loginCalls).toBe(after + 1);
  });

  it("maps wiki HTTP 429/503 to 503 with Retry-After and other failures to 502", async () => {
    for (const [status, expected] of [[429, 503], [503, 503], [500, 502]]) {
      const world = createWorld({ wikiResponse: () => new Response("UPSTREAM-BODY", { status }) });
      const response = await createClient(world).request("/v1/pcgw/cargo?steam=1");
      expect(response.status).toBe(expected);
      if (expected === 503) expect(response.headers.get("retry-after")).toBe("30");
    }
    const world = createWorld({ wikiResponse: () => new Response("UPSTREAM-BODY") });
    const response = await createClient(world).request("/v1/pcgw/cargo?steam=1");
    expect(response.status).toBe(502);
  });

  it("reports an unconfigured Worker as 503 without contacting the wiki", async () => {
    const world = createWorld();
    const response = await createClient(world, { env: { IGDB_CLIENT_ID: "x", IGDB_CLIENT_SECRET: "y" } }).request("/v1/pcgw/cargo?steam=1");
    expect(response.status).toBe(503);
    expect(response.json()).toEqual({ error: "not_configured" });
    expect(world.calls).toHaveLength(0);
  });

  it("caches wiki answers so a repeated lookup does not reach the wiki", async () => {
    const world = createWorld({ wikiResponse: () => new Response(WIKI_JSON) });
    const client = createClient(world);
    await client.request("/v1/pcgw/cargo?steam=1");
    const calls = world.calls.length;
    const hit = await client.request("/v1/pcgw/cargo?steam=1");
    expect(hit.headers.get("x-delos-cache")).toBe("HIT");
    expect(world.calls).toHaveLength(calls);
  });
});
