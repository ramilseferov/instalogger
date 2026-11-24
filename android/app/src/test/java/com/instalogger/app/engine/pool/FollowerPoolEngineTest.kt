package com.instalogger.app.engine.pool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowerPoolEngineTest {

    @Test
    fun resolveEffectiveThreshold_handlesAllModesAccurately() {
        assertEquals(1L, FollowerPoolEngine.resolveEffectiveThreshold(FollowerPoolMode.OFF, 50L, 1.0, 100_000L))
        assertEquals(75L, FollowerPoolEngine.resolveEffectiveThreshold(FollowerPoolMode.FIXED_COUNT, 75L, 1.0, 100_000L))
        assertEquals(500L, FollowerPoolEngine.resolveEffectiveThreshold(FollowerPoolMode.PERCENTAGE, 50L, 0.5, 100_000L))

        // Auto-adaptive tiers
        assertEquals(1L, FollowerPoolEngine.resolveEffectiveThreshold(FollowerPoolMode.AUTO_ADAPTIVE, 50L, 0.5, 450L))
        assertEquals(10L, FollowerPoolEngine.resolveEffectiveThreshold(FollowerPoolMode.AUTO_ADAPTIVE, 50L, 0.5, 4_200L))
        assertEquals(50L, FollowerPoolEngine.resolveEffectiveThreshold(FollowerPoolMode.AUTO_ADAPTIVE, 50L, 0.5, 45_000L))
        assertEquals(250L, FollowerPoolEngine.resolveEffectiveThreshold(FollowerPoolMode.AUTO_ADAPTIVE, 50L, 0.5, 250_000L))
        assertEquals(1_000L, FollowerPoolEngine.resolveEffectiveThreshold(FollowerPoolMode.AUTO_ADAPTIVE, 50L, 0.5, 2_500_000L))
        assertEquals(5_000L, FollowerPoolEngine.resolveEffectiveThreshold(FollowerPoolMode.AUTO_ADAPTIVE, 50L, 0.5, 15_000_000L))
    }

    @Test
    fun classicMode_instantAlertAndAntiFlickerHysteresis() {
        val now = 1_000_000L
        val classicNoFlicker = FollowerPoolPolicy(mode = FollowerPoolMode.OFF, antiFlickerEnabled = false)
        val resInstant = FollowerPoolEngine.evaluateChange(
            state = PoolMetricState(baseline = 1_000L, startedAt = now - 60_000L),
            previousCount = 1_000L,
            newCount = 1_001L,
            policy = classicNoFlicker,
            nowMs = now
        )
        assertEquals(PoolAction.INSTANT_ALERT, resInstant.action)
        assertTrue(resInstant.shouldDispatchNotification)
        assertTrue(resInstant.shouldWriteAuditLog)

        // With anti-flicker enabled, +/-1 requires a second confirmation poll
        val classicWithFlicker = FollowerPoolPolicy(mode = FollowerPoolMode.OFF, antiFlickerEnabled = true)
        val poll1 = FollowerPoolEngine.evaluateChange(
            state = PoolMetricState(baseline = 1_000L, startedAt = now - 60_000L),
            previousCount = 1_000L,
            newCount = 999L,
            policy = classicWithFlicker,
            nowMs = now
        )
        assertEquals(PoolAction.SUPPRESSED_FLICKER, poll1.action)
        assertFalse(poll1.shouldDispatchNotification)
        assertEquals(999L, poll1.updatedState.pendingFlickerCount)

        // Second poll confirms 999 -> fires alert
        val poll2 = FollowerPoolEngine.evaluateChange(
            state = poll1.updatedState,
            previousCount = 1_000L,
            newCount = 999L,
            policy = classicWithFlicker,
            nowMs = now + 60_000L
        )
        assertEquals(PoolAction.INSTANT_ALERT, poll2.action)
        assertTrue(poll2.shouldDispatchNotification)
        assertEquals(-1L, poll2.netDelta)
    }

    @Test
    fun fixedPool_accumulatesThenFlushesAtThresholdAndCancelsOscillation() {
        val t0 = 10_000_000L
        val policy = FollowerPoolPolicy(
            mode = FollowerPoolMode.FIXED_COUNT,
            fixedThreshold = 50L,
            antiFlickerEnabled = true,
            spikeAlertEnabled = true,
            suppressMinorAuditLogs = true
        )
        val init = PoolMetricState(baseline = 50_000L, startedAt = t0)

        // Poll 1: +20 -> accumulate silently
        val r1 = FollowerPoolEngine.evaluateChange(init, 50_000L, 50_020L, policy, t0 + 10_000L)
        assertEquals(PoolAction.ACCUMULATE, r1.action)
        assertFalse(r1.shouldDispatchNotification)
        assertFalse(r1.shouldWriteAuditLog)
        assertEquals(20L, r1.netDelta)
        assertEquals(20L, r1.updatedState.grossGain)

        // Poll 2: drops back to 50_000 -> CDN oscillation cancelled
        val rOsc = FollowerPoolEngine.evaluateChange(r1.updatedState, 50_020L, 50_000L, policy, t0 + 20_000L)
        assertEquals(PoolAction.SUPPRESSED_FLICKER, rOsc.action)
        assertEquals(0L, rOsc.updatedState.grossGain)
        assertEquals(0L, rOsc.updatedState.grossLoss)

        // Poll 3: +30 -> accumulate
        val r3 = FollowerPoolEngine.evaluateChange(rOsc.updatedState, 50_000L, 50_030L, policy, t0 + 30_000L)
        assertEquals(PoolAction.ACCUMULATE, r3.action)

        // Poll 4: +25 more (total +55 >= 50) -> FLUSH_THRESHOLD
        val r4 = FollowerPoolEngine.evaluateChange(r3.updatedState, 50_030L, 50_055L, policy, t0 + 40_000L)
        assertEquals(PoolAction.FLUSH_THRESHOLD, r4.action)
        assertTrue(r4.shouldDispatchNotification)
        assertTrue(r4.shouldWriteAuditLog)
        assertEquals(55L, r4.netDelta)
        assertEquals(55L, r4.grossGain)
        assertEquals(50_055L, r4.updatedState.baseline)
        assertEquals(0L, r4.updatedState.grossGain)
    }

    @Test
    fun digestWindowCooldownAndSpikeBreakthroughWorkCorrectly() {
        val t0 = 50_000_000L
        val policy = FollowerPoolPolicy(
            mode = FollowerPoolMode.FIXED_COUNT,
            fixedThreshold = 100L,
            digestWindowMinutes = 60,
            cooldownMinutes = 15,
            spikeAlertEnabled = true,
            spikeMultiplier = 3.0,
            suppressMinorAuditLogs = false
        )

        // Accumulate +35 at t0 + 10m
        val r1 = FollowerPoolEngine.evaluateChange(
            PoolMetricState(baseline = 20_000L, startedAt = t0),
            20_000L,
            20_035L,
            policy,
            t0 + 10 * 60_000L
        )
        assertEquals(PoolAction.ACCUMULATE, r1.action)
        assertTrue(r1.shouldWriteAuditLog) // suppressMinorAuditLogs = false
        assertFalse(r1.shouldDispatchNotification)

        // After 61 minutes with same count -> FLUSH_DIGEST
        val rDigest = FollowerPoolEngine.evaluateChange(
            r1.updatedState,
            20_035L,
            20_035L,
            policy,
            t0 + 61 * 60_000L
        )
        assertEquals(PoolAction.FLUSH_DIGEST, rDigest.action)
        assertTrue(rDigest.shouldDispatchNotification)
        assertEquals(35L, rDigest.netDelta)

        // 5 minutes later (inside 15m cooldown), +110 crosses threshold -> SUPPRESSED_COOLDOWN
        val rCool = FollowerPoolEngine.evaluateChange(
            rDigest.updatedState,
            20_035L,
            20_145L,
            policy,
            t0 + 66 * 60_000L
        )
        assertEquals(PoolAction.SUPPRESSED_COOLDOWN, rCool.action)
        assertFalse(rCool.shouldDispatchNotification)

        // Sudden +350 jump (>= 3x threshold of 100) breaks through cooldown -> FLUSH_SPIKE
        val rSpike = FollowerPoolEngine.evaluateChange(
            rCool.updatedState,
            20_145L,
            20_495L,
            policy,
            t0 + 67 * 60_000L
        )
        assertEquals(PoolAction.FLUSH_SPIKE, rSpike.action)
        assertTrue(rSpike.shouldDispatchNotification)
        assertEquals(460L, rSpike.netDelta)
        assertEquals(20_495L, rSpike.updatedState.baseline)
    }

    @Test
    fun manualFlush_resetsBaselineAndReturnsDelta() {
        val state = PoolMetricState(baseline = 12_000L, startedAt = 1_000L, grossGain = 40L, grossLoss = 12L)
        val (delta, reset) = FollowerPoolEngine.manualFlush(state, currentCount = 12_028L, nowMs = 5_000L)
        assertEquals(28L, delta)
        assertEquals(12_028L, reset.baseline)
        assertEquals(0L, reset.grossGain)
        assertEquals(0L, reset.grossLoss)
        assertEquals(5_000L, reset.lastAlertAt)
    }

    @Test
    fun listCompletenessGuard_blocksFalseLossesOnPartialAndEmptySlices() {
        // 50 fetched out of 15,000 -> PARTIAL, block loss/gain spam
        val partial = ListCompletenessGuard.evaluate(fetchedCount = 50, reportedTotalCount = 15_000L, guardEnabled = true)
        assertEquals(ListCompletenessStatus.PARTIAL, partial.status)
        assertFalse(partial.allowLossAlerts)
        assertFalse(partial.allowGainAlerts)

        // 0 fetched out of 500 -> EMPTY_UNEXPECTED, do not overwrite archive
        val empty = ListCompletenessGuard.evaluate(fetchedCount = 0, reportedTotalCount = 500L, guardEnabled = true)
        assertEquals(ListCompletenessStatus.EMPTY_UNEXPECTED, empty.status)
        assertFalse(empty.allowArchiveOverwrite)

        // 48 fetched out of 50 -> COMPLETE, allow exact diff
        val complete = ListCompletenessGuard.evaluate(fetchedCount = 48, reportedTotalCount = 50L, guardEnabled = true)
        assertEquals(ListCompletenessStatus.COMPLETE, complete.status)
        assertTrue(complete.allowGainAlerts)
        assertTrue(complete.allowLossAlerts)

        // Guard disabled -> user opted into raw diffing
        val raw = ListCompletenessGuard.evaluate(fetchedCount = 50, reportedTotalCount = 15_000L, guardEnabled = false)
        assertEquals(ListCompletenessStatus.COMPLETE, raw.status)
        assertTrue(raw.allowLossAlerts)
    }
}
