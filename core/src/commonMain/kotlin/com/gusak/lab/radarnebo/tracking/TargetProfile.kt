package com.gusak.lab.radarnebo.tracking

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoMath

/** What a target is. Decides its icon and which [TargetProfile]s apply. */
enum class TargetKind(val label: String) {
    AttackDrone("Attack drone"),
    JetDrone("Jet attack drone"),
    DecoyDrone("Decoy drone"),
    ReconDrone("Reconnaissance drone"),
    CruiseMissile("Cruise missile"),
    BallisticMissile("Ballistic missile"),
    AeroballisticMissile("Aeroballistic missile"),
    GuidedBomb("Guided aerial bomb"),
    TacticalAircraft("Tactical aviation"),
    StrategicBomber("Strategic aviation"),
    MiG31K("MiG-31K"),
    AirDefense("Air defense"),
    Unknown("Unknown threat"),
}

/** Type information shown for a target. */
data class TargetProfile(
    val kind: TargetKind,
    val model: String,
    val make: String,
    val role: String,
    /** Typical speed, km/h; null for things that don't fly (air defence) or aren't known. */
    val cruiseSpeedKmh: IntRange?,
    val source: Source = Source.Reported,
) {
    enum class Source {
        /** The alert text named the type. */
        Reported,

        /** alerts.in.ua does not identify models; inferred from the threat class and flight profile. */
        Probable,
    }
}

/**
 * alerts.in.ua reports the threat class (drones, cruise missiles, guided bombs...), not the model.
 * The model comes from the alert text when it names one (as Ukrainian alert messages often do),
 * otherwise it is the most likely model for the threat class and observed speed, labelled as such.
 */
object TargetClassifier {
    val Shahed = TargetProfile(
        kind = TargetKind.AttackDrone,
        model = "Shahed-136 / Geran-2",
        make = "HESA (Iran); licence-built as Geran-2 at Alabuga, Russia",
        role = "One-way attack loitering munition, ~50 kg warhead",
        cruiseSpeedKmh = 150..185,
    )

    val Gerbera = TargetProfile(
        kind = TargetKind.DecoyDrone,
        model = "Gerbera",
        make = "Russia",
        role = "Decoy to saturate air defence; sometimes reconnaissance or a small warhead",
        cruiseSpeedKmh = 120..160,
    )

    val JetShahed = TargetProfile(
        kind = TargetKind.JetDrone,
        model = "Shahed-238 / Geran-3",
        make = "HESA (Iran); built in Russia as Geran-3",
        role = "Jet-powered one-way attack drone",
        cruiseSpeedKmh = 350..550,
    )

    val ReconDrone = TargetProfile(
        kind = TargetKind.ReconDrone,
        model = "Orlan-10 / ZALA / Supercam",
        make = "Russia",
        role = "Reconnaissance and artillery spotting near the front",
        cruiseSpeedKmh = 90..150,
    )

    val CruiseMissile = TargetProfile(
        kind = TargetKind.CruiseMissile,
        model = "Kh-101 / Kh-555 / 3M14 Kalibr",
        make = "Russia; air-launched from Tu-95MS, Kalibr from ships and submarines",
        role = "Subsonic cruise missile flying low along a winding route",
        cruiseSpeedKmh = 700..950,
    )

    val BallisticMissile = TargetProfile(
        kind = TargetKind.BallisticMissile,
        model = "9M723 Iskander-M / KN-23",
        make = "Russia; KN-23 supplied by North Korea",
        role = "Short-range ballistic missile, minutes from launch to impact",
        cruiseSpeedKmh = 5_000..7_500,
    )

    val Kinzhal = TargetProfile(
        kind = TargetKind.AeroballisticMissile,
        model = "Kh-47M2 Kinzhal",
        make = "Russia",
        role = "Air-launched aeroballistic missile carried by the MiG-31K",
        cruiseSpeedKmh = 4_000..12_000,
    )

    val GuidedBomb = TargetProfile(
        kind = TargetKind.GuidedBomb,
        model = "KAB / FAB with UMPK kit",
        make = "Russia",
        role = "Glide bomb released by aircraft 40–70 km from its target",
        cruiseSpeedKmh = 500..900,
    )

    val TacticalAircraft = TargetProfile(
        kind = TargetKind.TacticalAircraft,
        model = "Su-34 / Su-35",
        make = "Russia",
        role = "Fighter-bombers launching guided bombs and missiles near the front",
        cruiseSpeedKmh = 800..1_400,
    )

