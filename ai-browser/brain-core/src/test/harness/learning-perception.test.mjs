// Run with PLAYWRIGHT_MODULE pointing to an installed Playwright package, or install
// Playwright locally and its Chromium browser. Tests use only routed synthetic pages.
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {test, before, after} from 'node:test';
const {chromium} = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright');
const source = readFileSync(new URL('../../../../app/src/main/assets/sitebrain/content.js', import.meta.url), 'utf8');
let browser;
before(async () => { browser = await chromium.launch({executablePath: process.env.CHROMIUM || undefined}); });
after(async () => { await browser?.close(); });

async function fixture(html) {
  const page = await browser.newPage({viewport: {width: 412, height: 915}});
  await page.route('**/*', route => route.fulfill({contentType: 'text/html', body: html}));
  await page.goto('https://fixtures.test/search');
  await page.evaluate(source);
  return page;
}
const style = '<style>a.card{display:block;height:150px;width:300px;margin:10px}aside{width:300px;height:120px}</style>';
const cards = count => Array.from({length: count}, (_, i) => `<a class="card" href="/item/${100001 + i}"><span>Mountain bike ${i + 1}</span><span>$${100 + i}</span></a>`).join('');

test('whole-card links have matching actionable IDs and open the intended listing', async () => {
  const page = await fixture(style + '<main><input type="search" placeholder="Search"><section>' + cards(3) + '</section></main>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    assert.equal(obs.items.length, 3);
    const item = obs.items[1];
    assert.equal(item.href, 'https://fixtures.test/item/100002');
    const target = obs.elements.find(e => e.id === item.aff);
    assert.equal(target.itemKey, item.key);
    await page.evaluate(({obs, item}) => window.__brainExecute({cmd: 'click', id: item.aff, expectedDocumentId: obs.documentId, expectedUrl: obs.url}), {obs, item});
    await page.waitForURL('**/item/100002');
  } finally { await page.close(); }
});

test('a two-card results grid remains recognizable without mistaking navigation for listings', async () => {
  const nav = '<nav><a href="/a">One</a><a href="/b">Two</a><a href="/c">Three</a></nav>';
  const page = await fixture(style + nav + '<main><input type="search" placeholder="Search"><section>' + cards(2) + '</section></main>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    assert.equal(obs.items.length, 2);
    assert.ok(obs.items.every(i => i.href.includes('/item/')));
  } finally { await page.close(); }
});

test('nested filter regions do not hide dialog membership of the close control', async () => {
  const page = await fixture(style + '<main><button>Close</button></main><div role="dialog"><aside class="filters"><button id="dismiss" aria-label="Close" onclick="this.closest(\'[role=dialog]\').remove()">×</button><p>Browse nearby items</p></aside></div>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    const close = obs.elements.find(e => e.name === 'Close' && e.inDialog);
    assert.ok(close, 'a closer inside a nested region still belongs to the dialog');
    await page.evaluate(({obs, close}) => window.__brainExecute({cmd: 'dismiss', id: close.id, expectedDocumentId: obs.documentId, expectedUrl: obs.url}), {obs, close});
    assert.equal((await page.evaluate(() => window.__brainObserve())).signals.dialog, false);
  } finally { await page.close(); }
});
