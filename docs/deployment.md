# Server deployment

How the server (`:mission-control:server`) is built, deployed and configured. Run it locally with
`./gradlew :mission-control:server:run` (headless) or `./gradlew :mission-control:ui:run` (with the console); for local
runs, put the variables below in `mission-control/server/.env` (git-ignored), and variables already set in the
environment win.

## Deployment (Oracle Cloud)

`.github/workflows/ci.yml` runs the JVM tests on pushes to `main` and on every pull request. `.github/workflows/deploy.yml` runs on
pushes to `main` that touch the server, by hand from the Actions tab, or for a pull request labelled `deploy`
(redeployed on every push while the label stays). Pull requests and pushes deploy only when they change the server or
what it's built from: `core`, `mission-control/server`, `buildSrc`, `gradle`, the root build files or the workflow. It tests the server, builds the fat jar,
builds and pushes `<DOCKERHUB_USERNAME>/radarnebo-server:<commit sha>` for amd64 and arm64 from
`mission-control/server/Dockerfile`, scans both pushed architectures with Trivy (fails on fixable HIGH/CRITICAL), moves
`:latest` to that image (runs on `main` only, so pull request deploys never touch it), copies
`mission-control/server/docker-compose.yaml` to `~/docker-deployments/apps/radarnebo/radarnebo-server` on the VM over
SSH, writes the image and secrets to `.env` beside it, and runs `docker compose up -d --wait`, so a container that
doesn't turn healthy fails the deploy. It then removes this app's unused images (nothing else on the shared VM) and
fetches `https://<DOMAIN>/health` from the runner, so a broken proxy, certificate, DNS record or firewall fails it too.

Rollback: Actions → Deploy → Run workflow with `image_tag=<previous good commit sha>` (the full sha the image was
pushed under). It skips tests, build and scan and deploys that image. The VM keeps `.env` next to
`docker-compose.yaml` (mode 600), so `docker compose ps`, `logs` and `up -d` also work by hand there.

A pull request deploy stays live until the pull request loses the `deploy` label or is closed unmerged (main is then
redeployed), or until main deploys again.

Secrets it needs: `DOCKERHUB_USERNAME`, `DOCKERHUB_PASSWORD`, `SSH_HOST`, `SSH_USERNAME`, `SSH_KEY`, `SSH_PORT`,
`SSH_HOST_FINGERPRINT`, `ALERTS_IN_UA_API_KEY` (the server's `ALERTS_API_TOKEN`; empty runs the simulation) and
`CLIENT_KEYS`. `SSH_HOST_FINGERPRINT` is the `SHA256:...` value of the VM's ECDSA host key, from
`ssh-keyscan -t ecdsa -p <port> <host> | ssh-keygen -lf -`. It has to be the ECDSA key: the SSH actions' Go client
prefers ECDSA over RSA and ed25519 and can't be told otherwise, so an ed25519 fingerprint never matches. The SSH steps
refuse any other host key, so the deploy fails until it is set.

`.github/dependabot.yml` opens one weekly pull request per ecosystem: the workflow actions, the Gradle dependencies,
the server's base image (pinned by digest in the Dockerfile) and the compose file's images.

Caddy (`mission-control/server/Caddyfile`) runs as a second container in the same compose project, in front of the
server, and is the only thing with a published port (443). On first start it gets a Let's Encrypt certificate for `DOMAIN` (`radar-nebo.worxbend.com`; a repository variable `DOMAIN`
overrides it) and renews it on its own, proving the domain over port 443 (TLS-ALPN-01), so port 80 can stay with another service.
`DOMAIN`'s DNS A record must point at the VM. The server isn't published, so it trusts Caddy's `X-Forwarded-For`
(`TRUST_PROXY_HEADERS=true`) to rate-limit each client by its own IP. Apps use `https://<DOMAIN>` as the server URL.

The compose file requires `CLIENT_KEYS`: an empty or missing secret (or one with only commas and spaces) fails the
deploy instead of serving an open API (the server itself still runs open without keys, for local use, and logs a
warning). Caddy adds HSTS, `nosniff`, `Referrer-Policy`, a CSP and drops the `Server` header, and rejects request
bodies over 1 KB. The server container is limited to 512 MB (the JVM heap is 75% of that, and an `OutOfMemoryError`
restarts it); raise `mem_limit` in the compose file if `docker stats` shows pressure. Container logs rotate at
3 × 10 MB.

The VM needs Docker with Compose v2.17 or later, and port 443 (TCP, plus UDP for HTTP/3) open in the subnet's
security list and the host firewall. The archive and lock live in the `radarnebo-data` volume, the
certificates in `caddy-data`.

To run the same image locally:

```
./gradlew :mission-control:server:buildFatJar
docker build -t radarnebo-server:local mission-control/server
# The server alone, without Caddy (curl http://localhost:8080/health). The compose file needs a real DOMAIN: its
# Caddyfile always asks Let's Encrypt, which can't issue a certificate for localhost.
docker run --rm -p 8080:8080 radarnebo-server:local
```

## Limits

Limits are per client IP (`CLIENT_REQUESTS_PER_MINUTE`, 120/min; history `HISTORY_REQUESTS_PER_MINUTE`, 2/min; track
archive `ARCHIVE_REQUESTS_PER_MINUTE`, 10/min). With `TRUST_PROXY_HEADERS=true` the client IP is the last
`X-Forwarded-For` entry, the one the proxy added. That is safe only when the server port isn't published and every call
goes through the proxy, as in the compose deployment; anywhere else leave it `false`, or any client could pick its own IP.

