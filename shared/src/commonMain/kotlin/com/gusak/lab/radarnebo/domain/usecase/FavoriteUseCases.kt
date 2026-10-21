package com.gusak.lab.radarnebo.domain.usecase

import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.FavoritesRepository
import com.gusak.lab.radarnebo.geo.GeoPoint
import kotlin.math.roundToInt
import kotlin.time.Clock

/** Adds a favorite; a blank name falls back to the coordinates. */
class AddFavoriteUseCase(
    private val favorites: FavoritesRepository,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    operator fun invoke(name: String, point: GeoPoint): FavoriteLocation {
        val trimmed = name.trim().ifBlank { "Point ${point.lat.format()}, ${point.lon.format()}" }
        val favorite = FavoriteLocation(
            id = "${clock()}-${trimmed.hashCode()}",
            name = trimmed,
            lat = point.lat,
            lon = point.lon,
        )
        favorites.add(favorite)
        return favorite
    }

    private fun Double.format() = ((this * 100).roundToInt() / 100.0).toString()
}

class RemoveFavoriteUseCase(private val favorites: FavoritesRepository) {
    operator fun invoke(id: String) = favorites.remove(id)
}
