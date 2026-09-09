package dev.andrii.headroom.notify

import dev.andrii.headroom.data.NotLinkedException
import dev.andrii.headroom.data.RelayRejectedException
import dev.andrii.headroom.data.SnapshotCache
import dev.andrii.headroom.data.UsageFetchException
import dev.andrii.headroom.domain.NotificationEvent
import dev.andrii.headroom.domain.TriggerEvaluator
import dev.andrii.headroom.domain.TriggerSettings
import dev.andrii.headroom.domain.UsageSnapshot
import dev.andrii.headroom.schedule.AlarmScheduler
import dev.andrii.headroom.schedule.nextAlarmAt

data class CycleResult(
    val notified: List<NotificationEvent>,
    val needsNewCode: Boolean,
    val nextAlarmAt: Long?,
)

/**
 * The whole pipeline in one place: fetch, evaluate, record, notify, reschedule.
 *
 * Every collaborator is injected, so the pipeline — including its failure
 * paths and its de-duplication across cycles — is unit-tested without an
 * emulator.
 */
class NotificationCoordinator(
    private val fetch: suspend () -> UsageSnapshot,
    private val evaluator: TriggerEvaluator,
    private val log: NotificationLog,
    private val notifier: Notifier,
    private val alarmScheduler: AlarmScheduler,
    private val settings: suspend () -> TriggerSettings,
    private val cache: SnapshotCache,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    suspend fun runCycle(): CycleResult {
        val previous = cache.load()
        val current = try {
            fetch()
        } catch (_: NotLinkedException) {
            // Nothing to say: the UI already shows the link prompt.
            return CycleResult(emptyList(), needsNewCode = false, nextAlarmAt = null)
        } catch (e: RelayRejectedException) {
            // Spec §7: never silent.
            notifier.notifyRelayRejected(
                e.message ?: "Your relay would not accept this phone's key. Scan a new code.",
            )
            return CycleResult(emptyList(), needsNewCode = true, nextAlarmAt = null)
        } catch (_: UsageFetchException) {
            // Transient. The cached snapshot and its age stay on screen.
            return CycleResult(emptyList(), needsNewCode = false, nextAlarmAt = null)
        }

        val currentSettings = settings()
        val events = evaluator.evaluate(
            previous = previous,
            current = current,
            settings = currentSettings,
            alreadyFired = log.fired(),
            nowEpochSeconds = now(),
            lastCycleAt = log.lastCycleAt(),
        )
        events.forEach(notifier::notify)
        log.record(events.map { it.key })
        cache.store(current)
        // Below the fetch, and it belongs there: a cycle that failed to read
        // anything must not move the baseline past a rollover nobody saw.
        log.recordCycle(now())

        val next = nextAlarmAt(current, currentSettings, now())
        if (next != null) alarmScheduler.schedule(next) else alarmScheduler.cancel()

        log.prune(beforeResetsAt = now() - TriggerEvaluator.LONGEST_WINDOW)

        return CycleResult(events, needsNewCode = false, nextAlarmAt = next)
    }
}
