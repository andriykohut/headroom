package dev.andrii.headroom

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.andrii.headroom.notify.NotificationCoordinator
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Runs one coordinator cycle immediately.
 *
 * Debug source set only. The real trigger is a 20-minute periodic worker or a
 * reset alarm, neither of which is a reasonable thing to wait for when
 * checking that notifications fire and then de-duplicate.
 *
 * adb shell am start -n dev.andrii.headroom/.DebugPollActivity
 */
class DebugPollActivity : ComponentActivity() {

    private val coordinator: NotificationCoordinator by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val summary = runCatching {
                val result = coordinator.runCycle()
                "cycle: ${result.notified.size} notified, " +
                    "relink=${result.relinkNeeded}, next=${result.nextAlarmAt}"
            }.getOrElse { "cycle failed: ${it::class.simpleName}" }
            // Written as well as toasted: a toast cannot be read back by a
            // script, and the point of the second run is comparing results.
            java.io.File(cacheDir, "last_cycle.txt").writeText(summary)
            Toast.makeText(this@DebugPollActivity, summary, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
