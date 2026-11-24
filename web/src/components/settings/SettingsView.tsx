import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { PoolMode, SystemSettings } from '../../types';
import { Card } from '../common/Card';
import { Button } from '../common/Button';
import {
  Bell,
  Send,
  Key,
  CheckCircle,
  ArrowLeft,
  SlidersHorizontal,
  Terminal,
  AlertCircle,
  Trash2,
} from 'lucide-react';

interface SettingsViewProps {
  onBack: () => void;
  onOpenDevLogs: () => void;
}

const POOL_MODES: { value: PoolMode; label: string }[] = [
  { value: 'OFF', label: 'Instant (Off)' },
  { value: 'FIXED_COUNT', label: 'Fixed Count' },
  { value: 'PERCENTAGE', label: 'Percentage (%)' },
  { value: 'AUTO_ADAPTIVE', label: 'Auto-Adaptive' },
];

export const SettingsView: React.FC<SettingsViewProps> = ({ onBack, onOpenDevLogs }) => {
  const [settings, setSettings] = useState<SystemSettings | null>(null);
  const [sessionCookie, setSessionCookie] = useState('');
  const [tgToken, setTgToken] = useState('');
  const [tgChatId, setTgChatId] = useState('');
  const [enableTermux, setEnableTermux] = useState(true);

  // Follower Change Pool & Protection Controls
  const [poolMode, setPoolMode] = useState<PoolMode>('OFF');
  const [poolFixed, setPoolFixed] = useState(50);
  const [poolPercent, setPoolPercent] = useState(0.5);
  const [poolDigest, setPoolDigest] = useState(0);
  const [poolCooldown, setPoolCooldown] = useState(0);
  const [poolAntiFlicker, setPoolAntiFlicker] = useState(true);
  const [poolSpike, setPoolSpike] = useState(true);
  const [poolFollowing, setPoolFollowing] = useState(false);
  const [poolSuppressLogs, setPoolSuppressLogs] = useState(true);
  const [listGuard, setListGuard] = useState(true);

  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const loadSettings = async () => {
    try {
      const s = await api.getSettings();
      setSettings(s);
      setEnableTermux(s.enable_termux_api);
      setTgToken(s.telegram_bot_token);
      setTgChatId(s.telegram_chat_id);
      setPoolMode(s.pool_mode || 'OFF');
      setPoolFixed(s.pool_fixed_threshold ?? 50);
      setPoolPercent(s.pool_percent_threshold ?? 0.5);
      setPoolDigest(s.pool_digest_minutes ?? 0);
      setPoolCooldown(s.pool_cooldown_minutes ?? 0);
      setPoolAntiFlicker(s.pool_anti_flicker ?? true);
      setPoolSpike(s.pool_spike_alert ?? true);
      setPoolFollowing(s.pool_apply_following ?? false);
      setPoolSuppressLogs(s.pool_suppress_logs ?? true);
      setListGuard(s.list_completeness_guard ?? true);
    } catch (e: any) {
      setErrorMsg(e?.message || 'Failed to load settings');
    }
  };

  useEffect(() => {
    loadSettings();
  }, []);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setErrorMsg(null);
    try {
      await api.updateSettings({
        enable_termux_api: enableTermux,
        telegram_bot_token: tgToken,
        telegram_chat_id: tgChatId,
        session_cookie: sessionCookie || undefined,
        pool_mode: poolMode,
        pool_fixed_threshold: poolFixed,
        pool_percent_threshold: poolPercent,
        pool_digest_minutes: poolDigest,
        pool_cooldown_minutes: poolCooldown,
        pool_anti_flicker: poolAntiFlicker,
        pool_spike_alert: poolSpike,
        pool_apply_following: poolFollowing,
        pool_suppress_logs: poolSuppressLogs,
        list_completeness_guard: listGuard,
      });
      setSessionCookie('');
      await loadSettings();
      setSaved(true);
      window.setTimeout(() => setSaved(false), 2500);
    } catch (e: any) {
      setErrorMsg(e?.message || 'Failed to save settings');
    } finally {
      setSaving(false);
    }
  };

  const handleClearSession = async () => {
    try {
      await api.updateSettings({ clear_session_cookie: true });
      await loadSettings();
    } catch (e: any) {
      setErrorMsg(e?.message || 'Failed to clear session');
    }
  };

  const ToggleRow = ({
    title,
    desc,
    checked,
    onChange,
  }: {
    title: string;
    desc: string;
    checked: boolean;
    onChange: (next: boolean) => void;
  }) => (
    <div className="flex items-center justify-between gap-4 py-1.5">
      <div>
        <div className="text-xs font-bold text-slate-900 dark:text-white">{title}</div>
        <div className="text-[11px] text-slate-500 dark:text-slate-400">{desc}</div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`w-11 h-6 rounded-full p-0.5 transition-colors shrink-0 ${
          checked ? 'bg-[#0C7FF2]' : 'bg-slate-300 dark:bg-slate-700'
        }`}
      >
        <div
          className={`w-5 h-5 rounded-full bg-white transition-transform ${
            checked ? 'translate-x-5' : 'translate-x-0'
          }`}
        />
      </button>
    </div>
  );

  return (
    <div className="min-h-screen bg-[#F2F4F7] dark:bg-slate-950 transition-colors">
      <div className="max-w-3xl mx-auto px-4 py-6 space-y-5">
        <div className="flex items-center gap-3">
          <button
            onClick={onBack}
            className="p-2 rounded-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-200 hover:text-[#0C7FF2] transition-colors"
            aria-label="Back"
          >
            <ArrowLeft size={18} />
          </button>
          <div>
            <h2 className="text-xl font-bold text-slate-900 dark:text-white">System Settings</h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Follower change pool, notifications, session isolation, and developer console
            </p>
          </div>
        </div>

        {errorMsg && (
          <div className="rounded-xl border border-rose-300 dark:border-rose-800 bg-rose-50 dark:bg-rose-950/40 px-4 py-2.5 text-xs text-rose-700 dark:text-rose-300 flex items-center gap-2">
            <AlertCircle size={15} />
            <span>{errorMsg}</span>
          </div>
        )}

        <form onSubmit={handleSave} className="space-y-4">
          {/* Follower Change Pool & Protection */}
          <Card className="space-y-4">
            <div className="flex items-center gap-3">
              <div className="p-2 rounded-xl bg-blue-500/10 text-[#0C7FF2]">
                <SlidersHorizontal size={18} />
              </div>
              <div>
                <h3 className="font-bold text-sm text-slate-900 dark:text-white">
                  Follower Change Pool & High-Velocity Protection
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Batch micro +1/-1 ticks on large accounts or stay on classic instant alerts.
                </p>
              </div>
            </div>

            <div className="flex flex-wrap gap-2">
              {POOL_MODES.map((m) => (
                <button
                  key={m.value}
                  type="button"
                  onClick={() => setPoolMode(m.value)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition-colors ${
                    poolMode === m.value
                      ? 'bg-[#0C7FF2] text-white border-[#0C7FF2]'
                      : 'bg-slate-50 dark:bg-slate-800 text-slate-700 dark:text-slate-300 border-slate-200 dark:border-slate-700'
                  }`}
                >
                  {m.label}
                </button>
              ))}
            </div>

            {poolMode === 'FIXED_COUNT' && (
              <div className="space-y-2">
                <div className="flex flex-wrap gap-1.5">
                  {[10, 50, 100, 250, 500, 1000].map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => setPoolFixed(preset)}
                      className={`px-2.5 py-1 rounded-md text-xs font-mono border ${
                        poolFixed === preset
                          ? 'bg-blue-500/15 border-[#0C7FF2] text-[#0C7FF2] font-bold'
                          : 'border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300'
                      }`}
                    >
                      ±{preset}
                    </button>
                  ))}
                </div>
                <input
                  type="number"
                  min={1}
                  max={1000000}
                  value={poolFixed}
                  onChange={(e) => setPoolFixed(Math.max(1, Number(e.target.value) || 1))}
                  className="w-full sm:w-48 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl px-3 py-2 text-xs font-mono text-slate-900 dark:text-white"
                />
              </div>
            )}

            {poolMode === 'PERCENTAGE' && (
              <div className="space-y-2">
                <div className="flex flex-wrap gap-1.5">
                  {[0.1, 0.5, 1.0, 2.0, 5.0].map((pct) => (
                    <button
                      key={pct}
                      type="button"
                      onClick={() => setPoolPercent(pct)}
                      className={`px-2.5 py-1 rounded-md text-xs font-mono border ${
                        poolPercent === pct
                          ? 'bg-blue-500/15 border-[#0C7FF2] text-[#0C7FF2] font-bold'
                          : 'border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300'
                      }`}
                    >
                      {pct}%
                    </button>
                  ))}
                </div>
              </div>
            )}

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-2 border-t border-slate-100 dark:border-slate-800">
              <div>
                <label className="block text-[11px] font-bold uppercase text-slate-500 mb-1.5">
                  Digest Window
                </label>
                <div className="flex flex-wrap gap-1.5">
                  {[
                    [0, 'Off'],
                    [15, '15m'],
                    [60, '1h'],
                    [360, '6h'],
                    [1440, '24h'],
                  ].map(([mins, label]) => (
                    <button
                      key={String(mins)}
                      type="button"
                      onClick={() => setPoolDigest(Number(mins))}
                      className={`px-2.5 py-1 rounded-md text-xs border ${
                        poolDigest === Number(mins)
                          ? 'bg-blue-500/15 border-[#0C7FF2] text-[#0C7FF2] font-bold'
                          : 'border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="block text-[11px] font-bold uppercase text-slate-500 mb-1.5">
                  Alert Cooldown
                </label>
                <div className="flex flex-wrap gap-1.5">
                  {[
                    [0, 'Off'],
                    [1, '1m'],
                    [5, '5m'],
                    [15, '15m'],
                    [60, '1h'],
                  ].map(([mins, label]) => (
                    <button
                      key={String(mins)}
                      type="button"
                      onClick={() => setPoolCooldown(Number(mins))}
                      className={`px-2.5 py-1 rounded-md text-xs border ${
                        poolCooldown === Number(mins)
                          ? 'bg-blue-500/15 border-[#0C7FF2] text-[#0C7FF2] font-bold'
                          : 'border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="pt-2 border-t border-slate-100 dark:border-slate-800 space-y-2">
              <ToggleRow
                title="CDN Anti-Flicker Filter"
                desc="Suppress single-poll +/-1 or +/-2 Instagram edge cache oscillations."
                checked={poolAntiFlicker}
                onChange={setPoolAntiFlicker}
              />
              <ToggleRow
                title="Rapid Spike Alert"
                desc="Notify immediately when a sudden surge or mass drop exceeds normal velocity."
                checked={poolSpike}
                onChange={setPoolSpike}
              />
              <ToggleRow
                title="Apply Pool to Following"
                desc="Apply the same threshold rules to following count updates."
                checked={poolFollowing}
                onChange={setPoolFollowing}
              />
              <ToggleRow
                title="Suppress Minor Audit Logs"
                desc="Write a single consolidated audit log entry when the pool flushes."
                checked={poolSuppressLogs}
                onChange={setPoolSuppressLogs}
              />
              <ToggleRow
                title="List Completeness Guard"
                desc="Block false unfollow alerts caused by truncated 50-item API responses."
                checked={listGuard}
                onChange={setListGuard}
              />
            </div>
          </Card>

          {/* Notifications & Telegram */}
          <Card className="space-y-4">
            <div className="flex items-center gap-3">
              <div className="p-2 rounded-xl bg-blue-500/10 text-[#0C7FF2]">
                <Bell size={18} />
              </div>
              <div className="flex-1">
                <ToggleRow
                  title="Android Termux Push Notifications"
                  desc="Dispatch local alerts via Termux:API notification bridge."
                  checked={enableTermux}
                  onChange={setEnableTermux}
                />
              </div>
            </div>

            <div className="pt-3 border-t border-slate-100 dark:border-slate-800 space-y-3">
              <div className="flex items-center gap-2 text-xs font-bold text-slate-800 dark:text-slate-200">
                <Send size={14} className="text-[#0C7FF2]" />
                <span>Telegram Bot Alerts</span>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <input
                  type="password"
                  placeholder="Bot Token (123456:ABC...)"
                  value={tgToken}
                  onChange={(e) => setTgToken(e.target.value)}
                  className="w-full bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl px-3.5 py-2.5 text-xs font-mono text-slate-900 dark:text-white"
                />
                <input
                  type="text"
                  placeholder="Chat ID or @channel"
                  value={tgChatId}
                  onChange={(e) => setTgChatId(e.target.value)}
                  className="w-full bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl px-3.5 py-2.5 text-xs font-mono text-slate-900 dark:text-white"
                />
              </div>
            </div>
          </Card>

          {/* Instagram Session Cookie */}
          <Card className="space-y-3">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-xl bg-blue-500/10 text-[#0C7FF2]">
                  <Key size={18} />
                </div>
                <div>
                  <h3 className="font-bold text-sm text-slate-900 dark:text-white">
                    Instagram Session Cookie (Optional)
                  </h3>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    Public profiles are fetched zero-login first; session is used only for private profiles and stories.
                  </p>
                </div>
              </div>
              {settings?.has_session_cookie && (
                <button
                  type="button"
                  onClick={handleClearSession}
                  className="px-2.5 py-1 rounded-lg border border-rose-300 dark:border-rose-800 text-rose-600 dark:text-rose-400 text-xs font-semibold flex items-center gap-1"
                >
                  <Trash2 size={12} /> Clear
                </button>
              )}
            </div>

            <textarea
              rows={2}
              placeholder="sessionid=...; csrftoken=...; ds_user_id=..."
              value={sessionCookie}
              onChange={(e) => setSessionCookie(e.target.value)}
              className="w-full bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl p-3 text-xs font-mono text-slate-900 dark:text-white resize-none"
            />
          </Card>

          {/* Developer Logs Card */}
          <Card className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="p-2 rounded-xl bg-emerald-500/10 text-emerald-500">
                <Terminal size={18} />
              </div>
              <div>
                <h3 className="font-bold text-sm text-slate-900 dark:text-white">Developer Logs Terminal</h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Live redacted console with copyable lines, level filters, and HTTP diagnostics.
                </p>
              </div>
            </div>
            <Button type="button" variant="secondary" size="sm" onClick={onOpenDevLogs}>
              Open Terminal
            </Button>
          </Card>

          <div className="flex items-center justify-end gap-3 pt-1">
            {saved && (
              <span className="flex items-center gap-1.5 text-xs font-semibold text-emerald-600 dark:text-emerald-400 bg-emerald-50 dark:bg-emerald-950/50 px-3 py-1.5 rounded-full border border-emerald-200 dark:border-emerald-800">
                <CheckCircle size={14} /> Saved
              </span>
            )}
            <Button type="submit" variant="primary" disabled={saving}>
              {saving ? 'Saving...' : 'Save Settings'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};
