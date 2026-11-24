package com.instalogger.app.engine.pool

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Operating mode for the Follower/Following Change Pool.
 * OFF preserves classic instant notifications on every delta.
 */
enum class FollowerPoolMode(val key: String) {
    OFF("OFF"),
    FIXED_COUNT("FIXED_COUNT"),
    PERCENTAGE("PERCENTAGE"),
    AUTO_ADAPTIVE("AUTO_ADAPTIVE");

    companion object {
        fun fromKey(key: String?): FollowerPoolMode {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: OFF
        }
    }
}

/**
 * Per-target override wrapper. GLOBAL inherits AppSettingsManager defaults;
 * OFF forces classic instant mode for that target regardless of global policy.
 */
enum class TargetPoolOverride(val key: String) {
    GLOBAL("GLOBAL"),
    OFF("OFF"),
    FIXED_COUNT("FIXED_COUNT"),
    PERCENTAGE("PERCENTAGE"),
    AUTO_ADAPTIVE("AUTO_ADAPTIVE");

    companion object {
        fun fromKey(key: String?): TargetPoolOverride {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: GLOBAL
        }
    }
}

/**
 * Effective policy resolved from Global Defaults + Per-Target Overrides.
 */
data class FollowerPoolPolicy(
    val mode: FollowerPoolMode = FollowerPoolMode.OFF,
    val fixedThreshold: Long = 50L,
    val percentThreshold: Double = 0.5,
    val digestWindowMinutes: Int = 0,
    val cooldownMinutes: Int = 0,
    val antiFlickerEnabled: Boolean = true,
    val spikeAlertEnabled: Boolean = true,
    val spikeMultiplier: Double = 3.0,
    val suppressMinorAuditLogs: Boolean = true
)

/**
 * Persistent per-metric accumulator state stored on TargetEntity.
 */
data class PoolMetricState(
    val baseline: Long = 0L,
    val startedAt: Long = 0L,
    val lastAlertAt: Long = 0L,
    val grossGain: Long = 0L,
    val grossLoss: Long = 0L,
    val pendingFlickerCount: Long = -1L
)

enum class PoolAction {
    NONE,
    INSTANT_ALERT,
    ACCUMULATE,
    SUPPRESSED_FLICKER,
    SUPPRESSED_COOLDOWN,
    FLUSH_THRESHOLD,
    FLUSH_DIGEST,
    FLUSH_SPIKE
}

data class PoolEvaluationResult(
    val action: PoolAction,
    val updatedState: PoolMetricState,
    val baselineCount: Long,
    val newCount: Long,
    val netDelta: Long,
    val tickDelta: Long,
    val grossGain: Long,
    val grossLoss: Long,
    val effectiveThreshold: Long,
    val windowDurationMs: Long,
    val shouldWriteAuditLog: Boolean,
    val shouldDispatchNotification: Boolean
)

/**
 * Pure, deterministic accumulator engine for high-velocity follower/following changes.
 * Zero Android dependencies so every branch is exhaustively unit-testable.
 */
object FollowerPoolEngine {

    private const val FLICKER_MAX_DELTA = 2L
    private const val SPIKE_MIN_PERCENT = 0.05 // 5% sudden jump

    fun resolveEffectiveThreshold(
        mode: FollowerPoolMode,
        fixedThreshold: Long,
        percentThreshold: Double,
        referenceCount: Long
    ): Long {
        val safeCount = max(0L, referenceCount)
        return when (mode) {
            FollowerPoolMode.OFF -> 1L
            FollowerPoolMode.FIXED_COUNT -> max(1L, fixedThreshold)
            FollowerPoolMode.PERCENTAGE -> {
                val pct = percentThreshold.coerceIn(0.01, 100.0)
                max(1L, ceil(safeCount * (pct / 100.0)).toLong())
            }
            FollowerPoolMode.AUTO_ADAPTIVE -> when {
                safeCount < 1_000L -> 1L
                safeCount < 10_000L -> 10L
                safeCount < 100_000L -> 50L
                safeCount < 1_000_000L -> 250L
                safeCount < 10_000_000L -> 1_000L
                else -> 5_000L
            }
        }
    }

