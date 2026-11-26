import React, { useEffect } from 'react';
import { ArchivedMedia } from '../../types';
import { X, Download, Calendar, EyeOff, Film, Image as ImageIcon } from 'lucide-react';
import { useI18n } from '../../i18n';

interface MediaViewerModalProps {
  media: ArchivedMedia | null;
  onClose: () => void;
}

export const MediaViewerModal: React.FC<MediaViewerModalProps> = ({ media, onClose }) => {
  const { t } = useI18n();

  useEffect(() => {
    if (!media) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [media, onClose]);

  if (!media) return null;

  const isVideo = /\.mp4$/i.test(media.relative_file_path);
  const mediaUrl = media.relative_file_path.startsWith('/')
    ? media.relative_file_path
    : `/${media.relative_file_path}`;
  const posterUrl = isVideo ? mediaUrl.replace(/\.mp4$/i, '_thumb.jpg') : undefined;

  return (
    <div
      role="dialog"
      aria-modal="true"
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 animate-backdrop"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-xl bg-[var(--bg-surface)] border border-[var(--border-strong)] rounded-2xl overflow-hidden shadow-2xl flex flex-col max-h-[90vh] animate-panel"
      >
        <div className="flex items-center justify-between px-4 py-3 bg-[var(--bg-surface)] border-b border-[var(--border-subtle)]">
          <div className="flex items-center gap-2 min-w-0">
            <span className="w-7 h-7 rounded-lg bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF] flex items-center justify-center shrink-0">
              {isVideo ? <Film size={15} strokeWidth={1.75} /> : <ImageIcon size={15} strokeWidth={1.75} />}
            </span>
            <span className="text-xs font-medium text-[var(--text-primary)] truncate">
              {media.highlight_title || media.media_type}
            </span>
            {media.is_vanished && (
              <span className="inline-flex items-center gap-1 text-[11px] font-medium bg-rose-500/12 text-rose-600 dark:text-rose-400 px-2 py-0.5 rounded-md border border-rose-500/20">
                <EyeOff size={11} strokeWidth={1.75} /> {t.vanished_badge}
              </span>
            )}
          </div>

          <button
            type="button"
            onClick={onClose}
            className="w-8 h-8 rounded-lg flex items-center justify-center text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-subtle)] transition-colors duration-150"
            aria-label={t.cancel}
          >
            <X size={18} strokeWidth={1.75} />
          </button>
        </div>

        <div className="flex-1 bg-black flex items-center justify-center overflow-hidden min-h-[280px]">
          {isVideo ? (
            <video
              src={mediaUrl}
              poster={posterUrl}
              controls
              autoPlay
              playsInline
              className="max-h-[60vh] w-auto object-contain"
            />
          ) : (
            <img
              src={mediaUrl}
              alt={media.caption || ''}
              className="max-h-[60vh] w-auto object-contain"
              onError={(e) => {
                if (media.cdn_url) (e.target as HTMLImageElement).src = media.cdn_url;
              }}
            />
          )}
        </div>

        <div className="p-4 bg-[var(--bg-surface)] border-t border-[var(--border-subtle)] space-y-3">
          {media.caption && (
            <p className="text-xs text-[var(--text-secondary)] leading-relaxed max-h-24 overflow-y-auto whitespace-pre-line">
              {media.caption}
            </p>
          )}

          <div className="flex items-center justify-between text-[11px] text-[var(--text-muted)] pt-2 border-t border-[var(--border-subtle)]">
            <span className="flex items-center gap-1.5 tabular-nums">
              <Calendar size={13} strokeWidth={1.75} />
              {media.posted_at ? new Date(media.posted_at).toLocaleString() : '—'}
            </span>
            <a
              href={mediaUrl}
              download
              className="inline-flex items-center gap-1.5 text-[#0C7FF2] dark:text-[#58A6FF] hover:underline font-medium"
            >
              <Download size={13} strokeWidth={1.75} /> {t.download_file}
            </a>
          </div>
        </div>
      </div>
    </div>
  );
};
