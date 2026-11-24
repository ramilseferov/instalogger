import React, { useState, useEffect } from 'react';
import { Target, ChangeLog, ArchivedMedia, TargetPoolConfig, TargetPoolOverrideMode } from '../../types';
import { api } from '../../api/client';
import { Card } from '../common/Card';
import { StatusBadge } from '../common/StatusBadge';
import { Button } from '../common/Button';
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
} from 'lucide-react';

interface ProfileDetailProps {
  target: Target;
  onBack: () => void;
  onTargetUpdated: () => void;
}

const POOL_OVERRIDE_MODES: { value: TargetPoolOverrideMode; label: string }[] = [
  { value: 'GLOBAL', label: 'Global' },
  { value: 'OFF', label: 'Instant (Off)' },
  { value: 'FIXED_COUNT', label: 'Fixed' },
  { value: 'PERCENTAGE', label: 'Percent (%)' },
  { value: 'AUTO_ADAPTIVE', label: 'Auto' },
];

export const ProfileDetail: React.FC<ProfileDetailProps> = ({
  target,
  onBack,
  onTargetUpdated,
}) => {
  const { t } = useI18n();
  const [activeTab, setActiveTab] = useState<'gallery' | 'timeline'>('gallery');
  const [logs, setLogs] = useState<ChangeLog[]>([]);
  const [media, setMedia] = useState<ArchivedMedia[]>([]);
  const [pool, setPool] = useState<TargetPoolConfig | null>(null);
  const [showPoolPanel, setShowPoolPanel] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [feedback, setFeedback] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  const [isAvatarModalOpen, setIsAvatarModalOpen] = useState(false);
  const [activeStoryGroup, setActiveStoryGroup] = useState<{ title: string; items: ArchivedMedia[] } | null>(null);

  const showToast = (kind: 'ok' | 'err', text: string) => {
    setFeedback({ kind, text });
    window.setTimeout(() => setFeedback(null), 4000);
  };

  const loadData = async () => {
    try {
      const [l, m, p] = await Promise.all([
        api.getLogs(target.id),
        api.getMedia(target.id),
        api.getTargetPool(target.id).catch(() => null),
      ]);
      setLogs(l);
      setMedia(m);
      if (p) setPool(p);
    } catch (e: any) {
      showToast('err', e?.message || 'Failed to load profile data');
    }
  };

  useEffect(() => {
    loadData();
  }, [target.id]);

  const handleCheckNow = async () => {
    setRefreshing(true);
    try {
      await api.checkNow(target.id);
      await loadData();
      onTargetUpdated();
      showToast('ok', `@${target.username} checked successfully`);
    } catch (e: any) {
      showToast('err', `Check failed: ${e?.message || 'Unknown error'}`);
    } finally {
      setRefreshing(false);
    }
  };

  const handleToggleTurbo = async () => {
    try {
      await api.toggleTurbo(target.id, !target.turbo_mode);
      onTargetUpdated();
    } catch (e: any) {
      showToast('err', e?.message || 'Failed to update turbo mode');
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
      showToast('ok', `Pool mode updated: ${updated.override_mode}`);
    } catch (e: any) {
      showToast('err', e?.message || 'Failed to save pool policy');
    }
  };

  const handleFlushPool = async () => {
    try {
      const updated = await api.flushTargetPool(target.id);
      setPool(updated);
      showToast('ok', 'Pool baseline flushed and reset');
    } catch (e: any) {
      showToast('err', e?.message || 'Failed to flush pool');
    }
  };

  const handleDelete = async () => {
    if (confirm(t.confirm_delete_desc)) {
      await api.deleteTarget(target.id);
      onBack();
      onTargetUpdated();
    }
  };

  return (
    <div className="min-h-screen bg-[#F2F4F7] dark:bg-slate-950 transition-colors">
      <div className="max-w-3xl mx-auto px-4 py-6 space-y-5">
        {feedback && (
          <div
            className={`rounded-xl px-4 py-2.5 text-xs font-semibold flex items-center gap-2 border ${
              feedback.kind === 'ok'
                ? 'bg-emerald-50 dark:bg-emerald-950/50 text-emerald-700 dark:text-emerald-300 border-emerald-200 dark:border-emerald-800'
                : 'bg-rose-50 dark:bg-rose-950/50 text-rose-700 dark:text-rose-300 border-rose-200 dark:border-rose-800'
            }`}
          >
            {feedback.kind === 'ok' ? <CheckCircle2 size={15} /> : <AlertCircle size={15} />}
            <span className="flex-1">{feedback.text}</span>
          </div>
        )}

        {/* Top Action Bar */}
        <div className="flex items-center justify-between">
          <button
            onClick={onBack}
            className="p-2.5 rounded-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-200 hover:text-[#0C7FF2] transition-colors"
            aria-label={t.back}
          >
            <ArrowLeft size={18} />
          </button>

          <div className="flex items-center gap-2">
            <Button
              variant={target.turbo_mode ? 'primary' : 'secondary'}
              size="sm"
              icon={<Zap size={14} />}
              onClick={handleToggleTurbo}
            >
              {target.turbo_mode ? t.turbo_active : t.turbo_mode}
            </Button>

            <button
              onClick={() => setShowPoolPanel((v) => !v)}
              className={`p-2.5 rounded-full border transition-colors ${
                showPoolPanel
                  ? 'bg-[#0C7FF2] text-white border-[#0C7FF2]'
                  : 'bg-white dark:bg-slate-900 border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-200 hover:text-[#0C7FF2]'
              }`}
              title="Follower Change Pool Settings"
              aria-label="Follower Change Pool Settings"
            >
              <SlidersHorizontal size={16} />
            </button>

            <button
              onClick={handleCheckNow}
              disabled={refreshing}
              className="p-2.5 rounded-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-200 hover:text-[#0C7FF2] disabled:opacity-50 transition-colors"
              title={t.check_now}
              aria-label={t.check_now}
            >
              <RefreshCw size={16} className={refreshing ? 'animate-spin text-[#0C7FF2]' : ''} />
            </button>

            <button
              onClick={handleDelete}
              className="p-2.5 rounded-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-rose-600 hover:bg-rose-50 dark:hover:bg-rose-950/40 transition-colors"
              title={t.delete}
              aria-label={t.delete}
            >
              <Trash2 size={16} />
            </button>
          </div>
        </div>

        {/* Profile Overview Card */}
        <Card className="space-y-4">
          <div className="flex items-center gap-4">
            <div
              onClick={() => setIsAvatarModalOpen(true)}
              className="w-16 h-16 rounded-full bg-slate-100 dark:bg-slate-800 overflow-hidden flex items-center justify-center border-2 border-slate-200 dark:border-slate-700 shrink-0 cursor-pointer hover:border-[#0C7FF2] transition-colors"
              title={t.avatar_history}
            >
              {target.profile_pic_path ? (
                <img
                  src={target.profile_pic_path.startsWith('/') ? target.profile_pic_path : `/${target.profile_pic_path}`}
                  alt={target.username}
                  className="w-full h-full object-cover"
                />
              ) : (
                <User size={28} className="text-slate-400" />
              )}
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <h2 className="text-xl font-bold text-slate-900 dark:text-white truncate">
                  @{target.username}
                </h2>
                <StatusBadge status={target.is_private ? 'private' : 'public'} />
                {pool && pool.override_mode !== 'GLOBAL' && (
                  <button
                    onClick={() => setShowPoolPanel(true)}
                    className="text-[11px] font-semibold px-2.5 py-0.5 rounded-full bg-blue-500/15 text-[#0C7FF2] dark:text-blue-300 border border-blue-500/30"
                  >
                    Pool: {pool.override_mode}
                  </button>
                )}
              </div>
              <p className="text-xs text-slate-500 dark:text-slate-400 font-medium mt-0.5">
                {target.full_name || t.no_name}
              </p>
            </div>
          </div>

          {target.biography && (
            <p className="text-xs text-slate-700 dark:text-slate-300 whitespace-pre-line leading-relaxed bg-slate-50 dark:bg-slate-800/60 p-3.5 rounded-xl border border-slate-100 dark:border-slate-800">
              {target.biography}
            </p>
          )}

          <div className="grid grid-cols-3 gap-2.5 pt-2 border-t border-slate-100 dark:border-slate-800 text-center">
            <div className="bg-slate-50 dark:bg-slate-800/60 py-2.5 rounded-xl border border-slate-100 dark:border-slate-800">
              <span className="text-lg font-extrabold text-slate-900 dark:text-white">
                {Math.max(target.media_count, media.length)}
              </span>
              <p className="text-[11px] font-semibold text-slate-400 uppercase">{t.instagram_posts}</p>
            </div>
            <div className="bg-slate-50 dark:bg-slate-800/60 py-2.5 rounded-xl border border-slate-100 dark:border-slate-800">
              <span className="text-lg font-extrabold text-[#0C7FF2]">{media.length}</span>
              <p className="text-[11px] font-semibold text-slate-400 uppercase">{t.local_archive_files}</p>
            </div>
            <div className="bg-slate-50 dark:bg-slate-800/60 py-2.5 rounded-xl border border-slate-100 dark:border-slate-800">
              <span className="text-lg font-extrabold text-slate-900 dark:text-white">{logs.length}</span>
              <p className="text-[11px] font-semibold text-slate-400 uppercase">{t.audit_timeline}</p>
            </div>
          </div>
        </Card>

        {/* Per-Target Follower Change Pool Controls */}
        {showPoolPanel && pool && (
          <Card className="space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                  Follower Change Pool Override (@{target.username})
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Inherit global settings, stay on classic instant alerts, or set a custom pool threshold.
                </p>
              </div>
              <button
                onClick={handleFlushPool}
                className="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-semibold text-slate-700 dark:text-slate-200 hover:border-[#0C7FF2] flex items-center gap-1.5"
              >
                <RotateCcw size={13} />
                Flush / Reset
              </button>
            </div>

            <div className="flex flex-wrap gap-2">
              {POOL_OVERRIDE_MODES.map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => handleSavePool({ override_mode: opt.value })}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition-colors ${
                    pool.override_mode === opt.value
                      ? 'bg-[#0C7FF2] text-white border-[#0C7FF2]'
                      : 'bg-slate-50 dark:bg-slate-800 text-slate-700 dark:text-slate-300 border-slate-200 dark:border-slate-700'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>

            {pool.override_mode === 'FIXED_COUNT' && (
              <div className="flex flex-wrap items-center gap-2">
                {[10, 50, 100, 250, 500, 1000].map((v) => (
                  <button
                    key={v}
                    onClick={() => handleSavePool({ custom_threshold: v })}
                    className={`px-2.5 py-1 rounded-md text-xs font-mono border ${
                      pool.custom_threshold === v
                        ? 'bg-blue-500/15 border-[#0C7FF2] text-[#0C7FF2] font-bold'
                        : 'border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300'
                    }`}
                  >
                    ±{v}
                  </button>
                ))}
              </div>
            )}

            {pool.override_mode === 'PERCENTAGE' && (
              <div className="flex flex-wrap items-center gap-2">
                {[0.1, 0.5, 1.0, 2.0, 5.0].map((pct) => (
                  <button
                    key={pct}
                    onClick={() => handleSavePool({ custom_percent: pct })}
                    className={`px-2.5 py-1 rounded-md text-xs font-mono border ${
                      pool.custom_percent === pct
                        ? 'bg-blue-500/15 border-[#0C7FF2] text-[#0C7FF2] font-bold'
                        : 'border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300'
                    }`}
                  >
                    {pct}%
                  </button>
                ))}
              </div>
            )}
          </Card>
        )}

        {/* Highlights Circles */}
        <HighlightCirclesRow
          media={media}
          onSelectHighlight={(title, items) => setActiveStoryGroup({ title, items })}
        />

        {/* Tabs */}
        <div className="flex items-center bg-slate-200/70 dark:bg-slate-900 p-1 rounded-xl border border-slate-200/80 dark:border-slate-800">
          <button
            onClick={() => setActiveTab('gallery')}
            className={`flex-1 flex items-center justify-center gap-2 py-2 rounded-lg text-xs font-bold transition-colors ${
              activeTab === 'gallery'
                ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-white shadow-xs'
                : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
            }`}
          >
            <Layers size={15} />
            {t.archive_gallery} ({media.length})
          </button>
          <button
            onClick={() => setActiveTab('timeline')}
            className={`flex-1 flex items-center justify-center gap-2 py-2 rounded-lg text-xs font-bold transition-colors ${
              activeTab === 'timeline'
                ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-white shadow-xs'
                : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
            }`}
          >
            <History size={15} />
            {t.audit_timeline} ({logs.length})
          </button>
        </div>

        {activeTab === 'gallery' ? <MediaGallery media={media} /> : <TimelineView logs={logs} />}

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
      </div>
    </div>
  );
};
