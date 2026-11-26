import React, { useEffect, useState, useMemo, useCallback } from 'react';
import { api } from '../../api/client';
import { PostComment } from '../../types';
import { useI18n } from '../../i18n';
import { Button } from '../common/Button';
import { X, RefreshCw, Search, Heart, Download, MessageSquare, BadgeCheck } from 'lucide-react';

interface PostCommentsModalProps {
  isOpen: boolean;
  targetId: string;
  shortcode: string;
  onClose: () => void;
}

export const PostCommentsModal: React.FC<PostCommentsModalProps> = ({
  isOpen,
  targetId,
  shortcode,
  onClose,
}) => {
  const { t } = useI18n();
  const [comments, setComments] = useState<PostComment[]>([]);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [query, setQuery] = useState('');
  const [sortBy, setSortBy] = useState<'likes' | 'newest'>('likes');
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const loadComments = useCallback(async () => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const list = await api.getPostComments(targetId, shortcode);
      setComments(list);
    } catch (e: any) {
      setErrorMsg(e?.message || t.toast_check_failed);
    } finally {
      setLoading(false);
    }
  }, [targetId, shortcode, t.toast_check_failed]);

  const handleSync = async () => {
    if (syncing) return;
    setSyncing(true);
    setErrorMsg(null);
    try {
      const list = await api.syncPostComments(targetId, shortcode);
      setComments(list);
    } catch (e: any) {
      setErrorMsg(e?.message || t.toast_check_failed);
    } finally {
      setSyncing(false);
    }
  };

  useEffect(() => {
    if (!isOpen) return;
    loadComments();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isOpen, loadComments, onClose]);

  const threads = useMemo(() => {
    const q = query.trim().toLowerCase();
    const filtered = q
      ? comments.filter(
          (c) =>
            c.author_username.toLowerCase().includes(q) ||
            c.text.toLowerCase().includes(q)
        )
      : comments;

    const repliesByParent: Record<string, PostComment[]> = {};
    const roots: PostComment[] = [];
    const byId = new Set(filtered.map((c) => c.id));
    for (const c of filtered) {
      if (c.parent_comment_id && byId.has(c.parent_comment_id)) {
        if (!repliesByParent[c.parent_comment_id]) repliesByParent[c.parent_comment_id] = [];
        repliesByParent[c.parent_comment_id].push(c);
      } else {
        roots.push(c);
      }
    }
    roots.sort((a, b) =>
      sortBy === 'likes'
        ? b.like_count - a.like_count
        : (b.created_at || '').localeCompare(a.created_at || '')
    );
    return roots.map((r) => ({ comment: r, replies: repliesByParent[r.id] || [] }));
  }, [comments, query, sortBy]);

  if (!isOpen) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 animate-backdrop"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-2xl h-[84vh] bg-[var(--bg-surface)] border border-[var(--border-strong)] rounded-2xl shadow-2xl flex flex-col overflow-hidden animate-panel"
      >
        <div className="px-5 py-4 border-b border-[var(--border-subtle)] flex items-center justify-between gap-3">
          <div className="flex items-center gap-2.5 min-w-0">
            <MessageSquare size={17} strokeWidth={1.75} className="text-[#0C7FF2] shrink-0" />
            <div className="min-w-0">
              <h2 className="text-sm font-semibold text-[var(--text-primary)] truncate">
                {t.comments_title} ({comments.length})
              </h2>
              <p className="text-xs font-mono text-[var(--text-muted)] truncate">/p/{shortcode}</p>
            </div>
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
            >
              <X size={18} strokeWidth={1.75} />
            </button>
          </div>
        </div>

        <div className="px-5 pt-3.5 pb-2 space-y-2.5 border-b border-[var(--border-subtle)]">
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative flex-1 min-w-[180px]">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t.comments_search}
                className="w-full h-9 bg-[var(--bg-subtle)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl pl-9 pr-3 text-xs text-[var(--text-primary)] focus:outline-none"
              />
            </div>
            <button
              type="button"
              onClick={() => setSortBy(sortBy === 'likes' ? 'newest' : 'likes')}
              className="h-9 px-3 rounded-xl bg-[var(--bg-subtle)] border border-[var(--border-subtle)] text-xs font-medium text-[var(--text-secondary)]"
            >
              {sortBy === 'likes' ? t.comments_sort_likes : t.comments_sort_newest}
            </button>
          </div>

          {comments.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5">
              {(['json', 'csv', 'txt', 'md'] as const).map((fmt) => (
                <a
                  key={fmt}
                  href={`/api/targets/${encodeURIComponent(targetId)}/posts/${encodeURIComponent(shortcode)}/comments/export?format=${fmt}`}
                  download
                  className="inline-flex items-center gap-1 h-7 px-2.5 rounded-lg bg-[var(--bg-subtle)] border border-[var(--border-subtle)] hover:border-[var(--border-strong)] text-[11px] font-medium text-[var(--text-secondary)]"
                >
                  <Download size={11} /> {fmt.toUpperCase()}
                </a>
              ))}
            </div>
          )}

          {errorMsg && (
            <div className="rounded-xl px-3 py-2 bg-rose-500/10 border border-rose-500/20 text-xs text-rose-600 dark:text-rose-400">
              {errorMsg}
            </div>
          )}
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-2.5">
          {loading || syncing ? (
            <div className="space-y-2">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-16 rounded-xl border border-[var(--border-subtle)] skeleton-box" />
              ))}
            </div>
          ) : threads.length === 0 ? (
            <div className="py-12 text-center space-y-3">
              <p className="text-sm font-medium text-[var(--text-primary)]">{t.comments_empty}</p>
              <Button type="button" size="sm" variant="primary" onClick={handleSync}>
                {t.comments_fetch_now}
              </Button>
            </div>
          ) : (
            threads.map(({ comment, replies }) => (
              <div
                key={comment.id}
                className="rounded-xl p-3 bg-[var(--bg-subtle)]/70 border border-[var(--border-subtle)] space-y-2"
              >
                <div className="flex items-center justify-between gap-2 text-xs">
                  <div className="flex items-center gap-1.5 font-semibold text-[var(--text-primary)]">
                    <span>@{comment.author_username}</span>
                    {comment.is_verified && <BadgeCheck size={13} className="text-[#0C7FF2]" />}
                  </div>
                  <span className="inline-flex items-center gap-1 text-[11px] tabular-nums text-[var(--text-muted)]">
                    <Heart size={11} /> {comment.like_count}
                  </span>
                </div>
                <p className="text-xs text-[var(--text-secondary)] whitespace-pre-wrap leading-relaxed">
                  {comment.text}
                </p>
                {replies.length > 0 && (
                  <div className="pl-3 border-l-2 border-[var(--border-strong)] space-y-1.5 pt-1">
                    {replies.map((r) => (
                      <div key={r.id} className="text-xs space-y-0.5">
                        <span className="font-semibold text-[var(--text-primary)]">@{r.author_username} </span>
                        <span className="text-[var(--text-secondary)]">{r.text}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};
