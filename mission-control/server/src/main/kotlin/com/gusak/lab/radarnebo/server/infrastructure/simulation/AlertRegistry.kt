package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertLocation
import com.gusak.lab.radarnebo.server.domain.alert.ThreatSignal
import kotlin.time.Instant

/**
 * Keeps the simulated alerts the way alerts.in.ua keeps real ones: one alert per location while any threat
 * is demanded there, with a stable id, its threats opening and clearing within it. A threat clears a while
 * after it was last demanded (the all-clear lags the danger); an alert finishes when its last threat
 * clears. Levels only escalate: a red alert does not turn yellow before the all-clear.
 */
internal class AlertRegistry(firstId: Long) {
    private var nextId = firstId

    private class OpenThreat(var signal: ThreatSignal, var lastDemandedAt: Long)

    private class OpenAlert(val id: Long, val location: AlertLocation, val startedAt: Long) {
        var updatedAt = startedAt
        val threats = LinkedHashMap<ThreatType, OpenThreat>()

        fun toAirAlert() = AirAlert(
            id = id,
            location = location,
            level = if (threats.values.any { it.signal.level == ThreatLevel.Red }) ThreatLevel.Red else ThreatLevel.Yellow,
            startedAt = Instant.fromEpochMilliseconds(startedAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
            threats = threats.values.map { it.signal },
        )
    }

    private val open = LinkedHashMap<String, OpenAlert>()

    /** Applies the demands at [t] and returns the alerts active afterwards. */
    fun update(demands: Map<AlertLocation, List<ThreatDemand>>, t: Long): List<AirAlert> {
        demands.forEach { (location, locationDemands) ->
            val alert = open.getOrPut(location.uid) { OpenAlert(nextId++, location, t) }
            locationDemands.groupBy { it.type }.forEach { (type, typeDemands) ->
                val strongest = typeDemands.minBy { it.level }
                val threat = alert.threats[type]
                if (threat == null) {
                    alert.threats[type] = OpenThreat(ThreatSignal(type, strongest.level, Instant.fromEpochMilliseconds(t), strongest.message), t)
                    alert.updatedAt = t
                } else {
                    threat.lastDemandedAt = t
                    if (strongest.level < threat.signal.level) {
                        threat.signal = threat.signal.copy(level = strongest.level)
                        alert.updatedAt = t
                    }
                }
            }
        }
        open.values.forEach { alert ->
            if (alert.threats.values.removeIf { t - it.lastDemandedAt > clearDelayMs(it.signal.type) }) alert.updatedAt = t
        }
        open.values.removeIf { it.threats.isEmpty() }
        return open.values.map { it.toAirAlert() }
    }

    /** Id of the alert open for [locationUid], if any. */
    fun alertId(locationUid: String): Long? = open[locationUid]?.id

    private fun clearDelayMs(type: ThreatType): Long = when (type) {
        ThreatType.Drones, ThreatType.GuidedBombs -> 3 * MINUTE_MS
        ThreatType.CruiseMissiles, ThreatType.BallisticMissiles, ThreatType.Mig31k -> 2 * MINUTE_MS
        else -> MINUTE_MS
    }
}
