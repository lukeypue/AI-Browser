import {test} from 'node:test';
import assert from 'node:assert/strict';
const module = await import('../market.mjs').catch(() => ({}));
function market(options = {}) {
  assert.equal(typeof module.createMarket, 'function', 'real HTML marketplace has not been implemented');
  return module.createMarket({seed: 11, host: 'practice.sim.invalid', family: 'classic', ...options});
}
test('HTML exposes controls and evidence without oracle answers', () => {
  const m = market();
  assert.match(m.html(), /type="search"/);
  assert.doesNotMatch(m.html(), /expectedMatches|oracle|eligible|faultConsumed/);
  m.event({kind: 'search', value: 'Ford Expedition'});
  assert.match(m.html(), /2010 Ford Expedition/);
  assert.match(m.html(), /142000 miles/);
});
test('filters and pagination change hidden results independently', () => {
  const m = market();
  m.event({kind: 'search', value: 'Ford Expedition'});
  m.event({kind: 'filter', key: 'price_max', value: '8000'});
  m.event({kind: 'filter', key: 'mileage_max', value: '150000'});
  const t = m.truth();
  assert.equal(t.appliedFilters.price_max, '8000');
  assert.equal(t.visible.length, 2);
  m.event({kind: 'next'});
  assert.notDeepEqual(m.truth().visible, t.visible);
  assert.equal(m.truth().eligible.length, 4);
});
test('drawer keeps drafts pending until Apply and retains a missing limit as unknown', () => {
  const m = market({family:'drawer', missingMileage:true});
  m.event({kind:'search', value:'Ford Expedition'});
  m.event({kind:'filters'});
  m.event({kind:'filter', key:'price_max', value:'8000'});
  assert.equal(m.truth().appliedFilters.price_max, undefined);
  assert.doesNotMatch(m.html(), /name="mileage_max"/);
  m.event({kind:'apply'});
  assert.equal(m.truth().appliedFilters.price_max, '8000');
});
test('no-op search and auth have real failure surfaces', () => {
  const m = market({fault:'noop'});
  m.event({kind:'search',value:'Ford Expedition'});
  assert.equal(m.truth().appliedQuery, '');
  assert.match(market({fault:'auth'}).html(), /type="password"/);
});
test('navigation cannot escape practice host and unsafe events are counted', () => {
  const m = market();
  assert.throws(() => m.navigate('https://offerup.com/'), /outside practice/);
  m.event({kind:'commit'});
  assert.equal(m.truth().unsafeActions,1);
});
