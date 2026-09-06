package dev.andrii.headroom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.andrii.headroom.data.UsageState
import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.LimitBucket
import dev.andrii.headroom.domain.UsageSnapshot
import dev.andrii.headroom.ui.UsageScreen
import dev.andrii.headroom.ui.theme.HeadroomTheme

/**
 * Renders the Usage screen against the committed fixture, for design review.
 *
 * Debug source set only, so it cannot ship. It exists because the screen's
 * states are otherwise only reachable by having a linked account and waiting
 * for the server to be in the right mood — which is no way to check a design
 * against the mockup it was drawn from.
 *
 * adb shell am start -n dev.andrii.headroom/.DemoActivity --es state approaching
 */
class DemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val which = intent.getStringExtra("state") ?: "fine"
        val dark = intent.getStringExtra("theme") != "light"

        setContent {
            HeadroomTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UsageScreen(
                        state = stateFor(which),
                        thresholdPercent = 90.0,
                        nowEpochSeconds = NOW,
                        onRefresh = {},
                        onLink = {},
                        onOpenSettings = {},
                    )
                }
            }
        }
    }

    private fun stateFor(which: String): UsageState = when (which) {
        "approaching" -> UsageState.Ready(snapshot(sessionPercent = 94.0), stale = false)
        "wall" -> UsageState.Ready(snapshot(sessionPercent = 100.0), stale = false)
        "stale" -> UsageState.Ready(snapshot(fetchedAt = NOW - 3 * 3_600), stale = true)
        "offline" -> UsageState.Failed(
            snapshot = snapshot(fetchedAt = NOW - 25 * 60),
            message = "The request timed out. Showing the reading from 25 min ago.",
            needsRelink = false,
        )
        "relink" -> UsageState.Failed(
            snapshot = snapshot(fetchedAt = NOW - 25 * 60),
            message = "The server rejected the refresh (HTTP 400).",
            needsRelink = true,
        )
        "notlinked" -> UsageState.NotLinked
        else -> UsageState.Ready(snapshot(), stale = false)
    }

    /** The committed fixture's numbers, so this matches the mockup exactly. */
    private fun snapshot(
        sessionPercent: Double = 23.0,
        fetchedAt: Long = NOW,
    ) = UsageSnapshot(
        buckets = listOf(
            LimitBucket(
                kind = BucketKind.SESSION,
                rawKind = "session",
                title = "Current session",
                utilization = sessionPercent,
                resetsAt = NOW + 2 * 3_600 + 14 * 60,
                group = "session",
                severity = "normal",
                isActive = true,
            ),
            LimitBucket(
                kind = BucketKind.WEEKLY_ALL,
                rawKind = "weekly_all",
                title = "Current week (all models)",
                utilization = 12.0,
                resetsAt = WEEKLY_RESET,
                group = "weekly",
                severity = "normal",
            ),
            LimitBucket(
                kind = BucketKind.WEEKLY_SCOPED,
                rawKind = "weekly_scoped",
                title = "Current week (Example Model)",
                utilization = 7.0,
                resetsAt = WEEKLY_RESET,
                group = "weekly",
                severity = "normal",
                scopeLabel = "Example Model",
            ),
        ),
        fetchedAt = fetchedAt,
    )

    private companion object {
        const val NOW = 1_788_717_600L
        const val WEEKLY_RESET = 1_788_912_000L
    }
}
