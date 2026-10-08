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

async function fixture(html, url = 'https://fixtures.test/search') {
  const page = await browser.newPage({viewport: {width: 412, height: 915}});
  await page.route('**/*', route => route.fulfill({contentType: 'text/html', body: html}));
  await page.goto(url);
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

test('paired image and title links do not hide the enclosing listing cards', async () => {
  const listings = Array.from({length: 3}, (_, i) => `<article class="listing"><div class="links"><a href="/item/${100001 + i}">Photo</a><a href="/item/${100001 + i}">Mountain bike ${i + 1}</a></div><p>$${100 + i}</p></article>`).join('');
  const page = await fixture('<style>.listing{height:220px;width:300px}.links a{display:block;height:60px}</style><main><section>' + listings + '</section></main>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    assert.equal(obs.items.length, 3);
    assert.ok(obs.items.every(item => item.aff && item.price && item.href.includes('/item/')));
  } finally { await page.close(); }
});

test('recommended cards do not turn a detail page into search results or supply its description', async () => {
  const page = await fixture(style + '<main><h1>Primary bicycle</h1><p>$500</p><p>Description: ' + 'The bicycle is in good condition. '.repeat(12) + '</p><section><h2>Similar items</h2>' + cards(2) + '</section></main>', 'https://fixtures.test/item/900001');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    assert.equal(obs.signals.detailHint, true);
    assert.equal(obs.signals.resultsHint, false);
    assert.ok(obs.detailText.includes('Primary bicycle'));
    assert.ok(!obs.detailText.includes('Mountain bike'), 'recommendation descriptions belong to other listings');
  } finally { await page.close(); }
});

