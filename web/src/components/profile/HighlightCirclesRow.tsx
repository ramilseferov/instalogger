import React from 'react';
import { ArchivedMedia } from '../../types';

interface HighlightCirclesRowProps {
  media: ArchivedMedia[];
  onSelectHighlight: (title: string, items: ArchivedMedia[]) => void;
}

export const HighlightCirclesRow: React.FC<HighlightCirclesRowProps> = ({
  media,
  onSelectHighlight,
}) => {
  // Group highlights by highlight_title
  const highlightGroups = media
    .filter((m) => m.media_type === 'highlight')
    .reduce((acc, item) => {
      const title = item.highlight_title || 'Highlight';
      if (!acc[title]) acc[title] = [];
      acc[title].push(item);
      return acc;
    }, {} as Record<string, ArchivedMedia[]>);

  const entries = Object.entries(highlightGroups);
  if (entries.length === 0) return null;

  return (
    <div className="bg-white rounded-3xl p-4 border border-[#E5E8EC] shadow-xs">
      <div className="flex items-center gap-4 overflow-x-auto no-scrollbar py-1 px-1">
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
              onClick={() => onSelectHighlight(title, items)}
              className="flex flex-col items-center gap-1.5 shrink-0 group focus:outline-hidden"
            >
              {/* Instagram Story Gradient Ring */}
              <div className="w-[68px] h-[68px] rounded-full p-[2.5px] bg-gradient-to-tr from-[#0C7FF2] to-[#38BDF8] group-hover:scale-105 active:scale-95 transition-all duration-200">
                <div className="w-full h-full rounded-full bg-white p-[2px] overflow-hidden">
                  {coverSrc ? (
                    <img
                      src={coverSrc}
                      alt={title}
                      className="w-full h-full object-cover rounded-full group-hover:opacity-90 transition-opacity"
                      onError={(e) => {
                        if (coverMedia?.cdn_url) {
                          (e.target as HTMLImageElement).src = coverMedia.cdn_url;
                        }
                      }}
                    />
                  ) : (
                    <div className="w-full h-full bg-gray-100 flex items-center justify-center text-xs font-bold text-gray-500 rounded-full">
                      {title.substring(0, 1).toUpperCase()}
                    </div>
                  )}
                </div>
              </div>

              {/* Title & Item Count */}
              <span className="text-[11px] font-semibold text-gray-700 max-w-[72px] truncate group-hover:text-[#0C7FF2] transition-colors">
                {title}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
};
