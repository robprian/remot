# Remot — Infrastructure

Remot needs two pieces of infrastructure to work across the public Internet:

1. **Signaling server** (`server/`) — a Node.js WebSocket broker. It handles
   device registration, pairing, session codes, and the WebRTC handshake relay.
   It never sees media.
2. **coturn (STUN/TURN)** — lets devices find each other behind NAT, with a
   relay fallback when P2P is impossible (carrier CGNAT etc.).

Everything below is reproducible; no secrets are hardcoded anywhere.

---

## 1. Signaling server

### Requirements

- Node.js >= 18 (20+ recommended; `--env-file` support needs 20.6+).
- A public hostname + TLS certificate for production (`wss://`).

### Run

```bash
cd server
npm ci
cp .env.example .env        # edit values
node --env-file=.env src/server.js
```

### Configuration (environment variables)

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | Cleartext ws listen port (legacy fallback) |
| `WSS_ENABLED` | `false` | `true` to also serve a TLS WebSocket (`wss://`) |
| `WSS_PORT` | `8443` | TLS WebSocket port |
| `WSS_CERT_PATH` / `WSS_KEY_PATH` | `/etc/remot/wss/cert.pem` / `key.pem` | TLS cert/key PEMs |
| `TURN_HOST` | `localhost` | Public hostname of the coturn server |
| `TURN_SECRET` | `dev-only-change-me` | HMAC secret; MUST match coturn `static-auth-secret` |
| `TURN_TTL_SEC` | `3600` | TURN credential lifetime |
| `TURN_STUN_PORT` / `TURN_TLS_PORT` | `3478` / `5349` | STUN/UDP and TURNS/TCP ports |
| `SESSION_CODE_TTL_MS` | `300000` | 6-digit code lifetime (5 min) |
| `PENDING_WAKE_TTL_MS` | `60000` | Queued join-request lifetime for offline hosts |
| `RATE_WINDOW_MS` / `RATE_MAX_MESSAGES` | `10000` / `200` | Per-connection flood protection |
| `RATE_MAX_JOIN_PER_MIN` | `10` | Join attempts per IP per minute (brute-force guard) |
| `FCM_ENABLED` | `false` | Enable Firebase wake pushes (requires `npm i firebase-admin` + `GOOGLE_APPLICATION_CREDENTIALS`) |

### Health

`GET /healthz` → `200 {"status":"ok","uptimeSec":…,"devices":…}`. Point a load
balancer or uptime monitor at it.

### Logs

The server logs structured JSON lines (`{ts, level, msg, ...}`) to stdout/stderr.
No secrets are ever logged. Ship stdout to your log aggregator of choice.

### Running as a system service (systemd — with auto-restart)

Both the signaling server and coturn ship ready-to-use systemd units with
**auto-restart**: if the process dies or exits non-zero, systemd brings it
back automatically. Install them from this repo:

```bash
echo "REMOTE_HOST_IP=$(hostname -I | awk '{print $1}')"    # for reference only, not committed
sudo cp infra/remot-signaling.service /etc/systemd/system/
sudo cp infra/remot-coturn.service    /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now remot-signaling remot-coturn
```

Both units use:

```ini
Restart=always
RestartSec=3
StartLimitIntervalSec=60
StartLimitBurst=5
```

So a crashed/hung process is retried, while a crash loop (5 exits in 60 s)
gives systemd a chance to back off instead of thrashing the 2-core host.

### Liveness watchdog

systemd restarts a process that *exits*; it does not by itself notice a
process that stays `active` but becomes unresponsive. Run the watchdog to
catch that too — it checks the signaling `GET /healthz` every 30 s and sends a
real STUN Binding over UDP to coturn; if either fails it restarts the unit:

```bash
sudo cp infra/remot-watchdog.sh /opt/remot/infra/remot-watchdog.sh
chmod +x /opt/remot/infra/remot-watchdog.sh
# one-shot check, good for a cron row:
/opt/remot/infra/remot-watchdog.sh once
# continuous loop (systemd timer or use as a spaced loop):
/opt/remot/infra/remot-watchdog.sh
```

Example crontab (every minute is cheap):

```cron
* * * * * /opt/remot/infra/remot-watchdog.sh once >> /var/log/remot-watchdog.log 2>&1
```

`remot-watchdog.sh` never touches or logs secrets; it only emits service
start/restart diagnostics.

---

## 2. coturn (STUN/TURN)

### Install

```bash
sudo apt install coturn
sudo cp infra/turnserver.conf /etc/turnserver.conf
sudo nano /etc/turnserver.conf   # see the "Edit before deploy" section
sudo turnserver -c /etc/turnserver.conf -v
```

### Edit before deploy

In `infra/turnserver.conf` replace:

