package com.appgate.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONTokener

private data class FoundResult(val source: String, val title: String, val url: String, val deepVerified: Boolean)
private data class CandidateResult(val source: String, val sourceKey: String, val title: String, val url: String)
private enum class ScanMode { SOURCES, DEEP }

class BrowserActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var resumeButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var names = arrayListOf<String>()
    private var keys = arrayListOf<String>()
    private var urls = arrayListOf<String>()
    private var index = 0
    private var waitingForHuman = false
    private var stopped = false
    private var rememberSignIns = true
    private var mode = ScanMode.SOURCES
    private lateinit var parsed: ParsedSearch

    private val candidates = LinkedHashMap<String, CandidateResult>()
    private val results = LinkedHashMap<String, FoundResult>()
    private val failures = mutableListOf<String>()
    private val sourceCounts = linkedMapOf<String, Int>()
    private var deepQueue = listOf<CandidateResult>()
    private var deepIndex = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AI Browser Deep Search"
        names = intent.getStringArrayListExtra("sourceNames") ?: arrayListOf()
        keys = intent.getStringArrayListExtra("sourceKeys") ?: arrayListOf()
        urls = intent.getStringArrayListExtra("sourceUrls") ?: arrayListOf()
        rememberSignIns = intent.getBooleanExtra("rememberSignIns", true)
        parsed = SearchIntentParser.parse(intent.getStringExtra("query").orEmpty())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 24))
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(18, 14, 18, 10)
        }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = if (names.isEmpty()) 1 else names.size
            progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resumeButton = Button(this).apply {
            text = "Resume"
            isEnabled = false
            setOnClickListener {
                waitingForHuman = false
                isEnabled = false
                inspectCurrentPage()
            }
        }
        controls.addView(resumeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "Skip"
            setOnClickListener {
                if (mode == ScanMode.SOURCES) {
                    failures.add("${currentName()}: skipped")
                    nextSource()
                } else {
                    nextDeepCandidate()
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "Show Results"
            setOnClickListener { showCombinedResults() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (rememberSignIns) CookieManager.getInstance().flush()
                    if (!stopped && !waitingForHuman) handler.postDelayed({ inspectCurrentPage() }, 1100L)
                }

                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && mode == ScanMode.SOURCES) {
                        failures.add("${currentName()}: page error")
                    }
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (urls.isEmpty()) showCombinedResults() else loadCurrentSource()
    }

    private fun loadCurrentSource() {
        if (stopped) return
        if (index >= urls.size) {
            finishSourcePhase()
            return
        }
        mode = ScanMode.SOURCES
        waitingForHuman = false
        resumeButton.isEnabled = false
        progress.max = names.size.coerceAtLeast(1)
        progress.progress = index
        status.text = "Searching ${index + 1} of ${urls.size}: ${currentName()}"
        webView.loadUrl(urls[index])
    }

    private fun finishSourcePhase() {
        if (parsed.requiredTerms.isEmpty() || candidates.isEmpty()) {
            showCombinedResults()
            return
        }
        mode = ScanMode.DEEP
        deepQueue = candidates.values.take(30)
        deepIndex = 0
        progress.max = deepQueue.size.coerceAtLeast(1)
        loadDeepCandidate()
    }

    private fun loadDeepCandidate() {
        if (stopped) return
        if (deepIndex >= deepQueue.size) {
            showCombinedResults()
            return
        }
        mode = ScanMode.DEEP
        waitingForHuman = false
        resumeButton.isEnabled = false
        progress.progress = deepIndex
        val candidate = deepQueue[deepIndex]
        status.text = "Deep checking ${deepIndex + 1} of ${deepQueue.size}: ${candidate.source}\nLooking for: ${parsed.requiredTerms.joinToString()}"
        webView.loadUrl(candidate.url)
    }

    private fun inspectCurrentPage() {
        if (stopped || waitingForHuman) return
        val challengeScript = """
            (function(){
              var t=((document.title||'')+' '+((document.body&&document.body.innerText)||'')).toLowerCase();
              return t.slice(0,12000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(challengeScript) { raw ->
            val text = decodeJsString(raw).lowercase()
            val human = listOf(
                "captcha", "verify you are human", "security check", "checkpoint",
                "unusual traffic", "confirm your identity", "log in to facebook", "login to facebook"
            ).any { text.contains(it) }
            if (human) {
                waitingForHuman = true
                resumeButton.isEnabled = true
                status.text = "${if (mode == ScanMode.SOURCES) currentName() else deepQueue.getOrNull(deepIndex)?.source ?: "Site"}: sign-in or human verification needed. Complete it in the page, then tap Resume.\n\nIf Remember Sign-ins is ON, the website session is kept on this device."
                return@evaluateJavascript
            }
            if (mode == ScanMode.SOURCES) extractSourceResults() else inspectDeepListing()
        }
    }

    private fun extractSourceResults() {
        val js = """
            (function(){
              var out=[]; var seen={}; var links=document.querySelectorAll('a[href]');
              function clean(s){return (s||'').replace(/\s+/g,' ').trim();}
              function cardText(a){
                var best=clean(a.innerText||a.getAttribute('aria-label')||'');
                var n=a;
                for(var d=0; d<5 && n; d++,n=n.parentElement){
                  var t=clean(n.innerText||'');
                  if(t.length>=best.length && t.length<=750) best=t;
                  if(n.matches && n.matches('article,li,[role="article"],[class*="listing"],[class*="result"],[class*="card"]')) break;
                }
                return best;
              }
              for(var i=0;i<links.length && out.length<40;i++){
                var a=links[i], href=a.href||'', txt=cardText(a);
                if(!href.startsWith('http') || txt.length<8 || txt.length>750) continue;
                var low=txt.toLowerCase();
                if(low==='sign in'||low==='log in'||low.includes('privacy policy')||low.includes('terms of use')) continue;
                if(seen[href]) continue; seen[href]=1;
                out.push({title:txt,url:href});
              }
              return JSON.stringify(out);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            var accepted = 0
            try {
                val decoded = decodeJsString(raw)
                val array = JSONArray(decoded)
                val sourceName = currentName()
                val sourceKey = currentKey()
                for (i in 0 until array.length()) {
                    if (parsed.requiredTerms.isNotEmpty() && accepted >= 6) break
                    val o = array.optJSONObject(i) ?: continue
                    val title = o.optString("title").trim()
                    val url = o.optString("url").trim()
                    if (title.length < 8 || !url.startsWith("http")) continue
                    if (!expectedDomainMatches(sourceKey, url)) continue
                    if (!SearchMatcher.summaryCouldMatch(title, parsed)) continue

                    if (parsed.requiredTerms.isEmpty()) {
                        if (!results.containsKey(url)) {
                            results[url] = FoundResult(sourceName, title, url, false)
                            accepted++
                        }
                    } else if (!candidates.containsKey(url)) {
                        candidates[url] = CandidateResult(sourceName, sourceKey, title, url)
                        accepted++
                    }
                }
                if (parsed.requiredTerms.isEmpty() && accepted > 0) {
                    sourceCounts[sourceName] = (sourceCounts[sourceName] ?: 0) + accepted
                }
                if (accepted == 0) failures.add("$sourceName: no matching cards read")
                status.text = "$sourceName: kept $accepted plausible listing${if (accepted == 1) "" else "s"} after hard filters"
            } catch (_: Exception) {
                failures.add("${currentName()}: could not read result cards")
            }
            handler.postDelayed({ nextSource() }, 450L)
        }
    }

    private fun inspectDeepListing() {
        val candidate = deepQueue.getOrNull(deepIndex) ?: run {
            showCombinedResults(); return
        }
        val js = """
            (function(){
              var title=(document.title||'');
              var body=((document.body&&document.body.innerText)||'');
              return (title+'\n'+body).slice(0,60000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val text = decodeJsString(raw)
            if (SearchMatcher.deepTextMatches(text, parsed)) {
                results[candidate.url] = FoundResult(candidate.source, candidate.title, candidate.url, true)
                sourceCounts[candidate.source] = (sourceCounts[candidate.source] ?: 0) + 1
            }
            handler.postDelayed({ nextDeepCandidate() }, 300L)
        }
    }

    private fun nextSource() {
        if (stopped) return
        index++
        loadCurrentSource()
    }

    private fun nextDeepCandidate() {
        if (stopped) return
        deepIndex++
        loadDeepCandidate()
    }

    private fun expectedDomainMatches(key: String, url: String): Boolean {
        val required = when (key) {
            "truecar" -> "truecar.com"
            "cargurus" -> "cargurus.com"
            "edmunds" -> "edmunds.com"
            "autolist" -> "autolist.com"
            "hemmings" -> "hemmings.com"
            "cars_bids" -> "carsandbids.com"
            "bringatrailer" -> "bringatrailer.com"
            else -> null
        }
        return required == null || url.contains(required, ignoreCase = true)
    }

    private fun showCombinedResults() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        if (rememberSignIns) CookieManager.getInstance().flush()

        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(11, 16, 24)) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 40)
        }
        list.addView(label("Deep Search Results", 26f, Color.WHITE, true))
        val constraintText = buildString {
            parsed.maxPrice?.let { append("Price ≤ $${"%,d".format(it)}  ") }
            parsed.maxMileage?.let { append("Mileage ≤ ${"%,d".format(it)}  ") }
            if (parsed.requiredTerms.isNotEmpty()) append("Deep match: ${parsed.requiredTerms.joinToString()}")
        }.ifBlank { "No hard constraints detected" }
        list.addView(label(constraintText, 14f, Color.rgb(175, 195, 220), false).apply { setPadding(0, 6, 0, 6) })
        list.addView(label("${results.size} verified result${if (results.size == 1) "" else "s"} after searching ${index.coerceAtMost(names.size)} sources", 14f, Color.rgb(175, 195, 220), false).apply { setPadding(0, 0, 0, 14) })

        if (sourceCounts.isNotEmpty()) {
            list.addView(label("Matches by source", 17f, Color.WHITE, true))
            sourceCounts.forEach { (source, count) -> list.addView(label("• $source: $count", 13f, Color.rgb(150, 205, 160), false)) }
        }

        if (results.isEmpty()) {
            list.addView(label("No listing passed every requested condition yet. That is better than showing expensive or irrelevant listings as matches. Source notes below show which sites may need a stronger adapter or login.", 15f, Color.rgb(230, 210, 150), false).apply { setPadding(0, 14, 0, 14) })
        }

        results.values.take(100).forEach { result ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                setBackgroundColor(Color.rgb(28, 37, 52))
            }
            card.addView(label(result.source, 13f, Color.rgb(135, 190, 255), true))
            if (result.deepVerified) card.addView(label("✓ Description requirement verified", 12f, Color.rgb(145, 220, 155), true))
            card.addView(label(result.title, 16f, Color.WHITE, true).apply { setPadding(0, 4, 0, 6) })
            card.addView(Button(this).apply {
                text = "Open Original Listing"
                setOnClickListener {
                    startActivity(Intent(this@BrowserActivity, ListingActivity::class.java).apply {
                        putExtra("url", result.url)
                        putExtra("rememberSignIns", rememberSignIns)
                    })
                }
            })
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 12)
            list.addView(card, lp)
        }

        if (failures.isNotEmpty()) {
            list.addView(label("Source Notes", 19f, Color.WHITE, true).apply { setPadding(0, 16, 0, 6) })
            failures.distinct().take(30).forEach { list.addView(label("• $it", 14f, Color.rgb(235, 175, 145), false)) }
        }

        list.addView(Button(this).apply { text = "New Search"; setOnClickListener { finish() } })
        root.addView(list)
        setContentView(root)
    }

    private fun decodeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            val value = JSONTokener(raw).nextValue()
            if (value is String) value else value.toString()
        } catch (_: Exception) {
            raw.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
        }
    }

    private fun currentName(): String = if (index in names.indices) names[index] else "Source"
    private fun currentKey(): String = if (index in keys.indices) keys[index] else ""

    private fun label(value: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = sp
        setTextColor(color)
        gravity = Gravity.START
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.visibility == View.VISIBLE && webView.canGoBack() && !stopped) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) webView.destroy()
        if (rememberSignIns) CookieManager.getInstance().flush()
        else CookieManager.getInstance().removeAllCookies(null)
        super.onDestroy()
    }
}
