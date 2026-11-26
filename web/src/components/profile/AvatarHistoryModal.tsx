import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { X, Download, Image as ImageIcon, Trash2, Columns2 } from 'lucide-react';
import { useI18n } from '../../i18n';

interface AvatarHistoryModalProps {
  isOpen: boolean;
  onClose: () => void;
  targetId: string;
  username: string;
}

export const AvatarHistoryModal: React.FC<AvatarHistoryModalProps> = ({
  isOpen,
  onClose,
  targetId,
  username,
}) => {
  const { t } = useI18n();
  const [avatars, setAvatars] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedAvatar, setSelectedAvatar] = useState<string | null>(null);
  const [compareMode, setCompareMode] = useState(false);

  const loadAvatars = () => {
    setLoading(true);
    api
      .getAvatars(targetId)
      .then(setAvatars)
      .catch(() => setAvatars([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (!isOpen) return;
    loadAvatars();

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (selectedAvatar) setSelectedAvatar(null);
        else onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isOpen, targetId, selectedAvatar, onClose]);

  const handleDeleteAvatar = async (path: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const filename = path.split('/').pop() || '';
    if (!filename) return;
    await api.deleteTargetAvatar(targetId, filename).catch(() => {});
    loadAvatars();
  };

  if (!isOpen) return null;

  const parseTimestamp = (path: string): string => {
    const filename = path.split('/').pop() || '';
    const match = filename.match(/avatar_(\d{4}-\d{2}-\d{2})_(\d{2}-\d{2}-\d{2})\.jpg/);
    if (match) {
      return `${match[1]} ${match[2].replace(/-/g, ':')}`;
    }
    return t.avatar_current;
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 backdrop-blur-sm p-4 animate-backdrop"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-lg bg-[var(--bg-surface)] rounded-2xl p-6 shadow-2xl border border-[var(--border-strong)] flex flex-col max-h-[85vh] animate-panel"
      >
        <div className="flex items-center justify-between pb-4 border-b border-[var(--border-subtle)]">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF] flex items-center justify-center">
              <ImageIcon size={17} strokeWidth={1.75} />
            </div>
            <div>
              <h3 className="font-semibold text-sm text-[var(--text-primary)]">{t.avatar_history}</h3>
              <p className="text-xs text-[var(--text-muted)]">
                @{username} · {t.avatar_history_desc}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-1.5">
            {avatars.length >= 2 && (
              <button
                type="button"
                onClick={() => setCompareMode((v) => !v)}
                className={`h-8 px-2.5 rounded-lg text-xs font-medium inline-flex items-center gap-1.5 border ${
                  compareMode
                    ? 'bg-[#0C7FF2] text-white border-[#0C7FF2]'
                    : 'bg-[var(--bg-subtle)] text-[var(--text-secondary)] border-[var(--border-subtle)]'
                }`}
              >
                <Columns2 size={14} /> {t.avatar_compare}
              </button>
            )}
            <button
              type="button"
              onClick={onClose}
              className="w-8 h-8 rounded-lg flex items-center justify-center text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-subtle)] transition-colors duration-150"
              aria-label={t.cancel}
            >
              <X size={18} strokeWidth={1.75} />
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto py-4 space-y-4">
          {compareMode && avatars.length >= 2 && (
            <div className="grid grid-cols-2 gap-3 p-3 rounded-xl bg-[var(--bg-subtle)] border border-[var(--border-subtle)]">
              <div className="text-center space-y-1.5">
                <span className="text-[11px] font-medium text-[var(--text-muted)]">{t.avatar_before}</span>
                <img
                  src={avatars[1].startsWith('/') ? avatars[1] : `/${avatars[1]}`}
                  alt={t.avatar_before}
                  className="w-28 h-28 rounded-full object-cover mx-auto border border-[var(--border-strong)]"
                />
                <div className="text-[10px] font-mono text-[var(--text-muted)]">{parseTimestamp(avatars[1])}</div>
              </div>
              <div className="text-center space-y-1.5">
                <span className="text-[11px] font-medium text-[#0C7FF2]">{t.avatar_after}</span>
                <img
                  src={avatars[0].startsWith('/') ? avatars[0] : `/${avatars[0]}`}
                  alt={t.avatar_after}
                  className="w-28 h-28 rounded-full object-cover mx-auto border-2 border-[#0C7FF2]"
                />
                <div className="text-[10px] font-mono text-[var(--text-muted)]">{parseTimestamp(avatars[0])}</div>
              </div>
            </div>
          )}

          {loading ? (
            <p className="text-xs text-center text-[var(--text-muted)] py-8">{t.loading}</p>
          ) : avatars.length === 0 ? (
            <p className="text-xs text-center text-[var(--text-muted)] py-8">{t.avatar_empty}</p>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {avatars.map((path, idx) => {
                const fullUrl = path.startsWith('/') ? path : `/${path}`;
                const label = parseTimestamp(path);

                return (
                  <div
                    key={path}
                    onClick={() => setSelectedAvatar(fullUrl)}
                    className="group bg-[var(--bg-subtle)] rounded-xl border border-[var(--border-subtle)] hover:border-[var(--border-strong)] p-3 flex flex-col items-center gap-2 cursor-pointer transition-colors duration-150"
                  >
                    <div className="w-20 h-20 rounded-full overflow-hidden border border-[var(--border-strong)] bg-[var(--bg-surface)]">
                      <img
                        src={fullUrl}
                        alt={`Avatar ${idx + 1}`}
                        className="w-full h-full object-cover"
                      />
                    </div>
                    <div className="text-center w-full space-y-1">
                      <span className="text-[11px] font-mono tabular-nums text-[var(--text-secondary)] truncate block">
                        {label}
                      </span>
                      <div className="flex items-center justify-center gap-3">
                        <a
                          href={fullUrl}
                          download
                          onClick={(e) => e.stopPropagation()}
                          className="inline-flex items-center gap-1 text-[11px] font-medium text-[#0C7FF2] dark:text-[#58A6FF] hover:underline"
                        >
                          <Download size={11} strokeWidth={1.75} /> {t.download_file}
                        </a>
                        {idx > 0 && (
                          <button
                            type="button"
                            onClick={(e) => handleDeleteAvatar(path, e)}
                            className="inline-flex items-center text-rose-500 hover:text-rose-600"
                            title={t.delete}
                          >
                            <Trash2 size={12} />
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {selectedAvatar && (
          <div
            onClick={() => setSelectedAvatar(null)}
            className="fixed inset-0 z-50 bg-black/75 flex items-center justify-center p-4 animate-backdrop"
          >
            <div
              onClick={(e) => e.stopPropagation()}
              className="relative max-w-sm w-full bg-[var(--bg-surface)] border border-[var(--border-strong)] rounded-2xl p-4 shadow-2xl animate-panel space-y-3"
            >
              <button
                type="button"
                onClick={() => setSelectedAvatar(null)}
                className="absolute top-3 right-3 w-8 h-8 rounded-lg bg-black/55 text-white flex items-center justify-center"
                aria-label={t.cancel}
              >
                <X size={16} strokeWidth={1.75} />
              </button>
              <img
                src={selectedAvatar}
                alt={username}
                className="w-full h-auto rounded-xl object-contain max-h-[60vh] mx-auto"
              />
              <div className="text-center">
                <a
                  href={selectedAvatar}
                  download
                  className="inline-flex items-center gap-1.5 text-xs font-medium text-[#0C7FF2] dark:text-[#58A6FF] bg-[var(--accent-soft)] px-4 py-2 rounded-xl"
                >
                  <Download size={14} strokeWidth={1.75} /> {t.download_file}
                </a>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
