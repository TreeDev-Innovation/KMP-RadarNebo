package com.gusak.lab.radarnebo.server.domain.alert

import com.gusak.lab.radarnebo.data.ThreatLevel

/**
 * Air raid status of one location, as the alerts.in.ua IoT endpoints encode it: one character per
 * location. v1 does not grade alerts, v2 tells red alerts from yellow (preliminary) ones.
 */
enum class AirRaidStatus(val v1: Char, val v2: Char) {
    /** Air raid alert over the whole location, red level. */
    Active(v1 = 'A', v2 = 'R'),

    /** Preliminary threat over the whole location, yellow level. */
    Preliminary(v1 = 'A', v2 = 'Y'),

    /** Alerts in some of the location's raions or hromadas only. */
    Partial(v1 = 'P', v2 = 'P'),

    /** No air raid alert. */
    None(v1 = 'N', v2 = 'N');

    fun code(version: IotVersion): Char = if (version == IotVersion.V1) v1 else v2
}

enum class IotVersion { V1, V2 }

/** Air raid status of every location under the alerts on [alerts]. */
class AirRaidStatusMap(alerts: Collection<AirAlert>, private val locations: LocationDirectory) {
    private val airRaids = alerts.filter { it.isActive && it.type == AlertType.AirRaid }
    private val byLocation = airRaids.groupBy { it.location.uid }
    private val oblastsWithAlerts = airRaids.mapNotNullTo(HashSet()) { it.location.oblastUid }

    /** Status of an oblast (or Kyiv / Sevastopol): whole when the region itself is alerted, partial when part of it is. */
    fun oblast(uid: Int): AirRaidStatus =
        wholeStatus(uid.toString()) ?: if (uid in oblastsWithAlerts) AirRaidStatus.Partial else AirRaidStatus.None

    /** Status of any location: its own alert, or the alert over its whole oblast. */
    fun location(uid: String): AirRaidStatus {
        val location = locations.find(uid)
        val oblastUid = location?.oblastUid ?: uid.toIntOrNull()?.takeIf { LocationDirectory.oblasts.any { it.uid == uid } }
        if (location == null || location.isRegion) return oblastUid?.let(::oblast) ?: AirRaidStatus.None
        return wholeStatus(uid) ?: oblastUid?.let { wholeStatus(it.toString()) } ?: AirRaidStatus.None
    }

    /** The 27 oblast statuses in the order the `by_oblast` endpoints use. */
    fun byOblast(version: IotVersion): String = OBLAST_ORDER.map { oblast(it).code(version) }.joinToString("")

    /** One character per location UID, at the position of the UID; a space where no location has that UID. */
    fun byUid(version: IotVersion): String {
        val uids = locations.all().mapNotNull { it.uid.toIntOrNull() }.toSet()
        val max = uids.maxOrNull() ?: return ""
        return (0..max).map { uid -> if (uid in uids) location(uid.toString()).code(version) else ' ' }.joinToString("")
    }

    private fun wholeStatus(uid: String): AirRaidStatus? = byLocation[uid]
        ?.let { alerts -> if (alerts.any { it.level != ThreatLevel.Yellow }) AirRaidStatus.Active else AirRaidStatus.Preliminary }

    companion object {
        /**
         * Order of the `active_air_raid_alerts_by_oblast` string: Crimea, Volyn, Vinnytsia, Dnipropetrovsk,
         * Donetsk, Zhytomyr, Zakarpattia, Zaporizhzhia, Ivano-Frankivsk, Kyiv city, Kyiv oblast, Kirovohrad,
         * Luhansk, Lviv, Mykolaiv, Odesa, Poltava, Rivne, Sevastopol, Sumy, Ternopil, Kharkiv, Kherson,
         * Khmelnytskyi, Cherkasy, Chernivtsi, Chernihiv.
         */
        val OBLAST_ORDER = listOf(29, 8, 4, 9, 28, 10, 11, 12, 13, 31, 14, 15, 16, 27, 17, 18, 19, 5, 30, 20, 21, 22, 23, 3, 24, 26, 25)
    }
}
