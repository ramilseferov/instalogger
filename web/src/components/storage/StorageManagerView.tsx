import React, { useEffect, useState, useCallback } from 'react';
import { api } from '../../api/client';
import { ProviderHealth, StorageOverview } from '../../types';
import { useI18n } from '../../i18n';
import { Card } from '../common/Card';
import { Button } from '../common/Button';
import { ArrowLeft, HardDrive, Trash2, Download, RefreshCw, Activity } from 'lucide-react';

interface StorageManagerViewProps {
  onBack: () => void;
}

function fmtBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

export const StorageManagerView: React.FC<StorageManagerViewProps> = ({ onBack }) => {
  const { t } = useI18n();
  const [storage, setStorage] = useState<StorageOverview | null>(null);
  const [providers, setProviders] = useState<ProviderHealth[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, p] = await Promise.all([api.getStorageOverview(), api.getProviderHealth()]);
      setStorage(s);
      setProviders(p);
    } catch {} finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const runCleanup = async (scope: 'logs' | 'target_media' | 'orphans', targetId?: string) => {
    setBusy(true);
    try {
      await api.cleanupStorage(scope, targetId);
      await load();
    } finally {
      setBusy(false);
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
              className="w-9 h-9 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] flex items-center justify-center shrink-0"
              aria-label={t.back}
            >
              <ArrowLeft size={16} strokeWidth={1.75} />
            </button>
            <div className="min-w-0">
              <h1 className="text-base font-semibold text-[var(--text-primary)] truncate">
                {t.storage_title}
              </h1>
              <p className="text-xs text-[var(--text-muted)] truncate">{t.storage_subtitle}</p>
            </div>
          </div>

          <Button size="sm" variant="secondary" icon={<RefreshCw size={13} />} onClick={load} disabled={loading}>
            {t.check_now}
          </Button>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-4 sm:px-6 pt-5 space-y-6">
        {loading || !storage ? (
          <div className="space-y-3">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-24 rounded-2xl border border-[var(--border-subtle)] skeleton-box" />
            ))}
          </div>
        ) : (
          <>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
              <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
                <div className="text-lg font-semibold text-[#0C7FF2] dark:text-[#58A6FF] tabular-nums">
                  {fmtBytes(storage.total_bytes)}
                </div>
                <div className="text-xs text-[var(--text-muted)] mt-0.5">{t.storage_total}</div>
              </div>
              <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
                <div className="text-lg font-semibold text-[var(--text-primary)] tabular-nums">
                  {fmtBytes(storage.archives_bytes)}
                </div>
                <div className="text-xs text-[var(--text-muted)] mt-0.5">{t.archived}</div>
              </div>
              <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
                <div className="text-lg font-semibold text-[var(--text-primary)] tabular-nums">
                  {fmtBytes(storage.db_bytes)}
                </div>
                <div className="text-xs text-[var(--text-muted)] mt-0.5">SQLite DB</div>
              </div>
              <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)]">
                <div className="text-lg font-semibold text-[var(--text-primary)] tabular-nums">
                  {fmtBytes(storage.logs_bytes)}
                </div>
                <div className="text-xs text-[var(--text-muted)] mt-0.5">{t.dev_logs}</div>
              </div>
            </div>

            <Card className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-2.5">
                <HardDrive size={17} className="text-[var(--text-secondary)]" />
                <div className="text-xs text-[var(--text-secondary)]">
                  {t.storage_orphans}: <span className="font-mono font-semibold">{storage.orphan_dirs}</span> ({fmtBytes(storage.orphan_bytes)})
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => runCleanup('logs')}>
                  {t.storage_clear_logs}
                </Button>
                <Button
                  size="sm"
                  variant="danger"
                  disabled={busy || storage.orphan_dirs === 0}
                  onClick={() => runCleanup('orphans')}
                >
                  {t.storage_prune_orphans}
                </Button>
              </div>
            </Card>

            <section className="space-y-2.5">
              <h2 className="text-xs font-medium text-[var(--text-muted)] px-1">{t.storage_per_profile}</h2>
              <div className="space-y-2.5">
                {storage.targets.map((item) => (
                  <Card key={item.target_id} className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                    <div className="space-y-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-semibold text-[var(--text-primary)]">@{item.username}</span>
                        <span className="text-xs font-mono tabular-nums text-[#0C7FF2] dark:text-[#58A6FF]">
                          {fmtBytes(item.total_bytes)}
                        </span>
                        <span className="text-[11px] text-[var(--text-muted)] tabular-nums">
                          ({item.file_count} {t.files_count})
                        </span>
                      </div>
                      <div className="flex flex-wrap gap-2 text-[11px] text-[var(--text-muted)] tabular-nums">
                        <span>{t.instagram_posts}: {fmtBytes(item.posts_bytes)}</span>
                        <span>Reels: {fmtBytes(item.reels_bytes)}</span>
                        <span>{t.stories}: {fmtBytes(item.stories_bytes)}</span>
                        <span>{t.highlights}: {fmtBytes(item.highlights_bytes)}</span>
                      </div>
                    </div>
                    <div className="flex items-center gap-2 shrink-0">
                      <a
                        href={`/api/storage/export/${encodeURIComponent(item.target_id)}`}
                        download
                        className="inline-flex items-center gap-1.5 h-8 px-3 rounded-lg bg-[var(--bg-subtle)] border border-[var(--border-subtle)] hover:border-[var(--border-strong)] text-xs font-medium text-[var(--text-primary)]"
                      >
                        <Download size={13} /> ZIP
                      </a>
                      <Button
                        size="sm"
                        variant="danger"
                        icon={<Trash2 size={13} />}
                        disabled={busy || item.total_bytes === 0}
                        onClick={() => runCleanup('target_media', item.target_id)}
                      >
                        {t.clear}
                      </Button>
                    </div>
                  </Card>
                ))}
              </div>
            </section>

            <section className="space-y-2.5">
              <div className="flex items-center justify-between px-1">
                <h2 className="text-xs font-medium text-[var(--text-muted)]">{t.provider_health_title}</h2>
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={async () => {
                    await api.resetProviderHealth();
                    load();
                  }}
                >
                  {t.clear}
                </Button>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
                {providers.map((p) => (
                  <Card key={p.provider_id} className="space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="text-xs font-semibold text-[var(--text-primary)] flex items-center gap-1.5">
                        <Activity size={13} className="text-[#0C7FF2]" />
                        {p.provider_id}
                      </span>
                      <span
                        className={`text-[10px] font-medium px-2 py-0.5 rounded-md ${
                          p.status === 'healthy'
                            ? 'bg-emerald-500/12 text-emerald-700 dark:text-emerald-400'
                            : p.status === 'cooldown'
                            ? 'bg-rose-500/12 text-rose-600 dark:text-rose-400'
                            : 'bg-amber-500/12 text-amber-700 dark:text-amber-300'
                        }`}
                      >
                        {p.status}
                      </span>
                    </div>
                    <div className="flex items-center justify-between text-[11px] text-[var(--text-muted)] tabular-nums">
                      <span>OK: {p.success_count}</span>
                      <span>ERR: {p.failure_count}</span>
                    </div>
                    {p.last_error && (
                      <p className="text-[10px] text-rose-500 truncate" title={p.last_error}>
                        {p.last_error}
                      </p>
                    )}
                  </Card>
                ))}
              </div>
            </section>
          </>
        )}
      </main>
    </div>
  );
};
