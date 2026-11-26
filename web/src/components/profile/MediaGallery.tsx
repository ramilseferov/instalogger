import React, { useState, useMemo } from 'react';
import { ArchivedMedia, DownloadItem } from '../../types';
import {
  Film,
  EyeOff,
  Layers,
  Folder,
  Download,
  RefreshCw,
  CheckCircle2,
  AlertCircle,
  Trash2,
  HardDrive,
  Clock,
} from 'lucide-react';
import { MediaViewerModal } from './MediaViewerModal';
import { SkeletonPostGrid } from '../common/Skeleton';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { useI18n } from '../../i18n';

interface MediaGalleryProps {
  media: ArchivedMedia[];
  loading?: boolean;
  syncing?: boolean;
  expectedCount?: number;
  downloadMap?: Record<string, DownloadItem>;
  onSyncMedia?: () => void;
  onDeleteMedia?: (item: ArchivedMedia) => Promise<void> | void;
}

type GalleryTab = 'all' | 'post' | 'reel' | 'story' | 'highlight' | 'vanished';

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function getTilePreviewUrl(item: ArchivedMedia): string {
  const raw = item.relative_file_path || '';
  if (!raw) return item.cdn_url || '';
  const clean = raw.startsWith('/') ? raw : `/${raw}`;
  if (/\.mp4$/i.test(clean)) {
    return clean.replace(/\.mp4$/i, '_thumb.jpg');
  }
  return clean;
}

function isStoryStillActive(item: ArchivedMedia): boolean {
  if (item.is_vanished || item.media_type !== 'story') return false;
  const postedMs = Date.parse(item.posted_at);
  if (Number.isNaN(postedMs)) return false;
  return Date.now() - postedMs < 24 * 60 * 60 * 1000;
}

