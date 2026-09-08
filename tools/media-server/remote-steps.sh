set -euo pipefail
echo "-- installing config and unit"
sudo install -m 644 /tmp/ytv-media.caddy /etc/caddy/ytv-media.caddy
sudo install -m 644 /tmp/ytv-media.service /etc/systemd/system/ytv-media.service
sudo systemctl daemon-reload
sudo systemctl enable --now ytv-media.service
systemctl is-active ytv-media.service

echo "-- ufw: 4244 on LAN + tailscale only"
sudo ufw status | grep -q '^4244 ' || \
    sudo ufw allow from 192.168.4.0/24 to any port 4244 comment 'ytv media'
sudo ufw status | grep -q '^4244 on tailscale0' || \
    sudo ufw allow in on tailscale0 to any port 4244 comment 'ytv media'
sudo ufw status | grep 4244

echo "-- rescanning media (as http, the only user who can read it)"
# http cannot traverse /home/hermanb (750, as it should be) and must not own anything
# permanent; the scan works on a throwaway copy and the rewritten confs are copied back.
SCAN_DIR=$(sudo -u http env HOME=/tmp mktemp -d /tmp/ytv-scan.XXXXXX)
sudo cp -a "$HOME/ytv-curation/." "$SCAN_DIR/"
sudo chown -R http:http "$SCAN_DIR"
sudo -u http env HOME=/tmp python3 "$SCAN_DIR/scan_media.py" --confs "$SCAN_DIR/confs"
sudo cp "$SCAN_DIR"/confs/file_*.json "$HOME/ytv-curation/confs/"
sudo rm -rf "$SCAN_DIR"

echo "-- asking Nextcloud to notice the files"
docker exec -u www-data nextcloud php occ files:scan --path=hermanb/files/Movies 2>/dev/null || \
docker exec -u abc nextcloud php occ files:scan --path=hermanb/files/Movies 2>/dev/null || \
    echo 'occ scan of Movies skipped (unknown exec user)'
docker exec -u www-data nextcloud php occ files:scan --path=hermanb/files/Series 2>/dev/null || \
docker exec -u abc nextcloud php occ files:scan --path=hermanb/files/Series 2>/dev/null || \
    echo 'occ scan of Series skipped (unknown exec user)'
