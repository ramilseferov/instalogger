import React, { useEffect, useState } from 'react';
import { ArchivedMedia, MediaAsset } from '../../types';
import { api } from '../../api/client';
import { PostCommentsModal } from './PostCommentsModal';
import { X, Download, Calendar, EyeOff, Film, Image as ImageIcon, ChevronLeft, ChevronRight, MessageSquare } from 'lucide-react';
import { useI18n } from '../../i18n';

interface MediaViewerModalProps {
  media: ArchivedMedia | null;
  onClose: () => void;
}

export const MediaViewerModal: React.FC<MediaViewerModalProps> = ({ media, onClose }) => {
  const { t } = useI18n();
  const [slides, setSlides] = useState<MediaAsset[]>([]);
  const [slideIdx, setSlideIdx] = useState(0);
  const [showComments, setShowComments] = useState(false);

  useEffect(() => {
    if (!media) return;
    setSlideIdx(0);
    setSlides([]);
    setShowComments(false);
    api
      .getMediaAssets(media.target_id, media.id)
      .then((list) => setSlides(list))
      .catch(() => setSlides([]));
  }, [media]);

  useEffect(() => {
    if (!media) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !showComments) onClose();
      if (slides.length > 1 && e.key === 'ArrowRight') {
        setSlideIdx((i) => Math.min(slides.length - 1, i + 1));
      }
      if (slides.length > 1 && e.key === 'ArrowLeft') {
        setSlideIdx((i) => Math.max(0, i - 1));
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [media, onClose, slides.length, showComments]);

  if (!media) return null;

  const activeSlide = slides.length > 1 ? slides[slideIdx] : null;
  const rawPath = activeSlide?.relative_file_path || media.relative_file_path;
  const mediaUrl = activeSlide
    ? (activeSlide.relative_file_path
        ? (activeSlide.relative_file_path.startsWith('/') ? activeSlide.relative_file_path : `/${activeSlide.relative_file_path}`)
        : activeSlide.cdn_url)
    : (rawPath.startsWith('/') ? rawPath : `/${rawPath}`);
  const isVideo = activeSlide
    ? activeSlide.media_type === 'video' || /\.mp4$/i.test(mediaUrl)
    : /\.mp4$/i.test(media.relative_file_path);
  const posterUrl = isVideo ? mediaUrl.replace(/\.mp4$/i, '_thumb.jpg') : undefined;
  const shortcode = media.id.replace(/^(post|reel|story|highlight)_/, '');
  const canHaveComments = media.media_type === 'post' || media.media_type === 'reel';

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

        <div className="relative flex-1 bg-black flex items-center justify-center overflow-hidden min-h-[280px]">
          {isVideo ? (
            <video
              key={mediaUrl}
              src={mediaUrl}
              poster={posterUrl}
              controls
              autoPlay
              playsInline
              className="max-h-[60vh] w-auto object-contain"
            />
          ) : (
            <img
              key={mediaUrl}
              src={mediaUrl}
              alt={media.caption || ''}
              className="max-h-[60vh] w-auto object-contain"
              onError={(e) => {
                const img = e.currentTarget;
                const fallback = activeSlide?.cdn_url || media.cdn_url;
                if (fallback && !img.dataset.fallbackApplied) {
                  img.dataset.fallbackApplied = '1';
                  img.src = fallback;
                }
              }}
            />
          )}

          {slides.length > 1 && (
            <>
              <span className="absolute top-3 right-3 px-2 py-0.5 rounded-full bg-black/65 text-white text-[11px] font-mono tabular-nums">
                {slideIdx + 1} / {slides.length}
              </span>
              {slideIdx > 0 && (
                <button
                  type="button"
                  onClick={() => setSlideIdx((i) => Math.max(0, i - 1))}
                  className="absolute left-2 top-1/2 -translate-y-1/2 w-8 h-8 rounded-full bg-black/60 text-white flex items-center justify-center"
                >
                  <ChevronLeft size={18} />
                </button>
              )}
              {slideIdx < slides.length - 1 && (
                <button
                  type="button"
                  onClick={() => setSlideIdx((i) => Math.min(slides.length - 1, i + 1))}
                  className="absolute right-2 top-1/2 -translate-y-1/2 w-8 h-8 rounded-full bg-black/60 text-white flex items-center justify-center"
                >
                  <ChevronRight size={18} />
                </button>
              )}
            </>
          )}
        </div>

        <div className="p-4 bg-[var(--bg-surface)] border-t border-[var(--border-subtle)] space-y-3">
          {media.caption && (
            <p className="text-xs text-[var(--text-secondary)] leading-relaxed max-h-24 overflow-y-auto whitespace-pre-line">
              {media.caption}
            </p>
          )}

          <div className="flex items-center justify-between gap-2 text-[11px] text-[var(--text-muted)] pt-2 border-t border-[var(--border-subtle)]">
            <span className="flex items-center gap-1.5 tabular-nums">
              <Calendar size={13} strokeWidth={1.75} />
              {media.posted_at ? new Date(media.posted_at).toLocaleString() : '—'}
            </span>
            <div className="flex items-center gap-3">
              {canHaveComments && (
                <button
                  type="button"
                  onClick={() => setShowComments(true)}
                  className="inline-flex items-center gap-1.5 text-[var(--text-primary)] hover:text-[#0C7FF2] font-medium"
                >
                  <MessageSquare size={13} strokeWidth={1.75} /> {t.comments_title}
                </button>
              )}
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

      {canHaveComments && (
        <PostCommentsModal
          isOpen={showComments}
          targetId={media.target_id}
          shortcode={shortcode}
          onClose={() => setShowComments(false)}
        />
      )}
    </div>
  );
};
