package com.appgate.tv.browser

import android.graphics.SurfaceTexture
import android.view.Surface
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession

/**
 * A GeckoDisplay backed by an off-screen SurfaceTexture. A session with no display looks
 * hidden to the page (visibilityState, IntersectionObserver and requestAnimationFrame stop),
 * which silently breaks infinite scroll and lazy rendering; rendering into an off-screen
 * surface keeps the page behaving exactly as it does on screen while the engine works in
 * the background.
 */
class OffscreenDisplay(private val width: Int = 412, private val height: Int = 915) {
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var display: GeckoDisplay? = null
    private var session: GeckoSession? = null

    val isAttached: Boolean get() = session != null

    fun attach(target: GeckoSession) {
        if (session === target) return
        detach()
        val tex = SurfaceTexture(false).apply { setDefaultBufferSize(width, height) }
        val surf = Surface(tex)
        val disp = target.acquireDisplay()
        disp.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(surf).size(width, height).build())
        texture = tex
        surface = surf
        display = disp
        session = target
        target.setActive(true)
        target.setFocused(true)
    }

    fun detach() {
        val s = session ?: return
        val d = display
        runCatching { d?.surfaceDestroyed() }
        runCatching { if (d != null) s.releaseDisplay(d) }
        runCatching { surface?.release() }
        runCatching { texture?.release() }
        display = null
        surface = null
        texture = null
        session = null
    }
}
