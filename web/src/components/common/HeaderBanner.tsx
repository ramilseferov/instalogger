import React from 'react';
import { Activity } from 'lucide-react';

interface HeaderBannerProps {
  title: string;
  subtitle?: string;
  onSettingsClick?: () => void;
  activeCount?: number;
}

export const HeaderBanner: React.FC<HeaderBannerProps> = ({
  title,
  subtitle,
  onSettingsClick,
  activeCount = 0,
}) => {
  return (
    <header className="pt-8 pb-6 px-6 max-w-2xl mx-auto animate-fade-up">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2 bg-white/70 backdrop-blur-sm px-3 py-1 rounded-full border border-gray-200/60 shadow-xs">
          <span className="relative flex h-2.5 w-2.5">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-emerald-500"></span>
          </span>
          <span className="text-[11px] font-bold uppercase tracking-wider text-gray-600">
            Monitorinq Aktivdir
          </span>
        </div>
        <div className="flex items-center gap-2">
          {activeCount > 0 && (
            <span className="text-xs font-bold bg-[#E3F2FD] text-[#0C7FF2] px-3 py-1 rounded-full border border-blue-100 shadow-xs animate-scale-in">
              {activeCount} Hədəf
            </span>
          )}
          {onSettingsClick && (
            <button
              onClick={onSettingsClick}
              className="p-2 rounded-full bg-white/80 hover:bg-white text-gray-600 hover:text-[#0C7FF2] shadow-xs hover:shadow-sm active:scale-90 transition-all duration-200"
              title="Tənzimləmələr"
            >
              <Activity size={18} />
            </button>
          )}
        </div>
      </div>

      <h1 className="text-3xl font-extrabold text-gray-900 tracking-tight transition-colors">
        {title}
      </h1>
      {subtitle && (
        <p className="mt-1 text-sm text-gray-500 font-normal leading-relaxed">
          {subtitle}
        </p>
      )}
    </header>
  );
};
