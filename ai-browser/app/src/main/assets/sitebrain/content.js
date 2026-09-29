/*
 * AI Browser Site Brain — content script (perception + action layer).
 *
 * Runs at document_start in the top frame of every page. It has two jobs and nothing else:
 *   1. OBSERVE: turn the live DOM into a raw, structured, redacted description (elements with
 *      attributes, regions, result cards, page signals, settle state). All *semantics* — roles,
 *      facet keys, page types — are assigned by the Kotlin perception layer, so this file stays
 *      site-agnostic and testable.
 *   2. ACT: execute typed commands (click / type / select / set_range / scroll / dismiss /
 *      navigate / back / settle) addressed by element id from the last observation.
 *
 * Privacy: password fields are never reported; on sign-in / challenge pages no input values and
 * no text are reported; emails and phone numbers are stripped before anything leaves the page.
 * The script talks only to the app process over the native port "aibrowser".
 */
(() => {
  if (window.__aiBrowserBrainInstalled) return;
  window.__aiBrowserBrainInstalled = true;
  const documentId = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);

  const MAX_ELEMENTS = 380;
  const MAX_ITEMS = 120;
  const NAME_MAX = 80;
  const NEAR_MAX = 90;
  const DETAIL_TEXT_MAX = 30000;

  // ------------------------------------------------------------------ utilities
  const clean = (v) => (v == null ? "" : String(v)).replace(/\s+/g, " ").trim();
  const emailRe = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
  const phoneRe = /(?<![0-9])(?:\+?1[\s.-]?)?\(?[0-9]{3}\)?[\s.-]?[0-9]{3}[\s.-]?[0-9]{4}(?![0-9])/g;
  const redact = (v, max) => {
    let t = clean(v).replace(emailRe, "[email]").replace(phoneRe, "[phone]");
    return t.length > max ? t.slice(0, max) : t;
  };
  const lower = (v) => clean(v).toLowerCase();

  function visible(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.getAttribute("aria-hidden") === "true") return false;
    const style = window.getComputedStyle(el);
    if (!style || style.display === "none" || style.visibility === "hidden" || parseFloat(style.opacity || "1") < 0.05) return false;
    const r = el.getBoundingClientRect();
    if (r.width < 1 && r.height < 1) {
      // Input elements can be visually replaced (custom checkboxes); treat labelled ones as visible.
      const tag = el.tagName;
      if (tag === "INPUT" && (el.type === "checkbox" || el.type === "radio") && el.labels && el.labels.length) return true;
      return false;
    }
    return true;
  }

  function inViewport(r) {
    const h = window.innerHeight || document.documentElement.clientHeight;
    const w = window.innerWidth || document.documentElement.clientWidth;
    return r.bottom > 0 && r.top < h && r.right > 0 && r.left < w;
  }

  function bbox(el) {
    const r = el.getBoundingClientRect();
    return [Math.round(r.left), Math.round(r.top + window.scrollY), Math.round(r.width), Math.round(r.height)];
  }

  function textOf(el, max) {
    if (!el) return "";
    let t = el.innerText;
    if (t == null || t === "") t = el.textContent;
    return clean(t).slice(0, max);
  }

  // Text with a space between every text node (innerText glues adjacent inline spans together).
  function spacedText(el, max) {
    if (!el) return "";
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    const parts = [];
    let total = 0;
    while (walker.nextNode()) {
      const n = walker.currentNode;
      const parent = n.parentElement;
      if (parent && /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)$/.test(parent.tagName)) continue;
      const t = clean(n.nodeValue);
      if (!t) continue;
      parts.push(t);
      total += t.length + 1;
      if (total > max * 2) break;
    }
    return clean(parts.join(" ")).slice(0, max);
  }

  // Label text without the text of the control it wraps (a <label> around a <select> would
  // otherwise include every option).
  function labelText(label, control, max) {
    const clone = label.cloneNode(true);
    clone.querySelectorAll("select, input, textarea, button, option").forEach((n) => n.remove());
    return clean(clone.textContent).slice(0, max);
  }

  function accessibleName(el) {
    const aria = el.getAttribute("aria-label");
    if (aria && clean(aria)) return clean(aria);
    const labelledBy = el.getAttribute("aria-labelledby");
    if (labelledBy) {
      const parts = labelledBy.split(/\s+/).map((id) => { const n = document.getElementById(id); return n ? textOf(n, 60) : ""; }).filter(Boolean);
      if (parts.length) return parts.join(" ");
    }
    if (el.labels && el.labels.length) {
      const t = Array.from(el.labels).map((l) => labelText(l, el, 60)).filter(Boolean).join(" ");
      if (t) return t;
    }
    const tag = el.tagName;
    if (tag === "INPUT") {
      const type = (el.type || "").toLowerCase();
      if (type === "submit" || type === "button" || type === "reset") return clean(el.value || el.getAttribute("value") || "");
      const ph = el.getAttribute("placeholder");
      if (ph) return clean(ph);
      const title = el.getAttribute("title");
      if (title) return clean(title);
      const name = el.getAttribute("name");
      if (name) return clean(name.replace(/[_\-\[\]]+/g, " "));
      return "";
    }
    if (tag === "SELECT" || tag === "TEXTAREA") {
      const ph = el.getAttribute("placeholder") || el.getAttribute("title") || el.getAttribute("name") || "";
      const prev = el.closest("label") || (el.previousElementSibling && el.previousElementSibling.tagName === "LABEL" ? el.previousElementSibling : null);
      return clean(prev ? labelText(prev, el, 60) : ph.replace(/[_\-\[\]]+/g, " "));
    }
    let t = textOf(el, NAME_MAX);
    if (!t) {
      const img = el.querySelector("img[alt], svg[aria-label], [aria-label]");
      if (img) t = clean(img.getAttribute("alt") || img.getAttribute("aria-label") || "");
    }
    if (!t) t = clean(el.getAttribute("title") || "");
    return t;
  }

  function nearbyText(el) {
    // Label-like context: a preceding label/legend/heading inside the same small container.
    const label = el.closest("label");
    const isChoice = el.tagName === "INPUT" && /^(checkbox|radio)$/i.test(el.type || "");
    // A checkbox's own label is its name; its *group* label (legend / heading / preceding text) is the context.
    if (label && !isChoice) return labelText(label, el, NEAR_MAX);
    let node = isChoice && label ? label : el;
    const fieldset = el.closest("fieldset");
    if (fieldset) { const legend = fieldset.querySelector(":scope > legend"); if (legend) return textOf(legend, NEAR_MAX); }
    const group = el.closest("[role=group][aria-label], [role=radiogroup][aria-label], [aria-labelledby]");
    if (group) {
      const byId = group.getAttribute("aria-labelledby");
      const labelled = byId ? document.getElementById(byId.split(/\s+/)[0]) : null;
      const t = labelled ? textOf(labelled, NEAR_MAX) : clean(group.getAttribute("aria-label") || "");
      if (t) return t;
    }
    for (let d = 0; d < 3 && node && node.parentElement; d++) {
      node = node.parentElement;
      const legend = node.querySelector(":scope > legend, :scope > label, :scope > h1, :scope > h2, :scope > h3, :scope > h4, :scope > span:first-child, :scope > div:first-child > label");
      if (legend && legend !== el && !legend.contains(el)) {
        const t = textOf(legend, NEAR_MAX);
        if (t) return t;
      }
      const prev = node.previousElementSibling;
      if (prev && /^(LABEL|LEGEND|H[1-6]|SPAN|P|DT|DIV)$/.test(prev.tagName) && prev.children.length <= 2) {
        const t = textOf(prev, NEAR_MAX);
        if (t && t.length <= NEAR_MAX) return t;
      }
    }
    return "";
  }

  function depthOf(el) {
    let d = 0;
    for (let n = el; n && n !== document.body; n = n.parentElement) d++;
    return d;
  }

  function samePath(u) {
    try {
      const url = new URL(u, location.href);
      return url;
    } catch (e) { return null; }
  }

  function baseDomain(host) {
    const parts = (host || "").toLowerCase().split(".");
    if (parts.length <= 2) return parts.join(".");
    const sld = new Set(["co", "com", "net", "org", "gov", "edu", "ac"]);
    if (sld.has(parts[parts.length - 2]) && parts[parts.length - 1].length === 2) return parts.slice(-3).join(".");
    return parts.slice(-2).join(".");
  }

  const ownBase = baseDomain(location.hostname);

  function hash32(s) {
    let h = 2166136261;
    for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0).toString(16).padStart(8, "0");
  }

  // ------------------------------------------------------------------ regions
  const REGION_SELECTORS = [
    ["HEADER", "header, [role=banner]"],
    ["NAV", "nav, [role=navigation]"],
    ["FILTERS", "aside, [role=complementary], [class*=filter i], [id*=filter i], [class*=facet i], [id*=facet i], [class*=refine i], [id*=refine i], [data-testid*=filter i]"],
    ["FOOTER", "footer, [role=contentinfo]"],
    ["MAIN", "main, [role=main], article"]
  ];

  function dialogElements() {
    const out = [];
    document.querySelectorAll("[role=dialog], [role=alertdialog], [aria-modal=true], dialog[open]").forEach((d) => { if (visible(d)) out.push(d); });
    // Fixed overlays that cover a large share of the viewport behave like dialogs.
    const vh = window.innerHeight || 1, vw = window.innerWidth || 1;
    document.querySelectorAll("div, section").forEach((d) => {
      if (out.length > 6) return;
      const st = window.getComputedStyle(d);
      if (st.position !== "fixed") return;
      const r = d.getBoundingClientRect();
      if (r.width * r.height > 0.35 * vw * vh && visible(d) && !out.some((o) => o.contains(d) || d.contains(o))) {
        const z = parseInt(st.zIndex || "0", 10);
        if (z > 5 || st.backgroundColor !== "rgba(0, 0, 0, 0)") out.push(d);
      }
    });
    return out;
  }

  function buildRegions() {
    const regions = [];
    const map = new Map();
    let i = 0;
    const dialogs = dialogElements();
    dialogs.forEach((d) => { const id = "r" + (i++); regions.push({ id, role: "DIALOG", bbox: bbox(d) }); map.set(d, id); });
    for (const [role, sel] of REGION_SELECTORS) {
      let nodes = [];
      try { nodes = Array.from(document.querySelectorAll(sel)); } catch (e) { nodes = []; }
      nodes.filter(visible).slice(0, 8).forEach((n) => {
        if (map.has(n)) return;
        const r = n.getBoundingClientRect();
        if (role === "FILTERS" && (r.width < 40 || r.height < 40)) return;
        if (role === "MAIN" && n.tagName === "ARTICLE" && r.height < 200) return;
        const id = "r" + (i++);
        regions.push({ id, role, bbox: bbox(n) });
        map.set(n, id);
      });
    }
    return { regions, map, dialogs };
  }

  function regionFor(el, map, dialogs) {
    // A nested main/filter region must not erase ownership by a modal overlay.
    const dialog = dialogs.find((d) => d.contains(el) && !dialogs.some((inner) => inner !== d && d.contains(inner) && inner.contains(el)));
    if (dialog) return { id: map.get(dialog), inDialog: true };
    for (let n = el; n && n !== document.body; n = n.parentElement) {
      if (map.has(n)) return { id: map.get(n), inDialog: dialogs.includes(n) };
    }
    return { id: null, inDialog: false };
  }

  // ------------------------------------------------------------------ result cards
  const sigCache = new WeakMap();
  function classSig(el) {
    const cached = sigCache.get(el);
    if (cached) return cached;
    const cls = Array.from(el.classList || []).filter((c) => !/\d{3,}|active|selected|hover|focus/i.test(c)).slice(0, 3).join(".");
    const sig = el.tagName + "." + cls;
    sigCache.set(el, sig);
    return sig;
  }

  const priceRe = /\$\s?[0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{2})?|\$\s?[0-9]+(?:\.[0-9]{2})?(?:\s?[kK]\b)?/;

  function findCards() {
    const links = Array.from(document.querySelectorAll("a[href]")).slice(0, 600).filter((a) => {
      const u = samePath(a.getAttribute("href"));
      return u && /^https?:$/.test(u.protocol) && baseDomain(u.hostname) === ownBase && visible(a) &&
        !a.closest('nav, header, footer, [role=navigation], [role=banner], [role=contentinfo]');
    });
    const containers = new Map(); // container -> Map(card -> sig)
    for (const a of links) {
      let card = a;
      let found = null;
      for (let d = 0; d < 7 && card && card !== document.body; d++) {
        const parent = card.parentElement;
        if (!parent) break;
        const sig = classSig(card);
        const siblings = Array.from(parent.children).filter((c) => classSig(c) === sig);
        if (siblings.length >= 2 && card.getBoundingClientRect().height >= 40) { found = { card, parent }; break; }
        card = parent;
      }
      if (!found) continue;
      if (!containers.has(found.parent)) containers.set(found.parent, new Map());
      const m = containers.get(found.parent);
      if (!m.has(found.card)) m.set(found.card, a);
    }
    let best = null, bestScore = -1;
    for (const [parent, cards] of containers) {
      if (cards.size < 2) continue;
      let withPrice = 0;
      for (const [card] of cards) if (priceRe.test(textOf(card, 400))) withPrice++;
      if (cards.size < 3 && withPrice < 2) continue;
      const score = cards.size + withPrice * 3;
      if (score > bestScore) { bestScore = score; best = { parent, cards, withPrice }; }
    }
    if (!best) return { cards: [], container: null, withPrice: 0 };
    return { cards: Array.from(best.cards.keys()), container: best.parent, withPrice: best.withPrice };
  }

  function cardInfo(card) {
    const text = spacedText(card, 500);
    // querySelectorAll excludes the root itself; whole-card anchors are common.
    const primary = (card.matches("a[href]") ? [card] : Array.from(card.querySelectorAll("a[href]"))).filter((a) => {
      const u = samePath(a.getAttribute("href"));
      return u && /^https?:$/.test(u.protocol) && baseDomain(u.hostname) === ownBase && visible(a);
    });
    let link = null, linkText = "";
    for (const a of primary) {
      const t = textOf(a, 160);
      if (t.length > linkText.length) { linkText = t; link = a; }
    }
    if (!link && primary.length) link = primary[0];
    const heading = card.querySelector("h1, h2, h3, h4, [role=heading], [class*=title i], [data-testid*=title i]");
    let title = heading ? textOf(heading, 160) : "";
    if (!title || title.length < 4) title = linkText;
    if (!title || title.length < 4) title = text.slice(0, 120);
    const priceMatch = text.match(priceRe);
    const href = link ? (samePath(link.getAttribute("href")) || {}).href || "" : "";
    const path = href ? href.replace(/^https?:\/\/[^/]+/, "").split("#")[0] : "";
    const key = hash32(lower(title) + "|" + (priceMatch ? priceMatch[0] : "") + "|" + path.split("?")[0]);
    const links = primary.filter((a) => (samePath(a.getAttribute("href")) || {}).href === href);
    return { key, title: redact(title, 160), price: priceMatch ? priceMatch[0] : null, href, text: redact(text, 400), link, links };
  }

  // ------------------------------------------------------------------ page signals
  const authPathRe = /(^|\/)(login|log-in|signin|sign-in|signup|sign-up|auth|oauth|checkpoint|sessions?\/new|password|account\/login|accounts\/login|identifier|challenge\/pwd|challenge\/totp|challenge\/az)(\/|$)/i;
  const authPhraseRe = /(log in to continue|login to continue|sign in to continue|sign up \/ log in|continue with google|continue with facebook|continue with apple|enter your password|forgot password|2-step verification|two-factor|verification code|enter the code|use your google account|sign in with)/i;
  const challengePhraseRe = /(verify you are human|verify that you are human|unusual traffic|confirm your identity|are you a robot|complete the captcha|enter the characters you see|checking your browser|just a moment|press and hold|security check|please verify|prove you're not a robot|access denied)/i;
  const challengePathRe = /(captcha|\/challenge|\/checkpoint|security-check|verify-you|are-you-human|cdn-cgi\/challenge|blocked)/i;
  const errorRe = /(page not found|404|not found|something went wrong|access denied|forbidden|503|service unavailable|error occurred|this page isn.t available)/i;
  const resultsTextRe = /\b([0-9][0-9,]*)\s+(results?|listings?|items?|matches|vehicles|cars|ads|found)\b|\bresults\s+for\b|\bshowing\s+[0-9]/i;

  // The element whose text/cards decide "is this a detail page": a large open dialog (marketplaces
  // render item details in a modal over the results) or the main content.
  function detailScope(dialogs) {
    const vh = window.innerHeight || 1, vw = window.innerWidth || 1;
    let best = null, bestCover = 0;
    for (const d of dialogs) {
      if (textOf(d, 200).length < 40) continue;                     // scrims / backdrops carry no content
      const r = d.getBoundingClientRect(); const c = (r.width * r.height) / (vw * vh);
      const explicit = d.getAttribute("role") === "dialog" || d.getAttribute("aria-modal") === "true" ? 0.2 : 0;
      if (c + explicit > bestCover) { bestCover = c + explicit; best = d; }
    }
    return best && bestCover >= 0.35 ? best : mainContent();
  }

  function computeSignals(elements, cards, dialogs, bodyText) {
    const vh = window.innerHeight || 1, vw = window.innerWidth || 1;
    let dialogCoverage = 0;
    for (const d of dialogs) { const r = d.getBoundingClientRect(); dialogCoverage = Math.max(dialogCoverage, (r.width * r.height) / (vw * vh)); }
    const scope = detailScope(dialogs);
    const scopedCards = scope === mainContent() ? cards : cards.filter((c) => scope.contains(c));
    const passwordFields = Array.from(document.querySelectorAll("input[type=password]")).filter(visible).length;
    const pathAndQuery = location.pathname + location.search;
    const lowerText = bodyText.toLowerCase();
    const challengeWidget = Array.from(document.querySelectorAll('iframe[src*="recaptcha"], iframe[src*="hcaptcha"], iframe[src*="turnstile"], iframe[src*="challenges.cloudflare"], iframe[src*="arkoselabs"], iframe[src*="funcaptcha"], iframe[src*="geo.captcha-delivery"], [class*="g-recaptcha"], [class*="h-captcha"], [class*="cf-turnstile"], [data-sitekey], #px-captcha, [id*="captcha" i]')).some(visible);
    const h1 = scope.querySelector("h1, h2, [role=heading][aria-level=\"1\"]") || document.querySelector("h1");
    const scopeText = textOf(scope, 60000);
    const priceCount = (scopeText.match(/\$\s?[0-9][0-9,]{2,}/g) || []).length;
    const mainLen = scopeText.length;
    const scopeLower = scopeText.toLowerCase();
    const detailHint = !!h1 && scopedCards.length < 2 && priceCount >= 1 && mainLen > 150 &&
      (/(description|details|about this|condition|seller|posted|listed|mileage|contact|message)/i.test(scopeLower));
    const resultsHint = cards.length >= 2 || (resultsTextRe.test(bodyText.slice(0, 4000)) && cards.length >= 1);
    const loginLinks = elements.filter((e) => /\b(log ?in|sign ?in|sign ?up)\b/i.test(e.name)).length;
    const sparse = elements.length < 18 && bodyText.length < 3500;
    return {
      passwordFields,
      authPath: authPathRe.test(pathAndQuery),
      authPhrases: authPhraseRe.test(bodyText.slice(0, 6000)),
      challengeWidget,
      challengePhrase: challengePhraseRe.test(bodyText.slice(0, 4000)) || challengePhraseRe.test(document.title || ""),
      challengePath: challengePathRe.test(pathAndQuery),
      dialog: dialogs.length > 0,
      dialogCoverage: Math.round(dialogCoverage * 100) / 100,
      sparse,
      textLength: bodyText.length,
      formCount: document.forms.length,
      resultsHint,
      detailHint,
      loginLinks,
      hasMain: !!document.querySelector("main, [role=main]"),
      errorHint: errorRe.test((document.title || "") + " " + bodyText.slice(0, 600)) && sparse,
      priceCount,
      h1: h1 ? redact(textOf(h1, 120), 120) : ""
    };
  }

  function mainContent() {
    return document.querySelector("main, [role=main]") || document.querySelector("article") || document.body;
  }

  // ------------------------------------------------------------------ observe
  const CANDIDATE_SELECTOR = 'a[href], button, input, select, textarea, [role=button], [role=link], [role=tab], [role=menuitem], [role=combobox], [role=listbox], [role=option], [role=checkbox], [role=radio], [role=switch], [role=searchbox], [role=textbox], [contenteditable=true], [aria-haspopup], [aria-expanded], summary, [tabindex="0"][class]';

  let lastElements = new Map();
  const idOf = new WeakMap();

  function observe() {
    const { regions, map, dialogs } = buildRegions();
    const { cards, container, withPrice } = findCards();
    const cardSet = new Set(cards);
    const cardInfos = cards.slice(0, MAX_ITEMS).map(cardInfo);
    const cardByLink = new Map();
    cardInfos.forEach((c) => { c.links.forEach((a) => cardByLink.set(a, c)); });

    let nodes = [];
    try { nodes = Array.from(document.querySelectorAll(CANDIDATE_SELECTOR)); } catch (e) { nodes = []; }
    const seen = new Set();
    const elements = [];
    const authHost = /^(accounts\.|login\.|signin\.|auth\.|id\.|sso\.|oauth\.|identity\.)/.test(location.hostname) || /(^|\.)(appleid\.apple\.com|login\.microsoftonline\.com|login\.live\.com|auth0\.com|okta\.com)$/.test(location.hostname);
    const authy = authHost || authPathRe.test(location.pathname) || document.querySelectorAll("input[type=password]").length > 0;

    const inViewportFirst = nodes.map((el, order) => ({ el, order, r: el.getBoundingClientRect() }));
    // Prefer visible elements; keep document order otherwise.
    inViewportFirst.sort((a, b) => (inViewport(b.r) - inViewport(a.r)) || (a.order - b.order));

    for (const { el } of inViewportFirst) {
      if (elements.length >= MAX_ELEMENTS) break;
      if (seen.has(el)) continue;
      seen.add(el);
      if (!visible(el)) continue;
      const tag = el.tagName.toLowerCase();
      const type = tag === "input" ? (el.type || "text").toLowerCase() : null;
      if (type === "password" || type === "hidden" || type === "file") continue;
      const region = regionFor(el, map, dialogs);
      const form = el.form || el.closest("form");
      const formHasSearch = !!(form && (form.querySelector('input[type=search], [role=searchbox], input[name*=search i], input[name=q], input[name=query], input[placeholder*=search i]') || /search/i.test(form.getAttribute("role") || "") || /search/i.test(form.action || "")));
      let card = null;
      for (let n = el; n && n !== document.body; n = n.parentElement) { if (cardSet.has(n)) { card = n; break; } }
      const cardMeta = card ? (cardByLink.get(el) || null) : null;
      const href = tag === "a" || el.getAttribute("role") === "link" ? el.getAttribute("href") : null;
      const url = href ? samePath(href) : null;
      let value = null, choices = [];
      if (!authy) {
        if (tag === "select") {
          const opts = Array.from(el.options || []);
          choices = opts.map((o) => clean(o.text || o.label || o.value)).filter(Boolean).slice(0, 120);
          const sel = opts[el.selectedIndex];
          value = sel ? clean(sel.text || sel.value) : "";
        } else if (tag === "input" && (type === "checkbox" || type === "radio")) {
          value = el.checked ? "on" : "";
        } else if (tag === "input" || tag === "textarea") {
          value = redact(el.value || "", 80);
        } else if (el.getAttribute("contenteditable") === "true") {
          value = redact(textOf(el, 80), 80);
        } else if (el.getAttribute("role") === "combobox" || el.getAttribute("role") === "listbox") {
          value = redact(el.getAttribute("aria-valuetext") || textOf(el, 60), 60);
          const listId = el.getAttribute("aria-controls") || el.getAttribute("aria-owns");
          const list = listId ? document.getElementById(listId) : null;
          if (list) choices = Array.from(list.querySelectorAll("[role=option], li")).map((o) => clean(o.textContent)).filter(Boolean).slice(0, 80);
        }
      }
      const name = authy ? "" : redact(accessibleName(el), NAME_MAX);
      const expanded = el.hasAttribute("aria-expanded") ? el.getAttribute("aria-expanded") === "true" : null;
      const listSize = card ? cards.length : 0;
      const parent = el.parentElement;
      const siblings = parent ? parent.children.length : 0;
      const idx = parent ? Array.prototype.indexOf.call(parent.children, el) : 0;
      elements.push({
        id: "a" + elements.length,
        tag,
        type,
        role: el.getAttribute("role") || null,
        name,
        placeholder: authy ? null : (el.getAttribute("placeholder") ? redact(el.getAttribute("placeholder"), 60) : null),
        region: region.id,
        bbox: bbox(el),
        visible: true,
        enabled: !(el.disabled || el.getAttribute("aria-disabled") === "true"),
        selected: !!(el.checked || el.selected || el.getAttribute("aria-selected") === "true" || el.getAttribute("aria-pressed") === "true" || el.getAttribute("aria-checked") === "true" || (el.getAttribute("aria-current") && el.getAttribute("aria-current") !== "false")),
        expanded,
        haspopup: !!(el.getAttribute("aria-haspopup") && el.getAttribute("aria-haspopup") !== "false"),
        href: url ? url.href : null,
        sameSite: url ? baseDomain(url.hostname) === ownBase : true,
        value,
        choices,
        near: authy ? "" : redact(nearbyText(el), NEAR_MAX),
        depth: depthOf(el),
        idx,
        siblings,
        inForm: !!form,
        submitType: (tag === "button" && (el.type || "submit").toLowerCase() === "submit" && !!form) || (tag === "input" && type === "submit"),
        min: el.getAttribute("min"),
        max: el.getAttribute("max"),
        cls: Array.from(el.classList || []).slice(0, 3),
        editable: el.getAttribute("contenteditable") === "true",
        rel: el.getAttribute("rel"),
        inCard: !!card,
        cardHasPrice: !!(card && priceRe.test(textOf(card, 400))),
        listSize,
        itemKey: cardMeta ? cardMeta.key : null,
        formHasSearch,
        inDialog: region.inDialog
      });
      idOf.set(el, elements[elements.length - 1].id);
    }
    // Keep a direct id -> element map for actions addressed by the last observation.
    const idMap = new Map();
    for (const el of seen) { const id = idOf.get(el); if (id) idMap.set(id, el); }
    lastElements = idMap;

    const bodyText = textOf(document.body, 12000);
    const signals = computeSignals(elements, cards, dialogs, bodyText);
    const authOrChallenge = signals.challengeWidget || (signals.passwordFields > 0 && (signals.sparse || signals.authPath || signals.authPhrases)) || authy;
    const items = authOrChallenge ? [] : cardInfos.map((c) => ({ key: c.key, title: c.title, price: c.price, href: c.href, text: c.text, aff: c.link && idOf.get(c.link) ? idOf.get(c.link) : null }));
    const scripts = Array.from(document.scripts).map((s) => s.src).filter(Boolean).map((s) => s.replace(/^https?:\/\/[^/]+/, "").split("?")[0]).filter((p) => /\.(m?js)$/.test(p)).slice(0, 25);
    const detailText = signals.detailHint && !authOrChallenge ? redact(textOf(detailScope(dialogs), DETAIL_TEXT_MAX), DETAIL_TEXT_MAX) : "";
    return {
      v: 3,
      documentId,
      url: location.href,
      host: location.host.toLowerCase(),
      title: authOrChallenge ? "" : redact(document.title || "", 120),
      readyState: document.readyState,
      ts: Date.now(),
      viewport: { w: window.innerWidth, h: window.innerHeight, scrollY: Math.round(window.scrollY), scrollH: Math.max(document.documentElement.scrollHeight || 0, document.body ? document.body.scrollHeight : 0) },
      settle: settleTracker.state(),
      signals,
      regions,
      elements,
      items,
      detailText,
      scripts,
      cardsWithPrice: withPrice
    };
  }

  // ------------------------------------------------------------------ settle tracker
  const settleTracker = (() => {
    let lastMutationAt = 0;
    let mutationsWindow = [];
    let lastResourceAt = 0;
    let observer = null;
    let perf = null;
    function install() {
      if (observer || !document.documentElement) return;
      observer = new MutationObserver((records) => {
        const now = performance.now();
        let counted = 0;
        for (const r of records) {
          if (r.type === "attributes") {
            const a = r.attributeName || "";
            if (a === "class" || a === "style" || a.startsWith("data-") || a === "aria-selected" || a === "tabindex") continue;
          }
          counted++;
        }
        if (counted > 0) { lastMutationAt = now; mutationsWindow.push(now); }
        if (mutationsWindow.length > 200) mutationsWindow = mutationsWindow.slice(-100);
      });
      observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, characterData: true });
      try {
        perf = new PerformanceObserver((list) => {
          for (const e of list.getEntries()) {
            const name = e.name || "";
            if (/analytics|beacon|pixel|doubleclick|googletagmanager|facebook\.com\/tr|hotjar|segment|sentry|datadog|newrelic|collect\?/i.test(name)) continue;
            if (e.initiatorType === "beacon") continue;
            lastResourceAt = Math.max(lastResourceAt, e.responseEnd || e.startTime + (e.duration || 0));
          }
        });
        perf.observe({ entryTypes: ["resource"] });
      } catch (e) { /* PerformanceObserver unavailable */ }
    }
    function busyCount() {
      try {
        return Array.from(document.querySelectorAll('[aria-busy="true"], [role=progressbar], progress:not([value="100"]), .spinner, .loading, .skeleton, [class*="skeleton" i], [class*="spinner" i], [class*="shimmer" i], [data-loading="true"]')).filter(visible).length;
      } catch (e) { return 0; }
    }
    function state() {
      const now = performance.now();
      const mutRate = mutationsWindow.filter((t) => now - t < 500).length;
      const netIdle = now - lastResourceAt > 300;
      const domQuiet = now - lastMutationAt > 400 && mutRate < 3;
      const busy = busyCount();
      const ready = document.readyState === "complete" || document.readyState === "interactive";
      const idle = ready && netIdle && domQuiet && busy === 0;
      return { state: idle ? "IDLE" : (ready ? "BUSY" : "UNKNOWN"), mutRate, netIdleMs: Math.round(now - lastResourceAt), domQuietMs: Math.round(now - lastMutationAt), busy };
    }
    return { install, state };
  })();

  function waitSettle(capMs) {
    return new Promise((resolve) => {
      const started = performance.now();
      let idleSamples = 0;
      let lastSig = "";
      const tick = () => {
        const s = settleTracker.state();
        const sig = quickSignature();
        if (s.state === "IDLE" && sig === lastSig) idleSamples++; else idleSamples = 0;
        lastSig = sig;
        const elapsed = performance.now() - started;
        if ((idleSamples >= 2 && elapsed >= 350) || elapsed >= capMs) {
          resolve({ ok: true, state: idleSamples >= 2 ? "IDLE" : "UNKNOWN", waitedMs: Math.round(elapsed), detail: s });
        } else setTimeout(tick, 150);
      };
      setTimeout(tick, 120);
    });
  }

  function quickSignature() {
    const b = document.body;
    if (!b) return "";
    const n = b.querySelectorAll("a,button,input,select,textarea,[role]").length;
    return location.href + "|" + n + "|" + (b.scrollHeight || 0) + "|" + (document.title || "");
  }

  // ------------------------------------------------------------------ actions
  function elementFor(id) {
    if (!id) return null;
    const el = lastElements instanceof Map ? lastElements.get(id) : null;
    if (el && el.isConnected && visible(el)) return el;
    return null;
  }

  function fire(el, type, init) {
    const Ctor = /^mouse|^click|^dblclick|^contextmenu/.test(type) ? MouseEvent : (/^pointer/.test(type) ? PointerEvent : Event);
    try { el.dispatchEvent(new Ctor(type, Object.assign({ bubbles: true, cancelable: true, composed: true, view: window }, init || {}))); } catch (e) { /* ignore */ }
  }

  function realClick(el) {
    try { el.scrollIntoView({ block: "center", inline: "nearest", behavior: "instant" }); } catch (e) { try { el.scrollIntoView(); } catch (e2) {} }
    const r = el.getBoundingClientRect();
    const init = { clientX: Math.round(r.left + r.width / 2), clientY: Math.round(r.top + r.height / 2), button: 0, buttons: 1 };
    fire(el, "pointerover", init); fire(el, "mouseover", init); fire(el, "pointerdown", init); fire(el, "mousedown", init);
    try { el.focus({ preventScroll: true }); } catch (e) {}
    fire(el, "pointerup", init); fire(el, "mouseup", init);
    if (typeof el.click === "function") el.click(); else fire(el, "click", init);
  }

  function setNativeValue(el, value) {
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : (el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype);
    const desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) desc.set.call(el, value); else el.value = value;
  }

  function typeInto(el, text, submit) {
    try { el.scrollIntoView({ block: "center", behavior: "instant" }); } catch (e) {}
    realClick(el);
    try { el.focus({ preventScroll: true }); } catch (e) {}
    if (el.getAttribute("contenteditable") === "true") {
      const sel = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(el); sel.removeAllRanges(); sel.addRange(range);
      let ok = false;
      try { ok = document.execCommand("insertText", false, text); } catch (e) { ok = false; }
      if (!ok) { el.textContent = text; fire(el, "input", { inputType: "insertText", data: text }); }
    } else {
      setNativeValue(el, "");
      fire(el, "input", { inputType: "deleteContentBackward" });
      setNativeValue(el, text);
      try { el.dispatchEvent(new InputEvent("input", { bubbles: true, cancelable: true, inputType: "insertText", data: text })); } catch (e) { fire(el, "input"); }
      fire(el, "change");
    }
    if (!submit) return { ok: true, detail: "typed" };
    const form = el.form || el.closest("form");
    const keyInit = { key: "Enter", code: "Enter", keyCode: 13, which: 13 };
    const kd = new KeyboardEvent("keydown", Object.assign({ bubbles: true, cancelable: true }, keyInit));
    const notCancelled = el.dispatchEvent(kd);
    el.dispatchEvent(new KeyboardEvent("keypress", Object.assign({ bubbles: true, cancelable: true }, keyInit)));
    el.dispatchEvent(new KeyboardEvent("keyup", Object.assign({ bubbles: true, cancelable: true }, keyInit)));
    if (!notCancelled) return { ok: true, detail: "typed+enter(handled)" };
    if (form) {
      const btn = form.querySelector('button[type=submit], input[type=submit], button:not([type]), [aria-label*="search" i]');
      if (btn && visible(btn) && !btn.disabled) { realClick(btn); return { ok: true, detail: "typed+submit-button" }; }
      try { if (typeof form.requestSubmit === "function") form.requestSubmit(); else form.submit(); return { ok: true, detail: "typed+form-submit" }; } catch (e) { /* fallthrough */ }
    } else {
      const scope = el.closest('[role=search], [class*="search" i], form, header') || el.parentElement;
      const btn = scope ? scope.querySelector('button[type=submit], input[type=submit], button[aria-label*="search" i], [role=button][aria-label*="search" i], button svg') : null;
      const b = btn && btn.tagName === "svg" ? btn.closest("button") : btn;
      if (b && visible(b) && !b.disabled) { realClick(b); return { ok: true, detail: "typed+nearby-submit" }; }
    }
    return { ok: true, detail: "typed+enter" };
  }

  function selectOption(el, optionText, direction) {
    const wanted = lower(optionText);
    if (el.tagName === "SELECT") {
      const opts = Array.from(el.options || []);
      let pick = opts.find((o) => lower(o.text) === wanted || lower(o.value) === wanted)
        || opts.find((o) => lower(o.text).includes(wanted) && wanted.length > 1)
        || opts.find((o) => wanted.includes(lower(o.text)) && lower(o.text).length > 1);
      if (!pick && direction) pick = numericOption(opts, optionText, direction);
      if (!pick) return { ok: false, detail: "option not found: " + optionText };
      setNativeValue(el, pick.value);
      pick.selected = true;
      fire(el, "input"); fire(el, "change");
      return { ok: true, detail: "selected " + clean(pick.text).slice(0, 40) };
    }
    // Custom dropdown / combobox: open it, then click the matching option.
    realClick(el);
    return new Promise((resolve) => {
      setTimeout(() => {
        const listId = el.getAttribute("aria-controls") || el.getAttribute("aria-owns");
        const scope = (listId && document.getElementById(listId)) || document;
        const options = Array.from(scope.querySelectorAll('[role=option], [role=menuitem], [role=menuitemradio], li, label, button, a')).filter(visible);
        let pick = options.find((o) => lower(o.textContent) === wanted)
          || options.find((o) => lower(o.textContent).includes(wanted) && wanted.length > 1);
        if (!pick && direction) {
          const numeric = options.map((o) => ({ o, n: parseAmount(o.textContent) })).filter((x) => x.n != null);
          const target = parseAmount(optionText);
          if (target != null && numeric.length) {
            const cands = direction === "min" ? numeric.filter((x) => x.n >= target) : numeric.filter((x) => x.n <= target);
            const best = direction === "min" ? cands.sort((a, b) => a.n - b.n)[0] : cands.sort((a, b) => b.n - a.n)[0];
            pick = best ? best.o : null;
          }
        }
        if (!pick) { resolve({ ok: false, detail: "custom option not found: " + optionText }); return; }
        realClick(pick);
        resolve({ ok: true, detail: "clicked option " + clean(pick.textContent).slice(0, 40) });
      }, 450);
    });
  }

  function parseAmount(text) {
    const t = lower(text).replace(/,/g, "").replace(/\$/g, "");
    const k = t.match(/^\s*([0-9]+(?:\.[0-9]+)?)\s*k\b/);
    if (k) return Math.round(parseFloat(k[1]) * 1000);
    const m = t.match(/-?[0-9]+(?:\.[0-9]+)?/);
    return m ? parseFloat(m[0]) : null;
  }

  function numericOption(opts, value, direction) {
    const target = parseAmount(value);
    if (target == null) return null;
    const numeric = opts.map((o) => ({ o, n: parseAmount(o.text) })).filter((x) => x.n != null && x.o.value !== "");
    if (!numeric.length) return null;
    const cands = direction === "min" ? numeric.filter((x) => x.n >= target) : numeric.filter((x) => x.n <= target);
    const best = direction === "min" ? cands.sort((a, b) => a.n - b.n)[0] : cands.sort((a, b) => b.n - a.n)[0];
    return best ? best.o : (direction === "min" ? numeric[numeric.length - 1].o : numeric[0].o);
  }

  function scrollBy(dy) {
    const before = window.scrollY;
    // Prefer scrolling an inner results container when the document itself does not scroll.
    const docScrollable = (document.documentElement.scrollHeight || 0) > window.innerHeight + 10;
    if (!docScrollable) {
      const scrollers = Array.from(document.querySelectorAll("main, [role=main], div")).filter((d) => {
        const st = window.getComputedStyle(d);
        return /(auto|scroll)/.test(st.overflowY) && d.scrollHeight > d.clientHeight + 50 && d.clientHeight > 200;
      });
      const target = scrollers.sort((a, b) => b.clientHeight - a.clientHeight)[0];
      if (target) { target.scrollBy({ top: dy, behavior: "instant" }); fire(target, "scroll"); return { ok: true, detail: "scrolled container" }; }
    }
    window.scrollBy({ top: dy, behavior: "instant" });
    fire(window, "scroll");
    fire(document, "scroll");
    return { ok: true, detail: "scrolled " + (window.scrollY - before) };
  }

  function dismiss(id) {
    const el = elementFor(id);
    if (el) { realClick(el); return { ok: true, detail: "clicked close" }; }
    const esc = { key: "Escape", code: "Escape", keyCode: 27, which: 27, bubbles: true, cancelable: true };
    document.dispatchEvent(new KeyboardEvent("keydown", esc));
    document.dispatchEvent(new KeyboardEvent("keyup", esc));
    return { ok: true, detail: "escape" };
  }

  async function execute(cmd) {
    const acts = new Set(['click', 'type', 'select', 'set_range', 'scroll', 'dismiss', 'navigate', 'back']);
    if (acts.has(cmd.cmd) && (cmd.expectedDocumentId !== documentId || cmd.expectedUrl !== location.href)) {
      return { ok: false, detail: 'STALE_DOCUMENT' };
    }
    switch (cmd.cmd) {
      case "observe": return { ok: true, observation: observe() };
      case "settle": return await waitSettle(Math.min(Math.max(cmd.capMs || 8000, 300), 20000));
      case "click": {
        const el = elementFor(cmd.id);
        if (!el) return { ok: false, detail: "TARGET_MISSING" };
        if (guard.blocks(el)) return { ok: false, detail: "GUARD_BLOCKED" };
        realClick(el);
        return { ok: true, detail: "clicked" };
      }
      case "type": {
        const el = elementFor(cmd.id);
        if (!el) return { ok: false, detail: "TARGET_MISSING" };
        if (guard.blocks(el)) return { ok: false, detail: "GUARD_BLOCKED" };
        return typeInto(el, String(cmd.text || ""), !!cmd.submit);
      }
      case "select": {
        const el = elementFor(cmd.id);
        if (!el) return { ok: false, detail: "TARGET_MISSING" };
        return await selectOption(el, String(cmd.option || ""), null);
      }
      case "set_range": {
        const el = elementFor(cmd.id);
        if (!el) return { ok: false, detail: "TARGET_MISSING" };
        const direction = cmd.direction || "max";
        if (el.tagName === "SELECT" || el.getAttribute("role") === "combobox" || el.tagName === "BUTTON") return await selectOption(el, String(cmd.value || ""), direction);
        const r = typeInto(el, String(cmd.value || ""), false);
        fire(el, "change");
        try { el.blur(); } catch (e) {}
        if (cmd.submit) {
          const keyInit = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
          el.dispatchEvent(new KeyboardEvent("keydown", keyInit)); el.dispatchEvent(new KeyboardEvent("keyup", keyInit));
        }
        return r;
      }
      case "scroll": return scrollBy(Number(cmd.dy) || 900);
      case "dismiss": return dismiss(cmd.id);
      case "navigate": {
        const u = samePath(cmd.url);
        if (!u || !/^https?:$/.test(u.protocol)) return { ok: false, detail: "BAD_URL" };
        location.assign(u.href);
        return { ok: true, detail: "navigating" };
      }
      case "back": history.back(); return { ok: true, detail: "back" };
      case "wait": await new Promise((r) => setTimeout(r, Math.min(Number(cmd.ms) || 500, 10000))); return { ok: true, detail: "waited" };
      case "guard": guard.setMode(cmd.mode || "OFF"); return { ok: true, detail: "guard " + guard.mode };
      case "teach": teach.set(!!cmd.on); return { ok: true, detail: "teach " + (cmd.on ? "on" : "off") };
      case "ping": return { ok: true, detail: "pong", url: location.href, ready: document.readyState };
      default: return { ok: false, detail: "UNSUPPORTED" };
    }
  }

  // ------------------------------------------------------------------ input guard (TRAIN mode)
  const guard = (() => {
    let mode = "OFF";
    function composerLike(el) {
      if (!el || el.nodeType !== 1) return false;
      const tag = el.tagName;
      if (tag === "TEXTAREA" || el.getAttribute("contenteditable") === "true") return true;
      if (tag === "INPUT" && /message|chat|reply|comment/i.test((el.getAttribute("name") || "") + " " + (el.getAttribute("placeholder") || "") + " " + (el.getAttribute("aria-label") || ""))) return true;
      return false;
    }
    function blocks(el) {
      if (mode !== "TRAIN") return false;
      if (composerLike(el)) return true;
      const label = lower(accessibleName(el));
      return /^(send|send message|submit offer|buy now|buy|checkout|place order|pay|post|publish|delete|follow|report)$/.test(label);
    }
    function onKey(e) {
      if (mode !== "TRAIN") return;
      if ((e.key === "Enter" || e.keyCode === 13) && composerLike(e.target)) { e.stopImmediatePropagation(); e.preventDefault(); }
    }
    function onSubmit(e) {
      if (mode !== "TRAIN") return;
      const form = e.target;
      if (form && form.querySelector && form.querySelector("textarea, [contenteditable=true]")) { e.stopImmediatePropagation(); e.preventDefault(); }
    }
    function onFocus(e) {
      if (mode !== "TRAIN") return;
      if (composerLike(e.target)) { try { e.target.blur(); } catch (err) {} }
    }
    document.addEventListener("keydown", onKey, true);
    document.addEventListener("keypress", onKey, true);
    document.addEventListener("submit", onSubmit, true);
    document.addEventListener("focusin", onFocus, true);
    return { setMode(m) { mode = m; }, blocks, get mode() { return mode; } };
  })();

  // ------------------------------------------------------------------ teach mode (human demonstration recorder)
  const teach = (() => {
    let on = false;
    let lastTypedEl = null;
    function rawFor(el) {
      // Reuse the observation format so Kotlin classifies the demonstrated element exactly like its own.
      const obs = observe();
      const id = idOf.get(el);
      const raw = obs.elements.find((e) => e.id === id) || null;
      return { raw, obsHash: hash32(JSON.stringify(obs.signals) + obs.elements.length) };
    }
    function target(e) {
      const el = e.target && e.target.closest ? e.target.closest(CANDIDATE_SELECTOR) : null;
      return el && visible(el) ? el : null;
    }
    function onClick(e) {
      if (!on || !e.isTrusted) return;
      const el = target(e); if (!el) return;
      const tag = el.tagName;
      const type = tag === "INPUT" ? (el.type || "text").toLowerCase() : "";
      if (type === "password") return;
      if ((tag === "INPUT" && !/^(checkbox|radio|submit|button)$/.test(type)) || tag === "TEXTAREA" || el.getAttribute("contenteditable") === "true") { lastTypedEl = el; return; }
      const { raw } = rawFor(el);
      post({ type: "TEACH", kind: "click", element: raw, url: location.href, ts: Date.now() });
    }
    function onChange(e) {
      if (!on || !e.isTrusted) return;
      const el = target(e); if (!el) return;
      const tag = el.tagName;
      const type = tag === "INPUT" ? (el.type || "text").toLowerCase() : "";
      if (type === "password") return;
      const { raw } = rawFor(el);
      const kind = tag === "SELECT" ? "select" : ((tag === "INPUT" && /^(checkbox|radio)$/.test(type)) ? "click" : "type");
      const value = kind === "select" ? clean(el.options[el.selectedIndex] ? el.options[el.selectedIndex].text : el.value) : (kind === "type" ? redact(el.value || textOf(el, 80), 80) : (el.checked ? "on" : ""));
      post({ type: "TEACH", kind, element: raw, value, url: location.href, ts: Date.now() });
    }
    function onKey(e) {
      if (!on || !e.isTrusted) return;
      if (e.key === "Enter" && lastTypedEl && (e.target === lastTypedEl || lastTypedEl.contains(e.target))) {
        const { raw } = rawFor(lastTypedEl);
        post({ type: "TEACH", kind: "type", element: raw, value: redact(lastTypedEl.value || textOf(lastTypedEl, 80), 80), submit: true, url: location.href, ts: Date.now() });
      }
    }
    document.addEventListener("click", onClick, true);
    document.addEventListener("change", onChange, true);
    document.addEventListener("keydown", onKey, true);
    return { set(v) { on = v; } };
  })();

  // ------------------------------------------------------------------ native port
  let port = null;
  function post(message) {
    try { if (port) port.postMessage(message); } catch (e) { /* port closed */ }
  }

  function connect() {
    try {
      port = browser.runtime.connectNative("aibrowser");
    } catch (e) {
      window.__aiBrowserBrainPortError = String(e);
      return;
    }
    port.onMessage.addListener(async (message) => {
      if (!message || typeof message !== "object") return;
      const reqId = message.reqId;
      try {
        const result = await execute(message);
        post(Object.assign({ type: "RESULT", reqId }, result));
      } catch (e) {
        post({ type: "RESULT", reqId, ok: false, detail: "EXCEPTION: " + String(e && e.message ? e.message : e) });
      }
    });
    port.onDisconnect.addListener(() => { port = null; });
    post({ type: "BRIDGE_READY", url: location.href, ready: document.readyState, ts: Date.now() });
  }

  const boot = () => { settleTracker.install(); };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot, { once: true }); else boot();
  boot();

  if (typeof browser !== "undefined" && browser.runtime && browser.runtime.connectNative) connect();

  // Test hook for the offline harness (no-op inside Gecko when the port is present).
  window.__brainObserve = observe;
  window.__brainExecute = execute;
})();
