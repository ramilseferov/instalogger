import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { ArchiverSpeedPreset, PoolMode, SystemSettings, ThemeMode, YtDlpQuality } from '../../types';
import { Card } from '../common/Card';
import { Button } from '../common/Button';
import { SegmentedControl } from '../common/SegmentedControl';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { useI18n, Language } from '../../i18n';
import {
  Bell,
  Send,
  Key,
  CheckCircle2,
  ArrowLeft,
  SlidersHorizontal,
  Terminal,
  AlertCircle,
  Trash2,
  Palette,
  Globe,
  Sun,
  Moon,
  Monitor,
  ChevronRight,
  Download,
  Gauge,
} from 'lucide-react';

interface SettingsViewProps {
  onBack: () => void;
  onOpenDevLogs: () => void;
  onOpenDownloads?: () => void;
}

const SPEED_WORKERS: Record<ArchiverSpeedPreset, number> = {
  ECO: 2,
  BALANCED: 4,
  FAST: 6,
  TURBO: 10,
};

export const SettingsView: React.FC<SettingsViewProps> = ({
  onBack,
  onOpenDevLogs,
  onOpenDownloads,
}) => {
  const { language, setLanguage, themeMode, setThemeMode, t } = useI18n();
  const [settings, setSettings] = useState<SystemSettings | null>(null);
  const [sessionCookie, setSessionCookie] = useState('');
  const [tgToken, setTgToken] = useState('');
  const [tgChatId, setTgChatId] = useState('');
  const [enableTermux, setEnableTermux] = useState(true);
  const [notifLang, setNotifLang] = useState<'az' | 'en' | 'ru'>('az');

  const [archiverSpeed, setArchiverSpeed] = useState<ArchiverSpeedPreset>('BALANCED');
  const [downloadWorkers, setDownloadWorkers] = useState(4);
  const [maxPostsLimit, setMaxPostsLimit] = useState(50);
  const [ytDlpEnabled, setYtDlpEnabled] = useState(true);
  const [ytDlpQuality, setYtDlpQuality] = useState<YtDlpQuality>('BEST');
  const [autoDownloadMedia, setAutoDownloadMedia] = useState(true);

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
  const [savingSession, setSavingSession] = useState(false);
  const [confirmClearSession, setConfirmClearSession] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const loadSettings = async () => {
    try {
      const s = await api.getSettings();
      setSettings(s);
      setEnableTermux(s.enable_termux_api);
      setTgToken(s.telegram_bot_token);
      setTgChatId(s.telegram_chat_id);
      setNotifLang(s.notification_language || 'az');
      setArchiverSpeed(s.archiver_speed_preset || 'BALANCED');
      setDownloadWorkers(s.download_workers ?? 4);
      setMaxPostsLimit(s.max_posts_sync_limit ?? 50);
      setYtDlpEnabled(s.yt_dlp_enabled ?? true);
      setYtDlpQuality(s.yt_dlp_quality || 'BEST');
      setAutoDownloadMedia(s.auto_download_media ?? true);
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
      setErrorMsg(e?.message || t.toast_check_failed);
    }
  };

  useEffect(() => {
    loadSettings();
  }, []);

  const flashSaved = () => {
    setSaved(true);
    window.setTimeout(() => setSaved(false), 2400);
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setErrorMsg(null);
    try {
      await api.updateSettings({
        enable_termux_api: enableTermux,
        telegram_bot_token: tgToken,
        telegram_chat_id: tgChatId,
        notification_language: notifLang,
        session_cookie: sessionCookie.trim() || undefined,
        archiver_speed_preset: archiverSpeed,
        download_workers: downloadWorkers,
        max_posts_sync_limit: maxPostsLimit,
        yt_dlp_enabled: ytDlpEnabled,
        yt_dlp_quality: ytDlpQuality,
        auto_download_media: autoDownloadMedia,
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
      flashSaved();
    } catch (e: any) {
      setErrorMsg(e?.message || t.toast_check_failed);
    } finally {
      setSaving(false);
    }
  };

  const handleSaveSessionOnly = async () => {
    if (!sessionCookie.trim()) return;
    setSavingSession(true);
    setErrorMsg(null);
    try {
      await api.updateSettings({ session_cookie: sessionCookie.trim() });
      setSessionCookie('');
      await loadSettings();
      flashSaved();
    } catch (e: any) {
      setErrorMsg(e?.message || t.toast_check_failed);
    } finally {
      setSavingSession(false);
    }
  };

  const handleClearSession = async () => {
    setConfirmClearSession(false);
    setSavingSession(true);
    setErrorMsg(null);
    try {
      await api.updateSettings({ clear_session_cookie: true });
      setSessionCookie('');
      await loadSettings();
      flashSaved();
    } catch (e: any) {
      setErrorMsg(e?.message || t.toast_check_failed);
    } finally {
      setSavingSession(false);
    }
  };

  const themeCards: {
    mode: ThemeMode;
    label: string;
    icon: React.ReactNode;
    previewBg: string;
    previewCard: string;
    previewBar: string;
  }[] = [
    {
      mode: 'light',
      label: t.theme_light,
      icon: <Sun size={14} strokeWidth={1.75} />,
      previewBg: 'bg-[#F4F5F7]',
      previewCard: 'bg-white border-slate-200',
      previewBar: 'bg-slate-300',
    },
    {
      mode: 'dark',
      label: t.theme_dark,
      icon: <Moon size={14} strokeWidth={1.75} />,
      previewBg: 'bg-[#0D0F12]',
      previewCard: 'bg-[#171B21] border-white/10',
      previewBar: 'bg-slate-600',
    },
    {
      mode: 'system',
      label: t.theme_system,
      icon: <Monitor size={14} strokeWidth={1.75} />,
      previewBg: 'bg-gradient-to-r from-[#F4F5F7] from-50% to-[#0D0F12] to-50%',
      previewCard: 'bg-white/80 dark:bg-[#171B21]/90 border-slate-300/60 dark:border-white/10',
      previewBar: 'bg-[#0C7FF2]',
    },
  ];

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
    <div className="flex items-center justify-between gap-4 py-2">
      <div className="min-w-0">
        <div className="text-xs font-medium text-[var(--text-primary)]">{title}</div>
        <div className="text-[11px] text-[var(--text-muted)] leading-relaxed mt-0.5">{desc}</div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`w-11 h-6 rounded-full p-0.5 transition-colors duration-150 shrink-0 ${
          checked ? 'bg-[#0C7FF2]' : 'bg-[var(--border-strong)]'
        }`}
      >
        <div
          className={`w-5 h-5 rounded-full bg-white shadow-xs transition-transform duration-200 ease-[cubic-bezier(0.22,1,0.36,1)] ${
            checked ? 'translate-x-5' : 'translate-x-0'
          }`}
        />
      </button>
    </div>
  );

  return (
    <div className="min-h-screen bg-[var(--bg-canvas)] pb-20">
      <header className="sticky top-0 z-30 bg-[var(--bg-canvas)]/90 backdrop-blur-md border-b border-[var(--border-subtle)]">
        <div className="max-w-3xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <button
              type="button"
              onClick={onBack}
              className="w-9 h-9 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:border-[var(--border-strong)] flex items-center justify-center active:scale-95 transition-[color,border-color,transform] duration-150 shrink-0"
              aria-label={t.back}
            >
              <ArrowLeft size={16} strokeWidth={1.75} />
            </button>
            <div className="min-w-0">
              <h1 className="text-base font-semibold text-[var(--text-primary)] truncate">
                {t.settings}
              </h1>
              <p className="text-xs text-[var(--text-muted)] truncate">{t.settings_subtitle}</p>
            </div>
          </div>

          {saved && (
            <span className="inline-flex items-center gap-1.5 text-xs font-medium text-emerald-600 dark:text-emerald-400 bg-emerald-500/10 px-3 py-1 rounded-lg border border-emerald-500/20 animate-toast shrink-0">
              <CheckCircle2 size={14} strokeWidth={1.75} />
              {t.saved}
            </span>
          )}
        </div>
      </header>

      <div className="max-w-3xl mx-auto px-4 sm:px-6 pt-6 space-y-6">
        {errorMsg && (
          <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-xs text-rose-600 dark:text-rose-400 flex items-center gap-2.5 animate-toast">
            <AlertCircle size={15} strokeWidth={1.75} className="shrink-0" />
            <span>{errorMsg}</span>
          </div>
        )}

        {/* Section 1: Appearance & Language */}
        <section className="space-y-2.5">
          <h2 className="text-xs font-medium text-[var(--text-muted)] px-1">
            {t.section_appearance_lang}
          </h2>
          <Card className="space-y-5">
            {/* Theme Selector (3 mini previews like Android/TourJet) */}
            <div className="space-y-3">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-lg bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center">
                  <Palette size={16} strokeWidth={1.75} />
                </div>
                <div>
                  <div className="text-sm font-medium text-[var(--text-primary)]">{t.appearance}</div>
                  <div className="text-xs text-[var(--text-muted)]">{t.appearance_desc}</div>
                </div>
              </div>

              <div className="grid grid-cols-3 gap-3">
                {themeCards.map((card) => {
                  const active = themeMode === card.mode;
                  return (
                    <button
                      key={card.mode}
                      type="button"
                      onClick={() => setThemeMode(card.mode)}
                      className={`group rounded-xl p-2.5 border text-left transition-[border-color,background-color,transform] duration-150 active:scale-[0.98] ${
                        active
                          ? 'border-[#0C7FF2] bg-[var(--accent-soft)]'
                          : 'border-[var(--border-subtle)] bg-[var(--bg-subtle)]/50 hover:border-[var(--border-strong)]'
                      }`}
                    >
                      <div
                        className={`h-16 rounded-lg p-2 border border-black/5 dark:border-white/5 flex flex-col justify-between ${card.previewBg}`}
                      >
                        <div className="flex items-center gap-1">
                          <span className="w-1.5 h-1.5 rounded-full bg-[#0C7FF2]" />
                          <span className={`h-1 w-8 rounded-full ${card.previewBar} opacity-60`} />
                        </div>
                        <div className={`h-7 rounded-md border p-1.5 space-y-1 ${card.previewCard}`}>
                          <div className={`h-1 w-10 rounded-full ${card.previewBar}`} />
                          <div className={`h-1 w-6 rounded-full ${card.previewBar} opacity-50`} />
                        </div>
                      </div>
                      <div className="mt-2 flex items-center justify-between gap-1 px-0.5">
                        <span
                          className={`text-xs font-medium ${
                            active ? 'text-[#0C7FF2] dark:text-[#58A6FF]' : 'text-[var(--text-secondary)]'
                          }`}
                        >
                          {card.label}
                        </span>
                        <span className={active ? 'text-[#0C7FF2] dark:text-[#58A6FF]' : 'text-[var(--text-muted)]'}>
                          {card.icon}
                        </span>
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="border-t border-[var(--border-subtle)] pt-5 space-y-3">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-lg bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center">
                  <Globe size={16} strokeWidth={1.75} />
                </div>
                <div>
                  <div className="text-sm font-medium text-[var(--text-primary)]">{t.language}</div>
                  <div className="text-xs text-[var(--text-muted)]">{t.language_desc}</div>
                </div>
              </div>

              <SegmentedControl<Language>
                value={language}
                onChange={setLanguage}
                options={[
                  { value: 'az', label: 'Azərbaycan' },
                  { value: 'en', label: 'English' },
                  { value: 'ru', label: 'Русский' },
                ]}
              />
            </div>
          </Card>
        </section>

        {/* Section 2: Instagram Session */}
        <section className="space-y-2.5">
          <h2 className="text-xs font-medium text-[var(--text-muted)] px-1">{t.section_session}</h2>
          <Card className="space-y-4">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2.5 min-w-0">
                <div className="w-8 h-8 rounded-lg bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center shrink-0">
                  <Key size={16} strokeWidth={1.75} />
                </div>
                <div className="min-w-0">
                  <div className="text-sm font-medium text-[var(--text-primary)]">{t.session_title}</div>
                  <div className="text-xs text-[var(--text-muted)]">{t.session_desc}</div>
                </div>
              </div>
              <span
                className={`shrink-0 text-xs font-medium px-2.5 py-1 rounded-lg border ${
                  settings?.has_session_cookie
                    ? 'bg-emerald-500/12 text-emerald-700 dark:text-emerald-400 border-emerald-500/20'
                    : 'bg-[var(--bg-subtle)] text-[var(--text-muted)] border-[var(--border-subtle)]'
                }`}
              >
                {settings?.has_session_cookie ? t.session_connected : t.session_not_set}
              </span>
            </div>

            <textarea
              rows={2}
              placeholder={t.session_placeholder}
              value={sessionCookie}
              onChange={(e) => setSessionCookie(e.target.value)}
              className="w-full bg-[var(--bg-subtle)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl p-3 text-xs font-mono text-[var(--text-primary)] placeholder:text-[var(--text-muted)] resize-none focus:outline-none transition-colors duration-150"
            />

            <div className="grid grid-cols-2 gap-2.5">
              <Button
                type="button"
                variant="primary"
                size="md"
                onClick={handleSaveSessionOnly}
                disabled={savingSession || !sessionCookie.trim()}
              >
                {t.session_save}
              </Button>
              <Button
                type="button"
                variant="danger"
                size="md"
                icon={<Trash2 size={14} strokeWidth={1.75} />}
                onClick={() => setConfirmClearSession(true)}
                disabled={savingSession || !settings?.has_session_cookie}
              >
                {t.session_clear}
              </Button>
            </div>
          </Card>
        </section>

        <form onSubmit={handleSave} className="space-y-6">
          {/* Section 3: Notifications & Alert Language */}
          <section className="space-y-2.5">
            <h2 className="text-xs font-medium text-[var(--text-muted)] px-1">
              {t.section_notifications}
            </h2>
            <Card className="space-y-4">
              <div className="space-y-2.5">
                <div className="flex items-center gap-2.5">
                  <div className="w-8 h-8 rounded-lg bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center">
                    <Bell size={16} strokeWidth={1.75} />
                  </div>
                  <div>
                    <div className="text-sm font-medium text-[var(--text-primary)]">{t.notif_lang_title}</div>
                    <div className="text-xs text-[var(--text-muted)]">{t.notif_lang_desc}</div>
                  </div>
                </div>
                <SegmentedControl<'az' | 'en' | 'ru'>
                  value={notifLang}
                  onChange={setNotifLang}
                  options={[
                    { value: 'az', label: 'Azərbaycan' },
                    { value: 'en', label: 'English' },
                    { value: 'ru', label: 'Русский' },
                  ]}
                />
              </div>

              <div className="border-t border-[var(--border-subtle)] pt-3">
                <ToggleRow
                  title={t.notif_termux_title}
                  desc={t.notif_termux_desc}
                  checked={enableTermux}
                  onChange={setEnableTermux}
                />
              </div>

              <div className="border-t border-[var(--border-subtle)] pt-4 space-y-3">
                <div className="flex items-center gap-2 text-xs font-medium text-[var(--text-primary)]">
                  <Send size={14} strokeWidth={1.75} className="text-[var(--text-secondary)]" />
                  <span>{t.notif_telegram_title}</span>
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <input
                    type="password"
                    placeholder={t.notif_telegram_token}
                    value={tgToken}
                    onChange={(e) => setTgToken(e.target.value)}
                    className="w-full h-10 bg-[var(--bg-subtle)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl px-3.5 text-xs font-mono text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none transition-colors duration-150"
                  />
                  <input
                    type="text"
                    placeholder={t.notif_telegram_chat}
                    value={tgChatId}
                    onChange={(e) => setTgChatId(e.target.value)}
                    className="w-full h-10 bg-[var(--bg-subtle)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl px-3.5 text-xs font-mono text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none transition-colors duration-150"
                  />
                </div>
              </div>
            </Card>
          </section>

          {/* Section 4: Downloads & Media Engine */}
          <section className="space-y-2.5">
            <h2 className="text-xs font-medium text-[var(--text-muted)] px-1">
              {t.section_downloads}
            </h2>
            <Card className="space-y-4">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2.5 min-w-0">
                  <div className="w-8 h-8 rounded-lg bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center shrink-0">
                    <Download size={16} strokeWidth={1.75} />
                  </div>
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-[var(--text-primary)] truncate">
                      {t.dl_engine_title}
                    </div>
                    <div className="text-xs text-[var(--text-muted)]">{t.dl_engine_desc}</div>
                  </div>
                </div>
                <span
                  className={`shrink-0 text-xs font-medium px-2.5 py-1 rounded-lg border ${
                    settings?.yt_dlp_available
                      ? 'bg-emerald-500/12 text-emerald-700 dark:text-emerald-400 border-emerald-500/20'
                      : 'bg-amber-500/12 text-amber-700 dark:text-amber-300 border-amber-500/20'
                  }`}
                >
                  {settings?.yt_dlp_available ? t.dl_engine_ready : t.dl_engine_fallback}
                </span>
              </div>

              <div className="border-t border-[var(--border-subtle)] pt-3">
                <ToggleRow
                  title={t.dl_engine_title}
                  desc={t.dl_engine_desc}
                  checked={ytDlpEnabled}
                  onChange={setYtDlpEnabled}
                />
              </div>

              <div className={`collapse-grid ${ytDlpEnabled ? 'is-open' : ''}`}>
                <div className="space-y-2 pt-1">
                  <label className="block text-xs font-medium text-[var(--text-secondary)]">
                    {t.dl_quality_title}
                  </label>
                  <SegmentedControl<YtDlpQuality>
                    value={ytDlpQuality}
                    onChange={setYtDlpQuality}
                    options={[
                      { value: 'BEST', label: t.dl_quality_best },
                      { value: 'HD_1080', label: '1080p' },
                      { value: 'HD_720', label: '720p' },
                      { value: 'DATA_SAVER', label: '480p' },
                    ]}
                  />
                </div>
              </div>

              <div className="border-t border-[var(--border-subtle)] pt-4 space-y-3">
                <div className="flex items-center gap-2.5">
                  <div className="w-8 h-8 rounded-lg bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center shrink-0">
                    <Gauge size={16} strokeWidth={1.75} />
                  </div>
                  <div>
                    <div className="text-sm font-medium text-[var(--text-primary)]">
                      {t.dl_speed_title}
                    </div>
                    <div className="text-xs text-[var(--text-muted)]">{t.dl_speed_desc}</div>
                  </div>
                </div>

                <SegmentedControl<ArchiverSpeedPreset>
                  value={archiverSpeed}
                  onChange={(preset) => {
                    setArchiverSpeed(preset);
                    setDownloadWorkers(SPEED_WORKERS[preset]);
                  }}
                  options={[
                    { value: 'ECO', label: t.dl_speed_eco },
                    { value: 'BALANCED', label: t.dl_speed_balanced },
                    { value: 'FAST', label: t.dl_speed_fast },
                    { value: 'TURBO', label: t.dl_speed_turbo },
                  ]}
                />

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2">
                  <div className="space-y-2">
                    <label className="block text-xs font-medium text-[var(--text-secondary)]">
                      {t.dl_workers_title}
                    </label>
                    <div className="flex flex-wrap gap-1.5">
                      {[1, 2, 4, 6, 8, 10].map((w) => (
                        <button
                          key={w}
                          type="button"
                          onClick={() => setDownloadWorkers(w)}
                          className={`h-8 px-3 rounded-lg text-xs font-mono tabular-nums border transition-colors duration-150 ${
                            downloadWorkers === w
                              ? 'bg-[var(--accent-soft)] border-[#0C7FF2] text-[#0C7FF2] dark:text-[#58A6FF] font-medium'
                              : 'bg-[var(--bg-subtle)] border-[var(--border-subtle)] text-[var(--text-secondary)] hover:border-[var(--border-strong)]'
                          }`}
                        >
                          {w}x
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="space-y-2">
                    <label className="block text-xs font-medium text-[var(--text-secondary)]">
                      {t.dl_max_posts_title}
                    </label>
                    <div className="flex flex-wrap gap-1.5">
                      {[24, 50, 100, 250, 0].map((limit) => (
                        <button
                          key={limit}
                          type="button"
                          onClick={() => setMaxPostsLimit(limit)}
                          className={`h-8 px-2.5 rounded-lg text-xs tabular-nums border transition-colors duration-150 ${
                            maxPostsLimit === limit
                              ? 'bg-[var(--accent-soft)] border-[#0C7FF2] text-[#0C7FF2] dark:text-[#58A6FF] font-medium'
                              : 'bg-[var(--bg-subtle)] border-[var(--border-subtle)] text-[var(--text-secondary)] hover:border-[var(--border-strong)]'
                          }`}
                        >
                          {limit === 0 ? t.dl_max_posts_unlimited : limit}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              </div>

              <div className="border-t border-[var(--border-subtle)] pt-3">
                <ToggleRow
                  title={t.dl_auto_title}
                  desc={t.dl_auto_desc}
                  checked={autoDownloadMedia}
                  onChange={setAutoDownloadMedia}
                />
              </div>
            </Card>
          </section>

          {/* Section 5: Follower Change Batching */}
          <section className="space-y-2.5">
            <h2 className="text-xs font-medium text-[var(--text-muted)] px-1">
              {t.section_follower_batch}
            </h2>
            <Card className="space-y-4">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-lg bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center">
                  <SlidersHorizontal size={16} strokeWidth={1.75} />
                </div>
                <div>
                  <div className="text-sm font-medium text-[var(--text-primary)]">{t.batch_title}</div>
                  <div className="text-xs text-[var(--text-muted)]">{t.batch_desc}</div>
                </div>
              </div>

              <SegmentedControl<PoolMode>
                value={poolMode}
                onChange={setPoolMode}
                options={[
                  { value: 'OFF', label: t.batch_mode_off },
                  { value: 'FIXED_COUNT', label: t.batch_mode_fixed },
                  { value: 'PERCENTAGE', label: t.batch_mode_percent },
                  { value: 'AUTO_ADAPTIVE', label: t.batch_mode_auto },
                ]}
              />

              <div className={`collapse-grid ${poolMode === 'FIXED_COUNT' ? 'is-open' : ''}`}>
                <div className="space-y-2.5 pt-1">
                  <div className="flex flex-wrap gap-1.5">
                    {[10, 50, 100, 250, 500, 1000].map((preset) => (
                      <button
                        key={preset}
                        type="button"
                        onClick={() => setPoolFixed(preset)}
                        className={`h-8 px-3 rounded-lg text-xs font-mono tabular-nums border transition-colors duration-150 ${
                          poolFixed === preset
                            ? 'bg-[var(--accent-soft)] border-[#0C7FF2] text-[#0C7FF2] dark:text-[#58A6FF] font-medium'
                            : 'bg-[var(--bg-subtle)] border-[var(--border-subtle)] text-[var(--text-secondary)] hover:border-[var(--border-strong)]'
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
                    className="w-full sm:w-44 h-9 bg-[var(--bg-subtle)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl px-3 text-xs font-mono tabular-nums text-[var(--text-primary)] focus:outline-none"
                  />
                </div>
              </div>

              <div className={`collapse-grid ${poolMode === 'PERCENTAGE' ? 'is-open' : ''}`}>
                <div className="flex flex-wrap gap-1.5 pt-1">
                  {[0.1, 0.5, 1.0, 2.0, 5.0].map((pct) => (
                    <button
                      key={pct}
                      type="button"
                      onClick={() => setPoolPercent(pct)}
                      className={`h-8 px-3 rounded-lg text-xs font-mono tabular-nums border transition-colors duration-150 ${
                        poolPercent === pct
                          ? 'bg-[var(--accent-soft)] border-[#0C7FF2] text-[#0C7FF2] dark:text-[#58A6FF] font-medium'
                          : 'bg-[var(--bg-subtle)] border-[var(--border-subtle)] text-[var(--text-secondary)] hover:border-[var(--border-strong)]'
                      }`}
                    >
                      {pct}%
                    </button>
                  ))}
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-3 border-t border-[var(--border-subtle)]">
                <div className="space-y-2">
                  <label className="block text-xs font-medium text-[var(--text-secondary)]">
                    {t.batch_digest}
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {[
                      [0, t.batch_off],
                      [15, '15m'],
                      [60, '1h'],
                      [360, '6h'],
                      [1440, '24h'],
                    ].map(([mins, label]) => (
                      <button
                        key={String(mins)}
                        type="button"
                        onClick={() => setPoolDigest(Number(mins))}
                        className={`h-8 px-2.5 rounded-lg text-xs tabular-nums border transition-colors duration-150 ${
                          poolDigest === Number(mins)
                            ? 'bg-[var(--accent-soft)] border-[#0C7FF2] text-[#0C7FF2] dark:text-[#58A6FF] font-medium'
                            : 'bg-[var(--bg-subtle)] border-[var(--border-subtle)] text-[var(--text-secondary)] hover:border-[var(--border-strong)]'
                        }`}
                      >
                        {label}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="space-y-2">
                  <label className="block text-xs font-medium text-[var(--text-secondary)]">
                    {t.batch_cooldown}
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {[
                      [0, t.batch_off],
                      [1, '1m'],
                      [5, '5m'],
                      [15, '15m'],
                      [60, '1h'],
                    ].map(([mins, label]) => (
                      <button
                        key={String(mins)}
                        type="button"
                        onClick={() => setPoolCooldown(Number(mins))}
                        className={`h-8 px-2.5 rounded-lg text-xs tabular-nums border transition-colors duration-150 ${
                          poolCooldown === Number(mins)
                            ? 'bg-[var(--accent-soft)] border-[#0C7FF2] text-[#0C7FF2] dark:text-[#58A6FF] font-medium'
                            : 'bg-[var(--bg-subtle)] border-[var(--border-subtle)] text-[var(--text-secondary)] hover:border-[var(--border-strong)]'
                        }`}
                      >
                        {label}
                      </button>
                    ))}
                  </div>
                </div>
              </div>

              <div className="pt-3 border-t border-[var(--border-subtle)] divide-y divide-[var(--border-subtle)]">
                <ToggleRow
                  title={t.batch_antiflicker_title}
                  desc={t.batch_antiflicker_desc}
                  checked={poolAntiFlicker}
                  onChange={setPoolAntiFlicker}
                />
                <ToggleRow
                  title={t.batch_spike_title}
                  desc={t.batch_spike_desc}
                  checked={poolSpike}
                  onChange={setPoolSpike}
                />
                <ToggleRow
                  title={t.batch_following_title}
                  desc={t.batch_following_desc}
                  checked={poolFollowing}
                  onChange={setPoolFollowing}
                />
                <ToggleRow
                  title={t.batch_suppress_title}
                  desc={t.batch_suppress_desc}
                  checked={poolSuppressLogs}
                  onChange={setPoolSuppressLogs}
                />
                <ToggleRow
                  title={t.batch_guard_title}
                  desc={t.batch_guard_desc}
                  checked={listGuard}
                  onChange={setListGuard}
                />
              </div>
            </Card>
          </section>

          <div className="flex items-center justify-end gap-3">
            <Button type="submit" variant="primary" size="md" disabled={saving}>
              {saving ? t.saving : t.save}
            </Button>
          </div>
        </form>

        {/* Section 6: Diagnostics & Queue */}
        <section className="space-y-2.5">
          <h2 className="text-xs font-medium text-[var(--text-muted)] px-1">
            {t.section_diagnostics}
          </h2>
          <div className="space-y-2.5">
            {onOpenDownloads && (
              <Card onClick={onOpenDownloads} className="flex items-center justify-between gap-4">
                <div className="flex items-center gap-3 min-w-0">
                  <div className="w-9 h-9 rounded-xl bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center shrink-0">
                    <Download size={17} strokeWidth={1.75} />
                  </div>
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-[var(--text-primary)]">{t.downloads}</div>
                    <div className="text-xs text-[var(--text-muted)] truncate">{t.downloads_subtitle}</div>
                  </div>
                </div>
                <ChevronRight size={17} strokeWidth={1.75} className="text-[var(--text-muted)] shrink-0" />
              </Card>
            )}

            <Card onClick={onOpenDevLogs} className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-9 h-9 rounded-xl bg-[var(--bg-subtle)] text-[var(--text-secondary)] flex items-center justify-center shrink-0">
                  <Terminal size={17} strokeWidth={1.75} />
                </div>
                <div className="min-w-0">
                  <div className="text-sm font-medium text-[var(--text-primary)]">{t.dev_logs}</div>
                  <div className="text-xs text-[var(--text-muted)] truncate">{t.dev_logs_desc}</div>
                </div>
              </div>
              <ChevronRight size={17} strokeWidth={1.75} className="text-[var(--text-muted)] shrink-0" />
            </Card>
          </div>
        </section>
      </div>

      <ConfirmDialog
        isOpen={confirmClearSession}
        title={t.session_clear_confirm_title}
        description={t.session_clear_confirm_desc}
        confirmLabel={t.clear}
        cancelLabel={t.cancel}
        destructive
        onConfirm={handleClearSession}
        onCancel={() => setConfirmClearSession(false)}
      />
    </div>
  );
};
