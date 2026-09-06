package dev.andrii.headroom.data

import dev.andrii.headroom.domain.UsageSnapshot

/**
 * What the UI should show.
 *
 * [Failed] deliberately carries the previous snapshot: spec §7 requires the
 * bars keep showing the last reading with its age rather than clearing.
 */
sealed interface UsageState {
    data object NotLinked : UsageState
    data object Loading : UsageState
    data class Ready(val snapshot: UsageSnapshot, val stale: Boolean) : UsageState
    data class Failed(
        val snapshot: UsageSnapshot?,
        val message: String,
        val needsRelink: Boolean,
    ) : UsageState
}
