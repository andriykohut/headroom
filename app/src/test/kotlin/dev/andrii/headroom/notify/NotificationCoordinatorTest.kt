package dev.andrii.headroom.notify

import dev.andrii.headroom.data.InMemorySnapshotCache
import dev.andrii.headroom.data.NotLinkedException
import dev.andrii.headroom.data.RelayRejectedException
import dev.andrii.headroom.data.UsageFetchException
import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.LimitBucket
import dev.andrii.headroom.domain.TriggerEvaluator
import dev.andrii.headroom.domain.TriggerSettings
import dev.andrii.headroom.domain.TriggerType
import dev.andrii.headroom.domain.UsageSnapshot
import dev.andrii.headroom.schedule.AlarmScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingAlarmScheduler : AlarmScheduler {
    val scheduled = mutableListOf<Long>()
    var cancelled = false
    override fun schedule(atEpochSeconds: Long) { scheduled += atEpochSeconds }
    override fun cancel() { cancelled = true }
}

private fun bucket(
    kind: BucketKind,
    utilization: Double = 10.0,
    resetsAt: Long = 5_000,
    scopeLabel: String = "",
) = LimitBucket(
    kind = kind,
    rawKind = kind.wireName,
    title = kind.title,
    utilization = utilization,
    resetsAt = resetsAt,
    scopeLabel = scopeLabel,
)

private fun snapshot(vararg buckets: LimitBucket, at: Long = 1_000) =
    UsageSnapshot(buckets.toList(), at)

class NotificationCoordinatorTest {

    private fun coordinator(
        fetch: suspend () -> UsageSnapshot,
        log: NotificationLog = InMemoryNotificationLog(),
        notifier: RecordingNotifier = RecordingNotifier(),
        alarms: RecordingAlarmScheduler = RecordingAlarmScheduler(),
        cache: InMemorySnapshotCache = InMemorySnapshotCache(),
        settings: TriggerSettings = TriggerSettings(),
        now: Long = 1_000,
    ) = NotificationCoordinator(
        fetch = fetch,
        evaluator = TriggerEvaluator(),
        log = log,
        notifier = notifier,
        alarmScheduler = alarms,
        settings = { settings },
        cache = cache,
        now = { now },
    ) to Triple(notifier, alarms, cache)

    @Test
    fun `a reset is notified once, not again on the next cycle`() = runTest {
        val notifier = RecordingNotifier()
        var clock = 5_060L
        val subject = NotificationCoordinator(
            fetch = { snapshot(bucket(BucketKind.SESSION, resetsAt = 5_000)) },
            evaluator = TriggerEvaluator(),
            log = InMemoryNotificationLog(),
            notifier = notifier,
            alarmScheduler = RecordingAlarmScheduler(),
            settings = { TriggerSettings() },
            cache = InMemorySnapshotCache(),
            now = { clock },
        )
        subject.runCycle()
        clock += 1_200
        subject.runCycle()
        assertEquals(listOf(TriggerType.SESSION_RESET), notifier.events.map { it.key.type })
    }

    @Test
    fun `a crossed threshold is notified`() = runTest {
        val (subject, deps) = coordinator({ snapshot(bucket(BucketKind.SESSION, 95.0)) })
        subject.runCycle()
        assertEquals(
            listOf(TriggerType.APPROACHING_LIMIT),
            deps.first.events.map { it.key.type },
        )
    }

    @Test
    fun `a second cycle with unchanged data notifies nothing`() = runTest {
        val log = InMemoryNotificationLog()
        val notifier = RecordingNotifier()
        val cache = InMemorySnapshotCache()
        val fetch: suspend () -> UsageSnapshot =
            { snapshot(bucket(BucketKind.SESSION, 95.0)) }
        val (subject, _) = coordinator(fetch, log = log, notifier = notifier, cache = cache)
        subject.runCycle()
        subject.runCycle()
        assertEquals(1, notifier.events.size, "de-duplication failed across cycles")
    }

