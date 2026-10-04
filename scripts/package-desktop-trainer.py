#!/usr/bin/env python3
"""Package only reviewed trainer files; runtimes and user data are never bundled."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

repo = Path(__file__).resolve().parents[1]
output = Path(sys.argv[1] if len(sys.argv) > 1 else repo / 'dist/Site-Brain-Trainer.zip')
source = repo / 'desktop-trainer'
files = {p.relative_to(source).as_posix(): p for p in source.rglob('*')
         if p.is_file() and (len(p.relative_to(source).parts) == 1 or
             (len(p.relative_to(source).parts) == 2 and p.relative_to(source).parts[0] == 'tests'))
         and p.suffix in {'.mjs', '.json', '.ps1', '.cmd', '.html', '.txt'}}
files['site-brain-trainer.jar'] = repo / 'ai-browser/brain-core/build/libs/site-brain-trainer.jar'
files['content.js'] = repo / 'ai-browser/app/src/main/assets/sitebrain/content.js'
manifest = {'version': '1.0.0', 'source_commit': subprocess.check_output(
    ['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
    'files': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in sorted(files.items())}}
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, path in sorted(files.items()):
        archive.write(path, 'Site-Brain-Trainer/' + name)
    archive.writestr('Site-Brain-Trainer/bundle-manifest.json', json.dumps(manifest, indent=2))
print(output)
