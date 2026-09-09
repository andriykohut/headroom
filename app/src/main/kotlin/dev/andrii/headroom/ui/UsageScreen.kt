package dev.andrii.headroom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.andrii.headroom.data.UsageState
import dev.andrii.headroom.ui.theme.CompactNumberStyle
import dev.andrii.headroom.ui.theme.HeroNumberStyle
import dev.andrii.headroom.domain.LimitBucket
import dev.andrii.headroom.domain.UsageSnapshot

/**
 * The whole app in one glance.
 *
 * There is no title bar and no wordmark: the app's name is on the launcher
 * icon, and a screen opened for two seconds should spend its top line on
 * something useful — how old the reading is.
 */
@Composable
fun UsageScreen(
    state: UsageState,
    thresholdPercent: Double,
    nowEpochSeconds: Long,
    onRefresh: () -> Unit,
    onLink: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Android 15 forces edge-to-edge for targetSdk 35+, so this draws
            // behind the status and navigation bars unless it says otherwise.
            // Import and Settings get this from their Scaffold; this screen has
            // none, and without it the refresh and settings buttons sit under
            // the clock and the battery.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        val snapshot = state.snapshotOrNull()
        val stale = state is UsageState.Ready && state.stale

        TopLine(
            age = snapshot?.let { formatAge(nowEpochSeconds - it.fetchedAt) },
            lastReported = snapshot?.let { formatLastReported(it.fetchedAt) },
            stale = stale,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
        )

        Spacer(Modifier.height(20.dp))

        when (state) {
            is UsageState.NotLinked -> NotLinkedPanel(onLink)
            is UsageState.Loading -> Text(
                "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is UsageState.Ready -> Bands(state.snapshot, thresholdPercent, nowEpochSeconds, stale)
            is UsageState.Failed -> {
                // Spec §7: say what went wrong, and keep the last reading on
                // screen rather than blanking it.
                if (state.needsNewCode) {
                    RelayRejectedPanel(state.message, onLink)
                } else {
                    OfflineStrip(state.message, onRefresh)
                }
                Spacer(Modifier.height(20.dp))
                state.snapshot?.let {
                    Bands(it, thresholdPercent, nowEpochSeconds, dimmed = true)
                }
            }
        }
    }
}

private fun UsageState.snapshotOrNull(): UsageSnapshot? = when (this) {
    is UsageState.Ready -> snapshot
    is UsageState.Failed -> snapshot
    else -> null
}

