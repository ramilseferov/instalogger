import React, { useEffect, useState, useMemo, useCallback } from 'react';
import { api } from '../../api/client';
import { DownloadItem, DownloadSummary } from '../../types';
import { useI18n } from '../../i18n';
import { Button } from '../common/Button';
import { SegmentedControl } from '../common/SegmentedControl';
import {
  ArrowLeft,
  Download,
  Pause,
  Play,
  RotateCw,
  X,
  Trash2,
  CheckCircle2,
  Film,
  Image as ImageIcon,
} from 'lucide-react';

interface DownloadsViewProps {
  onBack: () => void;
}

type DlFilter = 'all' | 'active' | 'completed' | 'failed';

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function getPreviewUrl(item: DownloadItem): string {
  if (item.relative_file_path) {
    const clean = item.relative_file_path.startsWith('/')
      ? item.relative_file_path
      : `/${item.relative_file_path}`;
    if (/\.mp4$/i.test(clean)) {
      return clean.replace(/\.mp4$/i, '_thumb.jpg');
    }
    return clean;
  }
  return item.cdn_url || '';
}

export const DownloadsView: React.FC<DownloadsViewProps> = ({ onBack }) => {
  const { t } = useI18n();
  const [items, setItems] = useState<DownloadItem[]>([]);
  const [summary, setSummary] = useState<DownloadSummary>({
    active: 0,
    queued: 0,
    paused: 0,
    completed: 0,
    failed: 0,
    total_speed_bps: 0,
    paused_all: false,
    workers: 4,
  });
  const [filter, setFilter] = useState<DlFilter>('all');
  const [loading, setLoading] = useState(true);

  const loadDownloads = useCallback(async () => {
    try {
      const res = await api.getDownloads();
      setItems(res.items);
      setSummary(res.summary);
    } catch {} finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDownloads();
    const timer = window.setInterval(loadDownloads, 1500);
    return () => window.clearInterval(timer);
  }, [loadDownloads]);

  const filtered = useMemo(() => {
    return items.filter((it) => {
      if (filter === 'all') return true;
      if (filter === 'active') return it.status === 'downloading' || it.status === 'queued' || it.status === 'paused';
      if (filter === 'completed') return it.status === 'completed';
      if (filter === 'failed') return it.status === 'failed' || it.status === 'cancelled';
      return true;
    });
  }, [items, filter]);

  const statusLabel = (s: DownloadItem['status']) => {
    switch (s) {
      case 'downloading':
        return t.downloads_active;
      case 'queued':
        return t.downloads_queued;
      case 'paused':
        return t.downloads_paused;
      case 'completed':
        return t.downloads_completed;
      case 'failed':
        return t.downloads_failed;
      case 'cancelled':
        return t.downloads_cancelled;
    }
  };

  return (
    <div className="min-h-screen bg-[var(--bg-canvas)] pb-20">
      <header className="sticky top-0 z-30 bg-[var(--bg-canvas)]/95 backdrop-blur-md border-b border-[var(--border-subtle)]">
        <div className="max-w-4xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-3">
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
                {t.downloads}
              </h1>
              <p className="text-xs text-[var(--text-muted)] tabular-nums truncate">
                {summary.workers} {t.downloads_workers}
                {summary.total_speed_bps > 0 ? ` · ${formatBytes(summary.total_speed_bps)}/s` : ''}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            {summary.paused_all ? (
              <Button
                size="sm"
                variant="primary"
                icon={<Play size={14} strokeWidth={1.75} />}
                onClick={async () => {
                  await api.resumeAllDownloads();
                  loadDownloads();
                }}
              >
                {t.downloads_resume_all}
              </Button>
            ) : (
              <Button
                size="sm"
                variant="secondary"
                icon={<Pause size={14} strokeWidth={1.75} />}
                disabled={summary.active === 0 && summary.queued === 0}
                onClick={async () => {
                  await api.pauseAllDownloads();
                  loadDownloads();
                }}
              >
                {t.downloads_pause_all}
              </Button>
            )}

            <Button
              size="sm"
              variant="secondary"
              icon={<Trash2 size={14} strokeWidth={1.75} />}
              disabled={summary.completed === 0}
              onClick={async () => {
                await api.clearCompletedDownloads();
                loadDownloads();
              }}
            >
              <span className="hidden sm:inline">{t.downloads_clear_completed}</span>
            </Button>
          </div>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-4 sm:px-6 pt-5 space-y-5">
        {/* Live Summary Strip */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
          <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
            <div className="text-lg font-semibold text-[#0C7FF2] dark:text-[#58A6FF] tabular-nums">
              {summary.active}
            </div>
            <div className="text-xs text-[var(--text-muted)] mt-0.5">{t.downloads_active}</div>
          </div>
          <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
            <div className="text-lg font-semibold text-[var(--text-primary)] tabular-nums">
              {summary.queued + summary.paused}
            </div>
            <div className="text-xs text-[var(--text-muted)] mt-0.5">{t.downloads_queued}</div>
          </div>
          <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
            <div className="text-lg font-semibold text-emerald-600 dark:text-emerald-400 tabular-nums">
              {summary.completed}
            </div>
            <div className="text-xs text-[var(--text-muted)] mt-0.5">{t.downloads_completed}</div>
          </div>
          <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
            <div className="text-lg font-semibold text-rose-600 dark:text-rose-400 tabular-nums">
              {summary.failed}
            </div>
            <div className="text-xs text-[var(--text-muted)] mt-0.5">{t.downloads_failed}</div>
          </div>
        </div>

        <SegmentedControl<DlFilter>
          value={filter}
          onChange={setFilter}
          options={[
            { value: 'all', label: t.all, count: items.length },
            { value: 'active', label: t.downloads_active, count: summary.active + summary.queued + summary.paused },
            { value: 'completed', label: t.downloads_completed, count: summary.completed },
            { value: 'failed', label: t.downloads_failed, count: summary.failed },
          ]}
        />

        {loading ? (
          <div className="space-y-2.5">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-20 rounded-2xl border border-[var(--border-subtle)] skeleton-box" />
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <div className="bg-[var(--bg-surface)] rounded-2xl p-10 text-center border border-[var(--border-subtle)] space-y-2">
            <Download className="w-8 h-8 text-[var(--text-muted)] mx-auto" strokeWidth={1.5} />
            <p className="text-sm font-medium text-[var(--text-primary)]">{t.downloads_empty_title}</p>
            <p className="text-xs text-[var(--text-muted)] max-w-sm mx-auto">{t.downloads_empty_desc}</p>
          </div>
        ) : (
          <div className="space-y-2.5">
            {filtered.map((item) => {
              const preview = getPreviewUrl(item);
              const isVideo = item.media_type === 'reel' || /\.mp4$/i.test(item.relative_file_path);
              return (
                <div
                  key={item.id}
                  className="bg-[var(--bg-surface)] rounded-2xl p-3.5 border border-[var(--border-subtle)] flex items-center gap-3.5 animate-card-enter"
                >
                  <div className="w-12 h-12 rounded-xl overflow-hidden bg-[var(--bg-subtle)] border border-[var(--border-subtle)] shrink-0 flex items-center justify-center">
                    {preview ? (
                      <img
                        src={preview}
                        alt={item.shortcode}
                        className="w-full h-full object-cover"
                        onError={(e) => {
                          const img = e.target as HTMLImageElement;
                          if (item.cdn_url && img.src !== item.cdn_url) {
                            img.src = item.cdn_url;
                          } else {
                            img.style.display = 'none';
                          }
                        }}
                      />
                    ) : isVideo ? (
                      <Film size={16} strokeWidth={1.75} className="text-[var(--text-muted)]" />
                    ) : (
                      <ImageIcon size={16} strokeWidth={1.75} className="text-[var(--text-muted)]" />
                    )}
                  </div>

                  <div className="flex-1 min-w-0 space-y-1.5">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-xs font-semibold text-[var(--text-primary)] truncate">
                          @{item.username}
                        </span>
                        <span className="text-[11px] font-mono text-[var(--text-muted)] truncate">
                          {item.shortcode}
                        </span>
                        {item.provider && (
                          <span className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-[var(--bg-subtle)] text-[var(--text-secondary)] shrink-0">
                            {item.provider}
                          </span>
                        )}
                      </div>
                      <span className="text-[11px] font-medium tabular-nums text-[var(--text-secondary)] shrink-0">
                        {statusLabel(item.status)}
                        {item.file_size_bytes > 0 ? ` · ${formatBytes(item.file_size_bytes)}` : ''}
                      </span>
                    </div>

                    <div className="h-1.5 w-full rounded-full bg-[var(--bg-subtle)] overflow-hidden">
                      <div
                        className={`h-full rounded-full transition-[width] duration-300 ease-out ${
                          item.status === 'completed'
                            ? 'bg-emerald-500'
                            : item.status === 'failed'
                            ? 'bg-rose-500'
                            : item.status === 'paused'
                            ? 'bg-amber-500'
                            : 'bg-[#0C7FF2]'
                        }`}
                        style={{
                          width: `${item.status === 'downloading' ? Math.max(25, item.progress) : item.progress}%`,
                        }}
                      />
                    </div>

                    {item.error && (
                      <p className="text-[11px] text-rose-600 dark:text-rose-400 truncate">{item.error}</p>
                    )}
                  </div>

                  <div className="flex items-center gap-1 shrink-0">
                    {(item.status === 'downloading' || item.status === 'queued') && (
                      <>
                        <button
                          type="button"
                          onClick={async () => {
                            await api.pauseDownload(item.id);
                            loadDownloads();
                          }}
                          className="w-8 h-8 rounded-lg bg-[var(--bg-subtle)] hover:bg-[var(--border-subtle)] text-[var(--text-secondary)] flex items-center justify-center"
                          title={t.downloads_pause}
                        >
                          <Pause size={14} strokeWidth={1.75} />
                        </button>
                        <button
                          type="button"
                          onClick={async () => {
                            await api.cancelDownload(item.id);
                            loadDownloads();
                          }}
                          className="w-8 h-8 rounded-lg bg-rose-500/10 text-rose-600 dark:text-rose-400 flex items-center justify-center"
                          title={t.cancel}
                        >
                          <X size={14} strokeWidth={1.75} />
                        </button>
                      </>
                    )}
                    {item.status === 'paused' && (
                      <button
                        type="button"
                        onClick={async () => {
                          await api.resumeDownload(item.id);
                          loadDownloads();
                        }}
                        className="w-8 h-8 rounded-lg bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF] flex items-center justify-center"
                        title={t.downloads_resume}
                      >
                        <Play size={14} strokeWidth={1.75} />
                      </button>
                    )}
                    {(item.status === 'failed' || item.status === 'cancelled') && (
                      <button
                        type="button"
                        onClick={async () => {
                          await api.retryDownload(item.id);
                          loadDownloads();
                        }}
                        className="w-8 h-8 rounded-lg bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF] flex items-center justify-center"
                        title={t.downloads_retry}
                      >
                        <RotateCw size={14} strokeWidth={1.75} />
                      </button>
                    )}
                    {item.status === 'completed' && (
                      <span className="w-8 h-8 flex items-center justify-center text-emerald-500">
                        <CheckCircle2 size={16} strokeWidth={1.75} />
                      </span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </main>
    </div>
  );
};
