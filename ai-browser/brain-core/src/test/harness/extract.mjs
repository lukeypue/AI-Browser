// Offline extractor harness: loads each fixture page in headless Chromium, injects the
// content script with a stub `browser` object, and writes the raw observation JSON next to
// the fixture. The Kotlin tests then parse those JSON files, so the extractor and the
// perception layer are tested together without a device.
//
// Usage: node extract.mjs [fixturesDir] [contentScriptPath]
import { createRequire } from "node:module";
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || "playwright");
import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { resolve, join, basename } from "node:path";

const fixtures = resolve(process.argv[2] || "../fixtures");
const script = readFileSync(resolve(process.argv[3] || "../../../../app/src/main/assets/sitebrain/content.js"), "utf8");
const stub = "window.browser = { runtime: { connectNative: () => { throw new Error('no native host'); } } };";

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined });
const page = await browser.newPage({ viewport: { width: 412, height: 915 } });
await page.route("**/*", (route) => {
  const url = new URL(route.request().url());
  if (url.hostname === "fixtures.test" && url.pathname.endsWith(".html")) {
    return route.fulfill({ status: 200, body: readFileSync(join(fixtures, basename(url.pathname)), "utf8"), contentType: "text/html" });
  }
  return route.fulfill({ status: 200, body: "", contentType: "application/javascript" });
});
let failures = 0;
for (const file of readdirSync(fixtures).filter((f) => f.endsWith(".html"))) {
  await page.goto("http://fixtures.test/" + file, { waitUntil: "load" });
  await page.evaluate(stub + "\n" + script);
  await page.waitForTimeout(500);
  const obs = await page.evaluate(() => window.__brainObserve());
  const out = join(fixtures, basename(file, ".html") + ".observation.json");
  writeFileSync(out, JSON.stringify(obs, null, 1));
  console.log(`${file}: ${obs.elements.length} elements, ${obs.items.length} items, regions=${obs.regions.map((r) => r.role).join(",")}, settle=${obs.settle.state}, pw=${obs.signals.passwordFields}, dialog=${obs.signals.dialog}`);
  if (file.startsWith("results") && obs.items.length < 3) failures++;
  if (file.startsWith("login") && obs.elements.some((e) => e.value && e.value.includes("hunter2"))) { console.log("PASSWORD LEAK"); failures++; }
  if (file.startsWith("login") && obs.elements.some((e) => (e.value || "").includes("@"))) { console.log("AUTH PAGE VALUE LEAK"); failures++; }
}
// Action smoke test on the results page: type into search and select a facet.
await page.goto("http://fixtures.test/results_page.html", { waitUntil: "load" });
await page.evaluate(stub + "\n" + script);
const obs = await page.evaluate(() => window.__brainObserve());
const search = obs.elements.find((e) => e.type === "search");
const priceTo = obs.elements.find((e) => e.tag === "select" && /price to/i.test(e.name));
const r1 = await page.evaluate((id) => window.__brainExecute({ cmd: "type", id, text: "Toyota Tacoma", submit: false }), search.id);
const r2 = await page.evaluate((id) => window.__brainExecute({ cmd: "set_range", id, value: "8000", direction: "max" }), priceTo.id);
const r3 = await page.evaluate(() => window.__brainExecute({ cmd: "settle", capMs: 2000 }));
const after = await page.evaluate(() => window.__brainObserve());
const searchAfter = after.elements.find((e) => e.type === "search");
const priceAfter = after.elements.find((e) => e.tag === "select" && /price to/i.test(e.name));
console.log("type:", r1.detail, "->", searchAfter.value, "| set_range:", r2.detail, "->", priceAfter.value, "| settle:", r3.state);
if (searchAfter.value !== "Toyota Tacoma") failures++;
if (priceAfter.value !== "$8,000") failures++;
// Custom combobox: select an option by text through the same command the engine uses.
await page.goto("http://fixtures.test/drawer_page.html", { waitUntil: "load" });
await page.evaluate(stub + "\n" + script);
const drawer = await page.evaluate(() => window.__brainObserve());
const sortBtn = drawer.elements.find((e) => e.role === "combobox");
const r4 = await page.evaluate((id) => window.__brainExecute({ cmd: "select", id, option: "Price: Lowest first" }), sortBtn.id);
const drawerAfter = await page.evaluate(() => window.__brainObserve());
const sortAfter = drawerAfter.elements.find((e) => e.role === "combobox");
const maxp = drawer.elements.find((e) => e.tag === "input" && /max price/i.test(e.name));
const r5 = await page.evaluate((id) => window.__brainExecute({ cmd: "set_range", id, value: "8000", direction: "max", submit: false }), maxp.id);
const maxAfter = (await page.evaluate(() => window.__brainObserve())).elements.find((e) => e.tag === "input" && /max price/i.test(e.name));
console.log("combobox:", r4.detail, "->", sortAfter.name, "| set_range input:", r5.detail, "->", maxAfter.value);
if (!/Lowest/.test(sortAfter.name)) failures++;
if (maxAfter.value !== "8000") failures++;
await browser.close();
if (failures) { console.error(`FAILURES: ${failures}`); process.exit(1); }
console.log("extractor harness OK");
