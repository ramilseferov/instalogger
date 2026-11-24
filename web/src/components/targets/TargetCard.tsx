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
        <div className="flex items-center gap-3 min-w-0">
          <div
            className={`shrink-0 rounded-full p-[2px] ${
              hasActiveStory
                ? 'bg-gradient-to-tr from-amber-500 via-pink-600 to-purple-600'
                : 'bg-slate-200 dark:bg-slate-700'
            }`}
          >
            <div className="w-12 h-12 rounded-full bg-slate-100 dark:bg-slate-800 flex items-center justify-center text-slate-500 overflow-hidden border-2 border-white dark:border-slate-900">
              {target.profile_pic_path ? (
                <img
                  src={target.profile_pic_path.startsWith('/') ? target.profile_pic_path : `/${target.profile_pic_path}`}
                  alt={target.username}
                  className="w-full h-full object-cover"
                />
              ) : (
                <User size={22} className="text-slate-400" />
              )}
            </div>
          </div>

          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="font-bold text-base text-slate-900 dark:text-white group-hover:text-[#0C7FF2] transition-colors truncate">
                @{target.username}
              </h3>
              {hasActiveStory && (
                <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-pink-500/15 text-pink-600 dark:text-pink-400 border border-pink-500/30">
                  Story
                </span>
              )}
              {target.turbo_mode && <StatusBadge status="turbo" />}
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400 truncate max-w-[200px] sm:max-w-[320px]">
              {target.full_name || t.no_name}
            </p>
          </div>
        </div>

        <ChevronRight size={18} className="text-slate-400 group-hover:text-[#0C7FF2] transition-colors shrink-0" />
      </div>

      <div className="flex items-center justify-between pt-2.5 border-t border-slate-100 dark:border-slate-800">
        <div className="flex items-center gap-2 flex-wrap">
          <StatusBadge status={target.is_private ? 'private' : 'public'} />
          <span className="text-xs text-slate-600 dark:text-slate-300 font-medium bg-slate-100 dark:bg-slate-800 px-2.5 py-0.5 rounded-full">
            {target.media_count} {t.posts}
          </span>
          {typeof target.followers_count === 'number' && target.followers_count > 0 && (
            <span className="text-xs text-slate-600 dark:text-slate-300 font-medium bg-slate-100 dark:bg-slate-800 px-2.5 py-0.5 rounded-full">
              {target.followers_count.toLocaleString()} {t.followers}
            </span>
          )}
        </div>

        <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button
            onClick={() => onToggleTurbo(target, !target.turbo_mode)}
            title={t.turbo_mode}
            className={`p-2 rounded-full transition-colors ${
              target.turbo_mode
                ? 'bg-blue-500/15 text-[#0C7FF2]'
                : 'text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-slate-700 dark:hover:text-slate-200'
            }`}
          >
            <Zap size={15} />
          </button>
          <button
            onClick={() => onCheckNow(target)}
            disabled={checking}
            title={t.check_now}
            className="p-2 rounded-full text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-[#0C7FF2] disabled:opacity-50 transition-colors"
          >
            <RefreshCw size={15} className={checking ? 'animate-spin text-[#0C7FF2]' : ''} />
          </button>
        </div>
      </div>
    </Card>
  );
};
