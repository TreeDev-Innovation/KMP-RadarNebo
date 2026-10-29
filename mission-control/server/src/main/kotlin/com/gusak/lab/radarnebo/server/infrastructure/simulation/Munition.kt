package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.tracking.TrackOutcome

/**
 * How Ukraine's air raid warning system alerts settlements for one kind of target. Settlements within
 * [redRadiusKm] of where the target is, or will be within [redLeadMs], get a red alert; settlements within
 * [corridorKm] of where it will be within [yellowLeadMs] get a yellow (preliminary) one.
 */
internal class WarningProfile(
    val redRadiusKm: Double,
    val redLeadMs: Long,
    val yellowLeadMs: Long,
    val corridorKm: Double,
) {
    companion object {
        val Drone = WarningProfile(redRadiusKm = 22.0, redLeadMs = 8 * MINUTE_MS, yellowLeadMs = 30 * MINUTE_MS, corridorKm = 15.0)
        val JetDrone = WarningProfile(redRadiusKm = 25.0, redLeadMs = 4 * MINUTE_MS, yellowLeadMs = 10 * MINUTE_MS, corridorKm = 20.0)
        val ReconDrone = WarningProfile(redRadiusKm = 18.0, redLeadMs = 4 * MINUTE_MS, yellowLeadMs = 0, corridorKm = 0.0)
        val CruiseMissile = WarningProfile(redRadiusKm = 30.0, redLeadMs = 4 * MINUTE_MS, yellowLeadMs = 12 * MINUTE_MS, corridorKm = 25.0)

        /** Ballistic missiles and guided bombs are alerted by region or target, not along the way. */
        val None = WarningProfile(redRadiusKm = 0.0, redLeadMs = 0, yellowLeadMs = 0, corridorKm = 0.0)
    }
}

internal enum class WeaponClass { Drone, CruiseMissile, BallisticMissile, GuidedBomb }

/**
 * A weapon used in the simulated attacks, with the performance and air-defence figures that drive it.
 * [message] is the warning text as Ukrainian monitoring channels word it; it names the model, so the
 * app's classifier recognises it.
 */
internal enum class Munition(
    val weaponClass: WeaponClass,
    val threat: ThreatType,
    /** Cruise speed; for ballistic missiles the average over the whole flight. */
    val speedKmh: ClosedFloatingPointRange<Double>,
    /** Chance air defence shoots it down or jams it. */
    val interceptChance: Double,
    /** Chance contact is lost without an interception: decoys drop off radar or come down on their own. */
    val lossChance: Double,
    /** Where along its path (as a fraction) an interception happens. */
    val interceptWindow: ClosedFloatingPointRange<Double>,
    /** Largest surveillance position error, km. */
    val positionErrorKm: Double,
    val warning: WarningProfile,
    val message: String,
    /** How a flight that reaches the end of its path ends. */
    val completesAs: TrackOutcome = TrackOutcome.Impact,
) {
    Shahed(
        WeaponClass.Drone, ThreatType.Drones, 150.0..185.0, interceptChance = 0.82, lossChance = 0.06, interceptWindow = 0.45..0.98,
        positionErrorKm = 0.4, warning = WarningProfile.Drone, message = "Ударні БпЛА типу «Шахед» (Shahed-136 / Герань-2)",
    ),
    Gerbera(
        WeaponClass.Drone, ThreatType.Drones, 120.0..160.0, interceptChance = 0.40, lossChance = 0.45, interceptWindow = 0.3..0.95,
        positionErrorKm = 0.5, warning = WarningProfile.Drone, message = "БпЛА-імітатори типу «Гербера»",
    ),
    JetShahed(
        WeaponClass.Drone, ThreatType.Drones, 420.0..520.0, interceptChance = 0.55, lossChance = 0.05, interceptWindow = 0.5..0.97,
        positionErrorKm = 0.6, warning = WarningProfile.JetDrone, message = "Реактивні ударні БпЛА «Герань-3» (Shahed-238)",
    ),
    Orlan(
        WeaponClass.Drone, ThreatType.Drones, 90.0..130.0, interceptChance = 0.30, lossChance = 0.0, interceptWindow = 0.25..0.8,
        positionErrorKm = 0.3, warning = WarningProfile.ReconDrone, message = "Розвідувальний БпЛА «Орлан-10» на лінії фронту",
        completesAs = TrackOutcome.Lost,
    ),
    Kh101(
        WeaponClass.CruiseMissile, ThreatType.CruiseMissiles, 720.0..800.0, interceptChance = 0.86, lossChance = 0.02, interceptWindow = 0.55..0.98,
        positionErrorKm = 0.3, warning = WarningProfile.CruiseMissile, message = "Крилаті ракети Х-101, пуски з Ту-95МС",
    ),
    Kalibr(
        WeaponClass.CruiseMissile, ThreatType.CruiseMissiles, 750.0..850.0, interceptChance = 0.80, lossChance = 0.02, interceptWindow = 0.5..0.98,
        positionErrorKm = 0.3, warning = WarningProfile.CruiseMissile, message = "Крилаті ракети «Калібр» з акваторії Чорного моря",
    ),
    IskanderM(
        WeaponClass.BallisticMissile, ThreatType.BallisticMissiles, 4_200.0..5_400.0, interceptChance = 0.40, lossChance = 0.0, interceptWindow = 0.9..0.99,
        positionErrorKm = 0.0, warning = WarningProfile.None, message = "Балістичні ракети «Іскандер-М»",
    ),
    Kn23(
        WeaponClass.BallisticMissile, ThreatType.BallisticMissiles, 4_500.0..5_600.0, interceptChance = 0.30, lossChance = 0.0, interceptWindow = 0.9..0.99,
        positionErrorKm = 0.0, warning = WarningProfile.None, message = "Балістичні ракети KN-23",
    ),
    Kinzhal(
        WeaponClass.BallisticMissile, ThreatType.BallisticMissiles, 4_500.0..5_800.0, interceptChance = 0.55, lossChance = 0.0, interceptWindow = 0.92..0.99,
        positionErrorKm = 0.0, warning = WarningProfile.None, message = "Аеробалістичні ракети Х-47М2 «Кинджал» з МіГ-31К",
    ),
    Kab(
        WeaponClass.GuidedBomb, ThreatType.GuidedBombs, 550.0..750.0, interceptChance = 0.0, lossChance = 0.0, interceptWindow = 1.0..1.0,
        positionErrorKm = 0.2, warning = WarningProfile.None, message = "Пуски КАБ (УМПК) з Су-34",
    ),
}
