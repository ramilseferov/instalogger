import React, { useState, useMemo } from 'react';
import { ArchivedMedia } from '../../types';
import { Film, EyeOff, Layers, Folder } from 'lucide-react';
import { MediaViewerModal } from './MediaViewerModal';
import { useI18n } from '../../i18n';

interface MediaGalleryProps {
  media: ArchivedMedia[];
}

type GalleryTab = 'all' | 'post' | 'reel' | 'highlight' | 'vanished';

export const MediaGallery: React.FC<MediaGalleryProps> = ({ media }) => {
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
    { id: 'highlight', label: t.highlights, count: media.filter((m) => m.media_type === 'highlight').length },
    { id: 'vanished', label: t.vanished, count: media.filter((m) => m.is_vanished).length },
  ];

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

      {activeTab === 'highlight' && Object.keys(highlightGroups).length > 0 ? (
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
                      src={item.relative_file_path.startsWith('/') ? item.relative_file_path : `/${item.relative_file_path}`}
                      alt={item.caption || title}
                      className="w-full h-full object-cover group-hover:opacity-90 transition-opacity duration-150"
                      onError={(e) => {
                        if (item.cdn_url) (e.target as HTMLImageElement).src = item.cdn_url;
                      }}
                    />
                    {item.relative_file_path.endsWith('.mp4') && (
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
        <div className="py-12 px-6 text-center bg-[var(--bg-surface)] rounded-2xl border border-[var(--border-subtle)] space-y-2">
          <Layers className="w-8 h-8 text-[var(--text-muted)] mx-auto" strokeWidth={1.5} />
          <p className="text-sm font-medium text-[var(--text-primary)]">{t.gallery_empty_title}</p>
          <p className="text-xs text-[var(--text-muted)] max-w-sm mx-auto">{t.gallery_empty_desc}</p>
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
          {filteredMedia.map((item, idx) => (
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
                src={item.relative_file_path.startsWith('/') ? item.relative_file_path : `/${item.relative_file_path}`}
                alt={item.caption || ''}
                className="w-full h-full object-cover group-hover:opacity-92 transition-opacity duration-150"
                onError={(e) => {
                  if (item.cdn_url) (e.target as HTMLImageElement).src = item.cdn_url;
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

              {(item.media_type === 'reel' || item.relative_file_path.endsWith('.mp4')) && (
                <span className="absolute bottom-2 right-2 p-1.5 rounded-lg bg-black/65 text-white">
                  <Film size={13} strokeWidth={1.75} />
                </span>
              )}
            </button>
          ))}
        </div>
      )}

      {selectedMedia && (
        <MediaViewerModal media={selectedMedia} onClose={() => setSelectedMedia(null)} />
      )}
    </div>
  );
};
