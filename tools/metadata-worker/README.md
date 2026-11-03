# delos-meta - metadata proxy Worker

A small Cloudflare Worker (Workers Free plan) that holds the IGDB and PCGamingWiki credentials so they never
ship inside the Delos APK (issue #59). The app calls fixed, validated endpoints here; the Worker builds the
upstream IGDB/PCGamingWiki queries itself and returns the providers' raw JSON, so the app's parsing, matching,
caching and backoff are unchanged. It is **not** an open relay: there is no pass-through of queries, paths or
headers.

HowLongToBeat has no credentials and is still queried directly by the app.

## Secrets

Worker secrets (never in the repository, never in `wrangler.toml`, never logged):

| Secret | Meaning |
| --- | --- |
| `IGDB_CLIENT_ID` | Twitch/IGDB application client id |
| `IGDB_CLIENT_SECRET` | Twitch/IGDB application client secret |
| `PCGW_BOT_USERNAME` | PCGamingWiki bot-password login name (`User@botname`) |
| `PCGW_BOT_PASSWORD` | PCGamingWiki bot password |

## API

All endpoints are `GET` only and answer `application/json`. Anything else is `405` (with `Allow: GET`), an
unknown path is `404`. CORS is not enabled (native app only, no preflight). Every response carries
`Cache-Control`: `public, max-age=21600` on success, `no-store` on errors. Parameter names are fixed, each may
appear once, unknown parameters are `400`. Strings are trimmed; control characters are rejected.

| Endpoint | Parameters | Upstream call (built by the Worker) | Success body |
| --- | --- | --- | --- |
| `/v1/igdb/games` | exactly one of `search=<term>` (1-128 code points) or `id=<positive int, max 2000000000>` | `POST https://api.igdb.com/v4/games` with `search "<escaped term>"; fields name,first_release_date,summary,platforms,rating_count,total_rating_count,genres.id,genres.name,themes.id,themes.name,external_games.uid,external_games.external_game_source; limit 50;` or `fields ...; where id = <id>; limit 1;` | IGDB's JSON array, unchanged |
| `/v1/pcgw/cargo` | exactly one of `steam=<id>[,<id>...]` (1-20 distinct decimal Steam app ids, max 4294967295) or `page=<title>` | Cargo query on `Game,Video` (`Game._pageName=Page,Game.Steam_AppID=Steam`, join `Game._pageID=Video._pageID`, `limit 10`) where `Game.Steam_AppID HOLDS "<id>" OR ...` (ids ascending) or `Game._pageName = "<escaped title>"` | MediaWiki JSON (`{"cargoquery":[...]}`) |
| `/v1/pcgw/page` | `title=<title>` | `action=parse&page=<title>&prop=wikitext` | MediaWiki JSON (`{"parse":{"wikitext":{"*":...}}}`) |
| `/v1/pcgw/redirect` | `title=<title>` | `action=query&titles=<title>&redirects=1` | MediaWiki JSON (`{"query":{"redirects":[...]}}`) |
| `/v1/pcgw/search` | `q=<title>` | `action=query&list=search&srsearch=<q>&srnamespace=0&srlimit=5` | MediaWiki JSON (`{"query":{"search":[...]}}`) |

A wiki `<title>` is 1-200 code points without control characters and without `# < > [ ] { | }` (MediaWiki
forbids those in page titles, so no wiki page can carry them). IGDB search escaping: `\` and `"` are escaped,
line breaks become spaces (the rules of the former in-app client).

### Errors

The body is always `{"error":"<code>"}`; upstream bodies, upstream error text, tokens and secrets are never
included.

| Status | `error` | Meaning |
| --- | --- | --- |
| 400 | `invalid_request` | validation failed |
| 404 | `not_found` | unknown path |
| 405 | `method_not_allowed` | not a GET |
| 413 | `body_not_allowed` | a request body was announced |
| 414 | `uri_too_long` | URL longer than 2048 characters |
| 431 | `headers_too_large` | request headers larger than 8 KiB |
| 429 | `rate_limited` | per-client rate limit; honor `Retry-After` (30 s) |
| 503 | `busy` | too many queued upstream calls in this isolate; `Retry-After` 30 s |
| 503 | `upstream_busy` | the provider answered 429/503; `Retry-After` 30 s |
| 503 | `not_configured` | a secret is missing |
| 502 | `upstream_auth` | token/login rejected by the provider |
| 502 | `upstream_error` | provider error, malformed answer, redirect, network failure |
| 504 | `upstream_timeout` | provider did not answer within 8 s |
| 500 | `internal` | unexpected Worker error |

The app treats every non-2xx answer and every network error as a provider failure that uses its existing
per-provider backoff.

## Behavior and abuse limits

- **Validation**: strict allow-lists, length caps and integer ranges (above); queries are built from the
  validated values only.
- **Per-client rate limit**: the Workers Rate Limiting binding `RATE_LIMITER` (`wrangler.toml`, 200 requests
  per 60 s, keyed by `CF-Connecting-IP`; counters are approximate and per Cloudflare location). It is counted
  before validation, so malformed floods are limited too. If the binding is missing or errors, an in-isolate
  token bucket (burst 60, refill 200 per minute per client, up to 2000 clients tracked) is used instead; it is
  per isolate and therefore approximate.
- **Upstream pacing**: per isolate, IGDB calls are spaced 260 ms apart (IGDB allows 4 requests/s) and
  PCGamingWiki calls 550 ms; a call that would wait longer than 2.5 s / 4 s is refused with 503 `busy`.
- **Caching**: successful lookups are cached for 6 hours, keyed by the normalized, validated URL only (not by
  headers or the client). Two layers: an in-isolate LRU (256 entries) and the Workers Cache API. The Cache API
  stores only on custom domains, not on `*.workers.dev`, so on workers.dev the in-isolate layer does the work.
  Identical concurrent lookups share one upstream call. Errors are never cached.
- **Twitch app token**: fetched with the client credentials, kept in memory until 60 s before expiry, refreshed
  once when IGDB answers 401 (a second 401 is `upstream_auth`), and never returned. A failing token request is
  not repeated for 30 s.
- **PCGamingWiki session**: the bot login (login token, then `action=login`) is done by the Worker, the session
  cookies are kept in memory per isolate, and one re-login plus one retry happens when the wiki reports a lost
  session. A definitely refused login is not retried for 10 minutes (a wrong or rotated password must not be
  hammered into a lockout); throttling is retried.
- **Redirects** from providers are never followed (`redirect: "manual"`), so a 307/308 cannot replay a
  credential-carrying POST elsewhere.
- **Logging**: nothing per request. On a failure (status >= 500) one line with route, status, error code and the
  first 12 hex digits of a SHA-256 of the normalized query; never the query text, secrets or upstream text.
- **Free plan limits**: 100,000 requests/day (beyond that Cloudflare answers with error 1027 before the Worker
  runs), 10 ms CPU per request (upstream waiting does not count), 50 subrequests per request (a request makes
  at most about 4: token, or login token + login + query).

## Develop and test

Node.js 20+ is required. Tests use mocked `fetch`, clock and Cache API; there is no network.

```powershell
cd tools\metadata-worker
npm install
npm test
```

## Deploy

One time: `npx wrangler login` (or set `CLOUDFLARE_API_TOKEN`). Then:

```powershell
cd tools\metadata-worker
npm install
npx wrangler deploy            # creates/updates the Worker "delos-meta"; prints https://delos-meta.<account>.workers.dev
.\set-secrets.ps1 -CheckOnly   # names only: which of the four values were found
.\set-secrets.ps1              # uploads the four secrets
```

`set-secrets.ps1` reads `DELOS_IGDB_CLIENT_ID`, `DELOS_IGDB_CLIENT_SECRET`, `DELOS_PCGW_BOT_USERNAME` and
`DELOS_PCGW_BOT_PASSWORD` from environment variables (when set and not blank) or from the repository-root
`local.properties` (git-ignored; `-PropertiesFile <path>` overrides it), and pipes each value to
`npx wrangler secret put <NAME>` on standard input. A value is never echoed, logged or passed as an argument;
the output names only the secrets.

Manual alternative for one secret (the value is typed at the hidden prompt):

```powershell
npx wrangler secret put IGDB_CLIENT_SECRET
```

Then put the Worker URL into the app build as `DELOS_METADATA_URL` (environment variable or
`local.properties`, see `docs/WINDOWS_SETUP.md`) and rebuild.

## Smoke test

Use `curl.exe` (PowerShell's `curl` is an alias). Replace `$URL` with the Worker URL.

```powershell
$URL = "https://delos-meta.<account>.workers.dev"
curl.exe -s "$URL/v1/igdb/games?search=The%20Witcher%203"            # IGDB array
curl.exe -s "$URL/v1/igdb/games?id=1942"                              # one game
curl.exe -s "$URL/v1/pcgw/cargo?steam=292030"                         # Cargo rows
curl.exe -s "$URL/v1/pcgw/cargo?page=Elden%20Ring"
curl.exe -s "$URL/v1/pcgw/page?title=Elden%20Ring"                    # wikitext (large)
curl.exe -s "$URL/v1/pcgw/redirect?title=Sea%20of%20Stars%3A%20Sunset%20Edition"
curl.exe -s "$URL/v1/pcgw/search?q=Witcher%203"
# Negative checks
curl.exe -s -i "$URL/v1/igdb/games?search="                           # 400 invalid_request
curl.exe -s -i -X POST "$URL/v1/igdb/games?search=x"                  # 405
curl.exe -s -i "$URL/v1/other"                                        # 404
curl.exe -s -i "$URL/v1/igdb/games?id=1942" -H "Origin: https://example.com"   # no Access-Control-* headers
```

A repeated identical request answers with `X-Delos-Cache: HIT`. For a manual rate-limit check, fire more than
200 requests within a minute from one IP (for example `1..250 | % { curl.exe -s -o NUL -w "%{http_code}\n" "$URL/v1/igdb/games?id=0" } | sort | group`)
and expect `429` with `Retry-After`.

## Logs

```powershell
npx wrangler tail delos-meta --format pretty
```

Only failure lines appear (route, status, code, query hash).

## Rotate a secret

Rotate the IGDB client secret in the Twitch developer console (or the PCGamingWiki bot password on
`Special:BotPasswords`), put the new value into `local.properties`, then run `.\set-secrets.ps1` again (a
secret upload takes effect for new requests immediately; the cached Twitch token / wiki session of a running
isolate expire or are renewed on the next rejection). No app rebuild is needed because the app never holds
the secrets. To drop a secret entirely: `npx wrangler secret delete <NAME>`.
