import React, { useState, useEffect } from 'react';
import { X, UserPlus, Zap } from 'lucide-react';
import { Button } from '../common/Button';
import { useI18n } from '../../i18n';

interface AddTargetModalProps {
  isOpen: boolean;
  onClose: () => void;
  onAdd: (username: string, turbo: boolean) => Promise<void>;
}

export const AddTargetModal: React.FC<AddTargetModalProps> = ({
  isOpen,
  onClose,
  onAdd,
}) => {
  const { t } = useI18n();
  const [username, setUsername] = useState('');
  const [turbo, setTurbo] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !loading) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isOpen, loading, onClose]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim()) return;

    setLoading(true);
    setError(null);

    try {
      await onAdd(username.trim(), turbo);
      setUsername('');
      setTurbo(false);
      onClose();
    } catch (err: any) {
      setError(err?.message || t.toast_check_failed);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      onClick={() => !loading && onClose()}
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50 backdrop-blur-sm p-4 animate-backdrop"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md bg-[var(--bg-surface)] rounded-2xl p-6 shadow-2xl border border-[var(--border-strong)] animate-panel"
      >
        <div className="flex items-center justify-between pb-4 border-b border-[var(--border-subtle)]">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF] flex items-center justify-center">
              <UserPlus size={18} strokeWidth={1.75} />
            </div>
            <h2 className="text-base font-semibold text-[var(--text-primary)]">
              {t.add_target_title}
            </h2>
          </div>
          <button
            type="button"
            onClick={() => !loading && onClose()}
            className="w-8 h-8 rounded-lg flex items-center justify-center text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-subtle)] transition-colors duration-150"
            aria-label={t.cancel}
          >
            <X size={18} strokeWidth={1.75} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="mt-5 flex flex-col gap-4">
          {error && (
            <div className="p-3 rounded-xl bg-rose-500/10 text-rose-600 dark:text-rose-400 text-xs font-medium border border-rose-500/20 animate-toast">
              {error}
            </div>
          )}

          <div className="space-y-1.5">
            <label className="block text-xs font-medium text-[var(--text-secondary)]">
              {t.username_label}
            </label>
            <div className="relative flex items-center">
              <span className="absolute left-3.5 text-[var(--text-muted)] font-medium select-none">@</span>
              <input
                type="text"
                required
                autoFocus
                placeholder={t.username_placeholder}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="w-full h-11 bg-[var(--bg-subtle)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl pl-8 pr-4 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none transition-colors duration-150"
              />
            </div>
          </div>

          <div className="flex items-center justify-between gap-3 p-3.5 bg-[var(--bg-subtle)] rounded-xl border border-[var(--border-subtle)]">
            <div className="flex items-center gap-3 min-w-0">
              <div
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors duration-150 shrink-0 ${
                  turbo
                    ? 'bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF]'
                    : 'bg-[var(--bg-surface)] text-[var(--text-muted)]'
                }`}
              >
                <Zap size={16} strokeWidth={1.75} />
              </div>
              <div className="min-w-0">
                <p className="text-xs font-medium text-[var(--text-primary)]">{t.turbo_mode}</p>
                <p className="text-[11px] text-[var(--text-muted)]">{t.turbo_desc}</p>
              </div>
            </div>

            <button
              type="button"
              role="switch"
              aria-checked={turbo}
              onClick={() => setTurbo(!turbo)}
              className={`w-11 h-6 rounded-full p-0.5 transition-colors duration-150 shrink-0 ${
                turbo ? 'bg-[#0C7FF2]' : 'bg-[var(--border-strong)]'
              }`}
            >
              <div
                className={`w-5 h-5 rounded-full bg-white shadow-xs transition-transform duration-200 ease-[cubic-bezier(0.22,1,0.36,1)] ${
                  turbo ? 'translate-x-5' : 'translate-x-0'
                }`}
              />
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2.5 pt-2">
            <Button
              type="button"
              variant="secondary"
              size="md"
              onClick={onClose}
              disabled={loading}
            >
              {t.cancel}
            </Button>
            <Button
              type="submit"
              variant="primary"
              size="md"
              disabled={loading || !username.trim()}
            >
              {loading ? t.checking : t.start_tracking}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};
