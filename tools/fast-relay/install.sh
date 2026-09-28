#!/bin/bash
# Install the US FAST channel relay on the home server. Run there, from this directory, after
# tools/pluto-sessions/install.sh (it provides the pluto-us tunnel namespace):
#   sudo ./install.sh
#
# Installs:
#   /usr/local/lib/fast-relay/fast_relay.py
#   fast-relay.service         (system, runs as hermanb inside netns pluto-us) the relay,
#                              on the namespace's veth address 10.103.1.2:8481 - host only
#   fast-relay-front.socket    (system) :4247, the port the televisions call
#   fast-relay-front.service   (system, unprivileged) systemd-socket-proxyd from :4247 to the
#                              relay - a byte copy, so Host, Range and streaming pass untouched
#   ufw: 4247 from the LAN and on tailscale0, as the other ytv ports are
set -euo pipefail
cd "$(dirname "$0")"
USER_NAME=${SUDO_USER:-hermanb}
PROXYD=/usr/lib/systemd/systemd-socket-proxyd
[ -x "$PROXYD" ] || { echo "no $PROXYD" >&2; exit 1; }
systemctl cat pluto-ns@us.service >/dev/null 2>&1 \
    || { echo "pluto-ns@us is not installed; run tools/pluto-sessions/install.sh first" >&2; exit 1; }

install -d /usr/local/lib/fast-relay
install -m 644 fast_relay.py /usr/local/lib/fast-relay/fast_relay.py

cat > /etc/systemd/system/fast-relay.service <<UNIT
[Unit]
Description=US FAST channel relay inside the pluto-us tunnel
BindsTo=pluto-ns@us.service
After=pluto-ns@us.service

[Service]
User=$USER_NAME
NetworkNamespacePath=/run/netns/pluto-us
# 10.103.1.2 is the namespace's end of the veth (pluto-ns.sh); 8480 is pluto-boot@us.
ExecStart=/usr/bin/python3 /usr/local/lib/fast-relay/fast_relay.py --bind 10.103.1.2:8481
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/fast-relay-front.socket <<'UNIT'
[Unit]
Description=US FAST channel relay for the televisions (:4247)

[Socket]
ListenStream=0.0.0.0:4247

[Install]
WantedBy=sockets.target
UNIT

cat > /etc/systemd/system/fast-relay-front.service <<UNIT
[Unit]
Description=US FAST channel relay front (:4247 -> pluto-us 10.103.1.2:8481)
Requires=fast-relay-front.socket fast-relay.service
After=fast-relay-front.socket fast-relay.service

[Service]
ExecStart=$PROXYD --connections-max=64 10.103.1.2:8481
DynamicUser=yes
PrivateTmp=yes
NoNewPrivileges=yes
UNIT

systemctl daemon-reload
systemctl enable --now fast-relay.service fast-relay-front.socket
systemctl restart fast-relay.service
ufw allow from 192.168.4.0/24 to any port 4247 proto tcp comment 'ytv fast relay (LAN)' >/dev/null
ufw allow in on tailscale0 to any port 4247 proto tcp comment 'ytv fast relay (tailnet)' >/dev/null
echo "installed; check: curl -s http://127.0.0.1:4247/health"
