import React from 'react';
import { ArchivedMedia } from '../../types';
import { useI18n } from '../../i18n';

interface HighlightCirclesRowProps {
  media: ArchivedMedia[];
  onSelectHighlight: (title: string, items: ArchivedMedia[]) => void;
}

function getCoverSrc(item?: ArchivedMedia): string {
  if (!item) return '';
  const raw = item.relative_file_path || '';
  if (!raw) return item.cdn_url || '';
  const clean = raw.startsWith('/') ? raw : `/${raw}`;
  if (/\.mp4$/i.test(clean)) {
    return clean.replace(/\.mp4$/i, '_thumb.jpg');
  }
  return clean;
}

export const HighlightCirclesRow: React.FC<HighlightCirclesRowProps> = ({
  media,
  onSelectHighlight,
}) => {
  const { t } = useI18n();

  const activeStories = media.filter((m) => m.media_type === 'story');

  const highlightGroups = media
    .filter((m) => m.media_type === 'highlight')
    .reduce((acc, item) => {
      const title = item.highlight_title || t.highlights;
      if (!acc[title]) acc[title] = [];
      acc[title].push(item);
      return acc;
    }, {} as Record<string, ArchivedMedia[]>);

  const entries = Object.entries(highlightGroups);
  if (activeStories.length === 0 && entries.length === 0) return null;

  return (
    <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
      <div className="flex items-center gap-4 overflow-x-auto no-scrollbar py-0.5 px-1">
        {activeStories.length > 0 && (
          <button
            type="button"
            onClick={() => onSelectHighlight(t.active_stories, activeStories)}
            className="flex flex-col items-center gap-1.5 shrink-0 group active:scale-95 transition-transform duration-150"
          >
            <div className="relative w-16 h-16 rounded-full p-[2px] bg-gradient-to-tr from-[#F59E0B] via-[#E1306C] to-[#833AB4]">
              <div className="w-full h-full rounded-full bg-[var(--bg-surface)] p-[2px] overflow-hidden">
                <img
                  src={getCoverSrc(activeStories[0])}
                  alt={t.active_stories}
                  className="w-full h-full object-cover rounded-full"
                  onError={(e) => {
                    const img = e.currentTarget;
                    if (activeStories[0]?.cdn_url && !img.dataset.fallbackApplied) {
                      img.dataset.fallbackApplied = '1';
                      img.src = activeStories[0].cdn_url;
                    }
                  }}
                />
              </div>
              <span className="absolute -bottom-0.5 -right-0.5 min-w-[20px] h-5 px-1 rounded-full bg-[#E1306C] text-white text-[10px] font-semibold tabular-nums flex items-center justify-center border-2 border-[var(--bg-surface)]">
                {activeStories.length}
              </span>
            </div>

            <span className="text-[11px] font-medium text-[var(--text-primary)] max-w-[76px] truncate">
              {t.stories}
            </span>
          </button>
        )}

        {entries.map(([title, items]) => {
          const coverMedia = items[0];
          const coverSrc = getCoverSrc(coverMedia);

          return (
            <button
              key={title}
              type="button"
              onClick={() => onSelectHighlight(title, items)}
              className="flex flex-col items-center gap-1.5 shrink-0 group active:scale-95 transition-transform duration-150"
            >
              <div className="relative w-16 h-16 rounded-full p-[2px] bg-[#0C7FF2]">
                <div className="w-full h-full rounded-full bg-[var(--bg-surface)] p-[2px] overflow-hidden">
                  {coverSrc ? (
                    <img
                      src={coverSrc}
                      alt={title}
                      className="w-full h-full object-cover rounded-full"
                      onError={(e) => {
                        const img = e.currentTarget;
                        if (coverMedia?.cdn_url && !img.dataset.fallbackApplied) {
                          img.dataset.fallbackApplied = '1';
                          img.src = coverMedia.cdn_url;
                        }
                      }}
                    />
                  ) : (
                    <div className="w-full h-full bg-[var(--bg-subtle)] flex items-center justify-center text-xs font-medium text-[var(--text-secondary)] rounded-full">
                      {title.substring(0, 1).toUpperCase()}
                    </div>
                  )}
                </div>
                <span className="absolute -bottom-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-[var(--bg-surface)] text-[var(--text-secondary)] border border-[var(--border-strong)] text-[10px] font-semibold tabular-nums flex items-center justify-center">
                  {items.length}
                </span>
              </div>

              <span className="text-[11px] font-medium text-[var(--text-secondary)] group-hover:text-[var(--text-primary)] max-w-[72px] truncate transition-colors duration-150">
                {title}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
};
