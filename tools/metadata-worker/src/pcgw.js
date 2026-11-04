import { HttpError, readText, rejectStatus, upstreamFetch } from "./errors.js";

const API = "https://www.pcgamingwiki.com/w/api.php";
const USER_AGENT = "Delos-metadata-proxy/1.0 (https://github.com/Meitoncz/Delos)";
const REFUSED_LOGIN_COOLDOWN_MS = 10 * 60 * 1000;
// MediaWiki error codes that mean "the session is gone"; anything else is a request problem, not an auth one.
const SESSION_ERRORS = new Set(["assertuserfailed", "assertnameduserfailed", "readapidenied", "notloggedin"]);

/** Cargo string literal escaping, identical to the app's former PcGamingWikiClient.cargoLiteral. */
export function cargoLiteral(value) {
  return value.replaceAll("\\", "\\\\").replaceAll('"', '\\"');
}

export const steamWhere = (ids) => ids.map((id) => `Game.Steam_AppID HOLDS "${id}"`).join(" OR ");
export const pageWhere = (title) => `Game._pageName = "${cargoLiteral(title)}"`;

// The parameter sets below are exactly the app's former calls, in the same order.
export const cargoParams = (where) => [
  ["action", "cargoquery"], ["tables", "Game,Video"],
  ["fields", "Game._pageName=Page,Game.Steam_AppID=Steam"],
  ["join_on", "Game._pageID=Video._pageID"], ["where", where], ["limit", "10"],
  ["assert", "user"],
];
export const parseParams = (title) => [["action", "parse"], ["page", title], ["prop", "wikitext"], ["assert", "user"]];
export const redirectParams = (title) => [["action", "query"], ["titles", title], ["redirects", "1"], ["assert", "user"]];
export const searchParams = (term) => [
  ["action", "query"], ["list", "search"], ["srsearch", term], ["srnamespace", "0"], ["srlimit", "5"], ["assert", "user"],
];

function withFormat(pairs) {
  return [...pairs, ["format", "json"]];
}

function setCookieLines(headers) {
  return typeof headers.getSetCookie === "function" ? headers.getSetCookie() : [];
}

/**
 * PCGamingWiki access through one bot-password session held in memory (cookies per isolate). A session error
 * triggers exactly one re-login and one retry. The wiki's JSON is returned unchanged.
 */
export function createPcgw({ fetch: fetchFn, now, gate }) {
  const cookies = new Map(); // name -> value
  let generation = 0; // increments with every successful login
  let loggedIn = false;
  let loggingIn = null;
  let refusedUntil = 0;

  function absorb(headers) {
    for (const line of setCookieLines(headers)) {
      const [pair, ...attributes] = line.split(";");
      const equals = pair.indexOf("=");
      if (equals <= 0) continue;
      const name = pair.slice(0, equals).trim();
      const value = pair.slice(equals + 1).trim();
      let expired = value === "";
      for (const attribute of attributes) {
        const [key, ...rest] = attribute.split("=");
        const text = rest.join("=").trim();
        const lower = key.trim().toLowerCase();
        if (lower === "max-age" && Number(text) <= 0) expired = true;
        if (lower === "expires" && Date.parse(text) <= now()) expired = true;
      }
      if (expired) cookies.delete(name);
      else cookies.set(name, value);
    }
  }

  /** One paced wiki call; returns the parsed object and its raw text. */
  async function call(pairs, { post = false } = {}) {
    await gate();
    const params = new URLSearchParams(withFormat(pairs));
    const headers = { "User-Agent": USER_AGENT, Accept: "application/json" };
    if (cookies.size > 0) headers.Cookie = [...cookies].map(([name, value]) => `${name}=${value}`).join("; ");
    const init = post
      ? { method: "POST", headers: { ...headers, "Content-Type": "application/x-www-form-urlencoded" }, body: params }
      : { method: "GET", headers };
    const response = await upstreamFetch(fetchFn, post ? API : `${API}?${params}`, init);
    absorb(response.headers);
    if (!response.ok) await rejectStatus(response);
    const text = await readText(response);
    let json;
    try {
      json = JSON.parse(text);
    } catch {
      throw new HttpError(502, "upstream_error");
    }
    if (json === null || typeof json !== "object" || Array.isArray(json)) throw new HttpError(502, "upstream_error");
    return { json, text };
  }

  async function login(env) {
    if (!env.PCGW_BOT_USERNAME || !env.PCGW_BOT_PASSWORD) throw new HttpError(503, "not_configured");
    if (now() < refusedUntil) throw new HttpError(502, "upstream_auth");
    cookies.clear();
    loggedIn = false;
    const { json: tokens } = await call([["action", "query"], ["meta", "tokens"], ["type", "login"]]);
    const loginToken = tokens.query?.tokens?.logintoken;
    if (typeof loginToken !== "string" || loginToken === "") throw new HttpError(502, "upstream_auth");
    const { json: result } = await call(
      [["action", "login"], ["lgname", env.PCGW_BOT_USERNAME], ["lgpassword", env.PCGW_BOT_PASSWORD], ["lgtoken", loginToken]],
      { post: true },
    );
    const outcome = result.login?.result;
    if (outcome !== "Success") {
      // Only a definite refusal is latched (a wrong or rotated password must not be hammered into a lockout);
      // throttling or an unreadable answer may be transient.
      if (typeof outcome === "string" && outcome !== "Throttled" && outcome !== "NeedToken") refusedUntil = now() + REFUSED_LOGIN_COOLDOWN_MS;
      throw new HttpError(502, "upstream_auth");
    }
    generation++;
    loggedIn = true;
  }

  /** Logs in unless a session newer than [staleGeneration] already exists; concurrent callers share one login. */
  function ensureSession(env, staleGeneration = -1) {
    if (loggedIn && generation > staleGeneration) return Promise.resolve();
    loggingIn ??= login(env).finally(() => {
      loggingIn = null;
    });
    return loggingIn;
  }

  /** Runs one read-only wiki query and returns the wiki's JSON text. */
  async function query(env, pairs) {
    await ensureSession(env);
    for (let attempt = 0; attempt < 2; attempt++) {
      const used = generation;
      const { json, text } = await call(pairs);
      if (json.error || json.errors) {
        if (attempt === 0 && SESSION_ERRORS.has(json.error?.code)) {
          if (generation === used) loggedIn = false;
          await ensureSession(env, used);
          continue;
        }
        // The wiki's error text may echo submitted parameters; it is never propagated.
        throw new HttpError(502, "upstream_error");
      }
      return text;
    }
    throw new HttpError(502, "upstream_error");
  }

  return { query };
}
