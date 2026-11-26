import React from 'react';
import { ChangeLog } from '../../types';
import { Lock, Unlock, AlertCircle, Clock } from 'lucide-react';
import { useI18n } from '../../i18n';

interface TimelineViewProps {
  logs: ChangeLog[];
}

export const TimelineView: React.FC<TimelineViewProps> = ({ logs }) => {
  const { language, t } = useI18n();

  if (logs.length === 0) {
    return (
      <div className="py-12 px-6 text-center bg-[var(--bg-surface)] rounded-2xl border border-[var(--border-subtle)] space-y-2">
        <Clock className="w-8 h-8 text-[var(--text-muted)] mx-auto" strokeWidth={1.5} />
        <p className="text-sm font-medium text-[var(--text-primary)]">{t.timeline_empty_title}</p>
        <p className="text-xs text-[var(--text-muted)] max-w-sm mx-auto">{t.timeline_empty_desc}</p>
      </div>
    );
  }

  const renderCountDelta = (label: string, oldRaw: string, newRaw: string) => {
    const oldNum = parseInt(oldRaw, 10) || 0;
    const newNum = parseInt(newRaw, 10) || 0;
    const diff = newNum - oldNum;
    return (
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="font-medium text-[var(--text-primary)]">{label}</span>
        <span className="font-mono tabular-nums text-[var(--text-muted)]">{oldNum.toLocaleString()}</span>
        <span className="text-[var(--text-muted)]">-&gt;</span>
        <span className="font-mono tabular-nums font-medium text-[var(--text-primary)]">
          {newNum.toLocaleString()}
        </span>
        <span
          className={`px-2 py-0.5 rounded-md text-[11px] font-medium tabular-nums ${
            diff > 0
              ? 'bg-emerald-500/12 text-emerald-700 dark:text-emerald-400'
              : 'bg-rose-500/12 text-rose-600 dark:text-rose-400'
          }`}
        >
          {diff > 0 ? `+${diff}` : diff}
        </span>
      </div>
    );
  };

  const renderEventDetails = (log: ChangeLog) => {
    switch (log.event_type) {
      case 'privacy_change': {
        const isNowPublic = log.new_value === 'false';
        return (
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span
              className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-lg font-medium ${
                isNowPublic
                  ? 'bg-emerald-500/12 text-emerald-700 dark:text-emerald-400'
                  : 'bg-amber-500/12 text-amber-700 dark:text-amber-400'
              }`}
            >
              {isNowPublic ? <Unlock size={12} strokeWidth={1.75} /> : <Lock size={12} strokeWidth={1.75} />}
              {isNowPublic ? t.event_privacy_opened : t.event_privacy_closed}
            </span>
            <span className="text-[var(--text-secondary)]">
              {isNowPublic ? t.event_privacy_opened_desc : t.event_privacy_closed_desc}
            </span>
          </div>
        );
      }
      case 'username_change':
        return (
          <div className="space-y-1.5 text-xs">
            <div className="font-medium text-[var(--text-primary)]">{t.event_username_changed}</div>
            <div className="flex flex-wrap items-center gap-2 text-[var(--text-secondary)]">
              <span className="line-through bg-[var(--bg-subtle)] px-2 py-0.5 rounded font-mono">
                @{log.old_value}
              </span>
              <span>-&gt;</span>
              <span className="font-medium text-[#0C7FF2] dark:text-[#58A6FF] bg-[var(--accent-soft)] px-2 py-0.5 rounded font-mono">
                @{log.new_value}
              </span>
            </div>
          </div>
        );
      case 'full_name_change':
        return (
          <div className="space-y-1 text-xs">
            <div className="font-medium text-[var(--text-primary)]">{t.event_fullname_changed}</div>
            <div className="flex flex-wrap items-center gap-2 text-[var(--text-secondary)]">
              <span className="line-through">{log.old_value || `(${t.empty_value})`}</span>
              <span>-&gt;</span>
              <span className="font-medium text-[var(--text-primary)]">{log.new_value}</span>
            </div>
          </div>
        );
      case 'bio_change':
        return (
          <div className="space-y-1.5 text-xs">
            <div className="font-medium text-[var(--text-primary)]">{t.event_bio_changed}</div>
            <div className="p-2.5 rounded-xl bg-[var(--bg-subtle)] border border-[var(--border-subtle)] text-[var(--text-secondary)] whitespace-pre-line">
              {log.new_value || `(${t.deleted_value})`}
            </div>
          </div>
        );
      case 'avatar_change': {
        const oldSrc = log.old_value.startsWith('/') ? log.old_value : `/${log.old_value}`;
        const newSrc = log.new_value.startsWith('/') ? log.new_value : `/${log.new_value}`;
        return (
          <div className="space-y-2 text-xs">
            <div className="font-medium text-[var(--text-primary)]">{t.event_avatar_changed}</div>
            <div className="flex items-center gap-3 bg-[var(--bg-subtle)] p-2.5 rounded-xl border border-[var(--border-subtle)] w-fit">
              <div className="w-11 h-11 rounded-full overflow-hidden border border-[var(--border-strong)] opacity-60">
                <img src={oldSrc} alt="" className="w-full h-full object-cover" />
              </div>
              <span className="text-[var(--text-muted)]">-&gt;</span>
              <div className="w-11 h-11 rounded-full overflow-hidden border-2 border-[#0C7FF2]">
                <img src={newSrc} alt="" className="w-full h-full object-cover" />
              </div>
            </div>
          </div>
        );
      }
      case 'post_count_change':
        return renderCountDelta(t.event_posts_changed, log.old_value, log.new_value);
      case 'followers_change':
        return renderCountDelta(t.event_followers_changed, log.old_value, log.new_value);
      case 'following_change':
        return renderCountDelta(t.event_following_changed, log.old_value, log.new_value);
      case 'post_vanished': {
        const [shortcode, relPath] = (log.old_value || '').split('|');
        const cleanPath = relPath
          ? (relPath.startsWith('/') ? relPath : `/${relPath}`).replace(/\.mp4$/i, '_thumb.jpg')
          : '';
        return (
          <div className="rounded-xl bg-rose-500/10 border border-rose-500/20 p-3 flex items-start gap-3">
            {cleanPath ? (
              <img
                src={cleanPath}
                alt={shortcode || ''}
                className="w-12 h-12 rounded-lg object-cover shrink-0 border border-rose-500/25 bg-[var(--bg-subtle)]"
                onError={(e) => {
                  (e.currentTarget as HTMLImageElement).style.display = 'none';
                }}
              />
            ) : (
              <AlertCircle size={16} strokeWidth={1.75} className="text-rose-500 shrink-0 mt-0.5" />
            )}
            <div className="min-w-0 space-y-1 text-xs">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-semibold text-rose-600 dark:text-rose-400">
                  {t.event_post_vanished}
                </span>
                {shortcode && (
                  <span className="font-mono text-[11px] px-1.5 py-0.5 rounded bg-rose-500/15 text-rose-700 dark:text-rose-300">
                    /p/{shortcode}
                  </span>
                )}
              </div>
              {log.new_value && (
                <p className="text-[var(--text-secondary)] leading-relaxed break-words">
                  {log.new_value}
                </p>
              )}
            </div>
          </div>
        );
      }
      case 'follower_gained':
      case 'follower_lost':
      case 'following_gained':
      case 'following_lost': {
        const isGain = log.event_type.endsWith('gained');
        const label =
          log.event_type === 'follower_gained'
            ? t.audit_new_followers
            : log.event_type === 'follower_lost'
            ? t.audit_unfollowers
            : log.event_type === 'following_gained'
            ? t.audit_new_following
            : t.audit_unfollowed;
        const userRef = (log.new_value || log.old_value || '').replace(/^@/, '');
        return (
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span
              className={`px-2 py-0.5 rounded-md font-medium ${
                isGain
                  ? 'bg-emerald-500/12 text-emerald-700 dark:text-emerald-400'
                  : 'bg-rose-500/12 text-rose-600 dark:text-rose-400'
              }`}
            >
              {label}
            </span>
            {userRef && (
              <a
                href={`https://www.instagram.com/${userRef}/`}
                target="_blank"
                rel="noreferrer"
                className="font-mono font-semibold text-[#0C7FF2] dark:text-[#58A6FF] hover:underline"
              >
                @{userRef}
              </a>
            )}
          </div>
        );
      }
      default:
        return (
          <div className="text-xs text-[var(--text-secondary)]">
            <span className="font-medium text-[var(--text-primary)]">{log.event_type}: </span>
            <span>{log.new_value}</span>
          </div>
        );
    }
  };

  const locale = language === 'az' ? 'az-AZ' : language === 'ru' ? 'ru-RU' : 'en-US';

  const formatTimestamp = (ts: string) => {
    try {
      return new Date(ts).toLocaleString(locale, {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return ts;
    }
  };

  return (
    <div className="relative pl-6 space-y-3 before:absolute before:left-2 before:top-3 before:bottom-3 before:w-px before:bg-[var(--border-strong)]">
      {logs.map((log, idx) => (
        <div
          key={log.id}
          className={`relative animate-card-enter stagger-${Math.min((idx % 5) + 1, 5)}`}
        >
          <div className="absolute -left-[1.35rem] top-4 w-3 h-3 rounded-full bg-[var(--bg-surface)] border-2 border-[#0C7FF2]" />
          <div className="bg-[var(--bg-surface)] rounded-2xl p-4 border border-[var(--border-subtle)] space-y-2">
            <div className="text-[11px] font-mono tabular-nums text-[var(--text-muted)]">
              {formatTimestamp(log.timestamp)}
            </div>
            {renderEventDetails(log)}
          </div>
        </div>
      ))}
    </div>
  );
};
