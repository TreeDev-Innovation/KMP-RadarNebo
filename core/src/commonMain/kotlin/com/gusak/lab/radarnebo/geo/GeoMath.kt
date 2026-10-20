package com.gusak.lab.radarnebo.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_KM = 6371.0

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat).toRadians()
        val dLon = (b.lon - a.lon).toRadians()
        val h = sin(dLat / 2).let { it * it } +
            cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h))
    }

    /** Ray casting point-in-polygon test over packed lon,lat pairs. */
    fun contains(lonLat: FloatArray, point: GeoPoint): Boolean {
        var inside = false
        val n = lonLat.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = lonLat[i * 2].toDouble()
            val yi = lonLat[i * 2 + 1].toDouble()
            val xj = lonLat[j * 2].toDouble()
            val yj = lonLat[j * 2 + 1].toDouble()
            if ((yi > point.lat) != (yj > point.lat) &&
                point.lon < (xj - xi) * (point.lat - yi) / (yj - yi) + xi
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Places a sub-oblast location (raion/hromada) that has no coordinates in the API at a stable
     * pseudo-random spot inside its oblast, so markers spread out instead of stacking on the centroid.
     */
    fun scatterInOblast(oblastUid: Int, seed: String): GeoPoint? {
        val center = Regions.center(oblastUid) ?: return null
        val shape = UkraineGeometry.oblasts.firstOrNull { it.uid == oblastUid } ?: return center
        var state = seed.hashCode().toLong() and 0xffffffffL
        fun next(): Double {
            state = (state * 1103515245L + 12345L) and 0x7fffffffL
            return state / 2147483647.0
        }
        repeat(24) {
            val candidate = GeoPoint(
                lat = center.lat + (next() - 0.5) * 1.6,
                lon = center.lon + (next() - 0.5) * 2.4,
            )
            if (contains(shape.lonLat, candidate)) return candidate
        }
        return center
    }

    private fun Double.toRadians() = this * PI / 180.0
}
