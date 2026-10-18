#!/usr/bin/env bash
# Build a Remu hub on Raspberry Pi OS Lite 64-bit (Bookworm, which ships Python 3.11).
#
# Decision (closes the TASK-098 OPEN, see docs/DECISIONS.md): the image is built ON a Pi.
# Flash the stock Raspberry Pi OS Lite 64-bit card, boot it, copy this repository's hub
# wheel and this script, run it as root, then clone the card for the rest of the kits.
#
# Usage: sudo ./build_pi_image.sh /path/to/remu_hub-0.1.0-py3-none-any.whl
set -euo pipefail

WHEEL="${1:?usage: build_pi_image.sh <remu_hub wheel>}"
[[ $EUID -eq 0 ]] || { echo "run as root"; exit 1; }
grep -q 'VERSION_CODENAME=bookworm' /etc/os-release || echo "warning: not Bookworm"

apt-get update -qq
apt-get install -y -qq python3.11 python3.11-venv ffmpeg cryptsetup e2fsprogs \
  util-linux avahi-daemon chrony

id remu >/dev/null 2>&1 || useradd --system --home /var/lib/remu --shell /usr/sbin/nologin remu
install -d -o root -g root -m 0700 /etc/remu
install -d -o remu -g remu -m 0700 /var/lib/remu /var/lib/remu/state /var/lib/remu/data

python3.11 -m venv /opt/remu/venv
/opt/remu/venv/bin/pip install --quiet --upgrade pip
/opt/remu/venv/bin/pip install --quiet "$WHEEL"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$HERE/../hub/deploy"
install -o root -g remu -m 0640 "$DEPLOY/hub.env.example" /etc/remu/hub.env
install -m 0644 "$DEPLOY/remu-hub.service" /etc/systemd/system/remu-hub.service
install -m 0644 "$DEPLOY/remu-hub-jobs.service" /etc/systemd/system/remu-hub-jobs.service

# mDNS name for the app: remu-hub.local
hostnamectl set-hostname remu-hub

cat >/usr/local/sbin/remu-firstboot <<'FIRSTBOOT'
#!/usr/bin/env bash
# First boot: shop key, certificate and pairing code. Runs once, then disables itself.
set -euo pipefail
export REMU_SHOP_KEY_PATH=/etc/remu/shop.key REMU_STATE_DIR=/var/lib/remu/state
set -a; . /etc/remu/hub.env; set +a
/opt/remu/venv/bin/remu-hub init
chown -R remu:remu /var/lib/remu
/opt/remu/venv/bin/remu-hub models download
systemctl disable remu-firstboot.service
FIRSTBOOT
chmod 0755 /usr/local/sbin/remu-firstboot
cat >/etc/systemd/system/remu-firstboot.service <<'UNIT'
[Unit]
Description=Remu hub first boot
After=network-online.target
Wants=network-online.target
Before=remu-hub.service
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/remu-firstboot
[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable remu-firstboot.service remu-hub.service remu-hub-jobs.service chrony
/opt/remu/venv/bin/remu-hub --version
echo "done. Fill /etc/remu/hub.env (REMU_SHOP_ID, REMU_CLOUD_URL), then reboot."
