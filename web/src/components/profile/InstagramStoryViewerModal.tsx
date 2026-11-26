import React, { useState, useEffect, useRef } from 'react';
import { ArchivedMedia } from '../../types';
import { X, ChevronLeft, ChevronRight } from 'lucide-react';
import { useI18n } from '../../i18n';

interface InstagramStoryViewerModalProps {
  title: string;
  items: ArchivedMedia[];
  onClose: () => void;
}

export const InstagramStoryViewerModal: React.FC<InstagramStoryViewerModalProps> = ({
  title,
  items,
  onClose,
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
        <div className="absolute top-7 inset-x-4 z-30 flex items-center justify-between text-white drop-shadow-md">
          <div className="flex items-center gap-2">
            <span className="text-xs font-bold bg-[#0C7FF2] px-2.5 py-1 rounded-full">
              {title}
            </span>
            <span className="text-xs text-white/80 font-medium">
              {currentIndex + 1} / {items.length}
            </span>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-full bg-black/40 hover:bg-black/60 text-white/90 transition-colors"
          >
            <X size={20} />
          </button>
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
