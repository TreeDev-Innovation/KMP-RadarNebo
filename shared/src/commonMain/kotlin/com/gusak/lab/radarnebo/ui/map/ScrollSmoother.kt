package com.gusak.lab.radarnebo.ui.map

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Turns discrete scroll/wheel deltas into smooth camera motion. Each delta is added to a pending
 * amount that is played out with exponential easing over the following frames, so coarse wheel
 * notches and bursty trackpad events glide instead of jumping. New input during the glide simply
 * adds to what is left, keeping fast flicks responsive.
 */
class ScrollSmoother(
    /** Runs the frame-driven glide; null means the caller drives [step] itself (tests). */
    private val scope: CoroutineScope?,
    /** Time constant of the easing: ~95% of a delta is applied after 3 * [tauSeconds]. */
    private val tauSeconds: Float = 0.06f,
    private val apply: (pan: Offset, zoomFactor: Float, anchor: Offset) -> Unit,
) {
    private var pendingPan = Offset.Zero
    private var pendingLogZoom = 0f
    private var anchor = Offset.Zero
    private var job: Job? = null

    fun pan(pixels: Offset) {
        pendingPan += pixels
        start()
    }

    fun zoom(factor: Float, at: Offset) {
        pendingLogZoom += ln(factor)
        anchor = at
        start()
    }

    /** Drops any motion still pending, e.g. when the user grabs the map. */
    fun stop() {
        job?.cancel()
        pendingPan = Offset.Zero
        pendingLogZoom = 0f
    }

    /**
     * Advances the easing by [dtSeconds] and applies the resulting portion. Returns false once
     * everything pending has been applied.
     */
    fun step(dtSeconds: Float): Boolean {
        val k = 1f - exp(-dtSeconds / tauSeconds)
        var pan = pendingPan * k
        var logZoom = pendingLogZoom * k
        val settled = (pendingPan - pan).getDistance() < SETTLE_PX && abs(pendingLogZoom - logZoom) < SETTLE_LOG_ZOOM
        if (settled) {
            pan = pendingPan
            logZoom = pendingLogZoom
        }
        pendingPan -= pan
        pendingLogZoom -= logZoom
        apply(pan, exp(logZoom), anchor)
        return !settled
    }

    private fun start() {
        if (scope == null || job?.isActive == true) return
        job = scope.launch {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                // Clamp so a stalled frame doesn't dump the whole pending delta at once.
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                if (!step(dt)) break
            }
        }
    }

    private companion object {
        const val SETTLE_PX = 0.3f
        const val SETTLE_LOG_ZOOM = 0.0005f
    }
}
