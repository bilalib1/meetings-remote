# Meetings Remote backend — Cloudflare Worker

The live token backend. A TypeScript port of `../server.py` (which ran on Hetzner+Postgres+Caddy,
torn down 2026-07-11). Same endpoints; **D1** (SQLite) replaces Postgres; Worker **custom domains**
replace Caddy; **Worker secrets** replace the `.env`.

- `src/index.ts` — the worker (all endpoints).
- `schema.sql` — D1 tables `sessions` + `oauth_pending` (mirror the old Postgres schema).
- `wrangler.toml` — bindings, `[vars]` (PUBLIC_BASE, APP_LINK_BASE, ASSETLINKS_JSON), custom domains.

## Hosts
- `api.meetingsremote.app` — API (`/health`, `/sdk-jwt`, `/oauth/*`, `/session`, `/refresh`,
  `/signout`, `/end-stuck-meeting`, `/deauthorize`).
- `meetingsremote.app` — apex pages (`/return`, `/delete`, `/privacy`, `/terms`, `/support`, `/`,
  `/.well-known/assetlinks.json`). The worker serves every path on both hosts.

Rate limiting is at the **CF edge** (zone rule 50 req/10s per IP on `api.*` + Bot Fight Mode),
not in the worker — Workers isolates share no counter.

## Auth (wrangler)
Uses the account **Global API Key** (email `ibbilal0@gmail.com`):
```sh
export CLOUDFLARE_API_KEY="$(cat ~/tmp/cf_globalkey)"
export CLOUDFLARE_EMAIL="ibbilal0@gmail.com"
export CLOUDFLARE_ACCOUNT_ID="3f190e8e67ba21f4454d7c079a42dd71"
```

## First-time provision (already done 2026-07-11)
```sh
npm install
npx wrangler d1 create meetingsremote           # → put database_id in wrangler.toml
npx wrangler d1 execute meetingsremote --remote --file schema.sql
npx wrangler deploy                              # deploys + provisions both custom domains
```
Custom domains require **no pre-existing DNS record** for those hostnames — wrangler creates the
proxied routing record. (The old Hetzner `A api`/`A @` records were deleted first.)

## Secrets (set once; re-set to rotate)
```sh
printf %s "<sdk client id>"     | npx wrangler secret put ZOOM_SDK_CLIENT_ID
printf %s "<sdk client secret>" | npx wrangler secret put ZOOM_SDK_CLIENT_SECRET
printf %s "<oauth client id>"   | npx wrangler secret put ZOOM_OAUTH_CLIENT_ID       # A4
printf %s "<webhook token>"     | npx wrangler secret put ZOOM_WEBHOOK_SECRET_TOKEN  # A4
# ZOOM_OAUTH_CLIENT_SECRET: only if the Marketplace app is NOT a public/PKCE client.
```
As of setup, `ZOOM_OAUTH_CLIENT_ID` + `ZOOM_WEBHOOK_SECRET_TOKEN` are **placeholders** — set the
real values from the Marketplace app (plan A4) and the sign-in flow goes live (until then Zoom
returns `4702 Invalid client_id`, the same awaiting-A4 state as before).

## Redeploy after a code change
```sh
npx wrangler deploy
```

## Verify
```sh
curl -s https://api.meetingsremote.app/health           # {"ok":true}
curl -s https://api.meetingsremote.app/sdk-jwt           # {"token":"<jwt>"}
curl -s "https://api.meetingsremote.app/session?sid=x"   # {"ready":false}
curl -sI https://meetingsremote.app/return               # 200, server: cloudflare
```
