import React from 'react';
import { Activity, Globe } from 'lucide-react';
import { useI18n, Language } from '../../i18n';

interface HeaderBannerProps {
  title?: string;
  subtitle?: string;
  onSettingsClick?: () => void;
  activeCount?: number;
}

const AzFlag = () => (
  <svg viewBox="0 0 640 480" className="w-3.5 h-2.5 inline-block rounded-xs shadow-xs">
    <path fill="#3f9c35" d="M0 0h640v480H0z"/>
    <path fill="#ed2939" d="M0 0h640v320H0z"/>
    <path fill="#00b9e4" d="M0 0h640v160H0z"/>
    <circle cx="304" cy="240" r="72" fill="#fff"/>
    <circle cx="320" cy="240" r="60" fill="#ed2939"/>
    <path fill="#fff" d="m384 200 7.7 21.5 20.6-9.8-9.8 20.7L424 240l-21.5 7.7 9.8 20.6-20.6-9.8L384 280l-7.7-21.5-20.6 9.8 9.8-20.6L344 240l21.5-7.7-9.8-20.6 20.6 9.8z"/>
  </svg>
);

const GbFlag = () => (
  <svg viewBox="0 0 640 480" className="w-3.5 h-2.5 inline-block rounded-xs shadow-xs">
    <path fill="#012169" d="M0 0h640v480H0z"/>
    <path fill="#FFF" d="m75 0 244 181L562 0h78v62L400 241l240 178v61h-80L320 301 81 480H0v-60l239-178L0 64V0z"/>
    <path fill="#C8102E" d="m424 281 216 159v40L369 281zm-184 20 6 35L54 480H0zM640 0v3L391 191l2-44L590 0zM0 0l239 176h-60L0 42z"/>
    <path fill="#FFF" d="M241 0v480h160V0zM0 160v160h640V160z"/>
    <path fill="#C8102E" d="M0 193v96h640v-96zM273 0v480h96V0z"/>
  </svg>
);

const RuFlag = () => (
  <svg viewBox="0 0 640 480" className="w-3.5 h-2.5 inline-block rounded-xs shadow-xs">
    <path fill="#fff" d="M0 0h640v160H0z"/>
    <path fill="#0039a6" d="M0 160h640v160H0z"/>
    <path fill="#d52b1e" d="M0 320h640v160H0z"/>
  </svg>
);

export const HeaderBanner: React.FC<HeaderBannerProps> = ({
  title,
  subtitle,
  onSettingsClick,
  activeCount = 0,
}) => {
  const { language, setLanguage, t } = useI18n();

  const languages: { code: Language; label: string; flag: React.ReactNode }[] = [
    { code: 'az', label: 'AZ', flag: <AzFlag /> },
    { code: 'en', label: 'EN', flag: <GbFlag /> },
    { code: 'ru', label: 'RU', flag: <RuFlag /> },
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
          <div className="flex items-center bg-white/80 backdrop-blur-sm rounded-full p-1 border border-gray-200/60 shadow-xs gap-1">
            <Globe size={14} className="ml-1.5 mr-0.5 text-gray-400" />
            {languages.map((l) => (
              <button
                key={l.code}
                onClick={() => setLanguage(l.code)}
                className={`flex items-center gap-1.5 px-2 py-0.5 text-xs font-bold rounded-full transition-all duration-150 ${
                  language === l.code
                    ? 'bg-[#0C7FF2] text-white shadow-xs'
                    : 'text-gray-500 hover:text-gray-900'
                }`}
              >
                {l.flag}
                <span>{l.label}</span>
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
