import React, { useState } from 'react';
import { ArchivedMedia } from '../../types';
import { Film, EyeOff, Layers, Folder } from 'lucide-react';
import { MediaViewerModal } from './MediaViewerModal';

interface MediaGalleryProps {
  media: ArchivedMedia[];
}

export const MediaGallery: React.FC<MediaGalleryProps> = ({ media }) => {
  const [activeTab, setActiveTab] = useState<'all' | 'post' | 'reel' | 'highlight' | 'vanished'>('all');
  const [selectedMedia, setSelectedMedia] = useState<ArchivedMedia | null>(null);

  const filteredMedia = media.filter((item) => {
    if (activeTab === 'all') return true;
    if (activeTab === 'vanished') return item.is_vanished;
    return item.media_type === activeTab;
  });

  const highlightGroups = media
    .filter((m) => m.media_type === 'highlight')
    .reduce((acc, item) => {
      const title = item.highlight_title || 'Digər Highlights';
      if (!acc[title]) acc[title] = [];
      acc[title].push(item);
      return acc;
    }, {} as Record<string, ArchivedMedia[]>);

  const tabs = [
    { id: 'all', label: 'Hamısı', count: media.length },
    { id: 'post', label: 'Postlar', count: media.filter((m) => m.media_type === 'post').length },
    { id: 'reel', label: 'Reels', count: media.filter((m) => m.media_type === 'reel').length },
    { id: 'highlight', label: 'Highlights', count: media.filter((m) => m.media_type === 'highlight').length },
    { id: 'vanished', label: 'Yoxa Çıxanlar', count: media.filter((m) => m.is_vanished).length },
  ];

  return (
    <div className="space-y-5 animate-fade-up">
      {/* Segmented Filter Pills */}
      <div className="flex items-center gap-1.5 overflow-x-auto pb-1 no-scrollbar">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id as any)}
            className={`px-4 py-2 rounded-full text-xs font-bold whitespace-nowrap transition-all duration-200 ease-out active:scale-95 ${
              activeTab === tab.id
                ? 'bg-[#0C7FF2] text-white shadow-sm'
                : 'bg-white text-gray-600 hover:bg-gray-100 hover:text-gray-900 border border-[#E5E8EC]'
            }`}
          >
            {tab.label} ({tab.count})
          </button>
        ))}
      </div>

      {/* Highlights Collections */}
      {activeTab === 'highlight' && Object.keys(highlightGroups).length > 0 && (
        <div className="space-y-6">
          {Object.entries(highlightGroups).map(([title, items]) => (
            <div key={title} className="bg-white p-5 rounded-3xl border border-[#E5E8EC] space-y-3 shadow-xs hover:shadow-sm transition-shadow">
              <div className="flex items-center gap-2 text-xs font-bold text-gray-800">
                <Folder size={16} className="text-[#0C7FF2]" />
                <span>{title}</span>
                <span className="text-gray-400 font-normal">({items.length} fayl)</span>
              </div>
              <div className="grid grid-cols-3 sm:grid-cols-4 gap-2.5">
                {items.map((item) => (
                  <div
                    key={item.id}
                    onClick={() => setSelectedMedia(item)}
                    className="aspect-square relative rounded-2xl overflow-hidden bg-gray-100 cursor-pointer group shadow-xs active:scale-95 transition-transform duration-200"
                  >
                    <img
                      src={`/${item.relative_file_path}`}
                      alt={item.caption || title}
                      className="w-full h-full object-cover group-hover:scale-108 transition-transform duration-300 ease-out"
                      onError={(e) => {
                        (e.target as HTMLImageElement).src = item.cdn_url;
                      }}
                    />
                    {item.relative_file_path.endsWith('.mp4') && (
                      <span className="absolute bottom-1.5 right-1.5 p-1 rounded-full bg-black/60 text-white backdrop-blur-xs">
                        <Film size={12} />
                      </span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Standard Grid for other views */}
      {activeTab !== 'highlight' && (
        <>
          {filteredMedia.length === 0 ? (
            <div className="py-12 text-center bg-white rounded-3xl border border-[#E5E8EC] shadow-xs animate-scale-in">
              <Layers className="w-10 h-10 text-gray-300 mx-auto mb-3" />
              <p className="text-sm font-semibold text-gray-600">Bu kateqoriyada arxivlənmiş media yoxdur</p>
              <p className="text-xs text-gray-400 mt-1">Profil açıldıqda və ya yeni paylaşımlar olduqda burada görünəcək.</p>
            </div>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {filteredMedia.map((item, idx) => (
                <div
                  key={item.id}
                  onClick={() => setSelectedMedia(item)}
                  className={`aspect-square relative rounded-3xl overflow-hidden bg-gray-100 border border-gray-200/80 cursor-pointer group shadow-xs hover:shadow-md hover:-translate-y-1 active:scale-95 transition-all duration-250 ease-out animate-fade-up stagger-${Math.min(
                    (idx % 5) + 1,
                    5
                  )}`}
                >
                  <img
                    src={`/${item.relative_file_path}`}
                    alt={item.caption || 'Arxiv faylı'}
                    className="w-full h-full object-cover group-hover:scale-108 transition-transform duration-350 ease-out"
                    onError={(e) => {
                      (e.target as HTMLImageElement).src = item.cdn_url;
                    }}
                  />

                  {/* Badge overlays */}
                  <div className="absolute top-2.5 left-2.5 flex gap-1">
                    {item.is_vanished && (
                      <span className="p-1.5 rounded-full bg-rose-600/90 text-white shadow-sm backdrop-blur-xs animate-scale-in" title="Post yoxa çıxıb">
                        <EyeOff size={12} />
                      </span>
                    )}
                  </div>

                  {item.media_type === 'reel' && (
                    <span className="absolute bottom-2.5 right-2.5 p-1.5 rounded-full bg-black/60 text-white shadow-sm backdrop-blur-xs group-hover:scale-110 transition-transform duration-200">
                      <Film size={14} />
                    </span>
                  )}
                </div>
              ))}
            </div>
          )}
        </>
      )}

      {/* Lightbox / Video Modal */}
      {selectedMedia && (
        <MediaViewerModal
          media={selectedMedia}
          onClose={() => setSelectedMedia(null)}
        />
      )}
    </div>
  );
};
