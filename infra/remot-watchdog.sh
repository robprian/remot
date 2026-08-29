#!/usr/bin/env bash
#
# Remot TURN + signaling watchdog.
#
# Runs on the VPS next to the servers. Every CHECK_INTERVAL it:
#   1. Confirms the signaling service is active and answers /healthz.
#   2. Confirms the coturn (TURN/STUN) service is active and its UDP port
#      responds to a lightweight STUN binding (performed with a tiny Python
#      STUN probe) so a hung-but-`active` coturn process is caught too.
#
# If a check fails it restarts the offending unit via systemctl (which the
# units already do on crash; this catches cases systemd's Restart= doesn't,
# e.g. the process staying alive but unresponsive), then logs + notifies.
#
# Install as a systemd timer (see the README) or cron:  */1 * * * * /opt/remot/infra/remot-watchdog.sh
#
# Generates only diagnostics — never logs secrets.

set -u

SIGNALING_SERVICE="${SIGNALING_SERVICE:-remot-signaling}"
COTURN_SERVICE="${COTURN_SERVICE:-remot-coturn}"
SIGNALING_HEALTH_URL="${SIGNALING_HEALTH_URL:-http://127.0.0.1:8080/healthz}"
TURN_PORT="${TURN_PORT:-3478}"
TURN_HOST="${TURN_HOST:-127.0.0.1}"
CHECK_INTERVAL="${CHECK_INTERVAL:-30}"   # seconds, only used when run as a loop
LOG_TAG="remot-watchdog"

log()   { echo "[$(date -u +%FT%TZ)] $*"; }
notify() { log "NOTIFY: $*"; }   # extend with your paging/webhook if desired

# Minimal STUN Binding request/response check over UDP, dependency-free.
stun_ok() {
  python3 - "$TURN_HOST" "$TURN_PORT" <<'PY'
import socket, struct, sys
def main():
    host, port = sys.argv[1], int(sys.argv[2])
    tx = bytes(range(12))
    msg = struct.pack(">HHI", 0x0001, 0, 0x2112A442) + tx
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(3)
    try:
        s.sendto(msg, (host, port))
        data, _ = s.recvfrom(65536)
        if len(data) < 20: sys.exit(1)
        typ = struct.unpack(">H", data[0:2])[0]
        cookie = struct.unpack(">I", data[4:8])[0]
        ok = (typ == 0x0101) and (cookie == 0x2112A442) and (data[8:20] == tx)
        sys.exit(0 if ok else 1)
    except Exception:
        sys.exit(1)
main()
PY
}

restart() {
  local svc="$1" reason="$2"
  log "restarting $svc (${reason})"
  systemctl restart "$svc"
  notify "restarted $svc — ${reason}"
}

check_signaling() {
  if systemctl is-active --quiet "$SIGNALING_SERVICE" 2>/dev/null; then
    if curl -fsS --max-time 5 "$SIGNALING_HEALTH_URL" >/dev/null 2>&1; then
      return 0
    fi
    restart "$SIGNALING_SERVICE" "signaling active but /healthz failing"
  else
    restart "$SIGNALING_SERVICE" "signaling service down"
  fi
}

check_coturn() {
  if systemctl is-active --quiet "$COTURN_SERVICE" 2>/dev/null; then
    if stun_ok "$TURN_HOST" "$TURN_PORT"; then
      return 0
    fi
    restart "$COTURN_SERVICE" "coturn active but STUN binding failed on :$TURN_PORT"
  else
    restart "$COTURN_SERVICE" "coturn service down"
  fi
}

main() {
  if [ "${1:-loop}" = "once" ]; then
    check_signaling
    check_coturn
    log "watchdog pass complete"
    return $?
  fi
  log "watchdog started (interval ${CHECK_INTERVAL}s)"
  while true; do
    check_signaling
    check_coturn
    sleep "$CHECK_INTERVAL"
  done
}

main "$@"