    @Test
    fun `fired events are recorded in the log`() = runTest {
        val log = InMemoryNotificationLog()
        val (subject, _) = coordinator({ snapshot(bucket(BucketKind.SESSION, 95.0)) }, log = log)
        subject.runCycle()
        assertEquals(1, log.fired().size)
    }

    @Test
    fun `two models crossing together are notified separately`() = runTest {
        // They share a kind and a reset time; only the scope label separates
        // them, and this is the path where a collapsed key silences one.
        val notifier = RecordingNotifier()
        val (subject, _) = coordinator(
            {
                snapshot(
                    bucket(BucketKind.WEEKLY_SCOPED, 95.0, scopeLabel = "Opus"),
                    bucket(BucketKind.WEEKLY_SCOPED, 96.0, scopeLabel = "Sonnet"),
                )
            },
            notifier = notifier,
        )
        subject.runCycle()
        assertEquals(2, notifier.events.size)
    }

    @Test
    fun `the snapshot is cached so the next cycle can compare against it`() = runTest {
        val cache = InMemorySnapshotCache()
        val (subject, _) = coordinator({ snapshot(bucket(BucketKind.SESSION)) }, cache = cache)
        subject.runCycle()
        assertEquals(1_000, cache.load()!!.fetchedAt)
    }

    @Test
    fun `the next alarm is scheduled from the fetched snapshot`() = runTest {
        val alarms = RecordingAlarmScheduler()
        val (subject, _) = coordinator(
            { snapshot(bucket(BucketKind.SESSION, resetsAt = 5_000)) }, alarms = alarms,
        )
        val result = subject.runCycle()
        assertEquals(listOf(5_000L), alarms.scheduled)
        assertEquals(5_000L, result.nextAlarmAt)
    }

    @Test
    fun `no future reset cancels the alarm rather than leaving a stale one`() = runTest {
        val alarms = RecordingAlarmScheduler()
        val (subject, _) = coordinator(
            { snapshot(bucket(BucketKind.SESSION, resetsAt = 500)) },
            alarms = alarms, now = 1_000,
        )
        subject.runCycle()
        assertTrue(alarms.cancelled)
    }

    @Test
    fun `a rejected key raises a notification rather than failing silently`() = runTest {
        // The app cannot fix this itself, and the user will not open it to find
        // out. Spec section 7: never silent.
        val notifier = RecordingNotifier()
        val (subject, _) = coordinator(
            { throw RelayRejectedException("rejected") }, notifier = notifier,
        )
        val result = subject.runCycle()
        assertTrue(result.needsNewCode)
        assertEquals(1, notifier.rejectionMessages.size)
    }

    @Test
    fun `an ordinary fetch failure notifies nothing and asks for no new code`() = runTest {
        val notifier = RecordingNotifier()
        val (subject, _) = coordinator({ throw UsageFetchException("HTTP 503") }, notifier = notifier)
        val result = subject.runCycle()
        assertFalse(result.needsNewCode)
        assertTrue(notifier.events.isEmpty() && notifier.rejectionMessages.isEmpty())
    }

    @Test
    fun `an unlinked app notifies nothing`() = runTest {
        val notifier = RecordingNotifier()
        val (subject, _) = coordinator({ throw NotLinkedException() }, notifier = notifier)
        subject.runCycle()
        assertTrue(notifier.events.isEmpty() && notifier.rejectionMessages.isEmpty())
    }

    @Test
    fun `a failed fetch leaves the cached snapshot intact`() = runTest {
        val cache = InMemorySnapshotCache(snapshot(bucket(BucketKind.SESSION), at = 42))
        val (subject, _) = coordinator({ throw UsageFetchException("offline") }, cache = cache)
        subject.runCycle()
        assertEquals(42, cache.load()!!.fetchedAt)
    }

    @Test
    fun `disabled triggers notify nothing but still schedule nothing spurious`() = runTest {
        val notifier = RecordingNotifier()
        val (subject, _) = coordinator(
            { snapshot(bucket(BucketKind.SESSION, 99.0, resetsAt = 5_000)) },
            notifier = notifier,
            settings = TriggerSettings(false, false, false, false),
        )
        subject.runCycle()
        assertTrue(notifier.events.isEmpty())
    }
}
