import React from 'react';
import { ArchivedMedia } from '../../types';
import { useI18n } from '../../i18n';

interface HighlightCirclesRowProps {
  media: ArchivedMedia[];
  onSelectHighlight: (title: string, items: ArchivedMedia[]) => void;
}

export const HighlightCirclesRow: React.FC<HighlightCirclesRowProps> = ({
  media,
  onSelectHighlight,
}) => {
  const { t } = useI18n();

  const highlightGroups = media
    .filter((m) => m.media_type === 'highlight')
    .reduce((acc, item) => {
      const title = item.highlight_title || t.highlights;
      if (!acc[title]) acc[title] = [];
      acc[title].push(item);
      return acc;
    }, {} as Record<string, ArchivedMedia[]>);

  const entries = Object.entries(highlightGroups);
  if (entries.length === 0) return null;

  return (
    <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
      <div className="flex items-center gap-4 overflow-x-auto no-scrollbar py-0.5 px-1">
        {entries.map(([title, items]) => {
          const coverMedia = items[0];
          const coverSrc = coverMedia
            ? coverMedia.relative_file_path.startsWith('/')
              ? coverMedia.relative_file_path
              : `/${coverMedia.relative_file_path}`
            : '';

          return (
            <button
              key={title}
              type="button"
              onClick={() => onSelectHighlight(title, items)}
              className="flex flex-col items-center gap-1.5 shrink-0 group active:scale-95 transition-transform duration-150"
            >
              <div className="w-16 h-16 rounded-full p-[2px] bg-[#0C7FF2]">
                <div className="w-full h-full rounded-full bg-[var(--bg-surface)] p-[2px] overflow-hidden">
                  {coverSrc ? (
                    <img
                      src={coverSrc}
                      alt={title}
                      className="w-full h-full object-cover rounded-full"
                      onError={(e) => {
                        if (coverMedia?.cdn_url) {
                          (e.target as HTMLImageElement).src = coverMedia.cdn_url;
                        }
                      }}
                    />
                  ) : (
                    <div className="w-full h-full bg-[var(--bg-subtle)] flex items-center justify-center text-xs font-medium text-[var(--text-secondary)] rounded-full">
                      {title.substring(0, 1).toUpperCase()}
                    </div>
                  )}
                </div>
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
