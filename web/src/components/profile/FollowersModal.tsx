import React, { useEffect, useState, useMemo, useCallback } from 'react';
import { api } from '../../api/client';
import { FollowerAudit, RelationshipUser } from '../../types';
import { useI18n } from '../../i18n';
import { SegmentedControl } from '../common/SegmentedControl';
import { Button } from '../common/Button';
import {
  X,
  RefreshCw,
  Search,
  Users,
  UserPlus,
  History,
  Copy,
  ExternalLink,
  Lock,
  User,
  Check,
} from 'lucide-react';

interface FollowersModalProps {
  isOpen: boolean;
  targetId: string;
  username: string;
  initialTab?: 'followers' | 'following' | 'changes';
  hasSession?: boolean;
  onOpenSettings?: () => void;
  onClose: () => void;
}

type AuditFilter = 'follower_lost' | 'following_lost' | 'follower_gained' | 'following_gained';

export const FollowersModal: React.FC<FollowersModalProps> = ({
  isOpen,
  targetId,
  username,
  initialTab = 'followers',
  hasSession = true,
  onOpenSettings,
  onClose,
}) => {
  const { t } = useI18n();
  const [tab, setTab] = useState<'followers' | 'following' | 'changes'>(initialTab);
  const [auditFilter, setAuditFilter] = useState<AuditFilter>('follower_lost');
  const [followers, setFollowers] = useState<RelationshipUser[]>([]);
  const [following, setFollowing] = useState<RelationshipUser[]>([]);
  const [audits, setAudits] = useState<FollowerAudit[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [copiedUser, setCopiedUser] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    setTab(initialTab);
  }, [initialTab, isOpen]);

  const loadStored = useCallback(async () => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const res = await api.getRelationships(targetId);
      setFollowers(res.followers || []);
      setFollowing(res.following || []);
      setAudits(res.audits || []);
    } catch (e: any) {
      setErrorMsg(e?.message || t.toast_check_failed);
    } finally {
      setLoading(false);
    }
  }, [targetId, t.toast_check_failed]);

  const handleSync = useCallback(async () => {
    if (syncing) return;
    setSyncing(true);
    setErrorMsg(null);
    try {
      const res = await api.syncRelationships(targetId);
      setFollowers(res.followers || []);
      setFollowing(res.following || []);
      setAudits(res.audits || []);
    } catch (e: any) {
      setErrorMsg(e?.message || t.toast_check_failed);
    } finally {
      setSyncing(false);
    }
  }, [syncing, targetId, t.toast_check_failed]);

  useEffect(() => {
    if (!isOpen) return;
    loadStored();
  }, [isOpen, loadStored]);

  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isOpen, onClose]);

  const activeUsers = tab === 'followers' ? followers : following;

  const filteredUsers = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return activeUsers;
    return activeUsers.filter(
      (u) =>
        u.username.toLowerCase().includes(q) ||
        (u.full_name || '').toLowerCase().includes(q)
    );
  }, [activeUsers, query]);

  const filteredAudits = useMemo(
    () => audits.filter((a) => a.event_type === auditFilter),
    [audits, auditFilter]
  );

  const copyUsername = (uname: string) => {
    navigator.clipboard?.writeText(uname).catch(() => {});
    setCopiedUser(uname);
    window.setTimeout(() => setCopiedUser((cur) => (cur === uname ? null : cur)), 1500);
  };

  const exportList = (fmt: 'csv' | 'json') => {
    const list = tab === 'followers' ? followers : following;
    if (list.length === 0) return;
    const content =
      fmt === 'json'
        ? JSON.stringify(list, null, 2)
        : ['username,full_name,is_private', ...list.map((u) => `"${u.username}","${(u.full_name || '').replace(/"/g, '""')}",${u.is_private}`)].join('\n');
    const blob = new Blob([content], { type: fmt === 'json' ? 'application/json' : 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${username}_${tab}.${fmt}`;
    a.click();
    URL.revokeObjectURL(url);
  };

  if (!isOpen) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4 animate-backdrop"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-2xl h-[82vh] bg-[var(--bg-surface)] border border-[var(--border-strong)] rounded-2xl shadow-2xl flex flex-col overflow-hidden animate-panel"
      >
        <div className="px-5 py-4 border-b border-[var(--border-subtle)] flex items-center justify-between gap-3">
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-[var(--text-primary)] truncate">
              {t.relationships_title}
            </h2>
            <p className="text-xs text-[var(--text-muted)] truncate">@{username}</p>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <Button
              type="button"
              size="sm"
              variant="secondary"
              icon={<RefreshCw size={13} strokeWidth={1.75} className={syncing ? 'animate-spin' : ''} />}
              onClick={handleSync}
              disabled={syncing}
            >
              {syncing ? t.checking : t.check_now}
            </Button>
            <button
              type="button"
              onClick={onClose}
              className="w-8 h-8 rounded-lg flex items-center justify-center text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-subtle)]"
              aria-label={t.cancel}
            >
              <X size={18} strokeWidth={1.75} />
            </button>
          </div>
        </div>

        <div className="px-5 pt-4 space-y-3">
          <SegmentedControl<'followers' | 'following' | 'changes'>
            value={tab}
            onChange={setTab}
            options={[
              { value: 'followers', label: t.followers, count: followers.length, icon: <Users size={13} strokeWidth={1.75} /> },
              { value: 'following', label: t.following, count: following.length, icon: <UserPlus size={13} strokeWidth={1.75} /> },
              { value: 'changes', label: t.relationships_changes, count: audits.length, icon: <History size={13} strokeWidth={1.75} /> },
            ]}
          />

          {!hasSession && (
            <div className="rounded-xl p-3 bg-amber-500/10 border border-amber-500/25 flex items-center justify-between gap-3 text-xs text-amber-800 dark:text-amber-300">
              <span>{t.relationships_session_required}</span>
              {onOpenSettings && (
                <Button type="button" size="sm" variant="secondary" onClick={onOpenSettings}>
                  {t.open_session_settings}
                </Button>
              )}
            </div>
          )}

          {errorMsg && (
            <div className="rounded-xl px-3.5 py-2.5 bg-rose-500/10 border border-rose-500/20 text-xs text-rose-600 dark:text-rose-400">
              {errorMsg}
            </div>
          )}

          {tab !== 'changes' ? (
            <div className="flex items-center gap-2">
              <div className="relative flex-1">
                <Search
                  size={14}
                  strokeWidth={1.75}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-muted)] pointer-events-none"
                />
                <input
                  type="text"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder={t.relationships_search}
                  className="w-full h-9 bg-[var(--bg-subtle)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl pl-9 pr-3 text-xs text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none"
                />
              </div>
              {activeUsers.length > 0 && (
                <>
                  <button
                    type="button"
                    onClick={() => exportList('csv')}
                    className="h-9 px-2.5 rounded-xl bg-[var(--bg-subtle)] border border-[var(--border-subtle)] text-[11px] font-medium text-[var(--text-secondary)] hover:border-[var(--border-strong)]"
                  >
                    {t.export_csv}
                  </button>
                  <button
                    type="button"
                    onClick={() => exportList('json')}
                    className="h-9 px-2.5 rounded-xl bg-[var(--bg-subtle)] border border-[var(--border-subtle)] text-[11px] font-medium text-[var(--text-secondary)] hover:border-[var(--border-strong)]"
                  >
                    {t.export_json}
                  </button>
                </>
              )}
            </div>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {(
                [
                  ['follower_lost', t.audit_unfollowers],
                  ['following_lost', t.audit_unfollowed],
                  ['follower_gained', t.audit_new_followers],
                  ['following_gained', t.audit_new_following],
                ] as [AuditFilter, string][]
              ).map(([ev, label]) => {
                const count = audits.filter((a) => a.event_type === ev).length;
                const active = auditFilter === ev;
                return (
                  <button
                    key={ev}
                    type="button"
                    onClick={() => setAuditFilter(ev)}
                    className={`h-8 px-2.5 rounded-lg text-xs font-medium border transition-colors duration-150 ${
                      active
                        ? 'bg-[#0C7FF2] text-white border-[#0C7FF2]'
                        : 'bg-[var(--bg-subtle)] text-[var(--text-secondary)] border-[var(--border-subtle)] hover:border-[var(--border-strong)]'
                    }`}
                  >
                    {label} <span className="tabular-nums opacity-80">({count})</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-2">
          {loading || syncing ? (
            <div className="space-y-2">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="h-14 rounded-xl border border-[var(--border-subtle)] skeleton-box" />
              ))}
            </div>
          ) : tab !== 'changes' ? (
            filteredUsers.length === 0 ? (
              <div className="py-12 text-center space-y-3">
                <p className="text-sm font-medium text-[var(--text-primary)]">{t.relationships_empty}</p>
                <Button
                  type="button"
                  size="sm"
                  variant="primary"
                  icon={<RefreshCw size={13} strokeWidth={1.75} />}
                  onClick={handleSync}
                >
                  {t.relationships_sync_now}
                </Button>
              </div>
            ) : (
              filteredUsers.map((u) => (
                <div
                  key={u.username}
                  className="flex items-center justify-between gap-3 p-2.5 rounded-xl bg-[var(--bg-subtle)]/70 border border-[var(--border-subtle)]"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="w-10 h-10 rounded-full bg-[var(--bg-surface)] border border-[var(--border-subtle)] overflow-hidden flex items-center justify-center shrink-0">
                      {u.profile_pic_url ? (
                        <img
                          src={u.profile_pic_url}
                          alt={u.username}
                          loading="lazy"
                          className="w-full h-full object-cover"
                        />
                      ) : (
                        <User size={16} className="text-[var(--text-muted)]" />
                      )}
                    </div>
                    <div className="min-w-0">
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs font-semibold text-[var(--text-primary)] truncate">
                          @{u.username}
                        </span>
                        {u.is_private && <Lock size={11} className="text-[var(--text-muted)] shrink-0" />}
                      </div>
                      {u.full_name && (
                        <p className="text-[11px] text-[var(--text-muted)] truncate">{u.full_name}</p>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-1 shrink-0">
                    <button
                      type="button"
                      onClick={() => copyUsername(u.username)}
                      className="w-8 h-8 rounded-lg hover:bg-[var(--bg-surface)] text-[var(--text-secondary)] flex items-center justify-center"
                      title={u.username}
                    >
                      {copiedUser === u.username ? (
                        <Check size={14} className="text-emerald-500" />
                      ) : (
                        <Copy size={14} />
                      )}
                    </button>
                    <a
                      href={`https://www.instagram.com/${u.username}/`}
                      target="_blank"
                      rel="noreferrer"
                      className="w-8 h-8 rounded-lg hover:bg-[var(--bg-surface)] text-[#0C7FF2] dark:text-[#58A6FF] flex items-center justify-center"
                    >
                      <ExternalLink size={14} />
                    </a>
                  </div>
                </div>
              ))
            )
          ) : filteredAudits.length === 0 ? (
            <div className="py-12 text-center text-xs text-[var(--text-muted)]">
              {t.timeline_empty_title}
            </div>
          ) : (
            filteredAudits.map((a) => (
              <div
                key={a.id}
                className="flex items-center justify-between gap-3 p-2.5 rounded-xl bg-[var(--bg-subtle)]/70 border border-[var(--border-subtle)]"
              >
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-[var(--text-primary)] truncate">
                    @{a.username}
                  </div>
                  <div className="text-[11px] text-[var(--text-muted)] truncate">
                    {a.full_name || new Date(a.timestamp).toLocaleString()}
                  </div>
                </div>
                <a
                  href={`https://www.instagram.com/${a.username}/`}
                  target="_blank"
                  rel="noreferrer"
                  className="w-8 h-8 rounded-lg hover:bg-[var(--bg-surface)] text-[#0C7FF2] dark:text-[#58A6FF] flex items-center justify-center shrink-0"
                >
                  <ExternalLink size={14} />
                </a>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};
