import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { X, Download, Image as ImageIcon } from 'lucide-react';
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

  useEffect(() => {
    if (!isOpen) return;
    setLoading(true);
    api
      .getAvatars(targetId)
      .then(setAvatars)
      .catch(() => setAvatars([]))
      .finally(() => setLoading(false));

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (selectedAvatar) setSelectedAvatar(null);
        else onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isOpen, targetId, selectedAvatar, onClose]);

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
          <button
            type="button"
            onClick={onClose}
            className="w-8 h-8 rounded-lg flex items-center justify-center text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-subtle)] transition-colors duration-150"
            aria-label={t.cancel}
          >
            <X size={18} strokeWidth={1.75} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto py-4">
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
                      <a
                        href={fullUrl}
                        download
                        onClick={(e) => e.stopPropagation()}
                        className="inline-flex items-center gap-1 text-[11px] font-medium text-[#0C7FF2] dark:text-[#58A6FF] hover:underline"
                      >
                        <Download size={11} strokeWidth={1.75} /> {t.download_file}
                      </a>
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
