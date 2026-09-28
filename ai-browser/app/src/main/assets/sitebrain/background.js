/*
 * AI Browser Site Brain — background script (network interlock).
 *
 * Three engine modes, set by the app over the native port "aibrowser-net":
 *   OFF    — human is browsing; nothing is blocked.
 *   TRAIN  — autonomous learning; every request that looks like a consequential mutation
 *            (send / buy / bid / post / delete / follow / save / report, or a learned COMMIT
 *            endpoint, or a GraphQL operation whose name says so) is blocked and reported.
 *   ASSIST — a person is present; consequential mutations are blocked unless a grant window
 *            is open (the app opens one for ~2 minutes after the user approves a preview).
 *
 * Unknown mutation endpoints are reported (method + path template only, never bodies or
 * query values) so the app can classify them from human demonstrations.
 */
(() => {
  let mode = "OFF";
  let commitEndpoints = [];        // learned "METHOD /path/template" strings
  let readAllowlist = [];          // learned safe mutation endpoints (search/filter POSTs)
  let grantOpenUntil = 0;
  let port = null;

  const commitPathRe = /(\/message|\/messages|\/messaging|\/send|\/inbox|\/chat|\/conversation|\/purchase|\/checkout|\/order|\/cart|\/bid|\/offer|\/make_offer|\/post\b|\/posts\b|\/create|\/publish|\/delete|\/remove|\/follow|\/unfollow|\/save|\/favorite|\/favourite|\/watchlist|\/report|\/flag|\/review|\/rating|\/subscribe|\/payment|\/pay\b|\/buy)/i;
  const commitOpRe = /(send|message|messaging|purchase|checkout|order|bid|offer|create|publish|delete|remove|follow|save|favorite|favourite|watch|report|flag|review|rate|subscribe|payment|pay\b|buy|mutation)/i;
  const readOpRe = /(search|query|filter|facet|list|browse|page|feed|marketplace_?search|typeahead|suggest|autocomplete|results|catalog|inventory|count)/i;

  function pathTemplate(url) {
    try {
      const u = new URL(url);
      let p = u.pathname.replace(/\/[0-9]{4,}(?=\/|$)/g, "/:id").replace(/\/[0-9a-fA-F-]{20,}(?=\/|$)/g, "/:id");
      return u.hostname + p;
    } catch (e) { return url.split("?")[0].slice(0, 120); }
  }

  function bodyOperation(details) {
    const body = details.requestBody;
    if (!body) return "";
    try {
      if (body.formData) {
        const fd = body.formData;
        const name = (fd.fb_api_req_friendly_name && fd.fb_api_req_friendly_name[0]) || (fd.operationName && fd.operationName[0]) || (fd.doc_id && "doc:" + fd.doc_id[0]) || "";
        const keys = Object.keys(fd).slice(0, 12).join(",");
        return name || keys;
      }
      if (body.raw && body.raw.length) {
        const bytes = body.raw[0].bytes;
        if (!bytes) return "";
        const text = new TextDecoder("utf-8").decode(bytes.slice(0, 2048));
        const m = text.match(/"operationName"\s*:\s*"([^"]+)"/) || text.match(/fb_api_req_friendly_name=([^&]+)/) || text.match(/"query"\s*:\s*"\s*(mutation|query)\s+([A-Za-z0-9_]+)/);
        if (m) return m[2] ? (m[1] + " " + m[2]) : decodeURIComponent(m[1]);
        return text.slice(0, 80);
      }
    } catch (e) { /* ignore */ }
    return "";
  }

  function classify(details) {
    const method = (details.method || "GET").toUpperCase();
    if (method === "GET" || method === "HEAD" || method === "OPTIONS") return { cls: "READ", key: method + " " + pathTemplate(details.url) };
    const key = method + " " + pathTemplate(details.url);
    const op = bodyOperation(details);
    if (commitEndpoints.includes(key) || commitEndpoints.some((e) => op && e.endsWith("#" + op))) return { cls: "COMMIT", key, op };
    if (readAllowlist.includes(key) || readAllowlist.some((e) => op && e.endsWith("#" + op))) return { cls: "READ", key, op };
    const commitByPath = commitPathRe.test(details.url.split("?")[0]);
    const commitByOp = op && commitOpRe.test(op) && !readOpRe.test(op);
    const readByOp = op && readOpRe.test(op) && !/(send|message|create|delete|purchase|order|bid|offer|follow|save|report)/i.test(op);
    if (commitByPath || commitByOp) return { cls: "COMMIT_HEURISTIC", key, op };
    if (readByOp) return { cls: "READ", key, op };
    return { cls: "UNKNOWN_MUTATION", key, op };
  }

  function report(event, extra) {
    try { if (port) port.postMessage(Object.assign({ type: event, ts: Date.now() }, extra || {})); } catch (e) { /* port closed */ }
  }

  function onBeforeRequest(details) {
    if (mode === "OFF") return {};
    if (details.type === "image" || details.type === "font" || details.type === "stylesheet" || details.type === "media" || details.type === "beacon" || details.type === "ping") return {};
    const c = classify(details);
    if (c.cls === "READ") return {};
    const grantOpen = Date.now() < grantOpenUntil;
    if (c.cls === "COMMIT" || c.cls === "COMMIT_HEURISTIC") {
      if ((mode === "ASSIST" || mode === "COMMIT") && grantOpen) { report("NET_COMMIT_ALLOWED", { key: c.key, op: c.op || "" }); return {}; }
      report("NET_BLOCKED", { key: c.key, op: c.op || "", cls: c.cls, mode });
      return { cancel: true };
    }
    // Unknown mutation: allowed in ASSIST (a person is present), blocked in TRAIN, always reported.
    report("NET_UNKNOWN_MUTATION", { key: c.key, op: c.op || "", mode });
    if (mode === "TRAIN") return { cancel: true };
    return {};
  }

  try {
    browser.webRequest.onBeforeRequest.addListener(onBeforeRequest, { urls: ["<all_urls>"] }, ["blocking", "requestBody"]);
  } catch (e) {
    // webRequest unavailable: the executor interlock still holds; report so the app can log it.
    setTimeout(() => report("NET_INTERLOCK_UNAVAILABLE", { error: String(e) }), 1000);
  }

  function connect() {
    try { port = browser.runtime.connectNative("aibrowser-net"); } catch (e) { return; }
    port.onMessage.addListener((m) => {
      if (!m || typeof m !== "object") return;
      if (m.type === "NET_MODE") {
        mode = m.mode || "OFF";
        commitEndpoints = Array.isArray(m.commit) ? m.commit : [];
        readAllowlist = Array.isArray(m.allow) ? m.allow : [];
        report("NET_MODE_ACK", { mode });
      } else if (m.type === "NET_GRANT") {
        grantOpenUntil = Date.now() + (Number(m.ttlMs) || 120000);
        report("NET_GRANT_ACK", { until: grantOpenUntil });
      } else if (m.type === "NET_GRANT_CLOSE") {
        grantOpenUntil = 0;
      } else if (m.type === "PING") {
        report("PONG", { mode });
      }
    });
    port.onDisconnect.addListener(() => { port = null; setTimeout(connect, 2000); });
    report("NET_READY", { mode });
  }
  connect();
})();
