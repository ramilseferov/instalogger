import React, { useState, useMemo } from 'react';
import { ArchivedMedia, DownloadItem } from '../../types';
import { Film, EyeOff, Layers, Folder, Download, RefreshCw, CheckCircle2, AlertCircle } from 'lucide-react';
import { MediaViewerModal } from './MediaViewerModal';
import { SkeletonPostGrid } from '../common/Skeleton';
import { Button } from '../common/Button';
import { useI18n } from '../../i18n';

interface MediaGalleryProps {
  media: ArchivedMedia[];
  loading?: boolean;
  syncing?: boolean;
  expectedCount?: number;
  downloadMap?: Record<string, DownloadItem>;
  onSyncMedia?: () => void;
}

type GalleryTab = 'all' | 'post' | 'reel' | 'story' | 'highlight' | 'vanished';

function getTilePreviewUrl(item: ArchivedMedia): string {
  const raw = item.relative_file_path || '';
  if (!raw) return item.cdn_url || '';
  const clean = raw.startsWith('/') ? raw : `/${raw}`;
  if (/\.mp4$/i.test(clean)) {
    return clean.replace(/\.mp4$/i, '_thumb.jpg');
  }
  return clean;
}

export const MediaGallery: React.FC<MediaGalleryProps> = ({
  media,
  loading = false,
  syncing = false,
  expectedCount = 0,
  downloadMap,
  onSyncMedia,
}) => {
  const { t } = useI18n();
  const [activeTab, setActiveTab] = useState<GalleryTab>('all');
  const [selectedMedia, setSelectedMedia] = useState<ArchivedMedia | null>(null);

  const filteredMedia = useMemo(() => {
    return media.filter((item) => {
      if (activeTab === 'all') return true;
      if (activeTab === 'vanished') return item.is_vanished;
      return item.media_type === activeTab;
    });
  }, [media, activeTab]);

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
    { id: 'story', label: t.stories, count: media.filter((m) => m.media_type === 'story').length },
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
      ) : activeTab === 'highlight' && Object.keys(highlightGroups).length > 0 ? (
        <div className="space-y-4">
          {Object.entries(highlightGroups).map(([title, items]) => (
            <div
              key={title}
              className="bg-[var(--bg-surface)] p-4 rounded-2xl border border-[var(--border-subtle)] space-y-3"
            >
              <div className="flex items-center gap-2 text-xs font-medium text-[var(--text-primary)]">
                <Folder size={15} strokeWidth={1.75} className="text-[var(--text-secondary)]" />
                <span>{title}</span>
                <span className="text-[var(--text-muted)] tabular-nums">
                  ({items.length} {t.files_count})
                </span>
              </div>
              <div className="grid grid-cols-3 sm:grid-cols-4 gap-2">
                {items.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => setSelectedMedia(item)}
                    className="aspect-square relative rounded-xl overflow-hidden bg-[var(--bg-subtle)] border border-[var(--border-subtle)] group active:scale-[0.98] transition-transform duration-150"
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
                    {/\.mp4$/i.test(item.relative_file_path) && (
                      <span className="absolute bottom-1.5 right-1.5 p-1 rounded-md bg-black/65 text-white">
                        <Film size={12} strokeWidth={1.75} />
                      </span>
                    )}
                  </button>
                ))}
              </div>
            </div>
          ))}
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
        <MediaViewerModal media={selectedMedia} onClose={() => setSelectedMedia(null)} />
      )}
    </div>
  );
};
