import { describe, expect, it } from "vitest";
import { createCacheApi, createClient, createWorld, SECRETS } from "./harness.js";
import { buildIdQuery, buildSearchQuery, escapeApicalypse } from "../src/igdb.js";

// Copied verbatim from the app's former IgdbMetadataClient (FIELDS, "search ... limit 50;", "where id = N; limit 1;").
const APP_FIELDS = "fields name,first_release_date,summary,platforms,rating_count,total_rating_count,genres.id,genres.name,themes.id,themes.name,external_games.uid,external_games.external_game_source;";
const RAW = '[{"id":1942,"name":"The Witcher 3: Wild Hunt","external_games":[{"external_game_source":1,"uid":"292030"}]}]';

const igdb = (query) => `/v1/igdb/games?${query}`;

describe("IGDB query construction", () => {
  it("builds exactly the queries the app used to send", () => {
    expect(buildSearchQuery("Game")).toBe(`search "Game"; ${APP_FIELDS} limit 50;`);
    expect(buildIdQuery(1942)).toBe(`${APP_FIELDS} where id = 1942; limit 1;`);
  });

  it("escapes backslashes and quotes and flattens line breaks like the app", () => {
    expect(escapeApicalypse('Game "edition" \\ x\r\ny')).toBe('Game \\"edition\\" \\\\ x  y');
    expect(buildSearchQuery('Game "edition"')).toBe(`search "Game \\"edition\\""; ${APP_FIELDS} limit 50;`);
  });

  it("sends the search and id queries to /v4/games with the IGDB headers and no redirects", async () => {
    const world = createWorld({ igdbResponse: () => new Response(RAW) });
    const client = createClient(world);
    expect((await client.request(igdb("search=Game%20%22edition%22"))).status).toBe(200);
    expect((await client.request(igdb("id=1942"))).status).toBe(200);
    expect(world.igdbBodies).toEqual([
      `search "Game \\"edition\\""; ${APP_FIELDS} limit 50;`,
      `${APP_FIELDS} where id = 1942; limit 1;`,
    ]);
    const call = world.calls.find((c) => c.url.host === "api.igdb.com");
    expect(call.url.href).toBe("https://api.igdb.com/v4/games");
    expect(call.method).toBe("POST");
    expect(call.headers.get("client-id")).toBe(SECRETS.IGDB_CLIENT_ID);
    expect(call.headers.get("authorization")).toBe("Bearer tok-1");
    expect(call.redirect).toBe("manual");
  });

  it("returns the IGDB array text unchanged with a long Cache-Control", async () => {
    const world = createWorld({ igdbResponse: () => new Response(RAW) });
    const response = await createClient(world).request(igdb("search=Witcher"));
    expect(response.text).toBe(RAW);
    expect(response.headers.get("content-type")).toContain("application/json");
    expect(response.headers.get("cache-control")).toBe("public, max-age=21600");
  });
});

describe("IGDB request validation", () => {
  const bad = [
    "", "search=", "search=%20%20", "id=", "id=0", "id=-1", "id=1.5", "id=abc", "id=01", "id=99999999999",
    "search=a&id=1", "search=a&search=b", "id=1&id=2", "search=a&limit=500", "where=id%3D1", "query=anything",
    `search=${"a".repeat(129)}`, "search=a%0Ab", "search=a%00b",
  ];
  for (const query of bad) {
    it(`rejects ?${query.slice(0, 40)}`, async () => {
      const world = createWorld();
      const response = await createClient(world).request(igdb(query));
      expect(response.status).toBe(400);
      expect(response.json()).toEqual({ error: "invalid_request" });
      expect(response.headers.get("cache-control")).toBe("no-store");
      expect(world.calls).toHaveLength(0);
    });
  }
});