- `external-ip=YOUR.PUBLIC.IP` → the server's public IP.
- `realm` / `server-name` → your TURN hostname.
- `static-auth-secret` → the SAME long random secret as the signaling server's
  `TURN_SECRET`. Clients get short-lived HMAC credentials from the signaling
  server, so coturn never needs per-user accounts.
- `cert` / `pkey` → paths from Let's Encrypt (`sudo certbot certonly
  --standalone -d turn.yourdomain.com`).

### Firewall

| Port | Protocol | Purpose |
| --- | --- | --- |
| 8080 | TCP | Signaling / WebSocket (cleartext ws fallback) |
| 8443 | TCP | Signaling / WebSocket (**wss**, TLS) |
| 3478 | UDP + TCP | STUN + TURN |
| 5349 | TCP | TURNS (TLS) |
| 49152–65535 | UDP | TURN relay allocations |

Apply these inbound rules in **both** the cloud security group (e.g. the
Alibaba Cloud Security Group) and any host firewall (ufw/nftables). If the app
uses a direct public IP (`SERVER_IP`) rather than a hostname, open the same
rules for that IP.

### Verify ports from the outside

The authoritative reachability test is a real allocation from a client
outside the network. A quick external scan helps first — run from a machine
**not** on the VPS (laptop, cloud shell):

```bash
export SERVER_HOST=your.public.ip
./check-ports.sh
```

Expected (from the public internet):

| Port | Protocol | Expected | Purpose |
| --- | --- | --- | --- |
| 8080 | TCP | open | Signaling / WebSocket (ws fallback) |
| 8443 | TCP | open | Signaling / WebSocket (**wss**, TLS) |
| 3478 | UDP + TCP | open | STUN + TURN |
| 5349 | TCP | open | TURNS (TLS) |
| 49152–65535 | UDP | open | TURN relay allocations |

> **If 3478/5349 show `filtered`** (or UDP closed) the TURN relay will report
> unreachable from the app. `filtered` in nmap means a firewall/security-group
> is silently dropping the packets, not rejecting them — apply the inbound
> rules below to the public IP (including a direct IP such as when using
> `SERVER_IP` instead of a hostname).

A confirmed full-stack test is a real relay allocation through the app or a
WebRTC trickle-ICE test page (e.g. webrtc.github.io/samples) with the TURN
credentials from the signaling server.

### CI-external probe (`scripts/probe-endpoints.mjs`)

Every GitHub Actions build runs a **network probe from GitHub's public
network** (`network-probe` job in `build.yml`) against the production
endpoints. It checks, in order:

1. TCP connect to the signaling port (from `SERVER_URL`, e.g. `:8080`)
2. HTTP `GET /healthz`
3. WebSocket upgrade (`101 Switching Protocols`)
4. Full `register` round-trip (expects the server to answer `register-failed`)
5. STUN Binding over UDP :3478 (real round-trip, ms)
6. TURN TLS port :5349 (TCP)

```bash
SERVER_URL="ws://your.host:8080" SERVER_IP=203.0.113.10 node scripts/probe-endpoints.mjs
```

The script reads endpoints from env (GitHub secrets in CI) — nothing is
hardcoded. It exits non-zero only when **signaling** is unreachable; STUN/TURN
results are reported without failing so a firewall-blocked TURN never blocks
Android releases (the probe job uses `continue-on-error: true`).

### Verify real relay media end-to-end (`scripts/turn-relay-test.mjs`)

`probe-endpoints.mjs` proves the control path (Allocate answers with a 401
challenge); it does **not** prove the relay range forwards media. The definitive
test for that is `scripts/turn-relay-test.mjs`, which runs the full RFC 5766
sequence on one UDP socket:

1. authenticated **Allocate** (auth-secret HMAC, same scheme as the app) →
   reads `XOR-RELAYED-ADDRESS`;
2. asserts the relayed port is inside `TURN_MIN_PORT..TURN_MAX_PORT`
   (default 49152–65535);
3. peer socket discovers its own public IP via STUN Binding;
4. **CreatePermission** for that IP (without it coturn silently drops the
   peer's datagram);
5. peer sends a datagram **at the relayed address** and the client must
   receive the exact payload back as a TURN DATA indication.

Run it from a machine **outside** the TURN server's network:

```bash
export TURN_HOST=203.0.113.10        # direct public IP
TURN_SECRET=<coturn static-auth-secret> node scripts/turn-relay-test.mjs
```

Exit 0 only when the media round-trip succeeds — that proves the UDP relay
range is open and forwarding, not merely that the TURN service answers.

---

## 2.1 Backup / secondary server (failover)

For resilience against a primary-server outage or a network route that blocks
only one host, Remot supports a **second, independent signaling + coturn
pair**. The Android app is built with the backup endpoints as automatic
fallbacks: `BuildConfig.SERVER_URL_ALT` and `BuildConfig.SERVER_IP_ALT` are
injected at build time **only from GitHub Secrets** (`SERVER_URL_ALT` /
`SERVER_IP_ALT`) — never hardcoded in source. The signaling client tries the
primary chain first (primary URL → wss variant → primary IP → **alt URL →
alt IP ws/wss**), so a reachable backup broker is used automatically when the
primary cannot connect.

The backup server runs the exact same software and units as the primary:

- **Signaling** — `infra/remot-signaling.service` (Node, `Restart=always`)
  with its own `.env`, including its own `TURN_SECRET`.
- **coturn** — `infra/remot-coturn.service` (`use-auth-secret`, relay range
  49152–65535, its own `external-ip` / `realm`). The backup's coturn
  `static-auth-secret` MUST match its own signaling `TURN_SECRET` — each
  server pair keeps its own secret, so they are independent.
- Same firewall ports: TCP 8080 (ws), UDP+TCP 3478 (STUN/TURN), and
  **UDP 49152–65535** (relay media).

Verify the backup the same way as the primary: `check-ports.sh`, the CI
`network-probe` (or `probe-endpoints.mjs` locally), and
`scripts/turn-relay-test.mjs` for real relay media.

### Pitfall: two coturns = open relay (fixed)

A host can end up running **two coturn instances**: the stock distro
`coturn.service` (default config, **no auth**) and the deployment's
`remot-coturn.service` (`use-auth-secret`). When the public IP is NAT'd onto a
private address where the stock coturn listens, external TURN requests can
land on the **no-auth instance** and succeed **without credentials — an open
relay** (anyone can route traffic through it). Symptoms: an unauthenticated
Allocate returns success instead of a 401 challenge, and `ss -lunp` shows more
than one `turnserver` on 3478.

Fix: identify which instance owns the public path, then disable **and mask**
the stock service so it can never hijack the port again, leaving only the
authenticated `remot-coturn.service`:

```bash
sudo systemctl disable --now coturn
sudo systemctl mask coturn          # cannot be started even manually
sudo systemctl enable --now remot-coturn
sudo ss -lunp | grep 3478           # exactly ONE turnserver
```

Then prove auth is enforced from outside (see `probe-endpoints.mjs`): an
unauthenticated Allocate must get a **401 challenge**, never a 0x0103
success.

---

## 3. TLS

### Signaling (`wss://`)

