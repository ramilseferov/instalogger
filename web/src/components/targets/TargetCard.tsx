import React from 'react';
import { Target } from '../../types';
import { Card } from '../common/Card';
import { StatusBadge } from '../common/StatusBadge';
import { Zap, RefreshCw, ChevronRight, User } from 'lucide-react';

interface TargetCardProps {
  target: Target;
  index?: number;
  onSelect: (target: Target) => void;
  onToggleTurbo: (target: Target, turbo: boolean) => void;
  onCheckNow: (target: Target) => void;
}

export const TargetCard: React.FC<TargetCardProps> = ({
  target,
  index = 0,
  onSelect,
  onToggleTurbo,
  onCheckNow,
}) => {
  return (
    <Card
      index={index}
      onClick={() => onSelect(target)}
      className="group relative flex flex-col gap-4 border border-[#E5E8EC]"
    >
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 rounded-full bg-[#E5E8EC] flex items-center justify-center text-gray-600 font-bold text-lg overflow-hidden border border-gray-100 group-hover:scale-105 transition-transform duration-200">
            {target.profile_pic_path ? (
              <img
                src={target.profile_pic_path}
                alt={target.username}
                className="w-full h-full object-cover"
              />
            ) : (
              <User size={22} className="text-gray-400" />
            )}
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="font-bold text-base text-gray-900 group-hover:text-[#0C7FF2] transition-colors duration-150">
                @{target.username}
              </h3>
              {target.turbo_mode && <StatusBadge status="turbo" />}
            </div>
            <p className="text-xs text-gray-500 font-medium truncate max-w-[180px] sm:max-w-[280px]">
              {target.full_name || 'İstifadəçi adı yoxdur'}
            </p>
          </div>
        </div>

        <ChevronRight size={18} className="text-gray-400 group-hover:text-[#0C7FF2] group-hover:translate-x-1 transition-all duration-200" />
      </div>

      <div className="flex items-center justify-between pt-2 border-t border-gray-100">
        <div className="flex items-center gap-2">
          <StatusBadge status={target.is_private ? 'private' : 'public'} />
          <span className="text-xs text-gray-500 font-medium bg-gray-100/80 px-2.5 py-1 rounded-full">
            {target.media_count} post
          </span>
        </div>

        <div className="flex items-center gap-1.5" onClick={(e) => e.stopPropagation()}>
          <button
            onClick={() => onToggleTurbo(target, !target.turbo_mode)}
            title={target.turbo_mode ? 'Turbo rejimi söndür' : 'Turbo rejimi aç (30-60s)'}
            className={`p-2 rounded-full transition-all duration-200 active:scale-90 ${
              target.turbo_mode
                ? 'bg-blue-100 text-[#0C7FF2] shadow-xs'
                : 'text-gray-400 hover:bg-gray-100 hover:text-gray-700'
            }`}
          >
            <Zap size={16} />
          </button>
          <button
            onClick={() => onCheckNow(target)}
            title="Dərhal yoxla"
            className="p-2 rounded-full text-gray-400 hover:bg-gray-100 hover:text-[#0C7FF2] active:scale-90 transition-all duration-200"
          >
            <RefreshCw size={16} />
          </button>
        </div>
      </div>
    </Card>
  );
};
