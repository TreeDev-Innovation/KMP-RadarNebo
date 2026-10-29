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
SSH, and runs `docker compose up -d --wait`, so a container that doesn't turn healthy fails the deploy.

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

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `ALERTS_API_TOKEN` | – | alerts.in.ua token. If unset, the server runs the simulation and makes no upstream requests. |
| `DATA_SOURCE` | – | `simulation` or `alerts_in_ua`, to choose explicitly |
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
| `TRUST_PROXY_HEADERS` | false | Take the client IP from the proxy's `X-Forwarded-For` entry. Only behind a proxy that is the sole way in |
| `PORT` | 8080 | 0–65535; 0 picks a free port |
| `LOG_HTTP` | – | `true` logs each alerts.in.ua request and response with its JSON body (token masked) |

Unset or blank means the default. A malformed value (a number that doesn't parse, a boolean other than `true` or
`false`, an unknown `DATA_SOURCE`) or a number outside the range above stops the server at startup with a message
naming the variable (the console shows it in a dialog), since falling back would hide the mistake.

`/health` needs no key but is rate limited per IP like the other routes. The poller backs off exponentially on
failures: up to 60 s on server or network errors, so the board recovers within a minute, and up to 10 min on
429/401/403.
