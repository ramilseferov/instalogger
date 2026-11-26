import React from 'react';
import { Target } from '../../types';
import { Card } from '../common/Card';
import { StatusBadge } from '../common/StatusBadge';
import { Zap, RefreshCw, ChevronRight, User } from 'lucide-react';
import { useI18n } from '../../i18n';

interface TargetCardProps {
  target: Target;
  index?: number;
  checking?: boolean;
  onSelect: (target: Target) => void;
  onToggleTurbo: (target: Target, turbo: boolean) => void;
  onCheckNow: (target: Target) => void;
}

export const TargetCard: React.FC<TargetCardProps> = ({
  target,
  index = 0,
  checking = false,
  onSelect,
  onToggleTurbo,
  onCheckNow,
}) => {
  const { t } = useI18n();
  const hasActiveStory =
    typeof target.latest_story_at === 'number' &&
    target.latest_story_at > 0 &&
    Date.now() - target.latest_story_at < 24 * 60 * 60 * 1000;

  return (
    <Card
      index={index}
      onClick={() => onSelect(target)}
      className="group relative flex flex-col gap-3.5"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3.5 min-w-0">
          <div
            className={`shrink-0 rounded-full p-[2px] ${
              hasActiveStory
                ? 'bg-[#0C7FF2]'
                : 'bg-[var(--border-strong)]'
            }`}
          >
            <div className="w-12 h-12 rounded-full bg-[var(--bg-subtle)] flex items-center justify-center text-[var(--text-muted)] overflow-hidden border-2 border-[var(--bg-surface)]">
              {target.profile_pic_path ? (
                <img
                  src={target.profile_pic_path.startsWith('/') ? target.profile_pic_path : `/${target.profile_pic_path}`}
                  alt={target.username}
                  className="w-full h-full object-cover"
                />
              ) : (
                <User size={20} strokeWidth={1.75} />
              )}
            </div>
          </div>

          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="font-semibold text-sm text-[var(--text-primary)] group-hover:text-[#0C7FF2] dark:group-hover:text-[#58A6FF] transition-colors duration-150 truncate">
                @{target.username}
              </h3>
              {hasActiveStory && (
                <span className="text-[11px] font-medium px-2 py-0.5 rounded-md bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF]">
                  {t.story_badge}
                </span>
              )}
              {target.turbo_mode && <StatusBadge status="turbo" />}
            </div>
            <p className="text-xs text-[var(--text-muted)] truncate mt-0.5">
              {target.full_name || t.no_name}
            </p>
          </div>
        </div>

        <ChevronRight
          size={17}
          strokeWidth={1.75}
          className="text-[var(--text-muted)] group-hover:text-[var(--text-primary)] transition-colors duration-150 shrink-0"
        />
      </div>

      <div className="flex items-center justify-between gap-2 pt-3 border-t border-[var(--border-subtle)]">
        <div className="flex items-center gap-2 flex-wrap">
          <StatusBadge status={target.is_private ? 'private' : 'public'} />
          <span className="text-xs text-[var(--text-secondary)] font-medium bg-[var(--bg-subtle)] px-2.5 py-0.5 rounded-lg tabular-nums">
            {(target.followers_count || 0).toLocaleString()} {t.followers.toLowerCase()}
          </span>
          <span className="text-xs text-[var(--text-secondary)] font-medium bg-[var(--bg-subtle)] px-2.5 py-0.5 rounded-lg tabular-nums hidden sm:inline-block">
            {(target.following_count || 0).toLocaleString()} {t.following.toLowerCase()}
          </span>
          <span className="text-xs text-[var(--text-secondary)] font-medium bg-[var(--bg-subtle)] px-2.5 py-0.5 rounded-lg tabular-nums">
            {target.media_count} {t.posts}
          </span>
        </div>

        <div className="flex items-center gap-1 shrink-0" onClick={(e) => e.stopPropagation()}>
          <button
            type="button"
            onClick={() => onToggleTurbo(target, !target.turbo_mode)}
            title={t.turbo_mode}
            aria-label={t.turbo_mode}
            className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors duration-150 ${
              target.turbo_mode
                ? 'bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF]'
                : 'text-[var(--text-muted)] hover:bg-[var(--bg-subtle)] hover:text-[var(--text-primary)]'
            }`}
          >
            <Zap size={15} strokeWidth={1.75} />
          </button>
          <button
            type="button"
            onClick={() => onCheckNow(target)}
            disabled={checking}
            title={t.check_now}
            aria-label={t.check_now}
            className="w-8 h-8 rounded-lg flex items-center justify-center text-[var(--text-muted)] hover:bg-[var(--bg-subtle)] hover:text-[#0C7FF2] dark:hover:text-[#58A6FF] disabled:opacity-45 transition-colors duration-150"
          >
            <RefreshCw size={15} strokeWidth={1.75} className={checking ? 'animate-spin text-[#0C7FF2]' : ''} />
          </button>
        </div>
      </div>
    </Card>
  );
};
