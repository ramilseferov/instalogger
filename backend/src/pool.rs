use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PoolMode {
    Off,
    FixedCount,
    Percentage,
    AutoAdaptive,
}

impl PoolMode {
    pub fn parse(raw: &str) -> Self {
        match raw.trim().to_uppercase().as_str() {
            "FIXED_COUNT" => Self::FixedCount,
            "PERCENTAGE" => Self::Percentage,
            "AUTO_ADAPTIVE" => Self::AutoAdaptive,
            _ => Self::Off,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PoolPolicy {
    pub mode: PoolMode,
    pub fixed_threshold: i64,
    pub percent_threshold: f64,
    pub digest_window_minutes: i64,
    pub cooldown_minutes: i64,
    pub anti_flicker_enabled: bool,
    pub spike_alert_enabled: bool,
    pub spike_multiplier: f64,
    pub suppress_minor_logs: bool,
}

impl Default for PoolPolicy {
    fn default() -> Self {
        Self {
            mode: PoolMode::Off,
            fixed_threshold: 50,
            percent_threshold: 0.5,
            digest_window_minutes: 0,
            cooldown_minutes: 0,
            anti_flicker_enabled: true,
            spike_alert_enabled: true,
            spike_multiplier: 3.0,
            suppress_minor_logs: true,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PoolMetricState {
    pub baseline: i64,
    pub started_at_ms: i64,
    pub last_alert_at_ms: i64,
    pub gross_gain: i64,
    pub gross_loss: i64,
    pub pending_flicker_count: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PoolAction {
    None,
    InstantAlert,
    Accumulate,
    SuppressedFlicker,
    SuppressedCooldown,
    FlushThreshold,
    FlushDigest,
    FlushSpike,
}

#[derive(Debug, Clone)]
pub struct PoolEvaluation {
    pub action: PoolAction,
    pub updated_state: PoolMetricState,
    pub baseline_count: i64,
    pub new_count: i64,
    pub net_delta: i64,
    pub tick_delta: i64,
    pub gross_gain: i64,
    pub gross_loss: i64,
    pub effective_threshold: i64,
    pub should_write_audit_log: bool,
    pub should_dispatch_notification: bool,
}

const FLICKER_MAX_DELTA: i64 = 2;
const SPIKE_MIN_PERCENT: f64 = 0.05;

pub fn resolve_effective_threshold(
    mode: PoolMode,
    fixed_threshold: i64,
    percent_threshold: f64,
    reference_count: i64,
) -> i64 {
    let safe_count = reference_count.max(0);
    match mode {
        PoolMode::Off => 1,
        PoolMode::FixedCount => fixed_threshold.max(1),
        PoolMode::Percentage => {
            let pct = percent_threshold.clamp(0.01, 100.0);
            ((safe_count as f64) * (pct / 100.0)).ceil().max(1.0) as i64
        }
        PoolMode::AutoAdaptive => match safe_count {
            0..=999 => 1,
            1_000..=9_999 => 10,
            10_000..=99_999 => 50,
            100_000..=999_999 => 250,
            1_000_000..=9_999_999 => 1_000,
            _ => 5_000,
        },
    }
}

pub fn evaluate_change(
    state: &PoolMetricState,
    previous_count: i64,
    new_count: i64,
    policy: &PoolPolicy,
    now_ms: i64,
) -> PoolEvaluation {
    let tick_delta = new_count - previous_count;
    let initial_baseline = if state.baseline > 0 {
        state.baseline
    } else {
        previous_count
    };
    let initial_started_at = if state.started_at_ms > 0 {
        state.started_at_ms
    } else {
        now_ms
    };
    let effective_threshold = resolve_effective_threshold(
        policy.mode,
        policy.fixed_threshold,
        policy.percent_threshold,
        initial_baseline.max(new_count),
    );

    if policy.mode == PoolMode::Off {
        if tick_delta == 0 {
            return PoolEvaluation {
                action: PoolAction::None,
                updated_state: PoolMetricState {
                    baseline: new_count,
                    started_at_ms: now_ms,
                    last_alert_at_ms: state.last_alert_at_ms,
                    gross_gain: 0,
                    gross_loss: 0,
                    pending_flicker_count: -1,
                },
                baseline_count: previous_count,
                new_count,
                net_delta: 0,
                tick_delta: 0,
                gross_gain: 0,
                gross_loss: 0,
                effective_threshold: 1,
                should_write_audit_log: false,
                should_dispatch_notification: false,
            };
        }

        if policy.anti_flicker_enabled && tick_delta.abs() <= FLICKER_MAX_DELTA {
            if state.pending_flicker_count != new_count {
                return PoolEvaluation {
                    action: PoolAction::SuppressedFlicker,
                    updated_state: PoolMetricState {
                        baseline: previous_count,
                        started_at_ms: initial_started_at,
                        last_alert_at_ms: state.last_alert_at_ms,
                        gross_gain: state.gross_gain,
                        gross_loss: state.gross_loss,
                        pending_flicker_count: new_count,
                    },
                    baseline_count: previous_count,
                    new_count,
                    net_delta: tick_delta,
                    tick_delta,
                    gross_gain: tick_delta.max(0),
                    gross_loss: (-tick_delta).max(0),
                    effective_threshold: 1,
                    should_write_audit_log: false,
                    should_dispatch_notification: false,
                };
            }
        }

        let cooldown_ms = policy.cooldown_minutes.max(0) * 60_000;
        let in_cooldown = cooldown_ms > 0
            && state.last_alert_at_ms > 0
            && (now_ms - state.last_alert_at_ms) < cooldown_ms;
        if in_cooldown {
            return PoolEvaluation {
                action: PoolAction::SuppressedCooldown,
                updated_state: PoolMetricState {
                    baseline: new_count,
                    started_at_ms: now_ms,
                    last_alert_at_ms: state.last_alert_at_ms,
                    gross_gain: 0,
                    gross_loss: 0,
                    pending_flicker_count: -1,
                },
                baseline_count: previous_count,
                new_count,
                net_delta: tick_delta,
                tick_delta,
                gross_gain: tick_delta.max(0),
                gross_loss: (-tick_delta).max(0),
                effective_threshold: 1,
                should_write_audit_log: !policy.suppress_minor_logs,
                should_dispatch_notification: false,
            };
        }

        return PoolEvaluation {
            action: PoolAction::InstantAlert,
            updated_state: PoolMetricState {
                baseline: new_count,
                started_at_ms: now_ms,
                last_alert_at_ms: now_ms,
                gross_gain: 0,
                gross_loss: 0,
                pending_flicker_count: -1,
            },
            baseline_count: previous_count,
            new_count,
            net_delta: tick_delta,
            tick_delta,
            gross_gain: tick_delta.max(0),
            gross_loss: (-tick_delta).max(0),
            effective_threshold: 1,
            should_write_audit_log: true,
            should_dispatch_notification: true,
        };
    }

    // Batched / Pooled Mode
    let new_gross_gain = state.gross_gain + tick_delta.max(0);
    let new_gross_loss = state.gross_loss + (-tick_delta).max(0);
    let net_delta = new_count - initial_baseline;

    let spike_threshold = ((effective_threshold as f64) * policy.spike_multiplier).ceil() as i64;
    let pct_jump = if initial_baseline > 0 {
        (tick_delta.abs() as f64) / (initial_baseline as f64)
    } else {
        0.0
    };
    let is_spike = policy.spike_alert_enabled
        && tick_delta.abs() >= spike_threshold.max(10)
        && pct_jump >= SPIKE_MIN_PERCENT;

    let cooldown_ms = policy.cooldown_minutes.max(0) * 60_000;
    let in_cooldown = cooldown_ms > 0
        && state.last_alert_at_ms > 0
        && (now_ms - state.last_alert_at_ms) < cooldown_ms;

    let digest_ms = policy.digest_window_minutes.max(0) * 60_000;
    let digest_due = digest_ms > 0 && net_delta != 0 && (now_ms - initial_started_at) >= digest_ms;
    let threshold_reached = net_delta.abs() >= effective_threshold;

    let flush_action = if is_spike {
        Some(PoolAction::FlushSpike)
    } else if !in_cooldown && threshold_reached {
        Some(PoolAction::FlushThreshold)
    } else if !in_cooldown && digest_due {
        Some(PoolAction::FlushDigest)
    } else {
        None
    };

    if let Some(act) = flush_action {
        return PoolEvaluation {
            action: act,
            updated_state: PoolMetricState {
                baseline: new_count,
                started_at_ms: now_ms,
                last_alert_at_ms: now_ms,
                gross_gain: 0,
                gross_loss: 0,
                pending_flicker_count: -1,
            },
            baseline_count: initial_baseline,
            new_count,
            net_delta,
            tick_delta,
            gross_gain: new_gross_gain,
            gross_loss: new_gross_loss,
            effective_threshold,
            should_write_audit_log: true,
            should_dispatch_notification: true,
        };
    }

    PoolEvaluation {
        action: if in_cooldown && (threshold_reached || digest_due) {
            PoolAction::SuppressedCooldown
        } else if tick_delta == 0 {
            PoolAction::None
        } else {
            PoolAction::Accumulate
        },
        updated_state: PoolMetricState {
            baseline: initial_baseline,
            started_at_ms: initial_started_at,
            last_alert_at_ms: state.last_alert_at_ms,
            gross_gain: new_gross_gain,
            gross_loss: new_gross_loss,
            pending_flicker_count: -1,
        },
        baseline_count: initial_baseline,
        new_count,
        net_delta,
        tick_delta,
        gross_gain: new_gross_gain,
        gross_loss: new_gross_loss,
        effective_threshold,
        should_write_audit_log: !policy.suppress_minor_logs && tick_delta != 0,
        should_dispatch_notification: false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_anti_flicker_and_batch_threshold() {
        let policy = PoolPolicy {
            mode: PoolMode::Off,
            anti_flicker_enabled: true,
            ..Default::default()
        };
        let s0 = PoolMetricState {
            baseline: 100,
            started_at_ms: 1_000,
            pending_flicker_count: -1,
            ..Default::default()
        };
        let e1 = evaluate_change(&s0, 100, 101, &policy, 2_000);
        assert_eq!(e1.action, PoolAction::SuppressedFlicker);
        let e2 = evaluate_change(&e1.updated_state, 100, 101, &policy, 3_000);
        assert_eq!(e2.action, PoolAction::InstantAlert);

        let batch = PoolPolicy {
            mode: PoolMode::FixedCount,
            fixed_threshold: 10,
            ..Default::default()
        };
        let b1 = evaluate_change(&s0, 100, 104, &batch, 4_000);
        assert_eq!(b1.action, PoolAction::Accumulate);
        let b2 = evaluate_change(&b1.updated_state, 104, 111, &batch, 5_000);
        assert_eq!(b2.action, PoolAction::FlushThreshold);
        assert_eq!(b2.net_delta, 11);
    }
}
