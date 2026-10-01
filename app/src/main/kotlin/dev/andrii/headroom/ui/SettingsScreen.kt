package dev.andrii.headroom.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.andrii.headroom.domain.TriggerSettings

/**
 * Four triggers and the numbers that tune them.
 *
 * The threshold and the reset gates live inside the row they belong to rather
 * than in a section of their own — a setting beside the trigger it affects is
 * what stops this reading as a form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: TriggerSettings,
    onChange: ((TriggerSettings) -> TriggerSettings) -> Unit,
    onUnlink: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmUnlink by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Landscape and small phones cut the last rows off otherwise,
                // and "Unlink this phone" is the last row.
                .verticalScroll(rememberScrollState()),
        ) {
            TriggerRow(
                title = "Session reset",
                description = "When the 5-hour window rolls over",
                checked = settings.sessionReset,
                onCheckedChange = { on -> onChange { it.copy(sessionReset = on) } },
            )
            AnimatedVisibility(visible = settings.sessionReset) {
                ResetGate(
                    description = "Skip it when the session ended below your line",
                    onlyIfUsed = settings.sessionResetOnlyIfUsed,
                    minUsage = settings.sessionResetMinUsage,
                    onOnlyIfUsedChange = { on -> onChange { it.copy(sessionResetOnlyIfUsed = on) } },
                    onMinUsageChange = { v -> onChange { it.copy(sessionResetMinUsage = v) } },
                )
            }
            TriggerRow(
                title = "Weekly reset",
                description = "When the 7-day window rolls over",
                checked = settings.weeklyReset,
                onCheckedChange = { on -> onChange { it.copy(weeklyReset = on) } },
            )
            AnimatedVisibility(visible = settings.weeklyReset) {
                ResetGate(
                    description = "Skip it when the week ended below your line",
                    onlyIfUsed = settings.weeklyResetOnlyIfUsed,
                    minUsage = settings.weeklyResetMinUsage,
                    onOnlyIfUsedChange = { on -> onChange { it.copy(weeklyResetOnlyIfUsed = on) } },
                    onMinUsageChange = { v -> onChange { it.copy(weeklyResetMinUsage = v) } },
                )
            }
            TriggerRow(
                title = "Approaching limit",
                description = "When any limit passes your warning line",
                checked = settings.approachingLimit,
                onCheckedChange = { on -> onChange { it.copy(approachingLimit = on) } },
            )
            AnimatedVisibility(visible = settings.approachingLimit) {
                PercentRow(
                    label = "Warn at",
                    value = settings.thresholdPercent,
                    valueRange = 50f..99f,
                    onValueChange = { v -> onChange { it.copy(thresholdPercent = v) } },
                )
            }
            TriggerRow(
                title = "Limit reached",
                description = "When a limit is fully used",
                checked = settings.wallHit,
                onCheckedChange = { on -> onChange { it.copy(wallHit = on) } },
            )

            Spacer(Modifier.height(32.dp))

            Text(
                "Relay",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            TextButton(
                onClick = { confirmUnlink = true },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text("Unlink this phone", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("Unlink this phone?") },
            text = {
                Text(
                    "Headroom will forget your relay's address and key, and stop " +
                        "updating. You can link again by scanning a new code.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlink = false
                    onUnlink()
                }) { Text("Unlink") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnlink = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TriggerRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(description, style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        // The whole row is the target, not just the switch.
        modifier = modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

/**
 * "Only after heavy use", nested under a reset row.
 *
 * Indented so it reads as a qualifier of the reset above it rather than a
 * fifth trigger. The line is per reset because a session and a week that both
 * ended at 70% are not the same news.
 */
@Composable
private fun ResetGate(
    description: String,
    onlyIfUsed: Boolean,
    minUsage: Double,
    onOnlyIfUsedChange: (Boolean) -> Unit,
    onMinUsageChange: (Double) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 16.dp)) {
        TriggerRow(
            title = "Only after heavy use",
            description = description,
            checked = onlyIfUsed,
            onCheckedChange = onOnlyIfUsedChange,
        )
        AnimatedVisibility(visible = onlyIfUsed) {
            PercentRow(
                label = "Used at least",
                value = minUsage,
                // 100 is "only after I ran out", which is a real choice here
                // in a way it is not for the warning line.
                valueRange = 10f..100f,
                onValueChange = onMinUsageChange,
            )
        }
    }
}

@Composable
private fun PercentRow(
    label: String,
    value: Double,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Double) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${value.toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = valueRange,
            // Continuous: a step per percent draws a tick mark per percent,
            // which turns a quiet row into a dotted rule. The view model's
            // clamps already round to a whole percent, so ticks bought nothing.
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
