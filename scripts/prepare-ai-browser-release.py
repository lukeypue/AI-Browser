"""Release automation is the sole owner of Android version allocation."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
version = json.loads((root / "ai-browser-release.json").read_text())
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
