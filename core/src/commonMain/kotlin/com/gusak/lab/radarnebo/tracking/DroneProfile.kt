package com.gusak.lab.radarnebo.tracking

import com.gusak.lab.radarnebo.geo.GeoMath

/** Drone type information shown for a target. */
data class DroneProfile(
    val model: String,
    val make: String,
    val role: String,
    /** Typical cruise speed, km/h. */
    val cruiseSpeedKmh: IntRange,
    val source: Source,
) {
    enum class Source {
        /** The alert text named the type. */
        Reported,

        /** alerts.in.ua does not identify drone types; inferred from the threat class and flight profile. */
        Probable,
    }
}

/**
 * alerts.in.ua reports *that* drones are coming, not *which* drones. The type comes from the alert
 * text when it names one (as Ukrainian alert messages often do), otherwise it is a probable type
 * based on the threat class and observed speed, and labelled as such.
 */
object DroneClassifier {
    val Shahed = DroneProfile(
        model = "Shahed-136 / Geran-2",
        make = "HESA (Iran); licence-built as Geran-2 at Alabuga, Russia",
        role = "One-way attack loitering munition, ~50 kg warhead",
        cruiseSpeedKmh = 150..185,
        source = DroneProfile.Source.Reported,
    )

    val Gerbera = DroneProfile(
        model = "Gerbera",
        make = "Russia",
        role = "Decoy to saturate air defence; sometimes reconnaissance or a small warhead",
        cruiseSpeedKmh = 120..160,
        source = DroneProfile.Source.Reported,
    )

    val JetShahed = DroneProfile(
        model = "Shahed-238 / Geran-3",
        make = "HESA (Iran); built in Russia as Geran-3",
        role = "Jet-powered one-way attack drone",
        cruiseSpeedKmh = 350..550,
        source = DroneProfile.Source.Reported,
    )

    private val keywords = listOf(
        // Most specific first: "реактивний шахед" must not be classified as a plain Shahed.
        listOf("реактивн", "shahed-238", "герань-3", "geran-3") to JetShahed,
        listOf("гербер", "gerbera") to Gerbera,
        listOf("шахед", "shahed", "герань", "geran") to Shahed,
    )

    fun classify(track: DroneTrack, speedKmh: Double? = null): DroneProfile =
        classify(track.samples.mapNotNull { it.message }, speedKmh)

    fun classify(messages: List<String>, speedKmh: Double? = null): DroneProfile {
        val text = messages.joinToString(" ").lowercase()
        keywords.firstOrNull { (words, _) -> words.any { it in text } }?.let { return it.second }
        // Unnamed: long-range drone alerts are overwhelmingly Shahed-class; a very fast group suggests a jet.
        val probable = if (speedKmh != null && speedKmh > 300) JetShahed else Shahed
        return probable.copy(source = DroneProfile.Source.Probable)
    }
}

object TrackMetrics {
    /** Distance flown along the reported positions, plus the estimated way from the last report to [current]. */
    fun distanceFlownKm(track: DroneTrack, current: InterpolatedPosition? = null): Double {
        val reported = track.samples.zipWithNext { a, b -> GeoMath.distanceKm(a.point, b.point) }.sum()
        val sinceLast = current
            ?.takeIf { it.mode == PositionMode.Extrapolated }
            ?.let { GeoMath.distanceKm(track.samples.last().point, it.point) }
            ?: 0.0
        return reported + sinceLast
    }
}
