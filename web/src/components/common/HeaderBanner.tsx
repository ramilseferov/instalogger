import React from 'react';
import { Settings, Globe, Terminal, Sun, Moon } from 'lucide-react';
import { useI18n, Language } from '../../i18n';

interface HeaderBannerProps {
  title?: string;
  subtitle?: string;
  onSettingsClick?: () => void;
  onDevLogsClick?: () => void;
  activeCount?: number;
  wsConnected?: boolean;
  darkMode?: boolean;
  onToggleDarkMode?: () => void;
}

const AzFlag = () => (
  <svg viewBox="0 0 640 480" className="w-3.5 h-2.5 inline-block rounded-xs">
    <path fill="#3f9c35" d="M0 0h640v480H0z" />
    <path fill="#ed2939" d="M0 0h640v320H0z" />
    <path fill="#00b9e4" d="M0 0h640v160H0z" />
    <circle cx="304" cy="240" r="72" fill="#fff" />
    <circle cx="320" cy="240" r="60" fill="#ed2939" />
    <path fill="#fff" d="m384 200 7.7 21.5 20.6-9.8-9.8 20.7L424 240l-21.5 7.7 9.8 20.6-20.6-9.8L384 280l-7.7-21.5-20.6 9.8 9.8-20.6L344 240l21.5-7.7-9.8-20.6 20.6 9.8z" />
  </svg>
);

const GbFlag = () => (
  <svg viewBox="0 0 640 480" className="w-3.5 h-2.5 inline-block rounded-xs">
    <path fill="#012169" d="M0 0h640v480H0z" />
    <path fill="#FFF" d="m75 0 244 181L562 0h78v62L400 241l240 178v61h-80L320 301 81 480H0v-60l239-178L0 64V0z" />
    <path fill="#C8102E" d="m424 281 216 159v40L369 281zm-184 20 6 35L54 480H0zM640 0v3L391 191l2-44L590 0zM0 0l239 176h-60L0 42z" />
    <path fill="#FFF" d="M241 0v480h160V0zM0 160v160h640V160z" />
    <path fill="#C8102E" d="M0 193v96h640v-96zM273 0v480h96V0z" />
  </svg>
);

const RuFlag = () => (
  <svg viewBox="0 0 640 480" className="w-3.5 h-2.5 inline-block rounded-xs">
    <path fill="#fff" d="M0 0h640v160H0z" />
    <path fill="#0039a6" d="M0 160h640v160H0z" />
    <path fill="#d52b1e" d="M0 320h640v160H0z" />
  </svg>
);

export const HeaderBanner: React.FC<HeaderBannerProps> = ({
  title,
  subtitle,
  onSettingsClick,
  onDevLogsClick,
  activeCount = 0,
  wsConnected = true,
  darkMode = false,
  onToggleDarkMode,
}) => {
  const { language, setLanguage, t } = useI18n();

  const languages: { code: Language; label: string; flag: React.ReactNode }[] = [
    { code: 'az', label: 'AZ', flag: <AzFlag /> },
    { code: 'en', label: 'EN', flag: <GbFlag /> },
    { code: 'ru', label: 'RU', flag: <RuFlag /> },
  ];

  return (
    <header className="pt-7 pb-5 px-4 max-w-3xl mx-auto">
      <div className="flex flex-wrap items-center justify-between gap-2 mb-4">
        <div className="flex items-center gap-2 bg-white dark:bg-slate-900 px-3 py-1 rounded-full border border-slate-200 dark:border-slate-800">
          <span
            className={`inline-block h-2 w-2 rounded-full ${
              wsConnected ? 'bg-emerald-500' : 'bg-amber-500'
            }`}
          />
          <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-600 dark:text-slate-300">
            {wsConnected ? t.monitoring_active : 'Reconnecting'}
          </span>
        </div>

        <div className="flex items-center gap-1.5 flex-wrap">
          <div className="flex items-center bg-white dark:bg-slate-900 rounded-full p-0.5 border border-slate-200 dark:border-slate-800 gap-0.5">
            <Globe size={13} className="ml-1.5 mr-0.5 text-slate-400" />
            {languages.map((l) => (
              <button
                key={l.code}
                onClick={() => setLanguage(l.code)}
                className={`flex items-center gap-1 px-2 py-0.5 text-[11px] font-bold rounded-full transition-colors ${
                  language === l.code
                    ? 'bg-[#0C7FF2] text-white'
                    : 'text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
                }`}
              >
                {l.flag}
                <span>{l.label}</span>
              </button>
            ))}
          </div>

          {onToggleDarkMode && (
            <button
              onClick={onToggleDarkMode}
              className="p-2 rounded-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-300 hover:text-[#0C7FF2] transition-colors"
              title="Toggle theme"
              aria-label="Toggle theme"
            >
              {darkMode ? <Sun size={16} /> : <Moon size={16} />}
            </button>
          )}

          {onDevLogsClick && (
            <button
              onClick={onDevLogsClick}
              className="p-2 rounded-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-600 dark:text-emerald-400 hover:text-[#0C7FF2] transition-colors"
              title="Developer Logs"
              aria-label="Developer Logs"
            >
              <Terminal size={16} />
            </button>
          )}

          {onSettingsClick && (
            <button
              onClick={onSettingsClick}
              className="p-2 rounded-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-300 hover:text-[#0C7FF2] transition-colors"
              title={t.settings}
              aria-label={t.settings}
            >
              <Settings size={16} />
            </button>
          )}
        </div>
      </div>

      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <img
            src="/instalogger-icon.svg"
            alt="InstaLogger"
            className="w-10 h-10 rounded-xl select-none object-contain"
          />
          <div>
            <h1 className="text-2xl font-extrabold text-slate-900 dark:text-white tracking-tight">
              {title || t.app_title}
            </h1>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              {subtitle || t.app_subtitle}
            </p>
          </div>
        </div>
        {activeCount > 0 && (
          <span className="text-xs font-bold bg-blue-50 dark:bg-blue-500/15 text-[#0C7FF2] dark:text-blue-300 px-3 py-1 rounded-full border border-blue-200/70 dark:border-blue-500/30">
            {activeCount} {t.targets_badge}
          </span>
        )}
      </div>
    </header>
  );
};
