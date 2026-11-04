import { describe, expect, it } from "vitest";
import { parseTags, unescapeHtml } from "../src/steam.js";
import { createCacheApi, createClient, createWorld } from "./harness.js";

// Fixture snippets in the shape of the public store page (trimmed to the parts the parser looks at).
const NORMAL_PAGE = `<html><head><title>Cronos on Steam</title></head><body>
<a href="https://store.steampowered.com/">Store</a>
<div class="glance_tags popular_tags" data-appid="2101960">
  <a href="https://store.steampowered.com/tags/en/Survival%20Horror/?snr=1_5_9__409" class="app_tag" style="display: none;">
    Survival Horror\t\t\t</a><a href="https://store.steampowered.com/tags/en/Horror/" class="app_tag" style="display: none;">Horror</a><a href="https://store.steampowered.com/tags/en/Sci-fi/" class="app_tag" style="display: none;">
    Sci-fi</a><a class="app_tag" href="https://store.steampowered.com/tags/en/Third-Person%20Shooter/">Third-Person Shooter</a>
  <div class="app_tag add_button" data-panel='{"focusable":true}' onclick="ShowAppTagModal( 2101960 )">+</div>
</div>
<a href="/other" class="not_a_tag">Nope</a></body></html>`;

const ENTITY_PAGE = `<div class="glance_tags popular_tags">
<a class="app_tag" href="/tags/1">Sim &amp; Management</a>
<a class="app_tag" href="/tags/2">Shoot &#39;Em Up</a>
<a class="app_tag" href="/tags/3">Beat &#x27;em up</a>
<a class="app_tag" href="/tags/4">Tom&apos;s &quot;Best&quot; &amp;lt;b&amp;gt;</a>
<a class="app_tag add_button" href="javascript:void(0)">+</a>
</div>`;

const AGE_GATE_PAGE = `<html><body><div class="agegate_birthday_selector"><select name="ageDay"></select>
<a href="/agecheck/app/1/" class="btn_medium">View Page</a></div></body></html>`;

const ADD_BUTTON_ONLY = `<div class="glance_tags popular_tags"><a href="#" class="app_tag add_button">+</a></div>`;

const html = (body, status = 200, headers = []) => new Response(body, { status, headers: new Headers(headers) });
const redirect = (location, status = 302) => new Response(null, { status, headers: { location } });
const steamCalls = (world) => world.calls.filter((c) => c.url.host === "store.steampowered.com");

describe("parseTags", () => {
  it("returns the anchors in page order, trimmed, without the + button or unrelated anchors", () => {
    expect(parseTags(NORMAL_PAGE)).toEqual(["Survival Horror", "Horror", "Sci-fi", "Third-Person Shooter"]);
  });

  it("unescapes entities in one pass", () => {
    expect(parseTags(ENTITY_PAGE)).toEqual(["Sim & Management", "Shoot 'Em Up", "Beat 'em up", `Tom's "Best" &lt;b&gt;`]);
  });

  it("skips the add_button anchor even when it is the only one", () => {
    expect(parseTags(ADD_BUTTON_ONLY)).toEqual([]);
  });

  it("finds nothing on an age gate and on empty input", () => {
    expect(parseTags(AGE_GATE_PAGE)).toEqual([]);
    expect(parseTags("")).toEqual([]);
  });

  it("keeps at most 20 tags, drops duplicates and tags that would carry markup", () => {
    const many = Array.from({ length: 30 }, (_, i) => `<a class="app_tag">T${i}</a>`).join("");
    expect(parseTags(`<div class="glance_tags popular_tags">${many}</div>`)).toHaveLength(20);
    expect(parseTags('<a class="app_tag">A</a><a class="app_tag">A</a><a class="app_tag">&lt;i&gt;</a>')).toEqual(["A"]);
  });

  it("unescapeHtml leaves unknown entities and invalid code points alone", () => {
    expect(unescapeHtml("&bogus; &#0; &#x110000; &amp;")).toBe("&bogus; &#0; &#x110000; &");
  });
});

