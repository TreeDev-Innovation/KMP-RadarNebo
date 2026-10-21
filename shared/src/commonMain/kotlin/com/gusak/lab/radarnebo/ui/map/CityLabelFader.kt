package com.gusak.lab.radarnebo.ui.map

import com.gusak.lab.radarnebo.geo.MapCity

/**
 * Fades city names in and out instead of popping: when zooming past the label threshold, panning a
 * city into view, or when a bigger city's name takes or frees the space.
 *
 * Plain (non-snapshot) state: it is updated while drawing and never triggers recomposition.
 */
class CityLabelFader(private val fadeInMs: Float = 260f, private val fadeOutMs: Float = 180f) {
    private val opacity = LinkedHashMap<MapCity, Float>()
    private var lastFrameMs = 0L

    /**
     * Moves every label towards shown (in [wanted]) or hidden, and returns the opacity of each label
     * worth drawing, including ones still fading out. [instant] skips the fades, for previews and
     * screenshots, which only render the first frames.
     */
    fun update(wanted: Set<MapCity>, nowMs: Long, instant: Boolean = false): Map<MapCity, Float> {
        if (instant) {
            opacity.clear()
            wanted.forEach { opacity[it] = 1f }
            lastFrameMs = nowMs
            return opacity
        }
        val dt = if (lastFrameMs == 0L) 0f else (nowMs - lastFrameMs).coerceIn(0L, 250L).toFloat()
        lastFrameMs = nowMs
        wanted.forEach { opacity.getOrPut(it) { 0f } }
        val entries = opacity.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key in wanted) {
                entry.setValue((entry.value + dt / fadeInMs).coerceAtMost(1f))
            } else {
                val next = entry.value - dt / fadeOutMs
                if (next <= 0f) entries.remove() else entry.setValue(next)
            }
        }
        return opacity
    }

    /** True while any label is mid-fade, so the caller keeps drawing frames. */
    val isAnimating: Boolean get() = opacity.values.any { it < 1f }
}