test('two results with long snippets still provide results evidence', async () => {
  const longCards = cards(2).replaceAll('</a>', '<span>Description: ' + 'Used bicycle in good condition. '.repeat(10) + '</span></a>');
  const page = await fixture(style + '<main><h1>Search results</h1><section>' + longCards + '</section></main>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    assert.equal(obs.items.length, 2);
    assert.equal(obs.signals.detailHint, false);
    assert.equal(obs.signals.resultsHint, true);
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

test('existing standalone and modal detail fixtures retain their own readable descriptions', async () => {
  for (const name of ['detail_page', 'modal_detail_page']) {
    const html = readFileSync(new URL(`../fixtures/${name}.html`, import.meta.url), 'utf8');
    const page = await fixture(html, `https://fixtures.test/${name}.html`);
    try {
      const obs = await page.evaluate(() => window.__brainObserve());
      assert.equal(obs.signals.detailHint, true, name);
      assert.ok(obs.detailText.includes('3.73'), name);
      assert.ok(obs.detailText.length > 150, name);
    } finally { await page.close(); }
  }
});

test('training opens observed listing links in this tab when unattended popups are refused', async () => {
  const page = await fixture(style + '<main><section>' + cards(3).replaceAll('<a ', '<a target="_blank" ') + '</section></main>');
  page.on('popup', popup => popup.close());
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    await page.evaluate(() => window.__brainExecute({cmd: 'guard', mode: 'TRAIN'}));
    const item = obs.items[1];
    await page.evaluate(({obs, item}) => window.__brainExecute({cmd: 'click', id: item.aff, expectedDocumentId: obs.documentId, expectedUrl: obs.url}), {obs, item});
    await page.waitForURL('**/item/100002', {timeout: 1500});
  } finally { await page.close(); }
});

test('manual browsing retains listing link popup behavior', async () => {
  const page = await fixture(style + '<main><section>' + cards(3).replaceAll('<a ', '<a target="_blank" ') + '</section></main>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    const popupPromise = page.waitForEvent('popup');
    await page.evaluate(({obs}) => window.__brainExecute({cmd: 'click', id: obs.items[0].aff, expectedDocumentId: obs.documentId, expectedUrl: obs.url}), {obs});
    const popup = await popupPromise;
    assert.equal(page.url(), obs.url);
    await popup.close();
  } finally { await page.close(); }
});

for (const button of ['<button type="submit">Go</button>', '<button type="submit" hidden>Hidden</button><button type="submit">Go</button>', '<button type="submit" disabled>Disabled</button><button type="submit">Go</button>']) {
  test('search submission chooses an enabled visible button instead of the labelled input: ' + button, async () => {
    const page = await fixture('<form onsubmit="event.preventDefault(); window.submissions=(window.submissions||0)+1"><input type="search" aria-label="Search listings">' + button + '</form>');
    try {
      const obs = await page.evaluate(() => window.__brainObserve());
      const input = obs.elements.find(e => e.tag === 'input');
      await page.evaluate(({obs, input}) => window.__brainExecute({cmd:'type', id:input.id, text:'bike', submit:true, expectedDocumentId:obs.documentId, expectedUrl:obs.url}), {obs, input});
      assert.equal(await page.evaluate(() => window.submissions || 0), 1);
      assert.equal(await page.locator('input').inputValue(), 'bike');
    } finally { await page.close(); }
  });
}

test('training follows a current listing href when its script cancels synthetic clicks', async () => {
  const page = await fixture(style + '<main><section>' + cards(3).replaceAll('<a ', '<a onclick="event.preventDefault()" ') + '</section></main>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    await page.evaluate(() => window.__brainExecute({cmd: 'guard', mode: 'TRAIN'}));
    const item = obs.items[1];
    await page.evaluate(({obs, item}) => window.__brainExecute({cmd: 'click', id: item.aff, expectedDocumentId: obs.documentId, expectedUrl: obs.url}), {obs, item});
    await page.waitForURL('**/item/100002', {timeout: 1500});
  } finally { await page.close(); }
});

test('training refuses a listing whose observed href changed before execution', async () => {
  const page = await fixture(style + '<main><section>' + cards(3) + '</section></main>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    await page.evaluate(() => { window.__brainExecute({cmd: 'guard', mode: 'TRAIN'}); document.querySelector('a.card').href = '/checkout'; });
    const result = await page.evaluate(({obs}) => window.__brainExecute({cmd: 'click', id: obs.items[0].aff, expectedDocumentId: obs.documentId, expectedUrl: obs.url}), {obs});
    assert.equal(result.detail, 'STALE_TARGET');
    assert.equal(page.url(), obs.url);
  } finally { await page.close(); }
});

test('unrelated thumbnail loading does not hold stable results busy', async () => {
  const page = await fixture(style + '<main><section>' + cards(3) + '</section></main><img id="thumbnail"><script>setInterval(() => { document.querySelector("#thumbnail").src="/thumb.png?t="+Date.now() }, 100)</script>');
  try {
    await page.route('**/thumb.png?*', route => route.fulfill({contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=', 'base64')}));
    await page.waitForTimeout(900);
    assert.equal((await page.evaluate(() => window.__brainObserve())).settle.state, 'IDLE');
  } finally { await page.close(); }
});

test('an actual results loader keeps the page busy', async () => {
  const page = await fixture(style + '<main aria-busy="true"><section>' + cards(3) + '</section><div role="progressbar">Loading results</div></main>');
  try {
    await page.waitForTimeout(900);
    assert.equal((await page.evaluate(() => window.__brainObserve())).settle.state, 'BUSY');
  } finally { await page.close(); }
});

test('an offscreen lazy loader does not block visible stable results', async () => {
  const page = await fixture(style + '<main><section>' + cards(3) + '</section><div style="margin-top:3000px" class="skeleton">Loading more images</div></main>');
  try {
    await page.waitForTimeout(900);
    assert.equal((await page.evaluate(() => window.__brainObserve())).settle.state, 'IDLE');
  } finally { await page.close(); }
});


test('a newly connected document applies TRAIN from the action envelope', async () => {
  const page = await fixture(style + '<main><section>' + cards(3).replaceAll('<a ', '<a onclick="event.preventDefault()" ') + '</section></main>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    await page.evaluate(({obs}) => window.__brainExecute({cmd:'click', guardMode:'TRAIN', id:obs.items[0].aff, expectedDocumentId:obs.documentId, expectedUrl:obs.url}), {obs});
    await page.waitForURL('**/item/100001', {timeout:1500});
  } finally { await page.close(); }
});

test('TRAIN action envelopes block commit controls and OFF restores manual actions', async () => {
  const page = await fixture('<button onclick="window.sent=(window.sent||0)+1">Send</button>');
  try {
    const obs = await page.evaluate(() => window.__brainObserve());
    const blocked = await page.evaluate(({obs}) => window.__brainExecute({cmd:'click', guardMode:'TRAIN', id:obs.elements[0].id, expectedDocumentId:obs.documentId, expectedUrl:obs.url}), {obs});
    assert.equal(blocked.detail, 'GUARD_BLOCKED');
    assert.equal(await page.evaluate(() => window.sent || 0), 0);
    await page.evaluate(({obs}) => window.__brainExecute({cmd:'click', guardMode:'OFF', id:obs.elements[0].id, expectedDocumentId:obs.documentId, expectedUrl:obs.url}), {obs});
    assert.equal(await page.evaluate(() => window.sent || 0), 1);
  } finally { await page.close(); }
});