export const MediaGallery: React.FC<MediaGalleryProps> = ({
  media,
  loading = false,
  syncing = false,
  expectedCount = 0,
  downloadMap,
  onSyncMedia,
  onDeleteMedia,
}) => {
  const { t } = useI18n();
  const [activeTab, setActiveTab] = useState<GalleryTab>('all');
  const [selectedMedia, setSelectedMedia] = useState<ArchivedMedia | null>(null);
  const [pendingDelete, setPendingDelete] = useState<ArchivedMedia | null>(null);

  const filteredMedia = useMemo(() => {
    return media.filter((item) => {
      if (activeTab === 'all') return true;
      if (activeTab === 'vanished') return item.is_vanished;
      return item.media_type === activeTab;
    });
  }, [media, activeTab]);

  const storiesList = useMemo(
    () =>
      media
        .filter((m) => m.media_type === 'story')
        .sort((a, b) => Date.parse(b.posted_at) - Date.parse(a.posted_at)),
    [media]
  );

  const storyStats = useMemo(() => {
    const totalBytes = storiesList.reduce((sum, s) => sum + (s.file_size_bytes || 0), 0);
    const activeCount = storiesList.filter((s) => isStoryStillActive(s)).length;
    const deletedCount = storiesList.filter((s) => s.is_vanished).length;
    return { totalBytes, activeCount, deletedCount };
  }, [storiesList]);

  const highlightGroups = useMemo(() => {
    return media
      .filter((m) => m.media_type === 'highlight')
      .reduce((acc, item) => {
        const title = item.highlight_title || t.highlights;
        if (!acc[title]) acc[title] = [];
        acc[title].push(item);
        return acc;
      }, {} as Record<string, ArchivedMedia[]>);
  }, [media, t.highlights]);

  const tabs: { id: GalleryTab; label: string; count: number }[] = [
    { id: 'all', label: t.all, count: media.length },
    { id: 'post', label: t.instagram_posts, count: media.filter((m) => m.media_type === 'post').length },
    { id: 'reel', label: t.reels, count: media.filter((m) => m.media_type === 'reel').length },
    { id: 'story', label: t.story_archive_title, count: storiesList.length },
    { id: 'highlight', label: t.highlights, count: media.filter((m) => m.media_type === 'highlight').length },
    { id: 'vanished', label: t.vanished, count: media.filter((m) => m.is_vanished).length },
  ];

  const pendingSkeletons =
    syncing && activeTab === 'all' && media.length > 0 && expectedCount > media.length
      ? Math.min(6, expectedCount - media.length)
      : 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-1.5 overflow-x-auto pb-1 no-scrollbar">
        {tabs.map((tab) => {
          const selected = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => setActiveTab(tab.id)}
              className={`h-8 px-3 rounded-lg text-xs font-medium whitespace-nowrap border transition-colors duration-150 ${
                selected
                  ? 'bg-[#0C7FF2] text-white border-[#0C7FF2]'
                  : 'bg-[var(--bg-surface)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border-[var(--border-subtle)] hover:border-[var(--border-strong)]'
              }`}
            >
              {tab.label} <span className="tabular-nums opacity-80">({tab.count})</span>
            </button>
          );
        })}
      </div>

      {loading || (syncing && media.length === 0) ? (
        <SkeletonPostGrid
          count={expectedCount > 0 ? expectedCount : 6}
          statusLabel={syncing ? t.downloads_syncing_posts : undefined}
        />
      ) : activeTab === 'story' ? (
        <div className="space-y-4">
          {/* Story Archive Summary Header */}
          <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)] flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap items-center gap-3 text-xs">
              <span className="font-semibold text-[var(--text-primary)]">
                {t.story_archive_title} ({storiesList.length})
              </span>
              <span className="px-2 py-0.5 rounded-md bg-emerald-500/12 text-emerald-700 dark:text-emerald-400 font-medium tabular-nums">
                {t.story_status_active}: {storyStats.activeCount}
              </span>
              {storyStats.deletedCount > 0 && (
                <span className="px-2 py-0.5 rounded-md bg-rose-500/12 text-rose-600 dark:text-rose-400 font-medium tabular-nums">
                  {t.story_status_deleted}: {storyStats.deletedCount}
                </span>
              )}
              <span className="inline-flex items-center gap-1 font-mono text-[var(--text-secondary)] tabular-nums">
                <HardDrive size={12} strokeWidth={1.75} />
                {t.story_storage_used}: {formatBytes(storyStats.totalBytes)}
              </span>
            </div>
            {onSyncMedia && (
              <Button
                type="button"
                size="sm"
                variant="secondary"
                icon={<RefreshCw size={13} className={syncing ? 'animate-spin' : ''} />}
                onClick={onSyncMedia}
              >
                {t.check_now}
              </Button>
            )}
          </div>

          {storiesList.length === 0 ? (
            <div className="py-12 px-6 text-center bg-[var(--bg-surface)] rounded-2xl border border-[var(--border-subtle)] space-y-2">
              <Layers className="w-8 h-8 text-[var(--text-muted)] mx-auto" strokeWidth={1.5} />
              <p className="text-sm font-medium text-[var(--text-primary)]">{t.gallery_empty_title}</p>
              <p className="text-xs text-[var(--text-muted)] max-w-sm mx-auto">{t.gallery_empty_desc}</p>
            </div>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
              {storiesList.map((item) => {
                const active = isStoryStillActive(item);
                return (
                  <div
                    key={item.id}
                    className="rounded-2xl overflow-hidden bg-[var(--bg-surface)] border border-[var(--border-subtle)] flex flex-col"
                  >
                    <button
                      type="button"
                      onClick={() => setSelectedMedia(item)}
                      className="aspect-[9/14] relative bg-[var(--bg-subtle)] group overflow-hidden"
                    >
                      <img
                        src={getTilePreviewUrl(item)}
                        alt={item.caption || ''}
                        loading="lazy"
                        className="w-full h-full object-cover group-hover:opacity-92 transition-opacity"
                        onError={(e) => {
                          const img = e.currentTarget;
                          if (item.cdn_url && !img.dataset.fallbackApplied) {
                            img.dataset.fallbackApplied = '1';
                            img.src = item.cdn_url;
                          }
                        }}
                      />
                      <span
                        className={`absolute top-2 left-2 px-2 py-0.5 rounded-md text-[10px] font-semibold ${
                          item.is_vanished
                            ? 'bg-rose-600/90 text-white'
                            : active
                            ? 'bg-emerald-600/90 text-white'
                            : 'bg-black/65 text-white/90'
                        }`}
                      >
                        {item.is_vanished
                          ? t.story_status_deleted
                          : active
                          ? t.story_status_active
                          : t.story_status_archived}
                      </span>
                      {/\.mp4$/i.test(item.relative_file_path) && (
                        <span className="absolute bottom-2 right-2 p-1 rounded-md bg-black/65 text-white">
                          <Film size={12} strokeWidth={1.75} />
                        </span>
                      )}
                    </button>
                    <div className="px-2.5 py-2 flex items-center justify-between gap-2 text-[11px] border-t border-[var(--border-subtle)]">
                      <div className="min-w-0">
                        <div className="flex items-center gap-1 text-[var(--text-secondary)] tabular-nums truncate">
                          <Clock size={10} />
                          {new Date(item.posted_at).toLocaleString(undefined, {
                            day: '2-digit',
                            month: '2-digit',
                            hour: '2-digit',
                            minute: '2-digit',
                          })}
                        </div>
                        <div className="font-mono text-[10px] text-[var(--text-muted)] tabular-nums">
                          {formatBytes(item.file_size_bytes)}
                        </div>
                      </div>
                      {onDeleteMedia && (
                        <button
                          type="button"
                          onClick={() => setPendingDelete(item)}
                          className="p-1.5 rounded-lg text-[var(--text-muted)] hover:text-rose-600 dark:hover:text-rose-400 hover:bg-rose-500/10 transition-colors shrink-0"
                          title={t.delete_from_storage}
                        >
                          <Trash2 size={13} strokeWidth={1.75} />
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ) : activeTab === 'highlight' && Object.keys(highlightGroups).length > 0 ? (
        <div className="space-y-4">
          {Object.entries(highlightGroups).map(([title, items]) => {
            const hlBytes = items.reduce((s, it) => s + (it.file_size_bytes || 0), 0);
            const hlDeletedCount = items.filter((it) => it.is_vanished).length;
            return (
              <div
                key={title}
                className="bg-[var(--bg-surface)] p-4 rounded-2xl border border-[var(--border-subtle)] space-y-3"
              >
                <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                  <div className="flex items-center gap-2 font-medium text-[var(--text-primary)]">
                    <Folder size={15} strokeWidth={1.75} className="text-[var(--text-secondary)]" />
                    <span>{title}</span>
                    <span className="text-[var(--text-muted)] tabular-nums">
                      ({items.length} {t.files_count})
                    </span>
                    {hlDeletedCount > 0 && (
                      <span className="px-1.5 py-0.5 rounded bg-rose-500/12 text-rose-600 dark:text-rose-400 text-[10px] font-semibold">
                        {t.story_status_deleted}: {hlDeletedCount}
                      </span>
                    )}
                  </div>
                  <span className="inline-flex items-center gap-1 font-mono text-[11px] text-[var(--text-muted)] tabular-nums">
                    <HardDrive size={12} /> {formatBytes(hlBytes)}
                  </span>
                </div>
                <div className="grid grid-cols-3 sm:grid-cols-4 gap-2">
                  {items.map((item) => (
                    <div
                      key={item.id}
                      className="rounded-xl overflow-hidden bg-[var(--bg-subtle)] border border-[var(--border-subtle)] flex flex-col"
                    >
                      <button
                        type="button"
                        onClick={() => setSelectedMedia(item)}
                        className="aspect-square relative group overflow-hidden"
                      >
                        <img
                          src={getTilePreviewUrl(item)}
                          alt={item.caption || title}
                          loading="lazy"
                          className="w-full h-full object-cover group-hover:opacity-90 transition-opacity duration-150"
                          onError={(e) => {
                            const img = e.currentTarget;
                            if (item.cdn_url && !img.dataset.fallbackApplied) {
                              img.dataset.fallbackApplied = '1';
                              img.src = item.cdn_url;
                            }
                          }}
                        />
                        {item.is_vanished && (
                          <span className="absolute top-1.5 left-1.5 px-1.5 py-0.5 rounded bg-rose-600/90 text-white text-[9px] font-semibold">
                            {t.story_status_deleted}
                          </span>
                        )}
                        {/\.mp4$/i.test(item.relative_file_path) && (
                          <span className="absolute bottom-1.5 right-1.5 p-1 rounded-md bg-black/65 text-white">
                            <Film size={12} strokeWidth={1.75} />
                          </span>
                        )}
                      </button>
                      <div className="px-2 py-1 flex items-center justify-between gap-1 text-[10px] bg-[var(--bg-surface)] border-t border-[var(--border-subtle)]">
                        <span className="font-mono text-[var(--text-muted)] tabular-nums truncate">
                          {formatBytes(item.file_size_bytes)}
                        </span>
                        {onDeleteMedia && (
                          <button
                            type="button"
                            onClick={() => setPendingDelete(item)}
                            className="p-1 rounded text-[var(--text-muted)] hover:text-rose-600 dark:hover:text-rose-400"
                            title={t.delete_from_storage}
                          >
                            <Trash2 size={11} />
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      ) : filteredMedia.length === 0 ? (
        <div className="py-12 px-6 text-center bg-[var(--bg-surface)] rounded-2xl border border-[var(--border-subtle)] space-y-3">
          <Layers className="w-8 h-8 text-[var(--text-muted)] mx-auto" strokeWidth={1.5} />
          <div className="space-y-1">
            <p className="text-sm font-medium text-[var(--text-primary)]">{t.gallery_empty_title}</p>
            <p className="text-xs text-[var(--text-muted)] max-w-sm mx-auto">{t.gallery_empty_desc}</p>
          </div>
          {activeTab === 'all' && expectedCount > 0 && onSyncMedia && (
            <div className="pt-1">
              <Button
                type="button"
                variant="primary"
                size="sm"
                icon={<Download size={14} strokeWidth={1.75} />}
                onClick={onSyncMedia}
              >
                {t.downloads_sync_media} ({expectedCount})
              </Button>
            </div>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
          {filteredMedia.map((item, idx) => {
            const dl = downloadMap?.[item.id];
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setSelectedMedia(item)}
                className={`aspect-square relative rounded-2xl overflow-hidden bg-[var(--bg-subtle)] border border-[var(--border-subtle)] hover:border-[var(--border-strong)] group active:scale-[0.98] transition-[border-color,transform] duration-150 animate-card-enter stagger-${Math.min(
                  (idx % 5) + 1,
                  5
                )}`}
              >
                <img
                  src={getTilePreviewUrl(item)}
                  alt={item.caption || ''}
                  loading="lazy"
                  className="w-full h-full object-cover group-hover:opacity-92 transition-opacity duration-150"
                  onError={(e) => {
                    const img = e.currentTarget;
                    if (item.cdn_url && !img.dataset.fallbackApplied) {
                      img.dataset.fallbackApplied = '1';
                      img.src = item.cdn_url;
                    }
                  }}
                />

                {item.is_vanished && (
                  <span
                    className="absolute top-2 left-2 p-1.5 rounded-lg bg-rose-600/90 text-white"
                    title={t.vanished_badge}
                  >
                    <EyeOff size={12} strokeWidth={1.75} />
                  </span>
                )}

                {dl && (dl.status === 'downloading' || dl.status === 'queued') && (
                  <span
                    className="absolute top-2 right-2 p-1.5 rounded-lg bg-black/70 text-white"
                    title={t.downloads_active}
                  >
                    <RefreshCw size={11} strokeWidth={2} className="animate-spin" />
                  </span>
                )}

                {dl && dl.status === 'completed' && (
                  <span
                    className="absolute top-2 right-2 p-1 rounded-lg bg-emerald-600/85 text-white"
                    title={t.downloads_completed}
                  >
                    <CheckCircle2 size={11} strokeWidth={2} />
                  </span>
                )}

                {dl && dl.status === 'failed' && (
                  <span
                    className="absolute top-2 right-2 p-1 rounded-lg bg-rose-600/90 text-white"
                    title={dl.error || t.downloads_failed}
                  >
                    <AlertCircle size={11} strokeWidth={2} />
                  </span>
                )}

                {item.file_size_bytes > 0 && (
                  <span className="absolute bottom-2 left-2 px-1.5 py-0.5 rounded-md bg-black/65 text-white text-[10px] font-mono tabular-nums">
                    {formatBytes(item.file_size_bytes)}
                  </span>
                )}

                {(item.media_type === 'reel' || /\.mp4$/i.test(item.relative_file_path)) && (
                  <span className="absolute bottom-2 right-2 p-1.5 rounded-lg bg-black/65 text-white">
                    <Film size={13} strokeWidth={1.75} />
                  </span>
                )}
              </button>
            );
          })}

          {Array.from({ length: pendingSkeletons }).map((_, i) => (
            <div
              key={`pending-sk-${i}`}
              className="aspect-square rounded-2xl border border-[var(--border-subtle)] skeleton-box"
            />
          ))}
        </div>
      )}

      {selectedMedia && (
        <MediaViewerModal
          media={selectedMedia}
          onClose={() => setSelectedMedia(null)}
          onDeleteMedia={onDeleteMedia}
        />
      )}

      <ConfirmDialog
        isOpen={pendingDelete !== null}
        title={t.confirm_delete_media_title}
        description={t.confirm_delete_media_desc}
        confirmLabel={t.delete}
        cancelLabel={t.cancel}
        destructive
        onConfirm={async () => {
          if (pendingDelete && onDeleteMedia) {
            await onDeleteMedia(pendingDelete);
          }
          setPendingDelete(null);
        }}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
};
