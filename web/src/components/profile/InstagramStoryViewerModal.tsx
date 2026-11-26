import React, { useState, useEffect } from 'react';
import { ArchivedMedia } from '../../types';
import { X, ChevronLeft, ChevronRight, Trash2, EyeOff } from 'lucide-react';
import { useI18n } from '../../i18n';

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

interface InstagramStoryViewerModalProps {
  title: string;
  items: ArchivedMedia[];
  onClose: () => void;
  onDeleteItem?: (item: ArchivedMedia) => Promise<void> | void;
}

export const InstagramStoryViewerModal: React.FC<InstagramStoryViewerModalProps> = ({
  title,
  items,
  onClose,
  onDeleteItem,
}) => {
  const { t } = useI18n();
  const [currentIndex, setCurrentIndex] = useState(0);
  const [progress, setProgress] = useState(0);
  const STORY_DURATION = 5000; // 5 seconds per story

  const currentItem = items[currentIndex];

  useEffect(() => {
    setProgress(0);
    const interval = 50; // update every 50ms
    const step = 100 / (STORY_DURATION / interval);

    const timer = setInterval(() => {
      setProgress((prev) => {
        if (prev >= 100) {
          handleNext();
          return 0;
        }
        return prev + step;
      });
    }, interval);

    return () => clearInterval(timer);
  }, [currentIndex, items.length]);

  const handleNext = () => {
    if (currentIndex < items.length - 1) {
      setCurrentIndex((prev) => prev + 1);
    } else {
      onClose();
    }
  };

  const handlePrev = () => {
    if (currentIndex > 0) {
      setCurrentIndex((prev) => prev - 1);
    }
  };

  // Keyboard navigation
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      if (e.key === 'ArrowRight') handleNext();
      if (e.key === 'ArrowLeft') handlePrev();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [currentIndex, items.length]);

  if (!currentItem) return null;

  const isVideo = /\.mp4$/i.test(currentItem.relative_file_path);
  const mediaSrc = currentItem.relative_file_path.startsWith('/')
    ? currentItem.relative_file_path
    : `/${currentItem.relative_file_path}`;
  const posterSrc = isVideo ? mediaSrc.replace(/\.mp4$/i, '_thumb.jpg') : undefined;

  return (
    <div className="fixed inset-0 z-50 bg-black/95 backdrop-blur-md flex items-center justify-center animate-fade-in">
      {/* Story Card Container */}
      <div className="relative w-full max-w-md h-[92vh] max-h-[820px] bg-black rounded-3xl overflow-hidden shadow-2xl flex flex-col justify-between select-none">
        {/* Top Progress Bars (Segmented) */}
        <div className="absolute top-3 inset-x-3 z-30 flex items-center gap-1.5">
          {items.map((_, idx) => (
            <div key={idx} className="h-1 flex-1 bg-white/30 rounded-full overflow-hidden">
              <div
                className="h-full bg-white transition-all duration-75 ease-linear rounded-full"
                style={{
                  width:
                    idx < currentIndex
                      ? '100%'
                      : idx === currentIndex
                      ? `${progress}%`
                      : '0%',
                }}
              />
            </div>
          ))}
        </div>

        {/* Header Bar */}
        <div className="absolute top-7 inset-x-4 z-30 flex items-center justify-between text-white drop-shadow-md gap-2">
          <div className="flex items-center gap-2 min-w-0 flex-wrap">
            <span className="text-xs font-bold bg-[#0C7FF2] px-2.5 py-1 rounded-full truncate max-w-[160px]">
              {title}
            </span>
            <span className="text-xs text-white/80 font-medium tabular-nums">
              {currentIndex + 1} / {items.length}
            </span>
            {currentItem.file_size_bytes > 0 && (
              <span className="text-[11px] font-mono text-white/80 bg-black/45 px-2 py-0.5 rounded-full tabular-nums">
                {formatBytes(currentItem.file_size_bytes)}
              </span>
            )}
            {currentItem.is_vanished && (
              <span className="inline-flex items-center gap-1 text-[11px] font-medium bg-rose-600/90 text-white px-2 py-0.5 rounded-full">
                <EyeOff size={11} /> {t.story_status_deleted}
              </span>
            )}
          </div>
          <div className="flex items-center gap-1.5 shrink-0">
            {onDeleteItem && (
              <button
                type="button"
                onClick={async () => {
                  await onDeleteItem(currentItem);
                  if (items.length <= 1) {
                    onClose();
                  } else if (currentIndex >= items.length - 1) {
                    setCurrentIndex((prev) => Math.max(0, prev - 1));
                  }
                }}
                className="p-1.5 rounded-full bg-rose-600/80 hover:bg-rose-600 text-white transition-colors"
                title={t.delete_from_storage}
              >
                <Trash2 size={16} />
              </button>
            )}
            <button
              onClick={onClose}
              className="p-1.5 rounded-full bg-black/40 hover:bg-black/60 text-white/90 transition-colors"
            >
              <X size={20} />
            </button>
          </div>
        </div>

        {/* Media Content */}
        <div className="relative w-full h-full flex items-center justify-center bg-black">
          {isVideo ? (
            <video
              key={currentItem.id}
              src={mediaSrc}
              poster={posterSrc}
              controls
              autoPlay
              playsInline
              loop
              className="w-full h-full object-contain"
              onError={(e) => {
                const vid = e.currentTarget;
                if (currentItem.cdn_url && !vid.dataset.fallbackApplied) {
                  vid.dataset.fallbackApplied = '1';
                  vid.src = currentItem.cdn_url;
                }
              }}
            />
          ) : (
            <img
              key={currentItem.id}
              src={mediaSrc}
              alt={currentItem.caption || title}
              className="w-full h-full object-contain"
              onError={(e) => {
                const img = e.currentTarget;
                if (currentItem.cdn_url && !img.dataset.fallbackApplied) {
                  img.dataset.fallbackApplied = '1';
                  img.src = currentItem.cdn_url;
                }
              }}
            />
          )}

          {/* Left / Right Touch Tap Areas */}
          <div
            onClick={handlePrev}
            className="absolute left-0 inset-y-0 w-1/3 z-20 cursor-pointer"
            title={t.previous}
          />
          <div
            onClick={handleNext}
            className="absolute right-0 inset-y-0 w-1/3 z-20 cursor-pointer"
            title={t.next}
          />
        </div>

        {/* Desktop Navigation Chevrons */}
        {currentIndex > 0 && (
          <button
            onClick={handlePrev}
            className="absolute left-2 top-1/2 -translate-y-1/2 z-30 p-2 rounded-full bg-black/40 hover:bg-black/70 text-white transition-all hidden sm:flex"
          >
            <ChevronLeft size={24} />
          </button>
        )}
        {currentIndex < items.length - 1 && (
          <button
            onClick={handleNext}
            className="absolute right-2 top-1/2 -translate-y-1/2 z-30 p-2 rounded-full bg-black/40 hover:bg-black/70 text-white transition-all hidden sm:flex"
          >
            <ChevronRight size={24} />
          </button>
        )}
      </div>
    </div>
  );
};