`/v1/drones/archive.json` covers a window of at most 7 days, with `limit` at most 200 (the default); more is a 400.

## Backup and restore

The archive is derived history (up to 45 days of alerts and finished tracks), not live data, but
alerts.in.ua won't serve all of it again. SQLite runs in WAL mode, so a plain `cp` of the file while the server runs can
miss committed rows or copy a torn file; use SQLite's online backup instead. Compose prefixes the volume with the project
name (`docker volume ls | grep radarnebo-data`; normally `radarnebo-server_radarnebo-data`). A weekly snapshot that keeps
the last four, from the VM user's crontab (`crontab -e`; `%` must be escaped there):

```
0 3 * * 0 mkdir -p $HOME/backups && docker run --rm --user "$(id -u):$(id -g)" -v radarnebo-server_radarnebo-data:/data:ro -v $HOME/backups:/out keinos/sqlite3:3.53.4 sqlite3 /data/radarnebo-archive.db ".backup /out/archive-$(date +\%F).db" && ls -1t $HOME/backups/archive-*.db | tail -n +5 | xargs -r rm --
```

The server keeps running; the snapshot includes what is still in the WAL. Copies on the same VM don't survive losing
it, so copy them off now and then, or enable an OCI backup policy on the boot volume instead. To restore, in the
compose directory:

```
docker compose stop radarnebo-server
docker run --rm --user 10001:10001 -v radarnebo-server_radarnebo-data:/data -v $HOME/backups:/in:ro keinos/sqlite3:3.53.4 \
  sh -c 'rm -f /data/radarnebo-archive.db-wal /data/radarnebo-archive.db-shm && cp /in/archive-<date>.db /data/radarnebo-archive.db'
docker compose start radarnebo-server
```

Remove the `-wal` and `-shm` files, or SQLite would apply the old WAL to the restored file. `10001` is the server
image's user, so the restored file stays writable for it.

## Health and monitoring

`GET /health` reports `status`, the data source, freshness (`dataAgeSeconds`), the upstream budget and outcome counts
and the last error. It needs no key but is rate limited per IP like the other routes, and always answers 200.

`status` is `warming_up` (no data yet), `ok`, `degraded` (the data source is failing and the server is backing off;
the last data is still served) or `stale` (the data is older than max(3 × poll interval, 60 s) while polls succeed,
or the poller is more than two poll intervals overdue). `upstreamRateLimitedTotal` (429), `upstreamRejectedTotal`
(401/403), `upstreamOtherFailuresTotal` and `upstreamLastSuccessAt` count alerts.in.ua outcomes since start, so a token
or IP drifting towards a block shows before it fails outright.

Because `/health` answers 200 in every state, a status-code check can't see stale or failing data. In Uptime Kuma, add
an `HTTP(s) - Keyword` monitor on `https://<DOMAIN>/health` for the keyword `"status":"ok"`. Kuma runs on the same VM,
so it can't see the VM itself go down: add one more uptime check from another provider against the same URL.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `ALERTS_API_TOKEN` | – | alerts.in.ua token. If unset, the server runs the simulation and makes no upstream requests. |
| `DATA_SOURCE` | – | `simulation` or `alerts_in_ua`, to choose explicitly |
| `TRACKING` | `auto` | How tracks are built: `auto` (sightings when the source has them, else alerts), `alerts`, `sightings`, or `fused` (both into one picture; see [trajectory tracking](./trajectory-tracking.md) §2.6) |
| `UPSTREAM_MAX_PER_MINUTE` | 6 | Upstream budget, clamped to 1–8 |
| `POLL_INTERVAL_SECONDS` | 15 | Never faster than the budget allows |
| `SIM_STRIKE_PERIOD_MINUTES` | 360 | Time between combined strikes (at least 180) |
| `SIM_TICK_SECONDS` | 10 | How often the simulation reports |
| `SIM_WARM_UP_MINUTES` | 120 | History replayed on start |
| `SIM_SEED` | 20220224 | Change it for different attacks |
| `ARCHIVE_JDBC_URL` | `jdbc:sqlite:data/radarnebo-archive.db` | `memory` for no persistence |
| `ARCHIVE_RETENTION_DAYS` | 45 | At least 1; 0 would prune the whole archive |
| `CLIENT_KEYS` | – | Comma-separated client keys (`PROXY_CLIENT_KEYS` still works) |
| `CLIENT_REQUESTS_PER_MINUTE` | 120 | Per-IP limit for clients, at least 1 |
| `HISTORY_REQUESTS_PER_MINUTE` | 2 | Per-IP limit for the history endpoint, at least 1 |
| `ARCHIVE_REQUESTS_PER_MINUTE` | 10 | Per-IP limit for `/v1/drones/archive.json`, at least 1 |
| `TRUST_PROXY_HEADERS` | false | Take the client IP from the proxy's `X-Forwarded-For` entry. Only behind a proxy that is the sole way in |
| `PORT` | 8080 | 0–65535; 0 picks a free port |
| `LOG_HTTP` | – | `true` logs each alerts.in.ua request and response with its JSON body (token masked) |

Unset or blank means the default. A malformed value (a number that doesn't parse, a boolean other than `true` or
`false`, an unknown `DATA_SOURCE`) or a number outside the range above stops the server at startup with a message
naming the variable (the console shows it in a dialog), since falling back would hide the mistake.

`/health` needs no key but is rate limited per IP like the other routes. The poller backs off exponentially on
failures: up to 60 s on server or network errors, so the board recovers within a minute, and up to 10 min on
429/401/403.
