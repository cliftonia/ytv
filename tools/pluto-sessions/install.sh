#!/bin/bash
# Install the Pluto session service on the home server. Run there, from this directory:
#   sudo ./install.sh
#
# Installs:
#   /usr/local/bin/pluto-ns.sh, /usr/local/lib/pluto-sessions/pluto_sessions.py
#   pluto-ns@us|uk.service    (system, root)  the tunnel namespaces
#   pluto-boot@us|uk.service  (system, runs as hermanb inside the namespace)  the boot answerers
#   pluto-sessions.service    (system, hermanb)  the front on :4246 the televisions call
#   ufw: 4246 from the LAN and on tailscale0, as the other ytv ports are
set -euo pipefail
cd "$(dirname "$0")"
USER_NAME=${SUDO_USER:-hermanb}

install -m 755 pluto-ns.sh /usr/local/bin/pluto-ns.sh
install -d /usr/local/lib/pluto-sessions
install -m 644 pluto_sessions.py /usr/local/lib/pluto-sessions/pluto_sessions.py

cat > /etc/systemd/system/pluto-ns@.service <<'UNIT'
[Unit]
Description=Pluto session tunnel namespace (%i)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/bin/pluto-ns.sh up %i
ExecStop=/usr/local/bin/pluto-ns.sh down %i

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/pluto-boot@.service <<UNIT
[Unit]
Description=Pluto boot answerer inside the %i tunnel
BindsTo=pluto-ns@%i.service
After=pluto-ns@%i.service

[Service]
User=$USER_NAME
NetworkNamespacePath=/run/netns/pluto-%i
# us is 10.103.1.2, uk 10.103.2.2 - see pluto-ns.sh.
ExecStart=/bin/sh -c 'case %i in us) n=1;; uk) n=2;; esac; exec /usr/bin/python3 /usr/local/lib/pluto-sessions/pluto_sessions.py --boot --bind 10.103.\$n.2:8480'
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/pluto-sessions.service <<UNIT
[Unit]
Description=Pluto sessions from the US and UK for the televisions (:4246)
After=pluto-boot@us.service pluto-boot@uk.service

[Service]
User=$USER_NAME
ExecStart=/usr/bin/python3 /usr/local/lib/pluto-sessions/pluto_sessions.py --front --bind 0.0.0.0:4246 --upstream us=http://10.103.1.2:8480 --upstream uk=http://10.103.2.2:8480
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now pluto-ns@us pluto-ns@uk pluto-boot@us pluto-boot@uk pluto-sessions
ufw allow from 192.168.4.0/24 to any port 4246 proto tcp comment 'ytv pluto sessions (LAN)' >/dev/null
ufw allow in on tailscale0 to any port 4246 proto tcp comment 'ytv pluto sessions (tailnet)' >/dev/null
echo "installed; check: curl -s http://127.0.0.1:4246/health"