describe("GET /v1/steam/tags", () => {
  it("fetches the english store page with the age cookie and a browser user agent and returns only JSON", async () => {
    const world = createWorld({ steamResponse: () => html(NORMAL_PAGE) });
    const response = await createClient(world).request("/v1/steam/tags?app=2101960");
    expect(response.status).toBe(200);
    expect(response.json()).toEqual({ app: 2101960, tags: ["Survival Horror", "Horror", "Sci-fi", "Third-Person Shooter"] });
    expect(response.headers.get("content-type")).toContain("application/json");
    expect(response.headers.get("cache-control")).toBe("public, max-age=86400");
    expect(response.text).not.toMatch(/[<>]/);
    const [call] = steamCalls(world);
    expect(steamCalls(world)).toHaveLength(1);
    expect(call.url.href).toBe("https://store.steampowered.com/app/2101960/?l=english");
    expect(call.method).toBe("GET");
    expect(call.redirect).toBe("manual");
    expect(call.headers.get("cookie")).toBe("birthtime=631152001; lastagecheckage=1-0-1990; wants_mature_content=1");
    expect(call.headers.get("user-agent")).toContain("Mozilla/5.0");
  });

  it("returns an empty list (200) for an age gate without tags and for a page without a tag block", async () => {
    for (const page of [AGE_GATE_PAGE, "<html>plain</html>", ADD_BUTTON_ONLY]) {
      const world = createWorld({ steamResponse: () => html(page) });
      const response = await createClient(world).request("/v1/steam/tags?app=10");
      expect(response.status).toBe(200);
      expect(response.json()).toEqual({ app: 10, tags: [] });
      expect(response.text).not.toMatch(/[<>]/);
    }
  });

  it("returns an empty list when the store redirects to the front page (removed game), without following it", async () => {
    const world = createWorld({ steamResponse: () => redirect("https://store.steampowered.com/") });
    const response = await createClient(world).request("/v1/steam/tags?app=10");
    expect(response.status).toBe(200);
    expect(response.json()).toEqual({ app: 10, tags: [] });
    expect(steamCalls(world)).toHaveLength(1);
  });

  it("returns an empty list for a 404", async () => {
    const world = createWorld({ steamResponse: () => html("UPSTREAM-BODY", 404) });
    const response = await createClient(world).request("/v1/steam/tags?app=10");
    expect(response.status).toBe(200);
    expect(response.json()).toEqual({ app: 10, tags: [] });
  });

  it("follows same-host redirects up to two times and no further", async () => {
    let step = 0;
    const world = createWorld({
      steamResponse: () => {
        step++;
        if (step === 1) return redirect("/app/10/Some_Game/?l=english");
        if (step === 2) return redirect("https://store.steampowered.com/app/10/Some_Game/");
        return html(NORMAL_PAGE);
      },
    });
    const response = await createClient(world).request("/v1/steam/tags?app=10");
    expect(response.status).toBe(200);
    expect(response.json().tags[0]).toBe("Survival Horror");
    expect(steamCalls(world).map((c) => c.url.pathname)).toEqual(["/app/10/", "/app/10/Some_Game/", "/app/10/Some_Game/"]);

    const loop = createWorld({ steamResponse: () => redirect("/app/10/loop/") });
    const failed = await createClient(loop).request("/v1/steam/tags?app=10");
    expect(failed.status).toBe(502);
    expect(steamCalls(loop)).toHaveLength(3);
  });

  it("never follows a redirect to another host or protocol", async () => {
    for (const target of ["https://evil.example/app/10/", "http://store.steampowered.com/app/10/", "//evil.example/x"]) {
      const world = createWorld({ steamResponse: () => redirect(target) });
      const response = await createClient(world).request("/v1/steam/tags?app=10");
      expect(response.status).toBe(502);
      expect(response.json()).toEqual({ error: "upstream_error" });
      expect(steamCalls(world)).toHaveLength(1);
    }
  });

  it("maps throttling to 503 with Retry-After and other failures to a generic 502, never relaying the body", async () => {
    for (const [status, expected] of [[429, 503], [503, 503], [403, 502], [500, 502]]) {
      const world = createWorld({ steamResponse: () => html("<html>UPSTREAM-BODY</html>", status) });
      const response = await createClient(world).request("/v1/steam/tags?app=10");
      expect(response.status).toBe(expected);
      expect(response.headers.get("cache-control")).toBe("no-store");
      expect(response.text).not.toMatch(/[<>]|UPSTREAM/);
      if (expected === 503) expect(response.headers.get("retry-after")).toBe("30");
    }
    const failing = createWorld({
      steamResponse: () => {
        throw new TypeError("network down UPSTREAM-ECHO");
      },
    });
    const response = await createClient(failing).request("/v1/steam/tags?app=10");
    expect(response.status).toBe(502);
    expect(response.json()).toEqual({ error: "upstream_error" });
  });

  it("maps a timeout to 504", async () => {
    const world = createWorld({
      steamResponse: () => {
        throw Object.assign(new Error("timed out"), { name: "TimeoutError" });
      },
    });
    const response = await createClient(world).request("/v1/steam/tags?app=10");
    expect(response.status).toBe(504);
    expect(response.json()).toEqual({ error: "upstream_timeout" });
  });

  it("does not cache failures", async () => {
    let calls = 0;
    const world = createWorld({ steamResponse: () => (++calls === 1 ? html("x", 500) : html(NORMAL_PAGE)) });
    const client = createClient(world);
    expect((await client.request("/v1/steam/tags?app=10")).status).toBe(502);
    expect((await client.request("/v1/steam/tags?app=10")).status).toBe(200);
  });

  it("caches an answer for 24 hours in both layers", async () => {
    const world = createWorld({ steamResponse: () => html(NORMAL_PAGE) });
    const cacheApi = createCacheApi();
    const client = createClient(world, { cacheApi });
    expect((await client.request("/v1/steam/tags?app=10")).headers.get("x-delos-cache")).toBe("MISS");
    const hit = await client.request("/v1/steam/tags?app=10");
    expect(hit.headers.get("x-delos-cache")).toBe("HIT");
    expect(hit.headers.get("cache-control")).toBe("public, max-age=86400");
    expect(steamCalls(world)).toHaveLength(1);
    expect(cacheApi.store.size).toBe(1);

    world.clock += 23 * 60 * 60 * 1000;
    expect((await client.request("/v1/steam/tags?app=10")).headers.get("x-delos-cache")).toBe("HIT");
    expect(steamCalls(world)).toHaveLength(1);
    world.clock += 2 * 60 * 60 * 1000;
    cacheApi.store.clear();
    expect((await client.request("/v1/steam/tags?app=10")).headers.get("x-delos-cache")).toBe("MISS");
    expect(steamCalls(world)).toHaveLength(2);
  });

  it("serves a repeated lookup from the cache without a second fetch", async () => {
    const world = createWorld({ steamResponse: () => html(NORMAL_PAGE) });
    const client = createClient(world);
    await client.request("/v1/steam/tags?app=10");
    await client.request("/v1/steam/tags?app=10");
    expect(steamCalls(world)).toHaveLength(1);
  });

  it("paces consecutive Steam fetches by 600 ms", async () => {
    const world = createWorld({ steamResponse: () => html(NORMAL_PAGE) });
    const client = createClient(world);
    await client.request("/v1/steam/tags?app=10");
    await client.request("/v1/steam/tags?app=11");
    await client.request("/v1/steam/tags?app=12");
    expect(world.sleeps).toEqual([600, 600]);
  });

  const bad = [
    "/v1/steam/tags", "/v1/steam/tags?app=", "/v1/steam/tags?app=0", "/v1/steam/tags?app=-1", "/v1/steam/tags?app=01",
    "/v1/steam/tags?app=1.5", "/v1/steam/tags?app=abc", "/v1/steam/tags?app=2000000001", "/v1/steam/tags?app=99999999999",
    "/v1/steam/tags?app=1,2", "/v1/steam/tags?app=1&app=2", "/v1/steam/tags?app=1&l=french", "/v1/steam/tags?id=1",
    "/v1/steam/tags?app=1%0A2", "/v1/steam/tags?app=1/../2",
  ];
  for (const path of bad) {
    it(`rejects ${path}`, async () => {
      const world = createWorld();
      const response = await createClient(world).request(path);
      expect(response.status).toBe(400);
      expect(response.json()).toEqual({ error: "invalid_request" });
      expect(world.calls).toHaveLength(0);
    });
  }

  it("accepts the largest allowed id and is GET only", async () => {
    const world = createWorld({ steamResponse: () => html(NORMAL_PAGE) });
    const client = createClient(world);
    expect((await client.request("/v1/steam/tags?app=2000000000")).status).toBe(200);
    const post = await client.request("/v1/steam/tags?app=1", { method: "POST", body: "x" });
    expect(post.status).toBe(405);
    expect(post.headers.get("allow")).toBe("GET");
  });
});
