#!/bin/bash
# Bring a Pluto session tunnel up or down:  sudo pluto-ns.sh up|down us|uk
#
# One network namespace per region (pluto-us, pluto-uk) holding a Mullvad WireGuard tunnel and a
# veth to the host. Inside, the default route is the tunnel; the veth carries only the host's
# questions to pluto_sessions.py --boot. Nothing else on this box is routed through these tunnels
# - the AU and international exit nodes, their watchdogs and the host's own traffic are untouched.
#
# Key: /etc/wireguard/mullvad/cachyos-pluto.key (registered 26 Sep 2026, the account's 5th and
# last device slot; its tunnel address is the cachyos-pluto line in addresses). One key serves
# both regions. Relays: the first active one in the city that answers, from Mullvad's relay list
# (/var/cache/mullvad-relays.json, refreshed when older than 12h - as mullvad-pool.sh does).
set -euo pipefail

ACTION=${1:-}; REGION=${2:-}
case "$REGION" in
    us) CC=us; CITY="Los Angeles, CA"; N=1 ;;
    uk) CC=gb; CITY="London";          N=2 ;;
    *) echo "usage: $0 up|down us|uk" >&2; exit 2 ;;
esac
NS=pluto-$REGION; WG=wg-$REGION; HOST_VETH=vp-$REGION; NS_VETH=vpn-$REGION
HOST_IP=10.103.$N.1; NS_IP=10.103.$N.2
MV=/etc/wireguard/mullvad
RELAYS=/var/cache/mullvad-relays.json

down() {
    ip netns del "$NS" 2>/dev/null || true
    ip link del "$HOST_VETH" 2>/dev/null || true
}

if [ "$ACTION" = down ]; then down; exit 0; fi
[ "$ACTION" = up ] || { echo "usage: $0 up|down us|uk" >&2; exit 2; }

if [ ! -s "$RELAYS" ] || [ -n "$(find "$RELAYS" -mmin +720)" ]; then
    curl -fsS --max-time 30 https://api.mullvad.net/www/relays/wireguard/ -o "$RELAYS.tmp" && mv "$RELAYS.tmp" "$RELAYS"
fi
ADDR=$(awk '$1=="cachyos-pluto"{print $2}' "$MV/addresses" | cut -d, -f1)
[ -n "$ADDR" ] || { echo "no cachyos-pluto address in $MV/addresses" >&2; exit 1; }

down
ip netns add "$NS"
ip -n "$NS" link set lo up
# The veth: host <-> namespace, for the front service's questions only.
ip link add "$HOST_VETH" type veth peer name "$NS_VETH"
ip link set "$NS_VETH" netns "$NS"
ip addr add "$HOST_IP/30" dev "$HOST_VETH"; ip link set "$HOST_VETH" up
ip -n "$NS" addr add "$NS_IP/30" dev "$NS_VETH"; ip -n "$NS" link set "$NS_VETH" up
mkdir -p "/etc/netns/$NS"; echo "nameserver 10.64.0.1" > "/etc/netns/$NS/resolv.conf"

# The tunnel: created on the host so its encrypted UDP leaves by the home line, then moved in.
while read -r host ip pub; do
    ip link del "$WG" 2>/dev/null || true; ip -n "$NS" link del "$WG" 2>/dev/null || true
    ip link add "$WG" type wireguard
    wg set "$WG" private-key "$MV/cachyos-pluto.key" \
        peer "$pub" endpoint "$ip:51820" allowed-ips 0.0.0.0/0 persistent-keepalive 25
    ip link set "$WG" netns "$NS"
    ip -n "$NS" addr add "$ADDR" dev "$WG"
    ip -n "$NS" link set "$WG" up
    ip -n "$NS" route replace default dev "$WG"
    # Any HTTP answer proves the tunnel reaches Pluto (its bare root is a 404 by design); only
    # no answer at all (000) means this relay is not carrying traffic.
    code=$(ip netns exec "$NS" curl -sS --max-time 10 -o /dev/null -w '%{http_code}' https://boot.pluto.tv/ || true)
    if [ -n "$code" ] && [ "$code" != 000 ]; then
        echo "$NS up via $host"; exit 0
    fi
    echo "$host did not answer, trying the next relay" >&2
done < <(python3 - "$RELAYS" "$CC" "$CITY" <<'PY'
import json, sys
relays = json.load(open(sys.argv[1]))
for s in sorted(relays, key=lambda s: s["hostname"]):
    if s.get("active") and s["country_code"] == sys.argv[2] and s.get("city_name") == sys.argv[3]:
        print(s["hostname"], s["ipv4_addr_in"], s["pubkey"])
PY
)
echo "no $CITY relay answered" >&2
exit 1
