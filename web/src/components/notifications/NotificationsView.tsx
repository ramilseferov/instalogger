import React, { useEffect, useState, useCallback } from 'react';
import { api } from '../../api/client';
import { NotificationRecord } from '../../types';
import { useI18n } from '../../i18n';
import { Button } from '../common/Button';
import { ArrowLeft, Bell, CheckCheck, Trash2, Settings } from 'lucide-react';

interface NotificationsViewProps {
  onBack: () => void;
  onOpenSettings: () => void;
}

export const NotificationsView: React.FC<NotificationsViewProps> = ({
  onBack,
  onOpenSettings,
}) => {
  const { t } = useI18n();
  const [items, setItems] = useState<NotificationRecord[]>([]);
  const [filter, setFilter] = useState<'all' | 'unread'>('all');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      const res = await api.getNotifications();
      setItems(res.notifications || []);
    } catch {} finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const visible = filter === 'unread' ? items.filter((n) => !n.is_read) : items;
  const unreadCount = items.filter((n) => !n.is_read).length;

  return (
    <div className="min-h-screen bg-[var(--bg-canvas)] pb-20">
      <header className="sticky top-0 z-30 bg-[var(--bg-canvas)]/95 backdrop-blur-md border-b border-[var(--border-subtle)]">
        <div className="max-w-3xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-3">
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
                {t.notifications_center}
              </h1>
              <p className="text-xs text-[var(--text-muted)] tabular-nums">
                {unreadCount} {t.notifications_unread}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-1.5 shrink-0">
            <Button
              size="sm"
              variant="secondary"
              icon={<CheckCheck size={14} />}
              disabled={unreadCount === 0}
              onClick={async () => {
                await api.markAllNotificationsRead();
                load();
              }}
            >
              <span className="hidden sm:inline">{t.notifications_mark_all_read}</span>
            </Button>
            <Button
              size="sm"
              variant="secondary"
              icon={<Trash2 size={14} />}
              disabled={items.length === 0}
              onClick={async () => {
                await api.clearNotifications();
                load();
              }}
            >
              <span className="hidden sm:inline">{t.clear}</span>
            </Button>
            <button
              type="button"
              onClick={onOpenSettings}
              className="w-9 h-9 rounded-xl bg-[var(--bg-surface)] border border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] flex items-center justify-center"
              title={t.settings}
            >
              <Settings size={15} />
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-3xl mx-auto px-4 sm:px-6 pt-5 space-y-4">
        <div className="flex items-center gap-2">
          {(['all', 'unread'] as const).map((f) => (
            <button
              key={f}
              type="button"
              onClick={() => setFilter(f)}
              className={`h-8 px-3 rounded-lg text-xs font-medium border ${
                filter === f
                  ? 'bg-[#0C7FF2] text-white border-[#0C7FF2]'
                  : 'bg-[var(--bg-surface)] text-[var(--text-secondary)] border-[var(--border-subtle)]'
              }`}
            >
              {f === 'all' ? `${t.all} (${items.length})` : `${t.notifications_unread} (${unreadCount})`}
            </button>
          ))}
        </div>

        {loading ? (
          <div className="space-y-2.5">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-20 rounded-2xl border border-[var(--border-subtle)] skeleton-box" />
            ))}
          </div>
        ) : visible.length === 0 ? (
          <div className="bg-[var(--bg-surface)] rounded-2xl p-10 text-center border border-[var(--border-subtle)] space-y-2">
            <Bell className="w-8 h-8 text-[var(--text-muted)] mx-auto" strokeWidth={1.5} />
            <p className="text-sm font-medium text-[var(--text-primary)]">{t.notifications_empty}</p>
          </div>
        ) : (
          <div className="space-y-2.5">
            {visible.map((n) => (
              <div
                key={n.id}
                onClick={async () => {
                  if (!n.is_read) {
                    await api.markNotificationRead(n.id);
                    load();
                  }
                }}
                className={`rounded-2xl p-4 border transition-colors cursor-pointer flex items-start justify-between gap-3 ${
                  n.is_read
                    ? 'bg-[var(--bg-surface)] border-[var(--border-subtle)] opacity-75'
                    : 'bg-[var(--bg-surface)] border-[#0C7FF2]/40'
                }`}
              >
                <div className="min-w-0 space-y-1">
                  <div className="flex items-center gap-2">
                    {!n.is_read && <span className="w-2 h-2 rounded-full bg-[#0C7FF2] shrink-0" />}
                    <span className="text-xs font-semibold text-[var(--text-primary)] truncate">
                      {n.title}
                    </span>
                  </div>
                  <p className="text-xs text-[var(--text-secondary)] leading-relaxed">{n.body}</p>
                  <p className="text-[11px] font-mono tabular-nums text-[var(--text-muted)]">
                    {new Date(n.timestamp).toLocaleString()}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={async (e) => {
                    e.stopPropagation();
                    await api.deleteNotification(n.id);
                    load();
                  }}
                  className="w-8 h-8 rounded-lg text-[var(--text-muted)] hover:text-rose-500 hover:bg-rose-500/10 flex items-center justify-center shrink-0"
                >
                  <Trash2 size={14} />
                </button>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
};
