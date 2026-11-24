import React, { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../../api/client';
import { DevLogEntry } from '../../types';
import {
  ArrowLeft,
  Copy,
  Trash2,
  Search,
  Terminal,
  RefreshCw,
  Lock,
  Check,
  ArrowDownToLine,
} from 'lucide-react';

interface DeveloperLogsViewProps {
  onBack: () => void;
}

const LEVEL_STYLES: Record<string, string> = {
  E: 'text-rose-400 font-bold',
  W: 'text-amber-400 font-bold',
  I: 'text-emerald-400 font-bold',
  D: 'text-sky-400 font-bold',
};

export const DeveloperLogsView: React.FC<DeveloperLogsViewProps> = ({ onBack }) => {
  const [logs, setLogs] = useState<DevLogEntry[]>([]);
  const [levelFilter, setLevelFilter] = useState<'ALL' | 'E' | 'W' | 'I' | 'D'>('ALL');
  const [query, setQuery] = useState('');
  const [livePoll, setLivePoll] = useState(true);
  const [autoScroll, setAutoScroll] = useState(true);
  const [copiedMsg, setCopiedMsg] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  const fetchLogs = async () => {
    try {
      const list = await api.getDevLogs();
      setLogs(list);
      setErrorMsg(null);
    } catch (e: any) {
      setErrorMsg(e?.message || 'Failed to load logs');
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

  const copyText = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedMsg(label);
      window.setTimeout(() => setCopiedMsg(null), 1800);
    } catch {
      setErrorMsg('Clipboard permission denied');
    }
  };

  const handleClear = async () => {
    try {
      await api.clearDevLogs();
      setLogs([]);
    } catch (e: any) {
      setErrorMsg(e?.message || 'Failed to clear logs');
    }
  };

  return (
    <div className="min-h-screen bg-[#0A0D12] text-slate-200 flex flex-col">
      {/* Top Bar */}
      <header className="sticky top-0 z-20 bg-[#111620]/95 backdrop-blur-md border-b border-slate-800 px-4 py-3">
        <div className="max-w-5xl mx-auto flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <button
              onClick={onBack}
              className="p-2 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-800 text-slate-300 transition-colors"
              aria-label="Back"
            >
              <ArrowLeft size={18} />
            </button>
            <div>
              <div className="flex items-center gap-2">
                <Terminal size={16} className="text-emerald-400" />
                <h1 className="font-mono text-sm font-bold text-slate-100">Developer Logs</h1>
              </div>
              <p className="font-mono text-[11px] text-slate-400">
                {filtered.length} lines · {errCount} err · {warnCount} warn
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setLivePoll((v) => !v)}
              className={`px-2.5 py-1.5 rounded-lg border font-mono text-xs flex items-center gap-1.5 transition-colors ${
                livePoll
                  ? 'bg-emerald-500/15 border-emerald-500/40 text-emerald-300'
                  : 'bg-slate-900 border-slate-800 text-slate-400'
              }`}
            >
              <RefreshCw size={13} className={livePoll ? 'animate-spin' : ''} />
              {livePoll ? 'LIVE' : 'PAUSED'}
            </button>

            <button
              onClick={() => setAutoScroll((v) => !v)}
              className={`p-2 rounded-lg border transition-colors ${
                autoScroll
                  ? 'bg-sky-500/15 border-sky-500/40 text-sky-300'
                  : 'bg-slate-900 border-slate-800 text-slate-400'
              }`}
              title="Auto-scroll"
            >
              <ArrowDownToLine size={15} />
            </button>

            <button
              onClick={() =>
                copyText(filtered.map(formatLine).join('\n'), 'All logs copied')
              }
              disabled={filtered.length === 0}
              className="px-2.5 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 font-mono text-xs text-sky-300 flex items-center gap-1.5 disabled:opacity-40"
            >
              {copiedMsg ? <Check size={13} className="text-emerald-400" /> : <Copy size={13} />}
              {copiedMsg || 'Copy All'}
            </button>

            <button
              onClick={handleClear}
              className="p-2 rounded-lg bg-slate-900 hover:bg-rose-950/60 border border-slate-800 hover:border-rose-500/40 text-rose-400 transition-colors"
              title="Clear logs"
            >
              <Trash2 size={15} />
            </button>
          </div>
        </div>

        {/* Search & Level Filter */}
        <div className="max-w-5xl mx-auto mt-3 flex flex-col sm:flex-row gap-2.5 sm:items-center justify-between">
          <div className="relative flex-1">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="grep tag, HTTP status, target..."
              className="w-full bg-[#0A0D12] border border-slate-800 focus:border-sky-500 rounded-lg pl-9 pr-3 py-1.5 font-mono text-xs text-slate-200 placeholder:text-slate-500 focus:outline-none"
            />
          </div>

          <div className="flex items-center gap-1.5 overflow-x-auto">
            {(['ALL', 'E', 'W', 'I', 'D'] as const).map((lvl) => (
              <button
                key={lvl}
                onClick={() => setLevelFilter(lvl)}
                className={`px-2.5 py-1 rounded-md font-mono text-[11px] border transition-colors ${
                  levelFilter === lvl
                    ? 'bg-slate-800 border-slate-500 text-white font-bold'
                    : 'bg-[#0A0D12] border-slate-800 text-slate-400 hover:text-slate-200'
                }`}
              >
                {lvl === 'ALL' ? `ALL (${logs.length})` : lvl}
              </button>
            ))}
          </div>
        </div>

        <div className="max-w-5xl mx-auto mt-2 flex items-center gap-1.5 font-mono text-[11px] text-emerald-400/90">
          <Lock size={11} />
          <span>Redacted output · session cookies and signed CDN tokens are stripped before display</span>
        </div>
      </header>

      {errorMsg && (
        <div className="max-w-5xl w-full mx-auto mt-3 px-4">
          <div className="rounded-lg border border-rose-500/40 bg-rose-950/40 px-3 py-2 font-mono text-xs text-rose-300">
            {errorMsg}
          </div>
        </div>
      )}

      {/* Terminal Output */}
      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto px-4 py-3 font-mono text-xs leading-relaxed select-text"
      >
        <div className="max-w-5xl mx-auto space-y-1">
          {filtered.length === 0 ? (
            <div className="py-20 text-center text-slate-500">
              No matching log entries.
            </div>
          ) : (
            filtered.map((entry) => (
              <div
                key={entry.id}
                onClick={() => copyText(formatLine(entry), 'Line copied')}
                className="px-2 py-0.5 rounded hover:bg-slate-900/90 cursor-pointer flex gap-2 break-all"
                title="Click line to copy"
              >
                <span className="text-slate-500 shrink-0">
                  {entry.timestamp.replace('T', ' ').slice(11, 23)}
                </span>
                <span className={`${LEVEL_STYLES[entry.level] || 'text-slate-300'} shrink-0`}>
                  {entry.level}/{entry.tag}:
                </span>
                <span className={entry.level === 'E' ? 'text-rose-200' : 'text-slate-200'}>
                  {entry.message}
                </span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};