    fun evaluateChange(
        state: PoolMetricState,
        previousCount: Long,
        newCount: Long,
        policy: FollowerPoolPolicy,
        nowMs: Long
    ): PoolEvaluationResult {
        val tickDelta = newCount - previousCount
        val initialBaseline = if (state.baseline > 0L) state.baseline else previousCount
        val initialStartedAt = if (state.startedAt > 0L) state.startedAt else nowMs
        val effectiveThreshold = resolveEffectiveThreshold(
            mode = policy.mode,
            fixedThreshold = policy.fixedThreshold,
            percentThreshold = policy.percentThreshold,
            referenceCount = max(initialBaseline, newCount)
        )

        // 1. Classic Instant Mode (OFF)
        if (policy.mode == FollowerPoolMode.OFF) {
            if (tickDelta == 0L) {
                return PoolEvaluationResult(
                    action = PoolAction.NONE,
                    updatedState = state.copy(
                        baseline = newCount,
                        startedAt = nowMs,
                        grossGain = 0L,
                        grossLoss = 0L,
                        pendingFlickerCount = -1L
                    ),
                    baselineCount = previousCount,
                    newCount = newCount,
                    netDelta = 0L,
                    tickDelta = 0L,
                    grossGain = 0L,
                    grossLoss = 0L,
                    effectiveThreshold = 1L,
                    windowDurationMs = 0L,
                    shouldWriteAuditLog = false,
                    shouldDispatchNotification = false
                )
            }

            // Optional 1-poll CDN anti-flicker confirmation for +/-1..2 jitter in Classic mode
            if (policy.antiFlickerEnabled && abs(tickDelta) <= FLICKER_MAX_DELTA) {
                if (state.pendingFlickerCount != newCount) {
                    return PoolEvaluationResult(
                        action = PoolAction.SUPPRESSED_FLICKER,
                        updatedState = state.copy(
                            baseline = previousCount,
                            pendingFlickerCount = newCount
                        ),
                        baselineCount = previousCount,
                        newCount = newCount,
                        netDelta = tickDelta,
                        tickDelta = tickDelta,
                        grossGain = if (tickDelta > 0L) tickDelta else 0L,
                        grossLoss = if (tickDelta < 0L) abs(tickDelta) else 0L,
                        effectiveThreshold = 1L,
                        windowDurationMs = 0L,
                        shouldWriteAuditLog = false,
                        shouldDispatchNotification = false
                    )
                }
            }

            // Optional cooldown in Classic mode
            val cooldownMs = policy.cooldownMinutes * 60_000L
            val inCooldown = cooldownMs > 0L && state.lastAlertAt > 0L && (nowMs - state.lastAlertAt) < cooldownMs
            if (inCooldown) {
                return PoolEvaluationResult(
                    action = PoolAction.SUPPRESSED_COOLDOWN,
                    updatedState = state.copy(
                        baseline = previousCount,
                        grossGain = state.grossGain + if (tickDelta > 0L) tickDelta else 0L,
                        grossLoss = state.grossLoss + if (tickDelta < 0L) abs(tickDelta) else 0L,
                        pendingFlickerCount = -1L
                    ),
                    baselineCount = previousCount,
                    newCount = newCount,
                    netDelta = newCount - initialBaseline,
                    tickDelta = tickDelta,
                    grossGain = state.grossGain + if (tickDelta > 0L) tickDelta else 0L,
                    grossLoss = state.grossLoss + if (tickDelta < 0L) abs(tickDelta) else 0L,
                    effectiveThreshold = 1L,
                    windowDurationMs = nowMs - initialStartedAt,
                    shouldWriteAuditLog = !policy.suppressMinorAuditLogs,
                    shouldDispatchNotification = false
                )
            }

            return PoolEvaluationResult(
                action = PoolAction.INSTANT_ALERT,
                updatedState = PoolMetricState(
                    baseline = newCount,
                    startedAt = nowMs,
                    lastAlertAt = nowMs,
                    grossGain = 0L,
                    grossLoss = 0L,
                    pendingFlickerCount = -1L
                ),
                baselineCount = previousCount,
                newCount = newCount,
                netDelta = tickDelta,
                tickDelta = tickDelta,
                grossGain = if (tickDelta > 0L) tickDelta else 0L,
                grossLoss = if (tickDelta < 0L) abs(tickDelta) else 0L,
                effectiveThreshold = 1L,
                windowDurationMs = 0L,
                shouldWriteAuditLog = true,
                shouldDispatchNotification = true
            )
        }

        // 2. Pooled Modes (FIXED_COUNT / PERCENTAGE / AUTO_ADAPTIVE)
        val updatedGrossGain = state.grossGain + if (tickDelta > 0L) tickDelta else 0L
        val updatedGrossLoss = state.grossLoss + if (tickDelta < 0L) abs(tickDelta) else 0L
        val netDelta = newCount - initialBaseline
        val windowDurationMs = max(0L, nowMs - initialStartedAt)

        // If CDN oscillation returned count exactly to baseline, quietly reset gross counters if anti-flicker is ON
        if (netDelta == 0L) {
            val resetState = PoolMetricState(
                baseline = initialBaseline,
                startedAt = if (tickDelta != 0L && policy.antiFlickerEnabled) nowMs else initialStartedAt,
                lastAlertAt = state.lastAlertAt,
                grossGain = if (policy.antiFlickerEnabled) 0L else updatedGrossGain,
                grossLoss = if (policy.antiFlickerEnabled) 0L else updatedGrossLoss,
                pendingFlickerCount = -1L
            )
            return PoolEvaluationResult(
                action = if (tickDelta != 0L && policy.antiFlickerEnabled) PoolAction.SUPPRESSED_FLICKER else PoolAction.NONE,
                updatedState = resetState,
                baselineCount = initialBaseline,
                newCount = newCount,
                netDelta = 0L,
                tickDelta = tickDelta,
                grossGain = resetState.grossGain,
                grossLoss = resetState.grossLoss,
                effectiveThreshold = effectiveThreshold,
                windowDurationMs = windowDurationMs,
                shouldWriteAuditLog = false,
                shouldDispatchNotification = false
            )
        }

        // Check Spike / Anomaly Breakthrough (bypasses cooldown & digest)
        val spikeTrigger = if (policy.spikeAlertEnabled) {
            val multiplierThreshold = max(
                effectiveThreshold * policy.spikeMultiplier,
                10.0
            ).toLong()
            val percentJumpThreshold = max(25L, ceil(initialBaseline * SPIKE_MIN_PERCENT).toLong())
            abs(tickDelta) >= multiplierThreshold || abs(tickDelta) >= percentJumpThreshold
        } else {
            false
        }

        if (spikeTrigger) {
            return PoolEvaluationResult(
                action = PoolAction.FLUSH_SPIKE,
                updatedState = PoolMetricState(
                    baseline = newCount,
                    startedAt = nowMs,
                    lastAlertAt = nowMs,
                    grossGain = 0L,
                    grossLoss = 0L,
                    pendingFlickerCount = -1L
                ),
                baselineCount = initialBaseline,
                newCount = newCount,
                netDelta = netDelta,
                tickDelta = tickDelta,
                grossGain = updatedGrossGain,
                grossLoss = updatedGrossLoss,
                effectiveThreshold = effectiveThreshold,
                windowDurationMs = windowDurationMs,
                shouldWriteAuditLog = true,
                shouldDispatchNotification = true
            )
        }

        val cooldownMs = policy.cooldownMinutes * 60_000L
        val inCooldown = cooldownMs > 0L && state.lastAlertAt > 0L && (nowMs - state.lastAlertAt) < cooldownMs
        val thresholdReached = abs(netDelta) >= effectiveThreshold
        val digestMs = policy.digestWindowMinutes * 60_000L
        val digestDue = digestMs > 0L && windowDurationMs >= digestMs

        if ((thresholdReached || digestDue) && inCooldown) {
            return PoolEvaluationResult(
                action = PoolAction.SUPPRESSED_COOLDOWN,
                updatedState = PoolMetricState(
                    baseline = initialBaseline,
                    startedAt = initialStartedAt,
                    lastAlertAt = state.lastAlertAt,
                    grossGain = updatedGrossGain,
                    grossLoss = updatedGrossLoss,
                    pendingFlickerCount = -1L
                ),
                baselineCount = initialBaseline,
                newCount = newCount,
                netDelta = netDelta,
                tickDelta = tickDelta,
                grossGain = updatedGrossGain,
                grossLoss = updatedGrossLoss,
                effectiveThreshold = effectiveThreshold,
                windowDurationMs = windowDurationMs,
                shouldWriteAuditLog = tickDelta != 0L && !policy.suppressMinorAuditLogs,
                shouldDispatchNotification = false
            )
        }

        if (thresholdReached) {
            return PoolEvaluationResult(
                action = PoolAction.FLUSH_THRESHOLD,
                updatedState = PoolMetricState(
                    baseline = newCount,
                    startedAt = nowMs,
                    lastAlertAt = nowMs,
                    grossGain = 0L,
                    grossLoss = 0L,
                    pendingFlickerCount = -1L
                ),
                baselineCount = initialBaseline,
                newCount = newCount,
                netDelta = netDelta,
                tickDelta = tickDelta,
                grossGain = updatedGrossGain,
                grossLoss = updatedGrossLoss,
                effectiveThreshold = effectiveThreshold,
                windowDurationMs = windowDurationMs,
                shouldWriteAuditLog = true,
                shouldDispatchNotification = true
            )
        }

        if (digestDue) {
            return PoolEvaluationResult(
                action = PoolAction.FLUSH_DIGEST,
                updatedState = PoolMetricState(
                    baseline = newCount,
                    startedAt = nowMs,
                    lastAlertAt = nowMs,
                    grossGain = 0L,
                    grossLoss = 0L,
                    pendingFlickerCount = -1L
                ),
                baselineCount = initialBaseline,
                newCount = newCount,
                netDelta = netDelta,
                tickDelta = tickDelta,
                grossGain = updatedGrossGain,
                grossLoss = updatedGrossLoss,
                effectiveThreshold = effectiveThreshold,
                windowDurationMs = windowDurationMs,
                shouldWriteAuditLog = true,
                shouldDispatchNotification = true
            )
        }

        // Accumulate inside the open pool
        return PoolEvaluationResult(
            action = PoolAction.ACCUMULATE,
            updatedState = PoolMetricState(
                baseline = initialBaseline,
                startedAt = initialStartedAt,
                lastAlertAt = state.lastAlertAt,
                grossGain = updatedGrossGain,
                grossLoss = updatedGrossLoss,
                pendingFlickerCount = -1L
            ),
            baselineCount = initialBaseline,
            newCount = newCount,
            netDelta = netDelta,
            tickDelta = tickDelta,
            grossGain = updatedGrossGain,
            grossLoss = updatedGrossLoss,
            effectiveThreshold = effectiveThreshold,
            windowDurationMs = windowDurationMs,
            shouldWriteAuditLog = tickDelta != 0L && !policy.suppressMinorAuditLogs,
            shouldDispatchNotification = false
        )
    }

    /**
     * Resets the pool baseline to currentCount and returns the accumulated delta for optional logging.
     */
    fun manualFlush(
        state: PoolMetricState,
        currentCount: Long,
        nowMs: Long
    ): Pair<Long, PoolMetricState> {
        val baseline = if (state.baseline > 0L) state.baseline else currentCount
        val netDelta = currentCount - baseline
        val reset = PoolMetricState(
            baseline = currentCount,
            startedAt = nowMs,
            lastAlertAt = if (netDelta != 0L) nowMs else state.lastAlertAt,
            grossGain = 0L,
            grossLoss = 0L,
            pendingFlickerCount = -1L
        )
        return netDelta to reset
    }
}
