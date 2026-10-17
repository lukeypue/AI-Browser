"""Release automation is the sole owner of Android version allocation."""
import json
import os
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
version = json.loads((root / "ai-browser-release.json").read_text())
# Allocate in release automation, never by manually editing Android version fields.
# A workflow rerun keeps its number; every later push gets a larger number.
if (os.environ.get("GITHUB_ACTIONS") == "true" and
        os.environ.get("GITHUB_EVENT_NAME") == "push" and
        os.environ.get("GITHUB_REF") == "refs/heads/ai-browser-v6-deep-search"):
    number = os.environ.get("GITHUB_RUN_NUMBER", "")
    if not re.fullmatch(r"[1-9][0-9]{0,9}", number):
        raise SystemExit("Stable release requires a positive workflow run number")
    allocated = int(version["versionCode"]) + int(number)
    if not 1 <= allocated <= 2_147_483_647:
        raise SystemExit("Allocated Android version code is out of range")
    version = dict(version, versionCode=allocated,
                   versionName=f"{version['versionName']}.build{number}")
path = root / "ai-browser/app/build.gradle"
content = path.read_text()
for pattern, replacement in [
    (r"versionCode\s+\d+", f"versionCode {int(version['versionCode'])}"),
    (r'versionName\s+"[^"]+"', f'versionName "{version["versionName"]}"'),
]:
    content, count = re.subn(pattern, replacement, content)
    if count != 1:
        raise SystemExit("Expected exactly one Android version declaration")
path.write_text(content)
print(f"Prepared AI Browser {version['versionName']} ({version['versionCode']})")
