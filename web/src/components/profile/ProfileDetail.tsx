import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Target,
  ChangeLog,
  ArchivedMedia,
  DownloadItem,
  TargetPoolConfig,
  TargetPoolOverrideMode,
} from '../../types';
import { api } from '../../api/client';
import { Card } from '../common/Card';
import { StatusBadge } from '../common/StatusBadge';
import { Button } from '../common/Button';
import { SegmentedControl } from '../common/SegmentedControl';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { SkeletonTimeline } from '../common/Skeleton';
import { TimelineView } from './TimelineView';
import { MediaGallery } from './MediaGallery';
import { AvatarHistoryModal } from './AvatarHistoryModal';
import { HighlightCirclesRow } from './HighlightCirclesRow';
import { InstagramStoryViewerModal } from './InstagramStoryViewerModal';
import { useI18n } from '../../i18n';
import {
  ArrowLeft,
  RefreshCw,
  Zap,
  Trash2,
  Layers,
  History,
  User,
  SlidersHorizontal,
  AlertCircle,
  CheckCircle2,
  RotateCcw,
  Key,
  Download,
} from 'lucide-react';

interface ProfileDetailProps {
  target: Target;
  onBack: () => void;
  onOpenSettings: () => void;
  onOpenDownloads?: () => void;
  onTargetUpdated: () => void;
}

