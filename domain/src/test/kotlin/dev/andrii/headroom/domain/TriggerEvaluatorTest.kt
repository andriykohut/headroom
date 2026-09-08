package dev.andrii.headroom.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriggerEvaluatorTest {

    private val evaluator = TriggerEvaluator()
    private val settings = TriggerSettings()

    private fun bucket(
        kind: BucketKind,
        utilization: Double = 10.0,
        resetsAt: Long = 2_000,
        scopeLabel: String = "",
    ) = LimitBucket(
        kind = kind,
        rawKind = kind.wireName,
        title = if (scopeLabel.isBlank()) kind.title else "Current week ($scopeLabel)",
        utilization = utilization,
        resetsAt = resetsAt,
        group = if (kind == BucketKind.SESSION) "session" else "weekly",
        scopeLabel = scopeLabel,
    )

    private fun snapshot(vararg buckets: LimitBucket, at: Long = 1_000) =
        UsageSnapshot(buckets.toList(), at)

    private fun evaluate(
        previous: UsageSnapshot?,
        current: UsageSnapshot,
        now: Long,
        fired: Set<EventKey> = emptySet(),
        settings: TriggerSettings = this.settings,
    ) = evaluator.evaluate(previous, current, settings, fired, now)

    // --- reset ---

    @Test
    fun `session reset fires once the window has rolled over`() {
        val current = snapshot(bucket(BucketKind.SESSION, resetsAt = 2_000))
        val events = evaluate(null, current, now = 2_000)
        assertEquals(listOf(TriggerType.SESSION_RESET), events.map { it.key.type })
    }

    @Test
    fun `session reset does not fire before the reset time`() {
        val current = snapshot(bucket(BucketKind.SESSION, resetsAt = 2_000))
        assertTrue(evaluate(null, current, now = 1_999).isEmpty())
    }

    @Test
    fun `session reset does not fire twice for the same window`() {
        val current = snapshot(bucket(BucketKind.SESSION, resetsAt = 2_000))
        val key = EventKey("session", 2_000, TriggerType.SESSION_RESET)
        assertTrue(evaluate(null, current, now = 2_500, fired = setOf(key)).isEmpty())
    }

    @Test
    fun `weekly reset fires for the weekly group`() {
        val current = snapshot(bucket(BucketKind.WEEKLY_ALL, resetsAt = 2_000))
        assertEquals(
            listOf(TriggerType.WEEKLY_RESET),
            evaluate(null, current, now = 2_000).map { it.key.type },
        )
    }

    @Test
    fun `per model weekly buckets are keyed separately`() {
        // Both arrive as kind "weekly_scoped" with the same reset time. Keying
        // on rawKind alone would collapse them and silence one model.
        val current = snapshot(
            bucket(BucketKind.WEEKLY_SCOPED, resetsAt = 2_000, scopeLabel = "Opus"),
            bucket(BucketKind.WEEKLY_SCOPED, resetsAt = 2_000, scopeLabel = "Sonnet"),
        )
        val events = evaluate(null, current, now = 2_000)
        assertEquals(2, events.size)
        assertEquals(2, events.map { it.key }.toSet().size)
    }

    @Test
    fun `clock skew does not fire a reset for a long past window`() {
        // Spec §7: never fire for a resetsAt more than one window length in the past.
        val current = snapshot(bucket(BucketKind.SESSION, resetsAt = 1_000))
        assertTrue(evaluate(null, current, now = 1_000 + 18_000 + 1).isEmpty())
    }

    @Test
    fun `a reset just inside the window length still fires`() {
        val current = snapshot(bucket(BucketKind.SESSION, resetsAt = 1_000))
        assertTrue(evaluate(null, current, now = 1_000 + 18_000 - 1).isNotEmpty())
    }

    @Test
    fun `a weekly window gets the weekly tolerance, not the session one`() {
        val current = snapshot(bucket(BucketKind.WEEKLY_ALL, resetsAt = 1_000))
        assertTrue(evaluate(null, current, now = 1_000 + 18_000 + 1).isNotEmpty())
    }

    // --- threshold ---

    @Test
    fun `approaching limit fires on the upward crossing`() {
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 80.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 91.0))
        assertEquals(
            listOf(TriggerType.APPROACHING_LIMIT),
            evaluate(previous, current, now = 1_500).map { it.key.type },
        )
    }

    @Test
    fun `approaching limit still fires when the previous reading was also above`() {
        // Not a crossing check: the UI's refresh writes to the same cache the
        // coordinator reads as `previous`, so requiring a crossing meant a user
        // who opened the app while over their line never got told. The log is
        // what makes this once-per-window - see the next test.
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 95.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 96.0))
        assertEquals(
            listOf(TriggerType.APPROACHING_LIMIT),
            evaluate(previous, current, now = 1_500).map { it.key.type },
        )
    }

    @Test
    fun `approaching limit does not fire again once the window has been notified`() {
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 95.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 96.0))
        val key = EventKey("session", 2_000, TriggerType.APPROACHING_LIMIT)
        assertTrue(evaluate(previous, current, now = 1_500, fired = setOf(key)).isEmpty())
    }

    @Test
    fun `lowering the threshold below current usage notifies rather than staying quiet`() {
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 70.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 70.0))
        val events = evaluate(
            previous, current, now = 1_500,
            settings = settings.copy(thresholdPercent = 53.0),
        )
        assertEquals(listOf(TriggerType.APPROACHING_LIMIT), events.map { it.key.type })
    }

    @Test
    fun `approaching limit does not fire below the threshold`() {
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 10.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 89.9))
        assertTrue(evaluate(previous, current, now = 1_500).isEmpty())
    }

    @Test
    fun `approaching limit fires with no previous snapshot if already above`() {
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 95.0))
        assertEquals(
            listOf(TriggerType.APPROACHING_LIMIT),
            evaluate(null, current, now = 1_500).map { it.key.type },
        )
    }

    @Test
    fun `approaching limit respects a custom threshold`() {
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 40.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 55.0))
        val events = evaluate(
            previous, current, now = 1_500,
            settings = settings.copy(thresholdPercent = 50.0),
        )
        assertEquals(listOf(TriggerType.APPROACHING_LIMIT), events.map { it.key.type })
    }

    @Test
    fun `approaching limit fires at most once per window`() {
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 80.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 95.0))
        val key = EventKey("session", 2_000, TriggerType.APPROACHING_LIMIT)
        assertTrue(evaluate(previous, current, now = 1_500, fired = setOf(key)).isEmpty())
    }

    // --- wall ---

    @Test
    fun `wall hit fires on the crossing to fully used`() {
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 97.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 100.0))
        assertTrue(evaluate(previous, current, now = 1_500)
            .any { it.key.type == TriggerType.WALL_HIT })
    }

    @Test
    fun `wall hit does not re-fire while still fully used`() {
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 100.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 100.0))
        assertTrue(evaluate(previous, current, now = 1_500)
            .none { it.key.type == TriggerType.WALL_HIT })
    }

    @Test
    fun `wall hit does not claim requests are being rejected`() {
        // Reaching 100% used is observable; rejection is not - the app never
        // makes a request that could be refused. The copy must not assert it.
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 50.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 100.0, resetsAt = 9_999))
        val event = evaluate(previous, current, now = 1_500)
            .first { it.key.type == TriggerType.WALL_HIT }
        assertTrue(event.body.isNotBlank())
        assertTrue("reject" !in event.body.lowercase(), "body claimed rejection: ${event.body}")
    }

    @Test
    fun `crossing straight past the threshold to the wall reports both`() {
        val previous = snapshot(bucket(BucketKind.SESSION, utilization = 10.0))
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 100.0))
        val types = evaluate(previous, current, now = 1_500).map { it.key.type }.toSet()
        assertEquals(setOf(TriggerType.APPROACHING_LIMIT, TriggerType.WALL_HIT), types)
    }

    // --- settings gates ---

    @Test
    fun `disabled triggers produce nothing`() {
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 100.0, resetsAt = 2_000))
        val off = TriggerSettings(false, false, false, false)
        assertTrue(evaluate(null, current, now = 2_000, settings = off).isEmpty())
    }

    @Test
    fun `unknown buckets never trigger`() {
        val unknown = LimitBucket(BucketKind.UNKNOWN, "future", "future", 100.0, 2_000)
        assertTrue(evaluate(null, snapshot(unknown), now = 2_000).isEmpty())
    }

    @Test
    fun `evaluation is idempotent when its own output is fed back as fired`() {
        val current = snapshot(bucket(BucketKind.SESSION, utilization = 95.0, resetsAt = 2_000))
        val first = evaluate(null, current, now = 2_000)
        assertTrue(first.isNotEmpty())
        val second = evaluate(null, current, now = 2_000, fired = first.map { it.key }.toSet())
        assertTrue(second.isEmpty(), "re-running with prior keys must produce nothing")
    }

    @Test
    fun `a session reset says what is left of the week`() {
        // The headline event arrives at 02:14 with no context. Whether the
        // fresh session is worth spending depends on the week, and the
        // evaluator is holding that figure already.
        val events = evaluate(
            previous = null,
            current = snapshot(
                bucket(BucketKind.SESSION, resetsAt = 900),
                bucket(BucketKind.WEEKLY_ALL, utilization = 61.0, resetsAt = 500_000),
                at = 1_000,
            ),
            now = 1_000,
        )
        val reset = events.single { it.key.type == TriggerType.SESSION_RESET }
        assertTrue(reset.body.contains("61%"), "no weekly figure in: ${reset.body}")
    }

    @Test
    fun `a session reset with no weekly window still notifies`() {
        val events = evaluate(
            previous = null,
            current = snapshot(bucket(BucketKind.SESSION, resetsAt = 900), at = 1_000),
            now = 1_000,
        )
        assertEquals(1, events.count { it.key.type == TriggerType.SESSION_RESET })
    }
}
