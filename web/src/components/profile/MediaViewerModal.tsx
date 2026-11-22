import React from 'react';
import { ArchivedMedia } from '../../types';
import { X, Download, Calendar, EyeOff, Film, Image as ImageIcon } from 'lucide-react';

interface MediaViewerModalProps {
  media: ArchivedMedia | null;
  onClose: () => void;
}

export const MediaViewerModal: React.FC<MediaViewerModalProps> = ({ media, onClose }) => {
  if (!media) return null;

  const isVideo = media.media_type === 'reel' || media.relative_file_path.endsWith('.mp4');
  const mediaUrl = `/${media.relative_file_path}`;

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-md p-4 transition-opacity duration-300"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-xl bg-white rounded-3xl overflow-hidden shadow-2xl flex flex-col max-h-[90vh] animate-scale-in"
      >
        {/* Top Header */}
        <div className="flex items-center justify-between p-4 bg-white/95 backdrop-blur-sm border-b border-gray-100 z-10">
          <div className="flex items-center gap-2">
            <span className="p-1.5 rounded-full bg-blue-50 text-[#0C7FF2]">
              {isVideo ? <Film size={16} /> : <ImageIcon size={16} />}
            </span>
            <span className="text-xs font-bold uppercase tracking-wider text-gray-700">
              {media.highlight_title ? `Highlight: ${media.highlight_title}` : media.media_type}
            </span>
            {media.is_vanished && (
              <span className="inline-flex items-center gap-1 text-[11px] font-bold bg-rose-50 text-rose-600 px-2.5 py-0.5 rounded-full border border-rose-100 animate-scale-in">
                <EyeOff size={12} /> Yoxa Çıxıb
              </span>
            )}
          </div>

          <button
            onClick={onClose}
            className="p-1.5 rounded-full text-gray-400 hover:text-gray-700 hover:bg-gray-100 active:scale-90 transition-all duration-150"
          >
            <X size={20} />
          </button>
        </div>

        {/* Media Content */}
        <div className="flex-1 bg-black flex items-center justify-center overflow-hidden min-h-[300px]">
          {isVideo ? (
            <video
              src={mediaUrl}
              controls
              autoPlay
              playsInline
              className="max-h-[60vh] w-auto object-contain transition-opacity duration-300"
            />
          ) : (
            <img
              src={mediaUrl}
              alt={media.caption || 'Arxiv faylı'}
              className="max-h-[60vh] w-auto object-contain transition-opacity duration-300"
              onError={(e) => {
                (e.target as HTMLImageElement).src = media.cdn_url;
              }}
            />
          )}
        </div>

        {/* Metadata Footer */}
        <div className="p-5 bg-white border-t border-gray-100 space-y-3">
          {media.caption && (
            <p className="text-xs text-gray-800 font-normal leading-relaxed max-h-24 overflow-y-auto whitespace-pre-line">
              {media.caption}
            </p>
          )}

          <div className="flex items-center justify-between text-[11px] text-gray-400 font-medium pt-2 border-t border-gray-50">
            <span className="flex items-center gap-1">
              <Calendar size={13} /> {media.taken_at || media.created_at}
            </span>
            <a
              href={mediaUrl}
              download
              className="inline-flex items-center gap-1.5 text-[#0C7FF2] hover:underline font-semibold"
            >
              <Download size={13} /> Faylı Endir
            </a>
          </div>
        </div>
      </div>
    </div>
  );
};