describe("Twitch token", () => {
  it("is fetched once, cached across lookups and never returned", async () => {
    const world = createWorld({ igdbResponse: () => new Response(RAW) });
    const client = createClient(world);
    for (const name of ["A", "B", "C"]) {
      const response = await client.request(igdb(`search=${name}`));
      expect(response.status).toBe(200);
    }
    expect(world.tokenCalls).toBe(1);
    const tokenCall = world.calls.find((c) => c.url.host === "id.twitch.tv");
    expect(tokenCall.method).toBe("POST");
    expect(tokenCall.url.search).toBe("");
    const form = new URLSearchParams(tokenCall.body);
    expect(form.get("client_id")).toBe(SECRETS.IGDB_CLIENT_ID);
    expect(form.get("client_secret")).toBe(SECRETS.IGDB_CLIENT_SECRET);
    expect(form.get("grant_type")).toBe("client_credentials");
  });

  it("is renewed after it expires", async () => {
    const world = createWorld({ igdbResponse: () => new Response(RAW) });
    const client = createClient(world);
    await client.request(igdb("search=A"));
    world.clock += 3600 * 1000;
    await client.request(igdb("search=B"));
    expect(world.tokenCalls).toBe(2);
    expect(world.calls.filter((c) => c.url.host === "api.igdb.com").map((c) => c.headers.get("authorization"))).toEqual(["Bearer tok-1", "Bearer tok-2"]);
  });

  it("is refreshed exactly once when IGDB answers 401, then the request is retried once", async () => {
    let games = 0;
    const world = createWorld({
      igdbResponse: (call) => {
        games++;
        return games === 1 ? new Response("UPSTREAM-BODY", { status: 401 }) : new Response(RAW, { headers: { "x-auth": call.headers.get("authorization") } });
      },
    });
    const response = await createClient(world).request(igdb("search=Game"));
    expect(response.status).toBe(200);
    expect(response.text).toBe(RAW);
    expect(world.tokenCalls).toBe(2);
    expect(games).toBe(2);
    expect(world.calls.filter((c) => c.url.host === "api.igdb.com").map((c) => c.headers.get("authorization"))).toEqual(["Bearer tok-1", "Bearer tok-2"]);
  });

  it("does not renew twice when IGDB keeps answering 401", async () => {
    const world = createWorld({ igdbResponse: () => new Response("UPSTREAM-BODY", { status: 401 }) });
    const response = await createClient(world).request(igdb("search=Game"));
    expect(response.status).toBe(502);
    expect(response.json()).toEqual({ error: "upstream_auth" });
    expect(world.tokenCalls).toBe(2);
    expect(world.igdbBodies).toHaveLength(2);
  });

  it("concurrent lookups share one token request", async () => {
    const world = createWorld({ igdbResponse: () => new Response(RAW) });
    const client = createClient(world);
    const results = await Promise.all(["A", "B", "C"].map((name) => client.request(igdb(`search=${name}`))));
    expect(results.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(world.tokenCalls).toBe(1);
  });

  it("a failing token endpoint is a generic 502 and is not hammered", async () => {
    const world = createWorld({ tokenResponse: () => new Response(`UPSTREAM-BODY ${SECRETS.IGDB_CLIENT_SECRET}`, { status: 403 }) });
    const client = createClient(world);
    const first = await client.request(igdb("search=A"));
    expect(first.status).toBe(502);
    expect(first.json()).toEqual({ error: "upstream_auth" });
    expect((await client.request(igdb("search=B"))).status).toBe(502);
    expect(world.tokenCalls).toBe(1);
    world.clock += 31_000;
    await client.request(igdb("search=C"));
    expect(world.tokenCalls).toBe(2);
  });

  it("an unreadable token answer is a generic 502", async () => {
    const world = createWorld({ tokenResponse: () => new Response("{}") });
    const response = await createClient(world).request(igdb("search=A"));
    expect(response.status).toBe(502);
    expect(response.json()).toEqual({ error: "upstream_auth" });
  });
});

describe("IGDB upstream failures", () => {
  it("maps upstream 429 and 503 to 503 with Retry-After and never echoes the body", async () => {
    for (const status of [429, 503]) {
      const world = createWorld({ igdbResponse: () => new Response("UPSTREAM-BODY", { status }) });
      const response = await createClient(world).request(igdb("search=Game"));
      expect(response.status).toBe(503);
      expect(response.json()).toEqual({ error: "upstream_busy" });
      expect(response.headers.get("retry-after")).toBe("30");
      expect(response.headers.get("cache-control")).toBe("no-store");
    }
  });

  it("maps other upstream errors, malformed JSON and non-arrays to a generic 502", async () => {
    for (const answer of [new Response("UPSTREAM-BODY", { status: 500 }), new Response("UPSTREAM-BODY"), new Response('{"id":1}'), new Response("UPSTREAM-BODY", { status: 302, headers: { location: "https://evil.example/" } })]) {
      const world = createWorld({ igdbResponse: () => answer });
      const response = await createClient(world).request(igdb("search=Game"));
      expect(response.status).toBe(502);
      expect(response.json()).toEqual({ error: "upstream_error" });
    }
  });

  it("maps a network failure to a generic 502 without the exception text", async () => {
    const world = createWorld({ igdbResponse: () => { throw new TypeError(`connect failed ${SECRETS.IGDB_CLIENT_SECRET}`); } });
    const response = await createClient(world).request(igdb("search=Game"));
    expect(response.status).toBe(502);
    expect(response.json()).toEqual({ error: "upstream_error" });
  });

  it("reports an unconfigured Worker as 503 without contacting upstreams", async () => {
    const world = createWorld();
    const response = await createClient(world, { env: {} }).request(igdb("search=Game"));
    expect(response.status).toBe(503);
    expect(response.json()).toEqual({ error: "not_configured" });
    expect(world.calls).toHaveLength(0);
  });
});

describe("response cache", () => {
  it("a repeated lookup is answered from cache without an upstream call", async () => {
    const world = createWorld({ igdbResponse: () => new Response(RAW) });
    const client = createClient(world);
    const first = await client.request(igdb("search=Game"));
    const second = await client.request(igdb("search=%20Game%20"));
    expect(first.headers.get("x-delos-cache")).toBe("MISS");
    expect(second.headers.get("x-delos-cache")).toBe("HIT");
    expect(second.text).toBe(RAW);
    expect(world.igdbBodies).toHaveLength(1);
  });

  it("expires after six hours", async () => {
    const world = createWorld({ igdbResponse: () => new Response(RAW) });
    const client = createClient(world);
    await client.request(igdb("id=5"));
    world.clock += 21_599_000;
    await client.request(igdb("id=5"));
    expect(world.igdbBodies).toHaveLength(1);
    world.clock += 2_000;
    await client.request(igdb("id=5"));
    expect(world.igdbBodies).toHaveLength(2);
  });

  it("the Cache API serves a fresh isolate and is keyed by the normalized URL only", async () => {
    const cacheApi = createCacheApi();
    const world = createWorld({ igdbResponse: () => new Response(RAW) });
    await createClient(world, { cacheApi }).request(igdb("search=Game"));
    expect([...cacheApi.store.keys()]).toEqual(["https://delos-meta.example.workers.dev/v1/igdb/games?search=Game"]);
    const fresh = createClient(world, { cacheApi });
    const hit = await fresh.request(igdb("search=%20Game"));
    expect(hit.headers.get("x-delos-cache")).toBe("HIT");
    expect(hit.text).toBe(RAW);
    expect(world.igdbBodies).toHaveLength(1);
  });

  it("errors are never cached", async () => {
    let status = 500;
    const world = createWorld({ igdbResponse: () => new Response(RAW, { status }) });
    const client = createClient(world);
    expect((await client.request(igdb("search=Game"))).status).toBe(502);
    status = 200;
    expect((await client.request(igdb("search=Game"))).status).toBe(200);
    expect(world.igdbBodies).toHaveLength(2);
  });

  it("concurrent identical lookups share one upstream call", async () => {
    const world = createWorld({ igdbResponse: () => new Response(RAW) });
    const client = createClient(world);
    await Promise.all([1, 2, 3].map(() => client.request(igdb("search=Game"))));
    expect(world.igdbBodies).toHaveLength(1);
  });
});
