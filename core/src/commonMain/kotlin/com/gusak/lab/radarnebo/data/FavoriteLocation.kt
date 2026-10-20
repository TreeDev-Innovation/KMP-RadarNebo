package com.gusak.lab.radarnebo.data

import com.gusak.lab.radarnebo.geo.GeoPoint
import kotlinx.serialization.Serializable

@Serializable
data class FavoriteLocation(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
) {
    val point get() = GeoPoint(lat, lon)
}
