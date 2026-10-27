package com.gusak.lab.radarnebo.data

import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.TargetClassifier
import com.gusak.lab.radarnebo.tracking.TargetKind
import com.gusak.lab.radarnebo.tracking.TargetProfile
import com.gusak.lab.radarnebo.tracking.TrackInterpolator

/** Which tracked targets the map and list show: every kind except [hiddenKinds], and only reported ones with [hideProbable]. */
data class TargetFilter(
    val hiddenKinds: Set<TargetKind> = emptySet(),
    val hideProbable: Boolean = false,
) {
    /** True when the filter hides anything at all. */
    val isActive get() = hiddenKinds.isNotEmpty() || hideProbable

    fun allows(track: DroneTrack, now: Long): Boolean = !isActive || allows(profileAt(track, now))

    fun allows(profile: TargetProfile): Boolean =
        profile.kind !in hiddenKinds && !(hideProbable && profile.source == TargetProfile.Source.Probable)

    companion object {
        /** The track's profile at [now]; its speed then tells drone types apart, as in the target list. */
        fun profileAt(track: DroneTrack, now: Long): TargetProfile =
            TargetClassifier.classify(track, TrackInterpolator.positionAt(track, now)?.speedKmh)
    }
}
