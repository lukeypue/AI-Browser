import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {test} from 'node:test';

const source = readFileSync(new URL('../../../../app/src/main/assets/sitebrain/content.js', import.meta.url), 'utf8');
const manifest = JSON.parse(readFileSync(new URL('../../../../app/src/main/assets/sitebrain/manifest.json', import.meta.url), 'utf8'));
function document() {
  const context = {window: {}, document: {readyState: 'loading', documentElement: null, addEventListener() {}}, location: {href: 'https://fake.market/a'}, setTimeout, performance, console};
  vm.createContext(context); vm.runInContext(source, context);
  return context;
}

test('an action addressed to a replaced document is refused before target lookup', async () => {
  const page = document();
  const result = await page.window.__brainExecute({cmd: 'click', id: 'old-id', expectedDocumentId: 'old-document', expectedUrl: page.location.href});
  assert.equal(result.ok, false);
  assert.equal(result.detail, 'STALE_DOCUMENT');
});

test('a command missing observation identity cannot act, but ping remains available', async () => {
  const page = document();
  assert.equal((await page.window.__brainExecute({cmd: 'click', id: 'old-id'})).detail, 'STALE_DOCUMENT');
  assert.equal((await page.window.__brainExecute({cmd: 'ping'})).ok, true);
});

test('the required document identity protocol upgrades existing 3.0.0 profiles', () => {
  const [major, minor, patch] = manifest.version.split('.').map(Number);
  assert.ok(major > 3 || (major === 3 && (minor > 0 || patch > 0)), 'ensureBuiltIn keeps an equal-version installed extension');
  assert.equal(manifest.browser_specific_settings.gecko.id, 'sitebrain@aibrowser.local');
});