    val StrategicBomber = TargetProfile(
        kind = TargetKind.StrategicBomber,
        model = "Tu-95MS / Tu-22M3",
        make = "Russia",
        role = "Long-range bombers that launch Kh-101 and Kh-22 cruise missiles",
        cruiseSpeedKmh = 700..900,
    )

    val MiG31K = TargetProfile(
        kind = TargetKind.MiG31K,
        model = "MiG-31K",
        make = "Russia",
        role = "Interceptor modified to carry the Kinzhal; its take-off triggers a nationwide alert",
        cruiseSpeedKmh = 1_500..3_000,
    )

    val AirDefense = TargetProfile(
        kind = TargetKind.AirDefense,
        model = "Air defense active",
        make = "Ukrainian Air Force",
        role = "Air defense is engaging targets nearby; stay in shelter",
        cruiseSpeedKmh = null,
    )

    val Unknown = TargetProfile(
        kind = TargetKind.Unknown,
        model = "Unknown threat",
        make = "–",
        role = "Threat type not reported",
        cruiseSpeedKmh = null,
    )

    /** Every profile, one per [TargetKind], in the order of [TargetKind]. */
    val all: List<TargetProfile> = listOf(
        Shahed, JetShahed, Gerbera, ReconDrone, CruiseMissile, BallisticMissile, Kinzhal,
        GuidedBomb, TacticalAircraft, StrategicBomber, MiG31K, AirDefense, Unknown,
    )

    fun profile(kind: TargetKind): TargetProfile = all.first { it.kind == kind }

    // Most specific first: "реактивний шахед" must not be classified as a plain Shahed.
    private val droneKeywords = listOf(
        listOf("реактивн", "shahed-238", "герань-3", "geran-3") to JetShahed,
        listOf("гербер", "gerbera", "імітатор") to Gerbera,
        listOf("розвідувальн", "орлан", "orlan", "zala", "зала", "supercam", "суперкам") to ReconDrone,
        listOf("шахед", "shahed", "герань", "geran") to Shahed,
    )
    private val missileKeywords = listOf(
        listOf("кинджал", "kinzhal", "х-47", "kh-47") to Kinzhal,
        listOf("іскандер", "iskander", "kn-23", "балістичн", "ballistic") to BallisticMissile,
        listOf("х-101", "kh-101", "х-555", "kh-555", "калібр", "kalibr", "крилат", "cruise") to CruiseMissile,
    )

    fun classify(track: DroneTrack, speedKmh: Double? = null): TargetProfile =
        classify(track.type, track.samples.mapNotNull { it.message }, speedKmh)

    fun classify(marker: ThreatMarker): TargetProfile = classify(marker.type, listOfNotNull(marker.message))

    fun classify(type: ThreatType, messages: List<String>, speedKmh: Double? = null): TargetProfile {
        val text = messages.joinToString(" ").lowercase()
        fun named(keywords: List<Pair<List<String>, TargetProfile>>) =
            keywords.firstOrNull { (words, _) -> words.any { it in text } }?.second
        fun probable(profile: TargetProfile) = profile.copy(source = TargetProfile.Source.Probable)
        return when (type) {
            // Unnamed long-range drone alerts are overwhelmingly Shahed-class; a very fast group suggests a jet.
            ThreatType.Drones -> named(droneKeywords)
                ?: probable(if (speedKmh != null && speedKmh > 300) JetShahed else Shahed)
            ThreatType.CruiseMissiles -> named(missileKeywords) ?: probable(CruiseMissile)
            ThreatType.BallisticMissiles -> named(missileKeywords) ?: probable(BallisticMissile)
            ThreatType.UnspecifiedMissiles -> named(missileKeywords)
                ?: probable(CruiseMissile.copy(model = "Missile, type not reported"))
            ThreatType.GuidedBombs -> GuidedBomb.reportedIf(text, "каб", "kab", "умпк", "umpk")
            ThreatType.TacticalAviation -> TacticalAircraft.reportedIf(text, "су-34", "su-34", "су-35", "su-35")
            ThreatType.StrategicAviation -> StrategicBomber.reportedIf(text, "ту-95", "tu-95", "ту-22", "tu-22", "ту-160", "tu-160")
            ThreatType.Mig31k -> MiG31K
            ThreatType.AirDefense -> AirDefense
            ThreatType.Unknown -> Unknown
        }
    }

    /** The threat class fixes the kind; the model only counts as reported when the text names it. */
    private fun TargetProfile.reportedIf(text: String, vararg names: String) =
        if (names.any { it in text }) this else copy(source = TargetProfile.Source.Probable)
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
