import React from 'react';
import { Activity, Globe } from 'lucide-react';
import { useI18n, Language } from '../../i18n';

interface HeaderBannerProps {
  title?: string;
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
  const { language, setLanguage, t } = useI18n();

  const languages: { code: Language; label: string }[] = [
    { code: 'en', label: 'EN' },
    { code: 'az', label: 'AZ' },
    { code: 'ru', label: 'RU' },
  ];

  return (
    <header className="pt-8 pb-6 px-6 max-w-2xl mx-auto animate-fade-up">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2 bg-white/70 backdrop-blur-sm px-3 py-1 rounded-full border border-gray-200/60 shadow-xs">
          <span className="relative flex h-2.5 w-2.5">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-emerald-500"></span>
          </span>
          <span className="text-[11px] font-bold uppercase tracking-wider text-gray-600">
            {t.monitoring_active}
          </span>
        </div>

        <div className="flex items-center gap-2">
          {/* Language Switcher */}
          <div className="flex items-center bg-white/80 backdrop-blur-sm rounded-full p-0.5 border border-gray-200/60 shadow-xs">
            <Globe size={14} className="ml-2 mr-1 text-gray-400" />
            {languages.map((l) => (
              <button
                key={l.code}
                onClick={() => setLanguage(l.code)}
                className={`px-2 py-0.5 text-xs font-bold rounded-full transition-all duration-150 ${
                  language === l.code
                    ? 'bg-[#0C7FF2] text-white shadow-xs'
                    : 'text-gray-500 hover:text-gray-900'
                }`}
              >
                {l.label}
              </button>
            ))}
          </div>

          {activeCount > 0 && (
            <span className="text-xs font-bold bg-[#E3F2FD] text-[#0C7FF2] px-3 py-1 rounded-full border border-blue-100 shadow-xs animate-scale-in">
              {activeCount} {t.targets_badge}
            </span>
          )}

          {onSettingsClick && (
            <button
              onClick={onSettingsClick}
              className="p-2 rounded-full bg-white/80 hover:bg-white text-gray-600 hover:text-[#0C7FF2] shadow-xs hover:shadow-sm active:scale-90 transition-all duration-200"
              title={t.settings}
            >
              <Activity size={18} />
            </button>
          )}
        </div>
      </div>

      <h1 className="text-3xl font-extrabold text-gray-900 tracking-tight transition-colors">
        {title || t.app_title}
      </h1>
      <p className="mt-1 text-sm text-gray-500 font-normal leading-relaxed">
        {subtitle || t.app_subtitle}
      </p>
    </header>
  );
};
