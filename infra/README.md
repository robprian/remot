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

### Running as a system service (systemd example)

```ini
# /etc/systemd/system/remot-signaling.service
[Unit]
Description=Remot signaling server
After=network.target

[Service]
WorkingDirectory=/opt/remot/server
ExecStart=/usr/bin/node --env-file=/opt/remot/server/.env src/server.js
Restart=always
RestartSec=3
User=remot

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now remot-signaling
```

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
| 3478 | UDP + TCP | STUN + TURN |
| 5349 | TCP | TURNS (TLS) |
| 49152–65535 | UDP | TURN relay allocations |

### Verify

Use a WebRTC trickle-ICE test page (e.g. webrtc.github.io/samples) with your
STUN and TURN credentials from the signaling server to confirm candidates are
gathered and relay works before wiring the app.

---

## 3. TLS

- Signaling: terminate TLS at your reverse proxy (Caddy/nginx) or run the
  server behind it; the app expects `wss://`.
- TURN: coturn terminates TLS directly with the Let's Encrypt certs
  (`turns:` scheme).

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
