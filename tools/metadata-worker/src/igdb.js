import { HttpError, discard, readText, rejectStatus, upstreamFetch } from "./errors.js";

const TOKEN_URL = "https://id.twitch.tv/oauth2/token";
const GAMES_URL = "https://api.igdb.com/v4/games";
const TOKEN_FAILURE_COOLDOWN_MS = 30_000;

// Field list of the app's IgdbMetadataClient; the golden strings in the tests pin it.
export const FIELDS = "fields name,first_release_date,summary,platforms,rating_count,total_rating_count,genres.id,genres.name,themes.id,themes.name,external_games.uid,external_games.external_game_source;";

/** APIcalypse string escaping, identical to the app's former IgdbMetadataClient.escape. */
export function escapeApicalypse(value) {
  return value.replaceAll("\\", "\\\\").replaceAll('"', '\\"').replaceAll("\n", " ").replaceAll("\r", " ");
}

export function buildSearchQuery(term) {
  return `search "${escapeApicalypse(term)}"; ${FIELDS} limit 50;`;
}

export function buildIdQuery(id) {
  return `${FIELDS} where id = ${id}; limit 1;`;
}

/**
 * IGDB v4 access with a Twitch app token held in memory only. The token is renewed once when IGDB rejects it and
 * is never part of any response. [gate] spaces upstream calls (IGDB allows 4 requests per second).
 */
export function createIgdb({ fetch: fetchFn, now, gate }) {
  let token = null; // { value, deadline }
  let refreshing = null;
  let failedUntil = 0;

  function obtain(env, rejected) {
    if (token && token.value !== rejected && now() < token.deadline) return Promise.resolve(token.value);
    if (refreshing) return refreshing;
    if (now() < failedUntil) return Promise.reject(new HttpError(502, "upstream_auth"));
    refreshing = requestToken(env).finally(() => {
      refreshing = null;
    });
    return refreshing;
  }

  async function requestToken(env) {
    try {
      const body = new URLSearchParams({
        client_id: env.IGDB_CLIENT_ID,
        client_secret: env.IGDB_CLIENT_SECRET,
        grant_type: "client_credentials",
      });
      const response = await upstreamFetch(fetchFn, TOKEN_URL, { method: "POST", body });
      if (!response.ok) {
        await discard(response);
        throw new HttpError(502, "upstream_auth");
      }
      let data;
      try {
        data = JSON.parse(await response.text());
      } catch {
        throw new HttpError(502, "upstream_auth");
      }
      const value = data && data.access_token;
      const seconds = data && data.expires_in;
      if (typeof value !== "string" || value === "" || typeof seconds !== "number" || !(seconds > 0)) {
        throw new HttpError(502, "upstream_auth");
      }
      token = { value, deadline: now() + Math.max(seconds - 60, 1) * 1000 };
      return value;
    } catch (error) {
      failedUntil = now() + TOKEN_FAILURE_COOLDOWN_MS;
      throw error instanceof HttpError ? error : new HttpError(502, "upstream_auth");
    }
  }

  /** Runs one APIcalypse [query] against /games and returns IGDB's JSON array text unchanged. */
  async function games(env, query) {
    if (!env.IGDB_CLIENT_ID || !env.IGDB_CLIENT_SECRET) throw new HttpError(503, "not_configured");
    let used = await obtain(env, null);
    for (let attempt = 0; attempt < 2; attempt++) {
      await gate();
      const response = await upstreamFetch(fetchFn, GAMES_URL, {
        method: "POST",
        headers: {
          "Client-ID": env.IGDB_CLIENT_ID,
          Authorization: `Bearer ${used}`,
          Accept: "application/json",
          "Content-Type": "text/plain; charset=utf-8",
        },
        body: query,
      });
      if (response.status === 401) {
        await discard(response);
        // One renewal is authentication maintenance, not a retry loop.
        if (attempt === 0) {
          used = await obtain(env, used);
          continue;
        }
        throw new HttpError(502, "upstream_auth");
      }
      if (!response.ok) await rejectStatus(response);
      const text = await readText(response);
      let parsed;
      try {
        parsed = JSON.parse(text);
      } catch {
        throw new HttpError(502, "upstream_error");
      }
      if (!Array.isArray(parsed)) throw new HttpError(502, "upstream_error");
      return text;
    }
    throw new HttpError(502, "upstream_auth");
  }

  return { games };
}
