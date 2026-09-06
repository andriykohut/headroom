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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.andrii.headroom.notify.NotificationCoordinator
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Runs one coordinator cycle. Used both for the 20-minute periodic poll and
 * for the one-shot runs triggered by a reset alarm or by boot (spec §6).
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

        fun enqueueOnce(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<PollWorker>().build(),
            )
        }
    }
}

/**
 * Fired at a window's reset. Hands off to the worker immediately: a receiver
 * has a few seconds of runtime, and a network fetch does not fit in it.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PollWorker.enqueueOnce(context)
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
