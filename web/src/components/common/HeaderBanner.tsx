import React from 'react';
import { Download, Settings, Terminal } from 'lucide-react';
import { useI18n } from '../../i18n';

interface HeaderBannerProps {
  onSettingsClick: () => void;
  onDevLogsClick: () => void;
  onDownloadsClick: () => void;
  activeDownloadsCount?: number;
  activeCount: number;
  wsConnected: boolean;
}

export const HeaderBanner: React.FC<HeaderBannerProps> = ({
  onSettingsClick,
  onDevLogsClick,
  onDownloadsClick,
  activeDownloadsCount = 0,
  activeCount,
  wsConnected,
}) => {
  const { t } = useI18n();

  return (
    <header className="sticky top-0 z-30 bg-[var(--bg-canvas)]/90 backdrop-blur-md border-b border-[var(--border-subtle)]">
      <div className="max-w-4xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-4">
        <div className="flex items-center gap-3 min-w-0">
          <img
            src="/instalogger-icon.svg"
            alt={t.app_title}
            className="w-8 h-8 rounded-lg select-none object-contain shrink-0"
          />
          <div className="min-w-0">
            <div className="flex items-center gap-2.5">
              <h1 className="text-base font-semibold text-[var(--text-primary)] tracking-tight truncate">
                {t.app_title}
              </h1>
              <span className="inline-flex items-center gap-1.5 text-[11px] font-medium text-[var(--text-secondary)] px-2 py-0.5 rounded-md bg-[var(--bg-surface)] border border-[var(--border-subtle)]">
                <span
                  className={`h-1.5 w-1.5 rounded-full ${
                    wsConnected ? 'bg-emerald-500' : 'bg-amber-500'
                  }`}
                />
                <span className="hidden sm:inline">
                  {wsConnected ? t.monitoring_active : t.reconnecting}
                </span>
                <span className="tabular-nums text-[var(--text-muted)]">{activeCount}</span>
              </span>
            </div>
            <p className="text-xs text-[var(--text-muted)] truncate hidden sm:block">
              {t.app_subtitle}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2 shrink-0">
          <button
            type="button"
            onClick={onDownloadsClick}
            className="relative inline-flex items-center justify-center gap-1.5 h-9 px-2.5 sm:px-3 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-xs font-medium text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:border-[var(--border-strong)] active:scale-95 transition-[color,border-color,transform] duration-150"
            title={t.downloads}
            aria-label={t.downloads}
          >
            <Download
              size={15}
              strokeWidth={1.75}
              className={activeDownloadsCount > 0 ? 'text-[#0C7FF2] dark:text-[#58A6FF]' : ''}
            />
            <span className="hidden md:inline">{t.downloads}</span>
            {activeDownloadsCount > 0 && (
              <span className="min-w-[18px] h-[18px] px-1 rounded-full bg-[#0C7FF2] text-white text-[10px] font-semibold tabular-nums inline-flex items-center justify-center">
                {activeDownloadsCount}
              </span>
            )}
          </button>

          <button
            type="button"
            onClick={onDevLogsClick}
            className="inline-flex items-center justify-center w-9 h-9 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:border-[var(--border-strong)] active:scale-95 transition-[color,border-color,transform] duration-150"
            title={t.dev_logs}
            aria-label={t.dev_logs}
          >
            <Terminal size={16} strokeWidth={1.75} />
          </button>

          <button
            type="button"
            onClick={onSettingsClick}
            className="inline-flex items-center gap-2 h-9 px-3 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-xs font-medium text-[var(--text-primary)] hover:border-[var(--border-strong)] active:scale-95 transition-[color,border-color,transform] duration-150"
            title={t.settings}
            aria-label={t.settings}
          >
            <Settings size={15} strokeWidth={1.75} className="text-[var(--text-secondary)]" />
            <span className="hidden sm:inline">{t.settings}</span>
          </button>
        </div>
      </div>
    </header>
  );
};
