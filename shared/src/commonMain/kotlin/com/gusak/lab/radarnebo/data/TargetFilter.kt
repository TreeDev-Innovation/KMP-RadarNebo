package com.gusak.lab.radarnebo.data

import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.TargetClassifier
import com.gusak.lab.radarnebo.tracking.TargetKind
import com.gusak.lab.radarnebo.tracking.TargetProfile
import com.gusak.lab.radarnebo.tracking.TrackInterpolator

/** Which tracked targets the Targets list and the map show. Alerts and proximity warnings are never filtered. */
data class TargetFilter(
    val hiddenKinds: Set<TargetKind> = emptySet(),
    /** Hides targets whose type is only inferred, not named in the alert. */
    val hideProbable: Boolean = false,
) {
    val isActive get() = hiddenKinds.isNotEmpty() || hideProbable

    fun allows(profile: TargetProfile) =
        profile.kind !in hiddenKinds && !(hideProbable && profile.source == TargetProfile.Source.Probable)

    /** [now] is server time; the speed at that moment can change a drone's probable type. */
    fun allows(track: DroneTrack, now: Long) = !isActive || allows(profileAt(track, now))

    companion object {
        fun profileAt(track: DroneTrack, now: Long): TargetProfile =
            TargetClassifier.classify(track, TrackInterpolator.positionAt(track, now)?.speedKmh)
    }
}
