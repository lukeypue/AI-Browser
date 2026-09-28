package com.appgate.tv.browser

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension

/**
 * One GeckoRuntime, one profile, one identity.
 *
 * Every session in the app — the learner, the deep-search worker, the human sign-in view —
 * opens on this runtime, so cookies, local storage and the site's own "remember me" state
 * are shared exactly as they would be in one browser. Nothing here reads the cookie jar:
 * the app process has no cookie API access at all; auth state lives only inside Gecko's
 * app-private profile directory.
 *
 * Gecko presents itself as Firefox for Android, so Google/Facebook/Apple sign-in flows that
 * refuse embedded web views ("disallowed_useragent") behave as they do in a real browser.
 */
object BrainRuntime {
    private const val TAG = "BrainRuntime"
    const val EXTENSION_ID = "sitebrain@aibrowser.local"
    const val EXTENSION_LOCATION = "resource://android/assets/sitebrain/"
    const val NATIVE_APP_CONTENT = "aibrowser"
    const val NATIVE_APP_NET = "aibrowser-net"

    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile var extension: WebExtension? = null
        private set
    private val extensionWaiters = ArrayList<(WebExtension?) -> Unit>()

    /** Must be called on the main thread. */
    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .consoleOutput(false)
                .remoteDebuggingEnabled(false)
                .loginAutofillEnabled(false)          // the app never stores passwords, not even via Gecko's manager
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_LIGHT)
                .contentBlocking(
                    ContentBlocking.Settings.Builder()
                        .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                        .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                        // Firefox's default: first-party cookies plus isolated third-party storage. Sign-in popups
                        // (Google, Facebook, Apple) get storage access through the same heuristics Firefox uses.
                        .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                        .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                        .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                        .build()
                )
                .build()
            val created = GeckoRuntime.create(context.applicationContext, settings)
            runtime = created
            installExtension(created)
            return created
        }
    }

    private fun installExtension(rt: GeckoRuntime) {
        rt.webExtensionController.ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID).accept({ ext ->
            extension = ext
            Log.i(TAG, "Site Brain bridge installed: ${ext?.metaData?.version}")
            val waiters = synchronized(extensionWaiters) { ArrayList(extensionWaiters).also { extensionWaiters.clear() } }
            waiters.forEach { it(ext) }
        }, { error ->
            Log.e(TAG, "Site Brain bridge failed to install", error)
            val waiters = synchronized(extensionWaiters) { ArrayList(extensionWaiters).also { extensionWaiters.clear() } }
            waiters.forEach { it(null) }
        })
    }

    /** Runs [callback] (on the main thread) once the bundled extension is available. */
    fun whenExtensionReady(callback: (WebExtension?) -> Unit) {
        val ext = extension
        if (ext != null) { callback(ext); return }
        synchronized(extensionWaiters) { extensionWaiters += callback }
    }

    /** "Forget all sign-ins": wipes every cookie, storage, cache and permission in the profile. */
    fun clearAllSiteData(context: Context): GeckoResult<Void> =
        get(context).storageController.clearData(StorageController.ClearFlags.ALL)

    fun clearSiteData(context: Context, baseDomain: String): GeckoResult<Void> =
        get(context).storageController.clearDataFromBaseDomain(baseDomain, StorageController.ClearFlags.ALL)
}
