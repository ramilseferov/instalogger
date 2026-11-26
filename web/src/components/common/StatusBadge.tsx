import React from 'react';
import { Lock, Globe, Zap, PauseCircle } from 'lucide-react';
import { useI18n } from '../../i18n';

interface StatusBadgeProps {
  status: 'public' | 'private' | 'turbo' | 'inactive';
  className?: string;
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({ status, className = '' }) => {
  const { t } = useI18n();

  if (status === 'public') {
    return (
      <span
        className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-lg text-xs font-medium bg-emerald-500/12 text-emerald-700 dark:text-emerald-400 border border-emerald-500/20 ${className}`}
      >
        <Globe size={12} strokeWidth={1.75} />
        {t.public}
      </span>
    );
  }

  if (status === 'private') {
    return (
      <span
        className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-lg text-xs font-medium bg-amber-500/12 text-amber-700 dark:text-amber-400 border border-amber-500/20 ${className}`}
      >
        <Lock size={12} strokeWidth={1.75} />
        {t.private}
      </span>
    );
  }

  if (status === 'turbo') {
    return (
      <span
        className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-lg text-xs font-medium bg-[#0C7FF2]/12 text-[#0C7FF2] dark:text-[#58A6FF] border border-[#0C7FF2]/25 ${className}`}
      >
        <Zap size={12} strokeWidth={1.75} />
        {t.turbo_active}
      </span>
    );
  }

  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-lg text-xs font-medium bg-[var(--bg-subtle)] text-[var(--text-muted)] border border-[var(--border-subtle)] ${className}`}
    >
      <PauseCircle size={12} strokeWidth={1.75} />
      {t.status_paused}
    </span>
  );
};
