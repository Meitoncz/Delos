// Strict input validation. Every upstream query is built from the values returned here, never from raw input.

const CONTROL = /[\u0000-\u001F\u007F-\u009F\u2028\u2029]/u;
// Characters MediaWiki forbids in page titles; no wiki page can carry one.
const WIKI_TITLE_FORBIDDEN = /[#<>[\]{|}]/u;

export const MAX_TERM_LENGTH = 128;
export const MAX_TITLE_LENGTH = 200;
export const MAX_STEAM_IDS = 20;
export const MAX_IGDB_ID = 2_000_000_000;
export const MAX_STEAM_APP_ID = 4_294_967_295;
export const MAX_STEAM_TAGS_APP_ID = 2_000_000_000;

function codePoints(value) {
  let count = 0;
  for (const _ of value) count++;
  return count;
}

/** Exactly the allowed query parameters, each present once; returns the single value map or null. */
export function singleParams(searchParams, allowed, { required = [] } = {}) {
  const values = {};
  for (const key of new Set(searchParams.keys())) {
    if (!allowed.includes(key)) return null;
    const all = searchParams.getAll(key);
    if (all.length !== 1) return null;
    values[key] = all[0];
  }
  for (const key of required) if (!(key in values)) return null;
  return values;
}

/** A free-text search term (a game name): trimmed, 1..128 code points, no control characters. */
export function validTerm(value) {
  if (typeof value !== "string") return null;
  const term = value.trim();
  if (term.length === 0 || CONTROL.test(term) || codePoints(term) > MAX_TERM_LENGTH) return null;
  return term;
}

/** A wiki page title: like a term, without the characters no MediaWiki title can contain, up to 200 code points. */
export function validTitle(value) {
  if (typeof value !== "string") return null;
  const title = value.trim();
  if (title.length === 0 || CONTROL.test(title) || WIKI_TITLE_FORBIDDEN.test(title) || codePoints(title) > MAX_TITLE_LENGTH) return null;
  return title;
}

/** Canonical decimal integer (no sign, no leading zero) in 1..max, as a number; otherwise null. */
export function validId(value, max) {
  if (typeof value !== "string" || !/^[1-9][0-9]{0,9}$/.test(value)) return null;
  const id = Number(value);
  return id <= max ? id : null;
}

/** Comma-separated Steam app ids, 1..20 of them; returned distinct and ascending, or null. */
export function validSteamIds(value) {
  if (typeof value !== "string") return null;
  const parts = value.split(",");
  if (parts.length > MAX_STEAM_IDS) return null;
  const ids = new Set();
  for (const part of parts) {
    const id = validId(part, MAX_STEAM_APP_ID);
    if (id === null) return null;
    ids.add(id);
  }
  return [...ids].sort((a, b) => a - b);
}
