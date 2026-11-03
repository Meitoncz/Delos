import { HttpError, discard, readText, rejectStatus, upstreamFetch } from "./errors.js";

// The public store page, read server-side. This is NOT an official API: the markup may change, in which case the
// parser finds no tags and the app falls back to IGDB.
const STORE_HOST = "store.steampowered.com";
const BROWSER_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
// Pre-answered age gate and mature-content prompt, so age-restricted games return their normal page.
const AGE_COOKIE = "birthtime=631152001; lastagecheckage=1-0-1990; wants_mature_content=1";

export const STEAM_TIMEOUT_MS = 5000;
export const MAX_REDIRECTS = 2;
export const MAX_TAGS = 20;
const MAX_TAG_LENGTH = 60;
// The tag block sits near the top of the page; scanning a bounded window keeps the CPU cost small.
const SCAN_WINDOW = 120_000;
const TAG_BLOCK_MARKER = "glance_tags popular_tags";

const NAMED_ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };

/** Decodes the HTML entities a Steam tag name can contain, in one pass (so "&amp;lt;" stays "&lt;"). */
export function unescapeHtml(text) {
  return text.replace(/&(#x[0-9a-f]{1,6}|#[0-9]{1,7}|[a-z]{2,6});/gi, (whole, body) => {
    if (body[0] === "#") {
      const code = body[1] === "x" || body[1] === "X" ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10);
      return Number.isInteger(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : whole;
    }
    return Object.hasOwn(NAMED_ENTITIES, body.toLowerCase()) ? NAMED_ENTITIES[body.toLowerCase()] : whole;
  });
}

function hasClass(attributes, wanted) {
  const match = /\bclass\s*=\s*(?:"([^"]*)"|'([^']*)')/i.exec(attributes);
  return match !== null && (match[1] ?? match[2]).split(/\s+/).includes(wanted);
}

/**
 * The ordered user tags of a store page: the text of every anchor with class "app_tag", minus the "+" button
 * (class "app_tag add_button"). Returns plain strings only; no markup survives.
 */
export function parseTags(html) {
  const marker = html.indexOf(TAG_BLOCK_MARKER);
  const window = html.slice(Math.max(marker, 0), Math.max(marker, 0) + SCAN_WINDOW);
  const tags = [];
  const anchor = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
  for (let match = anchor.exec(window); match !== null && tags.length < MAX_TAGS; match = anchor.exec(window)) {
    if (!hasClass(match[1], "app_tag") || hasClass(match[1], "add_button")) continue;
    const name = unescapeHtml(match[2].replace(/<[^>]*>/g, ""))
      .replace(/[\u0000-\u001F\u007F-\u009F\u2028\u2029]/gu, " ")
      .replace(/\s+/g, " ")
      .trim();
    if (name === "" || name === "+" || name.length > MAX_TAG_LENGTH || /[<>]/.test(name) || tags.includes(name)) continue;
    tags.push(name);
  }
  return tags;
}

/**
 * Steam store tags with the app's whole server-side policy: pacing, a 5 s timeout, at most two same-host
 * redirects, and a redirect to the store front page (a removed game) or an unreadable page meaning "no tags".
 */
export function createSteam({ fetch: fetchFn, gate }) {
  /** Returns the page HTML, or null when the store has no such page. */
  async function page(appId) {
    let url = new URL(`https://${STORE_HOST}/app/${appId}/?l=english`);
    const headers = { "User-Agent": BROWSER_USER_AGENT, Cookie: AGE_COOKIE, Accept: "text/html", "Accept-Language": "en-US,en;q=0.8" };
    for (let redirects = 0; ; redirects++) {
      const response = await upstreamFetch(fetchFn, url.href, { method: "GET", headers }, STEAM_TIMEOUT_MS);
      if (response.status >= 300 && response.status < 400) {
        await discard(response);
        const target = resolveLocation(response.headers.get("location"), url);
        if (target === null || redirects >= MAX_REDIRECTS) throw new HttpError(502, "upstream_error");
        // The front page (or any page that is not an app page) is where Steam sends removed or unavailable apps.
        if (target.pathname === "/" || target.pathname === "") return null;
        url = target;
        continue;
      }
      if (response.status === 404) {
        await discard(response);
        return null;
      }
      if (!(response.status >= 200 && response.status < 300)) await rejectStatus(response);
      return readText(response);
    }
  }

  /** The JSON body for one app id: {"app":<id>,"tags":[...]}. */
  async function tags(appId) {
    await gate();
    const html = await page(appId);
    return JSON.stringify({ app: appId, tags: html === null ? [] : parseTags(html) });
  }

  return { tags };
}

function resolveLocation(location, base) {
  if (typeof location !== "string" || location === "") return null;
  let target;
  try {
    target = new URL(location, base);
  } catch {
    return null;
  }
  return target.protocol === "https:" && target.host === STORE_HOST ? target : null;
}
