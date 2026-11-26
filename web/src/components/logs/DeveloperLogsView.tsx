import React, { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../../api/client';
import { DevLogEntry } from '../../types';
import { useI18n } from '../../i18n';
import { SegmentedControl } from '../common/SegmentedControl';
import { ConfirmDialog } from '../common/ConfirmDialog';
import {
  ArrowLeft,
  Copy,
  Trash2,
  Search,
  RefreshCw,
  ShieldCheck,
  Check,
  ArrowDownToLine,
} from 'lucide-react';

interface DeveloperLogsViewProps {
  onBack: () => void;
}

type LevelFilter = 'ALL' | 'E' | 'W' | 'I' | 'D';

const LEVEL_BADGE: Record<string, string> = {
  E: 'bg-rose-500/12 text-rose-600 dark:text-rose-400 border-rose-500/20',
  W: 'bg-amber-500/12 text-amber-700 dark:text-amber-400 border-amber-500/20',
  I: 'bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF] border-[#0C7FF2]/20',
  D: 'bg-[var(--bg-subtle)] text-[var(--text-muted)] border-[var(--border-subtle)]',
};

export const DeveloperLogsView: React.FC<DeveloperLogsViewProps> = ({ onBack }) => {
  const { t } = useI18n();
  const [logs, setLogs] = useState<DevLogEntry[]>([]);
  const [levelFilter, setLevelFilter] = useState<LevelFilter>('ALL');
  const [query, setQuery] = useState('');
  const [livePoll, setLivePoll] = useState(true);
  const [autoScroll, setAutoScroll] = useState(true);
  const [copiedId, setCopiedId] = useState<number | 'all' | null>(null);
  const [confirmClear, setConfirmClear] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  const fetchLogs = async () => {
    try {
      const list = await api.getDevLogs();
      setLogs(list);
      setErrorMsg(null);
    } catch (e: any) {
      setErrorMsg(e?.message || t.toast_check_failed);
    }
  };

  useEffect(() => {
    fetchLogs();
    if (!livePoll) return;
    const timer = window.setInterval(fetchLogs, 3000);
    return () => window.clearInterval(timer);
  }, [livePoll]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return logs.filter((entry) => {
      const levelOk = levelFilter === 'ALL' || entry.level === levelFilter;
      const qOk =
        !q ||
        entry.tag.toLowerCase().includes(q) ||
        entry.message.toLowerCase().includes(q);
      return levelOk && qOk;
    });
  }, [logs, levelFilter, query]);

  const errCount = useMemo(() => logs.filter((l) => l.level === 'E').length, [logs]);
  const warnCount = useMemo(() => logs.filter((l) => l.level === 'W').length, [logs]);

  useEffect(() => {
    if (autoScroll && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [filtered.length, autoScroll]);

  const formatLine = (l: DevLogEntry) => {
    const ts = l.timestamp.replace('T', ' ').slice(11, 23);
    return `${ts} [${l.level}][${l.tag}] ${l.message}`;
  };

  const copyText = async (text: string, key: number | 'all') => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(key);
      window.setTimeout(() => setCopiedId((prev) => (prev === key ? null : prev)), 1600);
    } catch {
      setErrorMsg('Clipboard access denied');
    }
  };

  const handleClearConfirmed = async () => {
    setConfirmClear(false);
    try {
      await api.clearDevLogs();
      setLogs([]);
    } catch (e: any) {
      setErrorMsg(e?.message || t.toast_check_failed);
    }
  };

  return (
    <div className="min-h-screen bg-[var(--bg-canvas)] text-[var(--text-primary)] flex flex-col">
      <header className="sticky top-0 z-20 bg-[var(--bg-canvas)]/95 backdrop-blur-md border-b border-[var(--border-subtle)] px-4 sm:px-6 py-3.5">
        <div className="max-w-5xl mx-auto space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3 min-w-0">
              <button
                type="button"
                onClick={onBack}
                className="w-9 h-9 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:border-[var(--border-strong)] flex items-center justify-center active:scale-95 transition-[color,border-color,transform] duration-150 shrink-0"
                aria-label={t.back}
              >
                <ArrowLeft size={16} strokeWidth={1.75} />
              </button>
              <div className="min-w-0">
                <h1 className="text-base font-semibold text-[var(--text-primary)] truncate">
                  {t.dev_logs}
                </h1>
                <p className="text-xs text-[var(--text-muted)] tabular-nums">
                  {filtered.length} {t.logs_lines} · {errCount} {t.logs_level_err.toLowerCase()} · {warnCount}{' '}
                  {t.logs_level_warn.toLowerCase()}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setLivePoll((v) => !v)}
                className={`h-9 px-3 rounded-xl border text-xs font-medium inline-flex items-center gap-1.5 transition-colors duration-150 ${
                  livePoll
                    ? 'bg-[var(--accent-soft)] border-[#0C7FF2]/30 text-[#0C7FF2] dark:text-[#58A6FF]'
                    : 'bg-[var(--bg-surface)] border-[var(--border-subtle)] text-[var(--text-secondary)]'
                }`}
              >
                <RefreshCw size={13} strokeWidth={1.75} className={livePoll ? 'animate-spin' : ''} />
                <span>{livePoll ? t.logs_live : t.logs_paused}</span>
              </button>

              <button
                type="button"
                onClick={() => setAutoScroll((v) => !v)}
                className={`w-9 h-9 rounded-xl border flex items-center justify-center transition-colors duration-150 ${
                  autoScroll
                    ? 'bg-[var(--accent-soft)] border-[#0C7FF2]/30 text-[#0C7FF2] dark:text-[#58A6FF]'
                    : 'bg-[var(--bg-surface)] border-[var(--border-subtle)] text-[var(--text-muted)]'
                }`}
                title="Auto-scroll"
                aria-label="Auto-scroll"
              >
                <ArrowDownToLine size={15} strokeWidth={1.75} />
              </button>

              <button
                type="button"
                onClick={() => copyText(filtered.map(formatLine).join('\n'), 'all')}
                disabled={filtered.length === 0}
                className="h-9 px-3 rounded-xl bg-[var(--bg-surface)] hover:bg-[var(--bg-subtle)] border border-[var(--border-subtle)] text-xs font-medium text-[var(--text-primary)] inline-flex items-center gap-1.5 disabled:opacity-40 transition-colors duration-150"
              >
                {copiedId === 'all' ? (
                  <Check size={14} strokeWidth={1.75} className="text-emerald-500" />
                ) : (
                  <Copy size={14} strokeWidth={1.75} className="text-[var(--text-secondary)]" />
                )}
                <span>{copiedId === 'all' ? t.logs_copied : t.logs_copy_all}</span>
              </button>

              <button
                type="button"
                onClick={() => setConfirmClear(true)}
                disabled={logs.length === 0}
                className="w-9 h-9 rounded-xl bg-rose-500/10 hover:bg-rose-500/15 border border-rose-500/20 text-rose-600 dark:text-rose-400 flex items-center justify-center disabled:opacity-40 transition-colors duration-150"
                title={t.logs_clear}
                aria-label={t.logs_clear}
              >
                <Trash2 size={15} strokeWidth={1.75} />
              </button>
            </div>
          </div>

          <div className="flex flex-col md:flex-row gap-2.5 md:items-center">
            <div className="relative flex-1">
              <Search
                size={14}
                strokeWidth={1.75}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-muted)]"
              />
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t.logs_search_placeholder}
                className="w-full h-10 bg-[var(--bg-surface)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl pl-9 pr-3 text-xs text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none transition-colors duration-150"
              />
            </div>

            <SegmentedControl<LevelFilter>
              value={levelFilter}
              onChange={setLevelFilter}
              className="md:w-[380px]"
              options={[
                { value: 'ALL', label: t.logs_level_all },
                { value: 'E', label: t.logs_level_err },
                { value: 'W', label: t.logs_level_warn },
                { value: 'I', label: t.logs_level_info },
                { value: 'D', label: t.logs_level_debug },
              ]}
            />
          </div>

          <div className="flex items-center gap-1.5 text-[11px] text-[var(--text-muted)]">
            <ShieldCheck size={13} strokeWidth={1.75} />
            <span>{t.logs_redacted_notice}</span>
          </div>
        </div>
      </header>

      {errorMsg && (
        <div className="max-w-5xl w-full mx-auto mt-3 px-4 sm:px-6">
          <div className="rounded-xl border border-rose-500/25 bg-rose-500/10 px-3.5 py-2.5 text-xs text-rose-600 dark:text-rose-400">
            {errorMsg}
          </div>
        </div>
      )}

      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto px-4 sm:px-6 py-4 select-text"
      >
        <div className="max-w-5xl mx-auto bg-[var(--bg-surface)] rounded-2xl border border-[var(--border-subtle)] overflow-hidden">
          {filtered.length === 0 ? (
            <div className="py-20 text-center text-xs text-[var(--text-muted)]">
              {t.logs_empty}
            </div>
          ) : (
            <div className="divide-y divide-[var(--border-subtle)] font-mono text-xs">
              {filtered.map((entry) => {
                const isCopied = copiedId === entry.id;
                return (
                  <div
                    key={entry.id}
                    onClick={() => copyText(formatLine(entry), entry.id)}
                    className={`px-3.5 py-2.5 flex items-start gap-2.5 cursor-pointer transition-colors duration-150 ${
                      isCopied
                        ? 'bg-emerald-500/10'
                        : 'hover:bg-[var(--bg-subtle)]/70'
                    }`}
                  >
                    <span className="text-[11px] text-[var(--text-muted)] tabular-nums shrink-0 pt-0.5">
                      {entry.timestamp.replace('T', ' ').slice(11, 23)}
                    </span>
                    <span
                      className={`px-1.5 py-0.5 rounded text-[10px] font-medium border shrink-0 ${
                        LEVEL_BADGE[entry.level] || LEVEL_BADGE.D
                      }`}
                    >
                      {entry.level}
                    </span>
                    <span className="text-[var(--text-secondary)] font-medium shrink-0 pt-0.5">
                      {entry.tag}
                    </span>
                    <span className="text-[var(--text-primary)] break-all leading-relaxed flex-1 pt-0.5">
                      {entry.message}
                    </span>
                    {isCopied && (
                      <Check size={14} strokeWidth={1.75} className="text-emerald-500 shrink-0 mt-0.5" />
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <ConfirmDialog
        isOpen={confirmClear}
        title={t.logs_clear_confirm_title}
        description={t.logs_clear_confirm_desc}
        confirmLabel={t.logs_clear}
        cancelLabel={t.cancel}
        destructive
        onConfirm={handleClearConfirmed}
        onCancel={() => setConfirmClear(false)}
      />
    </div>
  );
};
