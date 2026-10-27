package com.gusak.lab.radarnebo.server.domain.alert

import com.gusak.lab.radarnebo.geo.Regions
import java.util.concurrent.ConcurrentHashMap

/**
 * Every location the server knows: the oblasts, the locations its data source declares in advance, and
 * any location an alert has been seen for. The IoT status endpoints index their strings by it.
 */
class LocationDirectory(known: Collection<AlertLocation> = emptyList()) {
    private val byUid = ConcurrentHashMap<String, AlertLocation>()

    init {
        oblasts.forEach { byUid[it.uid] = it }
        known.forEach { byUid[it.uid] = it }
    }

    fun find(uid: String): AlertLocation? = byUid[uid]

    fun all(): Collection<AlertLocation> = byUid.values

    /** Remembers the locations of [alerts]; alerts.in.ua publishes no location list of its own. */
    fun learn(alerts: Collection<AirAlert>) = alerts.forEach { byUid.putIfAbsent(it.location.uid, it.location) }

    companion object {
        /** The 25 oblasts plus Kyiv and Sevastopol, as alerts.in.ua numbers them. */
        val oblasts: List<AlertLocation> = Regions.oblasts.map { oblast ->
            AlertLocation(
                uid = oblast.uid.toString(),
                title = oblast.nameUk,
                titleEn = oblast.nameEn,
                type = if (oblast.uid == Regions.KYIV_CITY_UID || oblast.uid == Regions.SEVASTOPOL_UID) LocationType.City else LocationType.Oblast,
                oblastUid = oblast.uid,
                oblastTitle = oblast.nameUk,
            )
        }

        private val regionUids: Set<String> = oblasts.mapTo(HashSet()) { it.uid }

        /** Oblasts, Kyiv and Sevastopol: the UIDs whose history includes the places inside them. */
        fun isRegionUid(uid: String): Boolean = uid in regionUids
    }
}
