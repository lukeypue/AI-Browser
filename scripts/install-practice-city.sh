#!/usr/bin/env bash
# Install from a verified clean checkout. No release, phone access, or report upload.
set -euo pipefail
if [[ $EUID != 0 ]]; then echo 'Run installation as root on the existing server.' >&2; exit 1; fi
source_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
revision=$(git -C "$source_dir" rev-parse HEAD)
if [[ -n $(git -C "$source_dir" status --porcelain -- ai-browser/brain-core scripts/practice-city.py scripts/install-practice-city.sh deploy/practice-city) ]]; then
  echo 'Commit and verify the practice-city source before installation.' >&2; exit 1
fi
for command in gradle git python3 runuser systemctl timeout; do command -v "$command" >/dev/null; done
state=/var/lib/aibrowser-practice-city
service_state=$(systemctl show -p ActiveState --value aibrowser-practice-city.service 2>/dev/null || true)
case "$service_state" in
  active|activating|deactivating|reloading)
    echo 'An existing practice-city session is running; installation did not replace it.' >&2; exit 1 ;;
esac
if systemctl is-enabled --quiet aibrowser-practice-city.timer 2>/dev/null || systemctl is-active --quiet aibrowser-practice-city.timer; then
  echo 'An existing practice-city timer is enabled; preserve it and review the upgrade first.' >&2; exit 1
fi
if ! id aibrowserlab >/dev/null 2>&1; then
  useradd --system --user-group --home-dir "$state" --shell /usr/sbin/nologin aibrowserlab
fi
install -d -o aibrowserlab -g aibrowserlab -m 750 "$state" "$state/reports" "$state/gradle-cache"
if [[ ! -e "$state/source" ]]; then
  git clone --no-hardlinks --no-checkout "$source_dir" "$state/source"
  git -C "$state/source" sparse-checkout init --cone
  git -C "$state/source" sparse-checkout set ai-browser/brain-core scripts deploy/practice-city
  git -C "$state/source" checkout --detach "$revision"
  chown -R aibrowserlab:aibrowserlab "$state/source"
else
  [[ $(runuser -u aibrowserlab -- git -C "$state/source" rev-parse HEAD) == "$revision" ]] || {
    echo 'Existing lab checkout has a different revision; preserve it and review the upgrade first.' >&2; exit 1;
  }
  [[ -z $(runuser -u aibrowserlab -- git -C "$state/source" status --porcelain) ]] || {
    echo 'Existing lab checkout contains changes; installation did not overwrite them.' >&2; exit 1;
  }
fi
# Warm dependencies and validate the full first session under the runner's shared locks.
timeout --kill-after=20s 35m runuser -u aibrowserlab -- env HOME="$state" GRADLE_USER_HOME="$state/gradle-cache" \
  python3 "$state/source/scripts/practice-city.py" --project "$state/source" --output-dir "$state/reports" --prepare-dependencies
install -m 644 "$source_dir/deploy/practice-city/aibrowser-practice-city.service" /etc/systemd/system/aibrowser-practice-city.service
install -m 644 "$source_dir/deploy/practice-city/aibrowser-practice-city.timer" /etc/systemd/system/aibrowser-practice-city.timer
systemctl daemon-reload
systemctl enable --now aibrowser-practice-city.timer
systemctl list-timers aibrowser-practice-city.timer --no-pager
printf 'Verified lab revision: %s\nLatest report: %s/reports/latest.json\n' "$revision" "$state"
