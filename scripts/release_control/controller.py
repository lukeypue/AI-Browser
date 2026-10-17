"""Opt-in, single-host release gate. Never interprets review text as commands."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import time
import urllib.request
import urllib.parse
import zipfile

STATE = Path('/var/lib/aibrowser-release-control')
PIN = '118079b156fabeae30aca7ad845f1a15cd7eea89740bc1472be2163400d40239'
BRANCH = 'ai-browser-v6-deep-search'
REMOTE = 'git@github.com:lukeypue/AI-Browser.git'
API = 'https://api.github.com/repos/lukeypue/AI-Browser'
WORKFLOW = 'Build AppGate APK'
PACKAGE = 'com.aibrowser.universal'
KOTLIN_STDLIB = '/opt/ai-browser-dev/verification-home/gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/30de6faa127a4a012db8e71bf1b9c0a99b1402b2/kotlin-stdlib-2.2.10.jar'
DEX_ENTRY_LIMIT = 64*1024*1024
DEX_TOTAL_LIMIT = 256*1024*1024
DEX_ENTRY_COUNT = 64

class ReleaseError(RuntimeError):
    pass

def require(condition, message):
    if not condition:
        raise ReleaseError(message)

def atomic_json(path, value):
    fd, name = tempfile.mkstemp(prefix='.ledger-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, sort_keys=True)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        if os.path.exists(name): os.unlink(name)

class ReleaseController:
    def __init__(self, adapter, state=STATE):
        self.adapter, self.state = adapter, Path(state)

    @contextmanager
    def locked(self):
        self.state.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = self.state.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.geteuid()
                and not info.st_mode & 0o022, 'Unsafe release state directory')
        fd = os.open(self.state/'release.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            info = os.fstat(fd)
            require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid()
                    and info.st_nlink == 1 and not info.st_mode & 0o022, 'Unsafe release lock')
            try: fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError: raise ReleaseError('Another release holds the shared lock') from None
            yield
        finally: os.close(fd)

    def release(self, candidate, expected, review):
        require(all(re.fullmatch('[0-9a-f]{40}', x) for x in (candidate, expected)), 'Full source SHAs required')
        with self.locked():
            require(review.get('source_sha') == candidate and review.get('status') == 'approved'
                    and review.get('independent') is True and bool(review.get('reviewer'))
                    and bool(review.get('evidence')), 'Independent approved review must bind candidate SHA')
            a = self.adapter
            require(a.source(candidate) == candidate, 'Candidate worktree does not match source')
            require(a.stable_head() == expected, 'Stable head changed; review and retry explicitly')
            require(a.is_ancestor(expected, candidate), 'Candidate is not a fast-forward descendant')
            a.checks()
            require(a.source(candidate) == candidate, 'Source changed during checks')
            require(a.stable_head() == expected, 'Stable head changed during checks')
            a.push(candidate)
            run = a.wait_ci(candidate)
            require(run.get('head_sha') == candidate and run.get('event') == 'push'
                    and run.get('head_branch') == BRANCH and run.get('name') == WORKFLOW
                    and run.get('conclusion') == 'success', 'Exact-source stable push CI did not succeed')
            artifact = a.verify_download(candidate, run)
            require(artifact.get('commit') == candidate, 'Published metadata source mismatch')
            require(artifact.get('certificate_sha256') == PIN and artifact.get('package') == PACKAGE,
                    'Published package or stable certificate mismatch')
            require(type(artifact.get('versionCode')) is int and artifact['versionCode'] > 918,
                    'Published version must advance latest verified stable version')
            require(a.stable_head() == candidate, 'Stable head moved before completion')
            previous = self.state/'ledger.json'
            if previous.exists():
                require(artifact['versionCode'] > json.loads(previous.read_text())['versionCode'],
                        'Published version did not advance ledger')
            result = dict(artifact, status='VERIFIED', source_sha=candidate, expected_stable_head=expected,
                          workflow_run_id=run['id'], review=review, verified_at=int(time.time()))
            atomic_json(previous, result)
            return result

class HTTPSOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        require(urllib.parse.urlsplit(newurl).scheme == 'https', 'Non-HTTPS redirect rejected')
        return super().redirect_request(req, fp, code, msg, headers, newurl)

class Production:
    """Trusted fixed commands; subprocess environment never inherits caller secrets."""
    def __init__(self, worktree):
        self.root = Path(worktree).resolve(strict=True)
        require(self.root.is_dir(), 'Worktree directory required')
        self.sdk = Path('/opt/ai-browser-dev/android-sdk')
        self.env = dict(PATH='/usr/local/bin:/usr/bin:/bin', LANG='C.UTF-8',
                        HOME='/opt/ai-browser-dev/verification-home',
                        JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64',
                        ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk),
                        GRADLE_USER_HOME='/opt/ai-browser-dev/verification-home/gradle',
                        PLAYWRIGHT_MODULE='/opt/ai-browser-dev/browser-harness/node_modules/playwright',
                        PLAYWRIGHT_BROWSERS_PATH='/opt/ai-browser-dev/verification-home/.cache/ms-playwright')
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), HTTPSOnly())

    def command(self, argv, *, cwd=None, git=False, timeout=1800):
        env = dict(self.env)
        if git:
            require(argv[0] == '/usr/bin/git', 'Only fixed Git executable is permitted')
            argv = [argv[0], '-c', 'core.hooksPath=/dev/null', '-c', 'credential.helper=',
                    '-c', 'protocol.allow=never', '-c', 'protocol.ssh.allow=always', *argv[1:]]
            env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null', GIT_TERMINAL_PROMPT='0',
                       GIT_SSH_COMMAND='ssh -F /dev/null -o BatchMode=yes -o IdentitiesOnly=yes '
                       '-o StrictHostKeyChecking=yes -o UserKnownHostsFile=/var/lib/aibrowser-practice-city-upload/known_hosts '
                       '-i /root/.ssh/ai-browser-source-upload')
        result = subprocess.run(argv, cwd=cwd or self.root, env=env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)
        # Do not echo tool output: repository-controlled output may contain sensitive material.
        require(result.returncode == 0, 'Trusted check/operation failed: ' + Path(argv[0]).name)
        return result.stdout.strip()

    def source(self, candidate):
        require(not self.command(['/usr/bin/git','status','--porcelain','--untracked-files=normal'], git=True),
                'Release requires a clean dedicated worktree')
        return self.command(['/usr/bin/git','rev-parse','HEAD'], git=True)

    def stable_head(self):
        out = self.command(['/usr/bin/git','ls-remote',REMOTE,'refs/heads/'+BRANCH], git=True, timeout=60)
        require(bool(re.fullmatch(r'[0-9a-f]{40}\s+refs/heads/'+BRANCH, out)), 'Unexpected stable ref response')
        return out.split()[0]

    def is_ancestor(self, old, new):
        # Fetch into FETCH_HEAD only; never reset or modify another checkout.
        self.command(['/usr/bin/git','fetch','--no-tags',REMOTE,'refs/heads/'+BRANCH], git=True, timeout=120)
        self.command(['/usr/bin/git','merge-base','--is-ancestor',old,new], git=True)
        return True

    def checks(self):
        self.command(['/usr/local/bin/gradle','--no-daemon','--max-workers=1',
                      '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m',
                      '-Pkotlin.compiler.execution.strategy=in-process', ':brain-core:test',
                      ':app:testDebugUnitTest',':app:assembleDebug',':app:lintDebug'], cwd=self.root/'ai-browser')
        self.command(['/usr/bin/python3','-m','unittest','discover','-s','scripts/tests'])
        self.command(['/usr/bin/python3','-m','unittest','discover','-s','deploy/phone-reports/receiver'])
        revision = self.command(['/usr/bin/git','rev-parse','HEAD'], git=True)
        require(bool(re.fullmatch('[0-9a-f]{40}', revision)), 'Invalid candidate core revision')
        classes = self.root/'ai-browser/brain-core/build/classes/kotlin/main'
        require(classes.is_dir() and Path(KOTLIN_STDLIB).is_file(), 'Pinned Kotlin core classpath unavailable')
        for name in ('perception/SpsParser', 'verify/Verifier', 'engine/LearningOpportunities', 'skills/BuiltinSkills'):
            source = self.root/'ai-browser/brain-core/src/main/kotlin/com/appgate/brain'/f'{name}.kt'
            compiled = classes/'com/appgate/brain'/f'{name}.class'
            require(source.is_file() and compiled.is_file()
                    and compiled.stat().st_mtime_ns >= source.stat().st_mtime_ns,
                    'Missing or stale candidate core classes')
        self.env.update(FIXTURE_CORE_CHECKOUT=str(self.root), FIXTURE_CORE_REVISION=revision,
                        FIXTURE_CORE_CLASSPATH=str(classes)+':'+KOTLIN_STDLIB)
        node = '/opt/ai-browser-dev/node-v24.21.0-linux-x64/bin/node'
        harness = self.root/'ai-browser/brain-core/src/test/harness'
        tests = sorted(harness.glob('*.test.mjs'))
        require(bool(tests), 'Trusted browser harness tests unavailable')
        self.command([node,'--test','--test-concurrency=1',*[str(path) for path in tests]])
        script_tests = sorted((self.root/'scripts/tests').glob('*.test.mjs'))
        require({'android-fixtures.test.mjs', 'android-fixture-curriculum.test.mjs'}
                <= {path.name for path in script_tests}, 'Required Android fixture coverage unavailable')
        self.command([node,'--test','--test-concurrency=1',*[str(path) for path in script_tests]])

    def push(self, candidate):
        self.command(['/usr/bin/git','push',REMOTE,candidate+':refs/heads/'+BRANCH], git=True, timeout=120)

    def download(self, url, limit):
        require(urllib.parse.urlsplit(url).scheme == 'https', 'HTTPS required')
        request = urllib.request.Request(url, headers={'Accept':'application/vnd.github+json',
                                                      'User-Agent':'AI-Browser-verified-release'})
        with self.opener.open(request, timeout=60) as response:
            data = response.read(limit+1)
        require(len(data) <= limit, 'Download exceeds size bound')
        return data

    def wait_ci(self, candidate):
        deadline = time.monotonic()+3600
        while time.monotonic() < deadline:
            runs = json.loads(self.download(API+'/actions/runs?head_sha='+candidate+'&event=push&per_page=100', 2_000_000))
            matches = [r for r in runs['workflow_runs'] if r.get('head_sha') == candidate
                       and r.get('event') == 'push' and r.get('head_branch') == BRANCH
                       and r.get('name') == WORKFLOW and r.get('path') == '.github/workflows/build.yml']
            if matches:
                run = max(matches, key=lambda r:(r['id'],r.get('run_attempt',1)))
                if run.get('status') == 'completed': return run
            time.sleep(15)
        raise ReleaseError('Timed out waiting for exact-source stable CI')

    def verify_download(self, candidate, run):
        release = json.loads(self.download(API+'/releases/tags/ai-browser-latest', 2_000_000))
        assets = {a['name']: a for a in release['assets']}
        def asset(name, limit):
            url = assets[name]['browser_download_url']
            prefix = 'https://github.com/lukeypue/AI-Browser/releases/download/ai-browser-latest/'
            require(url == prefix+name, 'Unexpected stable asset URL')
            return self.download(url, limit)
        meta = json.loads(asset('latest-version.json', 16384))
        source_version = json.loads((self.root/'ai-browser-release.json').read_text())
        require(meta.get('commit') == candidate and meta.get('versionCode') == source_version['versionCode']+run['run_number']
                and meta.get('versionName') == source_version['versionName']+'.build'+str(run['run_number']),
                'Published source/version allocation mismatch')
        apk = asset('AI-Browser-latest.apk', 512*1024*1024)
        apk_hash = hashlib.sha256(apk).hexdigest()
        apk_asset = assets['AI-Browser-latest.apk']
        digest = apk_asset.get('digest')
        require(isinstance(digest, str) and re.fullmatch('sha256:[0-9a-f]{64}', digest)
                and digest == 'sha256:'+apk_hash, 'GitHub APK asset digest mismatch or unavailable')
        require(type(apk_asset.get('size')) is int and apk_asset['size'] == len(apk), 'GitHub APK asset size mismatch')
        try:
            with zipfile.ZipFile(io.BytesIO(apk)) as archive:
                dex = [info for info in archive.infolist() if re.fullmatch(r'classes(?:[1-9][0-9]*)?\.dex', info.filename)]
                require(0 < len(dex) <= DEX_ENTRY_COUNT, 'APK DEX entry count invalid')
                total, found = 0, False
                for info in dex:
                    total += info.file_size
                    require(info.file_size <= DEX_ENTRY_LIMIT and total <= DEX_TOTAL_LIMIT, 'APK DEX size bound exceeded')
                    with archive.open(info) as stream:
                        content = stream.read(DEX_ENTRY_LIMIT+1)
                    require(len(content) == info.file_size and len(content) <= DEX_ENTRY_LIMIT, 'APK DEX read bound or size mismatch')
                    found |= candidate.encode('ascii') in content
                require(found, 'Candidate source revision absent from APK DEX')
        except (zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError):
            raise ReleaseError('Invalid or unsupported APK DEX archive') from None
        tools = sorted((self.sdk/'build-tools').glob('[0-9]*'), key=lambda p:tuple(int(x) for x in p.name.split('.') if x.isdigit()))
        require(bool(tools), 'Android build tools unavailable')
        with tempfile.TemporaryDirectory(prefix='verified-release-') as temp:
            path = Path(temp)/'stable.apk'
            path.write_bytes(apk)
            certs = self.command([str(tools[-1]/'apksigner'),'verify','--print-certs',str(path)])
            digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$',certs,re.M)
            require(digests and all(d.lower() == PIN for d in digests), 'Stable signing certificate mismatch')
            badging = self.command([str(tools[-1]/'aapt'),'dump','badging',str(path)])
            package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
            require(package and package.group(1) == PACKAGE and int(package.group(2)) == meta['versionCode']
                    and package.group(3) == meta['versionName'], 'APK package/version mismatch')
        return dict(meta, package=PACKAGE, certificate_sha256=PIN, apk_sha256=apk_hash)

def main():
    parser = argparse.ArgumentParser(description='Explicit verified stable release; never installs on a phone')
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--expected-stable-head', required=True)
    parser.add_argument('--worktree', required=True)
    parser.add_argument('--review-attestation', required=True)
    args = parser.parse_args()
    review = json.loads(Path(args.review_attestation).read_text())
    try:
        result = ReleaseController(Production(args.worktree)).release(args.candidate,args.expected_stable_head,review)
    except (ReleaseError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as exc:
        raise SystemExit('Release verification stopped: '+type(exc).__name__) from None
    print(json.dumps(result, sort_keys=True))