@Composable
private fun TopLine(
    age: String?,
    lastReported: String?,
    stale: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Stale is not a failure: the fetch worked and the machine that reports
        // is off. Dating the reading says that; ageing it invites a refresh
        // that cannot help, because only the next push makes a newer one.
        val text = when {
            stale && lastReported != null -> "Last reported $lastReported"
            age != null -> "Updated $age"
            else -> ""
        }
        Text(
            text = text,
            style = if (stale) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodyMedium,
            color = if (stale) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun Bands(
    snapshot: UsageSnapshot,
    thresholdPercent: Double,
    now: Long,
    dimmed: Boolean = false,
) {
    val bands = groupBuckets(snapshot)
    bands.forEachIndexed { index, band ->
        if (index > 0) Spacer(Modifier.height(28.dp))

        band.header?.let { header ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    header,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // One reset time for the whole band when they agree, so three
                // rows do not repeat the same clock time three times.
                band.sharedResetsAt?.let { resetsAt ->
                    formatResetTime(resetsAt)?.let {
                        Text(
                            "Resets $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        band.buckets.forEachIndexed { bucketIndex, bucket ->
            if (bucketIndex > 0) Spacer(Modifier.height(18.dp))
            val hero = band.group == "session" && bucketIndex == 0
            if (hero) {
                HeroBucket(bucket, thresholdPercent, now, dimmed)
            } else {
                CompactBucket(
                    bucket = bucket,
                    thresholdPercent = thresholdPercent,
                    now = now,
                    dimmed = dimmed,
                    showResetLine = band.sharedResetsAt == null,
                )
            }
        }
    }
}

@Composable
private fun HeroBucket(
    bucket: LimitBucket,
    thresholdPercent: Double,
    now: Long,
    dimmed: Boolean,
) {
    val state = barState(bucket.utilization, thresholdPercent, bucket.hasReset(now))
    val alpha = if (dimmed) DIMMED_ALPHA else 1f

    Text(
        bucket.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
    )
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        fontSize = HeroNumberStyle.fontSize,
                        letterSpacing = HeroNumberStyle.letterSpacing,
                        fontWeight = if (state == BarState.FINE) FontWeight.Normal
                        else FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                ) { append(bucket.utilization.toInt().toString()) }
                withStyle(SpanStyle(fontSize = MaterialTheme.typography.headlineLarge.fontSize)) {
                    append("%")
                }
            },
            color = statusColor(state).copy(alpha = alpha),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "used",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.padding(bottom = 14.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    UsageBar(
        bucket.utilization,
        thresholdPercent,
        hero = true,
        dimmed = dimmed,
        hasReset = bucket.hasReset(now),
        paceFraction = bucket.paceMark(now),
    )
    Spacer(Modifier.height(8.dp))
    StatusLine(bucket, state, now, alpha, hero = true)
}

@Composable
private fun CompactBucket(
    bucket: LimitBucket,
    thresholdPercent: Double,
    now: Long,
    dimmed: Boolean,
    showResetLine: Boolean,
) {
    val state = barState(bucket.utilization, thresholdPercent, bucket.hasReset(now))
    val alpha = if (dimmed) DIMMED_ALPHA else 1f

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            displayLabel(bucket),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "${bucket.utilization.toInt()}%",
            style = CompactNumberStyle.copy(
                fontWeight = if (state == BarState.FINE) FontWeight.Normal else FontWeight.Bold,
            ),
            color = statusColor(state).copy(alpha = alpha),
            textAlign = TextAlign.End,
            // Reserved at the width of the widest value it can hold, so the
            // digits do not shuffle sideways as the number changes.
            modifier = Modifier.width(64.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
    UsageBar(
        bucket.utilization,
        thresholdPercent,
        dimmed = dimmed,
        hasReset = bucket.hasReset(now),
        paceFraction = bucket.paceMark(now),
    )
    if (showResetLine || state != BarState.FINE) {
        Spacer(Modifier.height(8.dp))
        StatusLine(bucket, state, now, alpha, hero = false)
    }
}

@Composable
private fun StatusLine(
    bucket: LimitBucket,
    state: BarState,
    now: Long,
    alpha: Float,
    hero: Boolean,
) {
    val countdown = formatCountdown(bucket.resetsAt - now)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
    val accent = statusColor(state).copy(alpha = alpha)

    val text = buildAnnotatedString {
        when (state) {
            BarState.FINE -> append("Resets in $countdown")
            BarState.RESET -> {
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append("Reset")
                }
                val at = formatResetTime(bucket.resetsAt)
                append(if (at == null) " · fresh window" else " $at · fresh window")
                append(", measured again on your next message")
            }
            BarState.APPROACHING -> {
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append("Near limit")
                }
                append(", resets in $countdown")
            }
            BarState.WALL -> {
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append("Limit reached.")
                }
                append(" Lifts in $countdown")
            }
        }
    }

    Text(
        text = text,
        style = if (state == BarState.WALL) MaterialTheme.typography.titleMedium
        else MaterialTheme.typography.bodyMedium,
        color = variant,
    )
    if (bucket.severity.isNotBlank() && bucket.severity != "normal" && state == BarState.FINE) {
        // The user's threshold owns the picture; the server's opinion is a line
        // of text rather than a second colour system.
        Text(
            "Server flags this limit as ${bucket.severity}.",
            style = MaterialTheme.typography.bodySmall,
            color = variant,
        )
    }
    if (bucket.kind == dev.andrii.headroom.domain.BucketKind.UNKNOWN) {
        Text(
            "New limit type reported by the server.",
            style = MaterialTheme.typography.bodySmall,
            color = variant,
        )
    }
}

@Composable
private fun statusColor(state: BarState) = when (state) {
    BarState.FINE, BarState.RESET -> MaterialTheme.colorScheme.onSurface
    BarState.APPROACHING -> MaterialTheme.colorScheme.tertiary
    BarState.WALL -> MaterialTheme.colorScheme.error
}

@Composable
private fun NotLinkedPanel(onLink: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Nothing linked yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "On your computer, run /headroom-link. It prints a code that " +
                    "points this phone at your relay - scan it and the bars appear.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onLink) { Text("Scan code") }
        }
    }
}

@Composable
private fun RelayRejectedPanel(message: String, onLink: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Your relay refused this phone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your relay would not accept this phone's key, so usage can't be " +
                    "updated.\n$message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onLink) { Text("Scan a new code") }
        }
    }
}

@Composable
private fun OfflineStrip(message: String, onRefresh: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Couldn't update",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRefresh) { Text("Try again") }
        }
    }
}

/** Null for a window that has ended: a full bar of elapsed time tells nobody anything. */
private fun LimitBucket.paceMark(now: Long): Double? =
    if (hasReset(now)) null else elapsedFraction(now)