export const ProfileDetail: React.FC<ProfileDetailProps> = ({
  target,
  onBack,
  onOpenSettings,
  onOpenDownloads,
  onTargetUpdated,
}) => {
  const { t } = useI18n();
  const [activeTab, setActiveTab] = useState<'gallery' | 'timeline'>('gallery');
  const [logs, setLogs] = useState<ChangeLog[]>([]);
  const [media, setMedia] = useState<ArchivedMedia[]>([]);
  const [pool, setPool] = useState<TargetPoolConfig | null>(null);
  const [loadingData, setLoadingData] = useState(true);
  const [hasSession, setHasSession] = useState(true);
  const [isArchiving, setIsArchiving] = useState(false);
  const [syncingTrigger, setSyncingTrigger] = useState(false);
  const [targetDownloads, setTargetDownloads] = useState<DownloadItem[]>([]);
  const [showPoolPanel, setShowPoolPanel] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [feedback, setFeedback] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  const [isAvatarModalOpen, setIsAvatarModalOpen] = useState(false);
  const [activeStoryGroup, setActiveStoryGroup] = useState<{ title: string; items: ArchivedMedia[] } | null>(null);

  const showToast = useCallback((kind: 'ok' | 'err', text: string) => {
    setFeedback({ kind, text });
    window.setTimeout(() => {
      setFeedback((prev) => (prev?.text === text ? null : prev));
    }, 4500);
  }, []);

  const loadDownloadsState = useCallback(async () => {
    try {
      const res = await api.getDownloads();
      setIsArchiving((res.archiving_targets || []).includes(target.id));
      setTargetDownloads(res.items.filter((it) => it.target_id === target.id));
    } catch {}
  }, [target.id]);

  const loadData = useCallback(async () => {
    try {
      const [l, m, p, s] = await Promise.all([
        api.getLogs(target.id),
        api.getMedia(target.id),
        api.getTargetPool(target.id).catch(() => null),
        api.getSettings().catch(() => null),
      ]);
      setLogs(l);
      setMedia(m);
      if (p) setPool(p);
      if (s) setHasSession(Boolean(s.has_session_cookie));
    } catch (e: any) {
      showToast('err', e?.message || t.toast_check_failed);
    } finally {
      setLoadingData(false);
    }
  }, [target.id, showToast, t.toast_check_failed]);

  useEffect(() => {
    setLoadingData(true);
    loadData();
    loadDownloadsState();
  }, [loadData, loadDownloadsState]);

  const downloadMap = useMemo(() => {
    const map: Record<string, DownloadItem> = {};
    for (const item of targetDownloads) {
      map[item.id] = item;
    }
    return map;
  }, [targetDownloads]);

  const activeDownloadsCount = useMemo(
    () => targetDownloads.filter((it) => it.status === 'downloading' || it.status === 'queued').length,
    [targetDownloads]
  );

  // Live WebSocket updates for this target's archival and downloads
  useEffect(() => {
    let ws: WebSocket | null = null;
    try {
      if (window.location.protocol === 'http:' || window.location.protocol === 'https:') {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        ws = new WebSocket(`${protocol}//${window.location.host}/api/ws`);
        ws.onmessage = (event) => {
          try {
            const msg = JSON.parse(event.data);
            if (msg.target_id && msg.target_id !== target.id) return;
            if (msg.type === 'archive_started') {
              setIsArchiving(true);
            } else if (msg.type === 'archive_completed') {
              setIsArchiving(false);
              loadData();
              loadDownloadsState();
              onTargetUpdated();
            } else if (msg.type === 'media_discovered' || msg.type === 'media_updated') {
              loadData();
              loadDownloadsState();
            } else if (msg.type === 'download_update') {
              loadDownloadsState();
            }
          } catch {}
        };
      }
    } catch {}
    return () => {
      try {
        ws?.close();
      } catch {}
    };
  }, [target.id, loadData, loadDownloadsState, onTargetUpdated]);

  // Fallback polling while archiving or active downloads are in flight
  useEffect(() => {
    if (!isArchiving && activeDownloadsCount === 0) return;
    const timer = window.setInterval(() => {
      loadData();
      loadDownloadsState();
    }, 2000);
    return () => window.clearInterval(timer);
  }, [isArchiving, activeDownloadsCount, loadData, loadDownloadsState]);

  const handleSyncMedia = async () => {
    if (syncingTrigger) return;
    setSyncingTrigger(true);
    setIsArchiving(true);
    try {
      await api.syncTargetMedia(target.id);
      await loadDownloadsState();
      showToast('ok', t.downloads_syncing_posts);
    } catch (e: any) {
      setIsArchiving(false);
      showToast('err', `${t.toast_check_failed}: ${e?.message || ''}`);
    } finally {
      setSyncingTrigger(false);
    }
  };

  const handleCheckNow = async () => {
    setRefreshing(true);
    try {
      await api.checkNow(target.id);
      await loadData();
      onTargetUpdated();
      showToast('ok', `@${target.username} ${t.toast_target_updated}`);
    } catch (e: any) {
      showToast('err', `${t.toast_check_failed}: ${e?.message || ''}`);
    } finally {
      setRefreshing(false);
    }
  };

  const handleToggleTurbo = async () => {
    try {
      await api.toggleTurbo(target.id, !target.turbo_mode);
      onTargetUpdated();
    } catch (e: any) {
      showToast('err', e?.message || t.toast_check_failed);
    }
  };

  const handleSavePool = async (patch: Partial<TargetPoolConfig>) => {
    const next = {
      override_mode: pool?.override_mode ?? 'GLOBAL',
      custom_threshold: pool?.custom_threshold ?? 50,
      custom_percent: pool?.custom_percent ?? 0.5,
      custom_digest_minutes: pool?.custom_digest_minutes ?? -1,
      custom_cooldown_minutes: pool?.custom_cooldown_minutes ?? -1,
      ...patch,
    };
    try {
      const updated = await api.updateTargetPool(target.id, next);
      setPool(updated);
      showToast('ok', t.toast_pool_updated);
    } catch (e: any) {
      showToast('err', e?.message || t.toast_check_failed);
    }
  };

  const handleFlushPool = async () => {
    try {
      const updated = await api.flushTargetPool(target.id);
      setPool(updated);
      showToast('ok', t.toast_pool_flushed);
    } catch (e: any) {
      showToast('err', e?.message || t.toast_check_failed);
    }
  };

  const handleDeleteConfirmed = async () => {
    setConfirmDelete(false);
    await api.deleteTarget(target.id);
    onBack();
    onTargetUpdated();
  };

  const isProfileIncomplete =
    target.id.startsWith('usr_') &&
    (target.followers_count || 0) === 0 &&
    (target.following_count || 0) === 0 &&
    target.media_count === 0;

  const activeStories = useMemo(() => media.filter((m) => m.media_type === 'story'), [media]);
  const archivedPostCount = useMemo(
    () => media.filter((m) => m.media_type === 'post' || m.media_type === 'reel').length,
    [media]
  );
  const needsSessionForExtras =
    !loadingData &&
    !hasSession &&
    !isProfileIncomplete &&
    ((target.latest_story_at || 0) > 0 || target.media_count > 12 || media.filter((m) => m.media_type === 'highlight').length === 0);

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
                @{target.username}
              </h1>
              <p className="text-xs text-[var(--text-muted)] truncate">
                {target.full_name || t.no_name}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-1.5 shrink-0">
            <Button
              variant={target.turbo_mode ? 'primary' : 'secondary'}
              size="sm"
              icon={<Zap size={14} strokeWidth={1.75} />}
              onClick={handleToggleTurbo}
            >
              <span className="hidden sm:inline">
                {target.turbo_mode ? t.turbo_active : t.turbo_mode}
              </span>
            </Button>

            <button
              type="button"
              onClick={() => setShowPoolPanel((v) => !v)}
              className={`w-9 h-9 rounded-xl border flex items-center justify-center active:scale-95 transition-[background-color,border-color,color,transform] duration-150 ${
                showPoolPanel
                  ? 'bg-[#0C7FF2] text-white border-[#0C7FF2]'
                  : 'bg-[var(--bg-surface)] border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:border-[var(--border-strong)]'
              }`}
              title={t.batch_override_title}
              aria-label={t.batch_override_title}
            >
              <SlidersHorizontal size={15} strokeWidth={1.75} />
            </button>

            <button
              type="button"
              onClick={handleCheckNow}
              disabled={refreshing}
              className="w-9 h-9 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[#0C7FF2] dark:hover:text-[#58A6FF] hover:border-[var(--border-strong)] disabled:opacity-45 flex items-center justify-center active:scale-95 transition-[color,border-color,transform] duration-150"
              title={t.check_now}
              aria-label={t.check_now}
            >
              <RefreshCw size={15} strokeWidth={1.75} className={refreshing ? 'animate-spin text-[#0C7FF2]' : ''} />
            </button>

            <button
              type="button"
              onClick={() => setConfirmDelete(true)}
              className="w-9 h-9 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-600 dark:text-rose-400 hover:bg-rose-500/15 flex items-center justify-center active:scale-95 transition-[background-color,transform] duration-150"
              title={t.delete}
              aria-label={t.delete}
            >
              <Trash2 size={15} strokeWidth={1.75} />
            </button>
          </div>
        </div>
      </header>

      <div className="max-w-3xl mx-auto px-4 sm:px-6 pt-5 space-y-5">
        {feedback && (
          <div
            role="status"
            className={`rounded-xl px-4 py-3 text-xs font-medium flex items-center gap-2.5 border animate-toast ${
              feedback.kind === 'ok'
                ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-emerald-500/20'
                : 'bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/20'
            }`}
          >
            {feedback.kind === 'ok' ? (
              <CheckCircle2 size={15} strokeWidth={1.75} className="shrink-0" />
            ) : (
              <AlertCircle size={15} strokeWidth={1.75} className="shrink-0" />
            )}
            <span className="flex-1">{feedback.text}</span>
          </div>
        )}

        {isProfileIncomplete && (
          <div className="rounded-2xl p-4 bg-amber-500/10 border border-amber-500/25 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex items-start gap-2.5 text-xs text-amber-800 dark:text-amber-300 leading-relaxed">
              <AlertCircle size={16} strokeWidth={1.75} className="shrink-0 mt-0.5" />
              <span>{t.profile_incomplete_notice}</span>
            </div>
            <Button
              type="button"
              size="sm"
              variant="secondary"
              icon={<Key size={14} strokeWidth={1.75} />}
              onClick={onOpenSettings}
              className="shrink-0"
            >
              {t.open_session_settings}
            </Button>
          </div>
        )}

        {needsSessionForExtras && (
          <div className="rounded-2xl p-3.5 bg-amber-500/10 border border-amber-500/25 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex items-start gap-2.5 text-xs text-amber-800 dark:text-amber-300 leading-relaxed">
              <Key size={15} strokeWidth={1.75} className="shrink-0 mt-0.5" />
              <span>{t.story_highlight_session_notice}</span>
            </div>
            <Button
              type="button"
              size="sm"
              variant="secondary"
              icon={<Key size={13} strokeWidth={1.75} />}
              onClick={onOpenSettings}
              className="shrink-0"
            >
              {t.open_session_settings}
            </Button>
          </div>
        )}

        {/* Profile Overview Card */}
        <Card className="space-y-4">
          <div className="flex items-center gap-4">
            <button
              type="button"
              onClick={() =>
                activeStories.length > 0
                  ? setActiveStoryGroup({ title: t.active_stories, items: activeStories })
                  : setIsAvatarModalOpen(true)
              }
              className={`w-16 h-16 rounded-full shrink-0 transition-transform duration-150 active:scale-95 ${
                activeStories.length > 0
                  ? 'p-[2px] bg-gradient-to-tr from-[#F59E0B] via-[#E1306C] to-[#833AB4]'
                  : 'border border-[var(--border-strong)] hover:border-[#0C7FF2]'
              }`}
              title={activeStories.length > 0 ? t.active_stories : t.avatar_history}
            >
              <div className="w-full h-full rounded-full bg-[var(--bg-subtle)] overflow-hidden flex items-center justify-center p-[2px] bg-[var(--bg-surface)]">
                {target.profile_pic_path ? (
                  <img
                    src={target.profile_pic_path.startsWith('/') ? target.profile_pic_path : `/${target.profile_pic_path}`}
                    alt={target.username}
                    className="w-full h-full object-cover rounded-full"
                  />
                ) : (
                  <User size={24} strokeWidth={1.75} className="text-[var(--text-muted)]" />
                )}
              </div>
            </button>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <h2 className="text-lg font-semibold text-[var(--text-primary)] truncate">
                  @{target.username}
                </h2>
                <StatusBadge status={target.is_private ? 'private' : 'public'} />
                {pool && pool.override_mode !== 'GLOBAL' && (
                  <button
                    type="button"
                    onClick={() => setShowPoolPanel(true)}
                    className="text-[11px] font-medium px-2 py-0.5 rounded-lg bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF]"
                  >
                    {pool.override_mode}
                  </button>
                )}
              </div>
              <p className="text-xs text-[var(--text-secondary)] mt-0.5">
                {target.full_name || t.no_name}
              </p>
            </div>
          </div>

          {target.biography && (
            <p className="text-xs text-[var(--text-secondary)] whitespace-pre-line leading-relaxed bg-[var(--bg-subtle)] p-3.5 rounded-xl border border-[var(--border-subtle)]">
              {target.biography}
            </p>
          )}

          {/* 4 Balanced Tabular Stat Tiles: Followers, Following, Posts, Archived */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 pt-3 border-t border-[var(--border-subtle)]">
            <div className="bg-[var(--bg-subtle)] px-3 py-3 rounded-xl border border-[var(--border-subtle)] text-center">
              <div className="text-lg font-semibold text-[var(--text-primary)] tabular-nums">
                {(target.followers_count || 0).toLocaleString()}
              </div>
              <div className="text-[11px] font-medium text-[var(--text-muted)] mt-0.5">
                {t.followers}
              </div>
            </div>
            <div className="bg-[var(--bg-subtle)] px-3 py-3 rounded-xl border border-[var(--border-subtle)] text-center">
              <div className="text-lg font-semibold text-[var(--text-primary)] tabular-nums">
                {(target.following_count || 0).toLocaleString()}
              </div>
              <div className="text-[11px] font-medium text-[var(--text-muted)] mt-0.5">
                {t.following}
              </div>
            </div>
            <div className="bg-[var(--bg-subtle)] px-3 py-3 rounded-xl border border-[var(--border-subtle)] text-center">
              <div className="text-lg font-semibold text-[var(--text-primary)] tabular-nums">
                {Math.max(target.media_count, media.length).toLocaleString()}
              </div>
              <div className="text-[11px] font-medium text-[var(--text-muted)] mt-0.5">
                {t.instagram_posts}
              </div>
            </div>
            <div className="bg-[var(--bg-subtle)] px-3 py-3 rounded-xl border border-[var(--border-subtle)] text-center">
              <div className="text-lg font-semibold text-[#0C7FF2] dark:text-[#58A6FF] tabular-nums">
                {media.length.toLocaleString()}
              </div>
              <div className="text-[11px] font-medium text-[var(--text-muted)] mt-0.5">
                {t.archived}
              </div>
            </div>
          </div>
        </Card>

        {/* Smooth Collapsible Per-Profile Batching Panel */}
        <div className={`collapse-grid ${showPoolPanel && pool ? 'is-open' : ''}`}>
          <div>
            {pool && (
              <Card className="space-y-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <h3 className="text-sm font-medium text-[var(--text-primary)]">
                      {t.batch_override_title}
                    </h3>
                    <p className="text-xs text-[var(--text-muted)]">{t.batch_override_desc}</p>
                  </div>
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    icon={<RotateCcw size={13} strokeWidth={1.75} />}
                    onClick={handleFlushPool}
                  >
                    {t.batch_flush}
                  </Button>
                </div>

                <SegmentedControl<TargetPoolOverrideMode>
                  value={pool.override_mode}
                  onChange={(mode) => handleSavePool({ override_mode: mode })}
                  options={[
                    { value: 'GLOBAL', label: t.batch_mode_global },
                    { value: 'OFF', label: t.batch_mode_off },
                    { value: 'FIXED_COUNT', label: t.batch_mode_fixed },
                    { value: 'PERCENTAGE', label: t.batch_mode_percent },
                    { value: 'AUTO_ADAPTIVE', label: t.batch_mode_auto },
                  ]}
                />

                {pool.override_mode === 'FIXED_COUNT' && (
                  <div className="flex flex-wrap items-center gap-1.5">
                    {[10, 50, 100, 250, 500, 1000].map((v) => (
                      <button
                        key={v}
                        type="button"
                        onClick={() => handleSavePool({ custom_threshold: v })}
                        className={`h-8 px-3 rounded-lg text-xs font-mono tabular-nums border transition-colors duration-150 ${
                          pool.custom_threshold === v
                            ? 'bg-[var(--accent-soft)] border-[#0C7FF2] text-[#0C7FF2] dark:text-[#58A6FF] font-medium'
                            : 'bg-[var(--bg-subtle)] border-[var(--border-subtle)] text-[var(--text-secondary)]'
                        }`}
                      >
                        ±{v}
                      </button>
                    ))}
                  </div>
                )}

                {pool.override_mode === 'PERCENTAGE' && (
                  <div className="flex flex-wrap items-center gap-1.5">
                    {[0.1, 0.5, 1.0, 2.0, 5.0].map((pct) => (
                      <button
                        key={pct}
                        type="button"
                        onClick={() => handleSavePool({ custom_percent: pct })}
                        className={`h-8 px-3 rounded-lg text-xs font-mono tabular-nums border transition-colors duration-150 ${
                          pool.custom_percent === pct
                            ? 'bg-[var(--accent-soft)] border-[#0C7FF2] text-[#0C7FF2] dark:text-[#58A6FF] font-medium'
                            : 'bg-[var(--bg-subtle)] border-[var(--border-subtle)] text-[var(--text-secondary)]'
                        }`}
                      >
                        {pct}%
                      </button>
                    ))}
                  </div>
                )}
              </Card>
            )}
          </div>
        </div>

        {(isArchiving || activeDownloadsCount > 0) ? (
          <div className="rounded-2xl p-3.5 bg-[var(--bg-surface)] border border-[var(--border-subtle)] flex items-center justify-between gap-3">
            <div className="flex items-center gap-2.5 min-w-0 text-xs text-[var(--text-secondary)]">
              <RefreshCw size={14} strokeWidth={1.75} className="animate-spin text-[#0C7FF2] shrink-0" />
              <span className="truncate">
                {t.downloads_syncing_posts}
                {activeDownloadsCount > 0 ? ` (${activeDownloadsCount})` : ''}
              </span>
            </div>
            {onOpenDownloads && (
              <Button
                type="button"
                size="sm"
                variant="secondary"
                icon={<Download size={13} strokeWidth={1.75} />}
                onClick={onOpenDownloads}
                className="shrink-0"
              >
                {t.downloads}
              </Button>
            )}
          </div>
        ) : !loadingData && !target.is_private && target.media_count > archivedPostCount ? (
          <div className="rounded-2xl p-3.5 bg-[var(--bg-surface)] border border-[var(--border-subtle)] flex items-center justify-between gap-3">
            <span className="text-xs text-[var(--text-secondary)] tabular-nums">
              {archivedPostCount} / {target.media_count} {t.posts}
            </span>
            <Button
              type="button"
              size="sm"
              variant="primary"
              icon={<Download size={13} strokeWidth={1.75} />}
              onClick={handleSyncMedia}
              disabled={syncingTrigger}
              className="shrink-0"
            >
              {t.downloads_sync_media}
            </Button>
          </div>
        ) : null}

        <HighlightCirclesRow
          media={media}
          onSelectHighlight={(title, items) => setActiveStoryGroup({ title, items })}
        />

        <SegmentedControl<'gallery' | 'timeline'>
          value={activeTab}
          onChange={setActiveTab}
          options={[
            {
              value: 'gallery',
              label: t.archive_gallery,
              count: media.length,
              icon: <Layers size={14} strokeWidth={1.75} />,
            },
            {
              value: 'timeline',
              label: t.audit_timeline,
              count: logs.length,
              icon: <History size={14} strokeWidth={1.75} />,
            },
          ]}
        />

        <div key={activeTab} className="animate-view-enter">
          {activeTab === 'gallery' ? (
            <MediaGallery
              media={media}
              loading={loadingData}
              syncing={isArchiving || activeDownloadsCount > 0}
              expectedCount={target.media_count}
              downloadMap={downloadMap}
              onSyncMedia={!target.is_private ? handleSyncMedia : undefined}
            />
          ) : loadingData ? (
            <SkeletonTimeline count={3} />
          ) : (
            <TimelineView logs={logs} />
          )}
        </div>

        <AvatarHistoryModal
          isOpen={isAvatarModalOpen}
          onClose={() => setIsAvatarModalOpen(false)}
          targetId={target.id}
          username={target.username}
        />

        {activeStoryGroup && (
          <InstagramStoryViewerModal
            title={activeStoryGroup.title}
            items={activeStoryGroup.items}
            onClose={() => setActiveStoryGroup(null)}
          />
        )}

        <ConfirmDialog
          isOpen={confirmDelete}
          title={t.confirm_delete_title}
          description={t.confirm_delete_desc}
          confirmLabel={t.delete}
          cancelLabel={t.cancel}
          destructive
          onConfirm={handleDeleteConfirmed}
          onCancel={() => setConfirmDelete(false)}
        />
      </div>
    </div>
  );
};
