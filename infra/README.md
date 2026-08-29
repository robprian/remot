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
| `PORT` | `8080` | Listen port |
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
| 8080 | TCP | Signaling / WebSocket |
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
| 8080 | TCP | open | Signaling / WebSocket |
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

---

## 3. TLS

- Signaling: terminate TLS at your reverse proxy (Caddy/nginx) or run the
  server behind it; the app expects `wss://`.
- TURN: coturn terminates TLS directly with the Let's Encrypt certs
  (`turns:` scheme). Until a certificate is provisioned, the signaling server
  deliberately does **not** advertise `turns:` URLs (see `server/src/turn.js`)
  so clients never waste time on a TURNS endpoint that cannot connect.

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
