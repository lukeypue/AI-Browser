package com.appgate.tv.browser

import kotlin.math.roundToInt

/** Logical mobile viewport converted to Surface pixels, with a bounded allocation. */
internal object OffscreenViewportGeometry {
    private const val LOGICAL_WIDTH = 412
    private const val LOGICAL_HEIGHT = 915
    private const val MAX_SURFACE_EDGE = 4096
    data class Pixels(val width: Int, val height: Int)

    fun physicalPixels(displayDensity: Float): Pixels {
        val density = if (displayDensity.isFinite() && displayDensity > 0f) displayDensity else 1f
        val bounded = density.coerceAtMost(MAX_SURFACE_EDGE.toFloat() / LOGICAL_HEIGHT)
        return Pixels(
            (LOGICAL_WIDTH * bounded).roundToInt().coerceAtLeast(1),
            (LOGICAL_HEIGHT * bounded).roundToInt().coerceAtLeast(1)
        )
    }
}