The signaling server can serve **both** a cleartext WebSocket (default `:8080`)
**and** a TLS WebSocket (`:8443`) from the same process — set `WSS_ENABLED=true`,
`WSS_CERT_PATH` / `WSS_KEY_PATH` to a cert/key PEM pair, then open `8443` in the
security group. The app always **prefers the wss://:8443** endpoint for every
host and falls back to ws://:8080 only if TLS is unreachable.

Two certificate options:

- **Let's Encrypt (primary):** obtain a real public cert for the signaling
  hostname (e.g. `turn.robrion.net`) and point `WSS_CERT_PATH`/`WSS_KEY_PATH`
  at it. Issue with `certbot --standalone -d turn.robrion.net` (opens port 80
  for HTTP-01) or a DNS-01 plugin, and renew via `certbot renew` + a systemd
  timer/reload hook. The app validates it against the Android system trust
  store.
- **Remot private CA (servers with no domain, e.g. the backup IP):** generate a
  Remot CA + a server leaf cert (SAN = the IP), and bundle the CA **public**
  cert in the app at `res/raw/remot_ca.pem`. The app adds it to its trust
  anchors (system CAs UNION the Remot CA) so the self-signed backup validates
  while a public-cert primary still works. The private CA key stays
  server-side only.

### TURN

- coturn terminates TLS directly with a certificate (`turns:` scheme). Until a
  certificate is provisioned, the signaling server deliberately does **not**
  advertise `turns:` URLs (see `server/src/turn.js`) so clients never waste
  time on a TURNS endpoint that cannot connect.

---

## 4. Deployment topology

```
Android Controller ──wss──► Signaling Server (HTTPS/WSS, port 443)
        │                          ▲
        │   WebRTC P2P             │  handshake relay only
        ▼                          │
   STUN/TURN (3478/5349) ◄─────────┘  issues TURN creds
        │
Android Target ──wss──► Signaling Server
```

- Media flows peer-to-peer (DTLS-SRTP encrypted). TURN relays encrypted
  packets only when P2P fails; the signaling server never touches media.
- For scale-out, back `server/src/state.js` (in-memory) with Redis — the
  module interface is deliberately small to make that swap straightforward.

---

## 5. Monitoring / backups

- No persistent server state exists (in-memory), so there is nothing to back
  up server-side. Device pairings and grants live encrypted on each device.
- Monitor: `/healthz` uptime checks, log volume/error rate, TURN relay
  bandwidth, and signaling connections (the health payload reports device
  count).
