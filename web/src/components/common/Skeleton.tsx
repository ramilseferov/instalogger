import React from 'react';
import { RefreshCw } from 'lucide-react';

export const SkeletonTargetList: React.FC<{ count?: number }> = ({ count = 3 }) => (
  <div className="grid grid-cols-1 gap-3" aria-busy="true">
    {Array.from({ length: count }).map((_, idx) => (
      <div
        key={idx}
        className="bg-[var(--bg-surface)] rounded-2xl p-5 border border-[var(--border-subtle)] space-y-4"
      >
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-full skeleton-box shrink-0" />
          <div className="flex-1 space-y-2">
            <div className="h-3.5 w-32 rounded-md skeleton-box" />
            <div className="h-3 w-48 rounded-md skeleton-box" />
          </div>
        </div>
        <div className="flex items-center gap-2 pt-3 border-t border-[var(--border-subtle)]">
          <div className="h-5 w-16 rounded-lg skeleton-box" />
          <div className="h-5 w-24 rounded-lg skeleton-box" />
          <div className="h-5 w-20 rounded-lg skeleton-box" />
        </div>
      </div>
    ))}
  </div>
);

export const SkeletonPostGrid: React.FC<{
  count?: number;
  statusLabel?: string;
}> = ({ count = 6, statusLabel }) => {
  const safeCount = Math.max(3, Math.min(count, 12));
  return (
    <div className="space-y-3" aria-busy="true">
      {statusLabel && (
        <div className="flex items-center gap-2.5 px-3.5 py-2.5 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-xs text-[var(--text-secondary)]">
          <RefreshCw size={14} strokeWidth={1.75} className="animate-spin text-[#0C7FF2] shrink-0" />
          <span>{statusLabel}</span>
        </div>
      )}
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
        {Array.from({ length: safeCount }).map((_, idx) => (
          <div
            key={idx}
            className="aspect-square rounded-2xl border border-[var(--border-subtle)] skeleton-box"
          />
        ))}
      </div>
    </div>
  );
};

export const SkeletonTimeline: React.FC<{ count?: number }> = ({ count = 3 }) => (
  <div className="space-y-3" aria-busy="true">
    {Array.from({ length: count }).map((_, idx) => (
      <div
        key={idx}
        className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)] space-y-2.5"
      >
        <div className="h-3 w-28 rounded-md skeleton-box" />
        <div className="h-3.5 w-3/4 rounded-md skeleton-box" />
      </div>
    ))}
  </div>
);
