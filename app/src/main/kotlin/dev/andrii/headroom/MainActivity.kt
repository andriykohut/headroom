package dev.andrii.headroom

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.andrii.headroom.ui.ImportScreen
import dev.andrii.headroom.ui.SettingsScreen
import dev.andrii.headroom.ui.SettingsViewModel
import dev.andrii.headroom.ui.UsageScreen
import dev.andrii.headroom.ui.UsageViewModel
import dev.andrii.headroom.ui.theme.HeadroomTheme
import org.koin.androidx.compose.koinViewModel

private enum class Screen { USAGE, IMPORT, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeadroomTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HeadroomRoot()
                }
            }
        }
    }
}

/**
 * Asks for the permission the whole app depends on.
 *
 * Denied, `NotificationManagerCompat` drops every post without throwing, so a
 * reset, a threshold and the wall all pass silently while every screen in the
 * app keeps showing the right numbers. Nothing else here would reveal it.
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun HeadroomRoot() {
    RequestNotificationPermission()

    // Saveable so a rotation does not throw the user back to the Usage screen
    // mid-paste. Three screens do not justify a navigation library.
    var screen by rememberSaveable { mutableStateOf(Screen.USAGE) }

    val usageViewModel: UsageViewModel = koinViewModel()
    val state by usageViewModel.state.collectAsState()
    val usageSettings by usageViewModel.settings.collectAsState()

    // Back returns to Usage rather than leaving the app, which is what a user
    // who opened Settings expects.
    BackHandler(enabled = screen != Screen.USAGE) { screen = Screen.USAGE }

    when (screen) {
        Screen.USAGE -> UsageScreen(
            state = state,
            thresholdPercent = usageSettings.thresholdPercent,
            nowEpochSeconds = System.currentTimeMillis() / 1_000,
            onRefresh = usageViewModel::refresh,
            onLink = { screen = Screen.IMPORT },
            onOpenSettings = { screen = Screen.SETTINGS },
        )

        Screen.IMPORT -> ImportScreen(
            onLinked = { credential ->
                usageViewModel.link(credential)
                screen = Screen.USAGE
            },
            onCancel = { screen = Screen.USAGE },
        )

        Screen.SETTINGS -> {
            val settingsViewModel: SettingsViewModel = koinViewModel()
            val settings by settingsViewModel.settings.collectAsState()
            SettingsScreen(
                settings = settings,
                onChange = settingsViewModel::update,
                onUnlink = {
                    settingsViewModel.unlink()
                    usageViewModel.refresh()
                    screen = Screen.USAGE
                },
                onBack = { screen = Screen.USAGE },
            )
        }
    }
}
