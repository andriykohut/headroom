package dev.andrii.headroom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.andrii.headroom.ui.UsageScreen
import dev.andrii.headroom.ui.UsageViewModel
import dev.andrii.headroom.ui.theme.HeadroomTheme
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeadroomTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: UsageViewModel = koinViewModel()
                    val state by viewModel.state.collectAsState()
                    val settings by viewModel.settings.collectAsState()
                    UsageScreen(
                        state = state,
                        thresholdPercent = settings.thresholdPercent,
                        nowEpochSeconds = System.currentTimeMillis() / 1_000,
                        onRefresh = viewModel::refresh,
                        onLink = { /* Import screen arrives in Task 15 */ },
                        onOpenSettings = { /* Settings screen arrives in Task 16 */ },
                    )
                }
            }
        }
    }
}
