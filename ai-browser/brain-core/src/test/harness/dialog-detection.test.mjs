import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {test} from 'node:test';

// Execute the real detector with deterministic layout data when Chromium is unavailable.
const source = readFileSync(new URL('../../../../app/src/main/assets/sitebrain/content.js', import.meta.url), 'utf8')
  .replace('function buildRegions() {', 'window.__testDialogs = dialogElements; function buildRegions() {');
function element({top = 0, height = 500, width = 412, explicit = false, chrome = false, interactive = true, blocking = true, transparentAuto = false} = {}) {
  const control = {nodeType: 1, tagName: 'BUTTON', getAttribute: () => null,
    getBoundingClientRect: () => ({top: 20, left: 20, bottom: 50, right: 100, width: 80, height: 30})};
  const candidate = {nodeType: 1, tagName: 'DIV', explicit, chrome, blocking, transparentAuto, getAttribute: () => null,
    getBoundingClientRect: () => ({top, left: 0, bottom: top + height, right: width, width, height}),
    closest: () => chrome ? {} : null, contains: other => other === control || other === candidate,
    querySelectorAll: () => interactive ? [control] : []};
  return candidate;
}
function detect(elements) {
  const context = {window: {innerHeight: 915, innerWidth: 412, getComputedStyle: el => ({position: 'fixed',
    display: 'block', visibility: 'visible', opacity: '1', zIndex: el.transparentAuto ? 'auto' : '20',
    backgroundColor: el.transparentAuto ? 'rgba(0, 0, 0, 0)' : 'rgb(255, 255, 255)'})},
    document: {readyState: 'loading', documentElement: null, addEventListener() {},
      querySelectorAll: selector => selector.startsWith('[role=dialog]') ? elements.filter(e => e.explicit) : elements,
      elementFromPoint: () => elements.find(e => e.blocking) || {tagName: 'MAIN'}},
    location: {href: 'https://fixtures.test/search'}, setTimeout, performance, console};
  vm.createContext(context); vm.runInContext(source, context);
  return context.window.__testDialogs();
}
test('oversized fixed header chrome is not a dialog', () => {
  assert.equal(detect([element({chrome: true})]).length, 0);
});
test('fixed decoration without interactive descendants is not a dialog', () => {
  assert.equal(detect([element({interactive: false})]).length, 0);
});
test('mostly offscreen fixed panel does not inflate viewport coverage', () => {
  assert.equal(detect([element({top: -950, height: 1000})]).length, 0);
});
test('explicit small dialog inside header remains authoritative', () => {
  assert.equal(detect([element({explicit: true, chrome: true, height: 100})]).length, 1);
});
test('large nonsemantic interactive overlay remains detectable', () => {
  assert.equal(detect([element()]).length, 1);
});
test('full-screen click-through wrapper is not a blocking dialog', () => {
  assert.equal(detect([element({height: 915, blocking: false})]).length, 0);
});
test('transparent auto-z layout wrapper remains excluded', () => {
  assert.equal(detect([element({height: 915, transparentAuto: true})]).length, 0);
});

test('explicit dialog outside the viewport does not block live results', () => {
  assert.equal(detect([element({explicit: true, top: -950, height: 100})]).length, 0);
});
