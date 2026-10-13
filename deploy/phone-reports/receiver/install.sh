#!/usr/bin/env bash
# Explicit deployment only. DNS must be ready; no phone install or app release.
set -euo pipefail
test "$EUID" -eq 0
source_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
python3 - <<'PY'
import socket
try:addresses={x[4][0] for x in socket.getaddrinfo('reports.ryzod.com',443,type=socket.SOCK_STREAM)}
except socket.gaierror:raise SystemExit('DNS is not ready; no installation performed')
if addresses!={'165.227.50.222'}:raise SystemExit('DNS is not ready or has a conflicting address; no installation performed')
print('DNS points exclusively to the authorized server')
PY
if [[ ${1:-} == --check-dns ]]; then exit 0; fi
python3 -m unittest discover -s "$source_dir" -v
sha256sum -c /opt/ai-browser-report-sharing/training-units.sha256
case $(systemctl show aibrowser-practice-city-upload.service -p ActiveState --value) in
 active|activating|deactivating) echo 'Uploader currently busy; retry deployment after it completes.' >&2; exit 1;;
esac
test ! -e /etc/nginx/sites-available/reports.ryzod.com
test ! -e /etc/systemd/system/aibrowser-phone-receiver.service
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx certbot python3-certbot-nginx
if ! id aibrowserreceiver >/dev/null 2>&1; then useradd --system --gid aibrowserupload --home-dir /var/lib/aibrowser-phone-receiver --shell /usr/sbin/nologin aibrowserreceiver; fi
install -d -o root -g root -m 755 /opt/aibrowser-phone-receiver /var/lib/aibrowser-acme
install -d -o aibrowserreceiver -g aibrowserupload -m 750 /var/lib/aibrowser-phone-receiver
install -m 644 "$source_dir/receiver.py" /opt/aibrowser-phone-receiver/receiver.py
install -m 644 "$source_dir/aibrowser-phone-receiver.service" /etc/systemd/system/aibrowser-phone-receiver.service
cat > /etc/nginx/sites-available/reports.ryzod.com <<'NGINX'
server {
 listen 80;
 server_name reports.ryzod.com;
 access_log off;
 error_log /dev/null crit;
 location ^~ /.well-known/acme-challenge/ { root /var/lib/aibrowser-acme; }
 location / { return 503; }
}
NGINX
ln -s /etc/nginx/sites-available/reports.ryzod.com /etc/nginx/sites-enabled/reports.ryzod.com
nginx -t
systemctl reload nginx
certbot certonly --webroot -w /var/lib/aibrowser-acme -d reports.ryzod.com --non-interactive --agree-tos --register-unsafely-without-email
cat > /etc/nginx/sites-available/reports.ryzod.com <<'NGINX'
server {
 listen 80;
 server_name reports.ryzod.com;
 access_log off;
 error_log /dev/null crit;
 location ^~ /.well-known/acme-challenge/ { root /var/lib/aibrowser-acme; }
 location / { return 308 https://reports.ryzod.com$request_uri; }
}
server {
 listen 443 ssl;
 server_name reports.ryzod.com;
 ssl_certificate /etc/letsencrypt/live/reports.ryzod.com/fullchain.pem;
 ssl_certificate_key /etc/letsencrypt/live/reports.ryzod.com/privkey.pem;
 ssl_protocols TLSv1.2 TLSv1.3;
 access_log off;
 error_log /dev/null crit;
 client_max_body_size 1m;
 client_body_buffer_size 1m;
 client_body_timeout 20s;
 location / {
  proxy_pass http://127.0.0.1:8765;
  proxy_set_header Host reports.ryzod.com;
  proxy_set_header X-Forwarded-Proto https;
  proxy_set_header X-Forwarded-For "";
  proxy_connect_timeout 5s;
  proxy_read_timeout 30s;
 }
}
NGINX
nginx -t
systemctl daemon-reload
systemctl enable --now aibrowser-phone-receiver.service
systemctl reload nginx
curl --fail --silent --show-error --connect-timeout 15 https://reports.ryzod.com/health
printf '\nHTTPS health verified with normal certificate validation\n'
install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nnginx -t && systemctl reload nginx\n' > /etc/letsencrypt/renewal-hooks/deploy/aibrowser-nginx.sh
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/aibrowser-nginx.sh
systemctl enable --now certbot.timer
# Publish only through the existing repository-scoped uploader and shared lock.
install -m 644 "$source_dir/receiver.py" /opt/ai-browser-report-sharing/receiver.py
install -m 644 "$source_dir/publisher.py" /opt/ai-browser-report-sharing/publisher.py
cp -n /opt/ai-browser-report-sharing/export_reports.py /opt/ai-browser-report-sharing/export_reports.py.before-phone-reports
install -m 644 "$source_dir/synthetic_writer.py" /opt/ai-browser-report-sharing/export_reports.py.next
mv /opt/ai-browser-report-sharing/export_reports.py.next /opt/ai-browser-report-sharing/export_reports.py
python3 - <<'PY'
from pathlib import Path
p=Path('/etc/systemd/system/aibrowser-practice-city-upload.path')
s=p.read_text();line='PathChanged=/var/lib/aibrowser-phone-receiver/outbox'
if line not in s:s=s.replace('[Path]','[Path]\n'+line);p.write_text(s)
PY
systemctl daemon-reload
systemctl restart aibrowser-practice-city-upload.path
systemctl start aibrowser-practice-city-upload.service
sha256sum -c /opt/ai-browser-report-sharing/training-units.sha256
systemctl is-active aibrowser-practice-city.timer aibrowser-practice-city-upload.path aibrowser-phone-receiver.service
printf 'No phone has been paired or installed. Real phone receipt remains unverified.\n'
