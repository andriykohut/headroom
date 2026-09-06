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
 * Four triggers and the number that tunes one of them.
 *
 * The threshold lives inside the row it belongs to rather than in a section of
 * its own — a setting beside the trigger it affects is what stops this reading
 * as a form.
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
            TriggerRow(
                title = "Weekly reset",
                description = "When a weekly window rolls over, per model",
                checked = settings.weeklyReset,
                onCheckedChange = { on -> onChange { it.copy(weeklyReset = on) } },
            )
            TriggerRow(
                title = "Approaching limit",
                description = "When any limit passes your warning line",
                checked = settings.approachingLimit,
                onCheckedChange = { on -> onChange { it.copy(approachingLimit = on) } },
            )
            AnimatedVisibility(visible = settings.approachingLimit) {
                ThresholdRow(
                    value = settings.thresholdPercent,
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
                "Linked account",
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
                    "Headroom will forget the saved credential and stop updating. " +
                        "You can link again by scanning a new code.",
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
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(description, style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        // The whole row is the target, not just the switch.
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

@Composable
private fun ThresholdRow(value: Double, onValueChange: (Double) -> Unit) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Warn at",
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
            valueRange = 50f..99f,
            // Continuous: 48 steps draws 48 tick marks, which turns a quiet
            // row into a dotted rule. clampThreshold already rounds the value
            // to a whole percent, so the ticks bought nothing.
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
