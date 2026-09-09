package dev.andrii.headroom.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.andrii.headroom.notify.NotificationCoordinator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Runs one coordinator cycle: the 20-minute periodic poll, and the one-shot
 * runs from boot or from a reset alarm whose own attempt failed (spec §6).
 */
class PollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val coordinator: NotificationCoordinator by inject()

    override suspend fun doWork(): Result {
        return try {
            coordinator.runCycle()
            Result.success()
        } catch (_: Exception) {
            // The coordinator already handles its own expected failures; this
            // is the belt-and-braces path, and retrying is safe because the
            // cycle is idempotent.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "headroom-poll"

        /** 20 minutes clears WorkManager's 15-minute floor with margin. */
        fun enqueuePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PollWorker>(20, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
        }

        /**
         * Expedited, because both callers are late already: an alarm whose own
         * attempt failed, and a boot that has no alarm scheduled until this
         * runs.
         */
        fun enqueueOnce(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<PollWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }
    }
}

/**
 * Fired at a window's reset, and runs the cycle itself.
 *
 * Not handed to WorkManager, tempting as that looks: the alarm allowlists the
 * app for a few seconds, but a job enqueued from inside Doze is precisely what
 * Doze defers, and a reset deferred to the next unlock is a reset nobody was
 * told about. `goAsync` keeps those seconds, which one relay request fits
 * inside; the worker is the fallback for when it does not.
 */
class AlarmReceiver : BroadcastReceiver(), KoinComponent {

    private val coordinator: NotificationCoordinator by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(RECEIVER_BUDGET_MILLIS) { coordinator.runCycle() }
            } catch (_: Exception) {
                PollWorker.enqueueOnce(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /**
         * Under the ~10 seconds a broadcast is allowed, with room for the
         * fallback to be enqueued before the system stops waiting.
         */
        const val RECEIVER_BUDGET_MILLIS = 8_000L
    }
}

/**
 * Alarms do not survive a reboot, so poll once on boot: that fetch reschedules
 * the next alarm as a side effect (spec §6).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        PollWorker.enqueueOnce(context)
    }
}

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {

    private val manager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(atEpochSeconds: Long) {
        val triggerAtMillis = atEpochSeconds * 1_000
        val intent = pendingIntent()
        // Spec §6: exact where permitted, degrade to inexact rather than
        // dropping reset notifications entirely.
        if (manager.canScheduleExactAlarms()) {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, intent),
                intent,
            )
        } else {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, intent,
            )
        }
    }

    override fun cancel() {
        manager.cancel(pendingIntent())
    }

    fun canScheduleExact(): Boolean = manager.canScheduleExactAlarms()

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_CODE = 100
    }
}
