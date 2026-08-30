#!/usr/bin/env bash
#
# Remot external reachability check.
#
# Run from a machine OUTSIDE (e.g. a laptop or a cloud shell) to verify the VPS
# is reachable on the ports Remot needs. Prints each port's state from the
# internet's point of view:
#
#   signaling wss 443/tcp    (OPEN required — Nginx reverse proxy, standard TLS)
#   signaling wss 8443/tcp   (OPEN — direct TLS listener, fallback)
#   STUN/TURN  3478/udp+tcp
#   TURNS      5349/tcp
#   TURN relay 49152-65535/udp   (sample check only; full range is covered by
#                                 allocating a real relay via the app)
#
# Requires nmap. On Debian/Ubuntu:  sudo apt install nmap
#
# Usage:  ./check-ports.sh [HOST]
#   HOST  default: TURN/STUN hostname (env HOSTNAME) else the same as the DNS
#         name passed via SERVER_HOST env, else first arg.

set -u

HOST="${1:-${SERVER_HOST:-}}"
if [ -z "$HOST" ]; then
  echo "usage: $0 <host-or-ip>   (or export SERVER_HOST)" >&2
  exit 2
fi

echo "=== Checking external reachability of ${HOST} ==="
echo "  (run from a machine OUTSIDE the VPS/cloud — not from the server itself)"
echo

printf "%-22s %-12s %s\n" "SERVICE" "PORT" "STATE (expected)"
printf "%-22s %-12s %s\n" "-------" "----" "-----"

for spec in "Signaling wss 443|443/|tcp|open" "Signaling ws|8080/|tcp|open" \
            "Signaling wss 8443|8443/|tcp|open" \
            "STUN/TURN TCP|3478/|tcp|open" "TURNS TCP|5349/|tcp|open"; do
  IFS='|' read -r name port proto want <<<"$spec"
  out=$(nmap -Pn -sT -p "${port%%/}" "$HOST" 2>/dev/null)
  state=$(echo "$out" | grep -E "^${port%%/}/${proto}" | awk '{print $2}')
  [ -z "$state" ] && state="(n/a)"
  printf "%-22s %-12s %s  [want %s]\n" "$name" "${port}${proto}" "$state" "$want"
done

# UDP (best-effort; ICMP-port-unreachable must be allowed for accurate results).
echo
for spec in "STUN/TURN UDP|3478|udp|open|filtered" "TURNS UDP|5349|udp|open|filtered"; do
  IFS='|' read -r name port proto want <<<"$spec"
  out=$(nmap -Pn -sU -p "$port" "$HOST" 2>/dev/null)
  state=$(echo "$out" | grep -E "^${port}/${proto}" | awk '{print $2}')
  [ -z "$state" ] && state="(n/a)"
  printf "%-22s %-12s %s  [want %s]\n" "$name" "${port}/${proto}" "$state" "$want"
done

echo
echo "Note: STUN/TURN commonly report 'open|filtered' over UDP from nmap; that can be"
echo "OK. The authoritative UDP test is a real relay allocation via the app, or the"
echo "python STUN probe in remot-watchdog.sh."
echo
echo "Firewall checklist (Alibaba Security Group + host ufw/nftables where used):"
echo "  - Allow inbound  TCP 443    (signaling wss via Nginx reverse proxy, standard TLS)"
echo "  - Allow inbound  TCP 8080   (signaling ws fallback)"
echo "  - Allow inbound  TCP 8443   (signaling wss direct TLS, fallback)"
echo "  - Allow inbound  UDP+TCP 3478   (STUN/TURN)"
echo "  - Allow inbound  TCP 5349   (TURNS/TLS)"
echo "  - Allow inbound  UDP 49152-65535   (TURN relay allocations)"
echo "  - If the app uses a direct public IP (SERVER_IP) instead of a hostname,"
echo "    apply those rules to that IP as well."