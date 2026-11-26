import React, { useState } from 'react';
import { api } from '../../api/client';
import { useI18n } from '../../i18n';
import { Button } from '../common/Button';
import { Lock } from 'lucide-react';

interface AppLockOverlayProps {
  onUnlocked: (token: string) => void;
}

export const AppLockOverlay: React.FC<AppLockOverlayProps> = ({ onUnlocked }) => {
  const { t } = useI18n();
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pin.trim() || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const token = await api.unlockPin(pin.trim());
      onUnlocked(token);
    } catch {
      setError(t.pin_incorrect);
      setPin('');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-[var(--bg-canvas)] flex items-center justify-center p-4">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-xs bg-[var(--bg-surface)] border border-[var(--border-strong)] rounded-2xl p-6 shadow-2xl space-y-5 text-center"
      >
        <div className="w-12 h-12 rounded-2xl bg-[var(--accent-soft)] text-[#0C7FF2] flex items-center justify-center mx-auto">
          <Lock size={22} strokeWidth={1.75} />
        </div>
        <div className="space-y-1">
          <h1 className="text-base font-semibold text-[var(--text-primary)]">{t.pin_lock_title}</h1>
          <p className="text-xs text-[var(--text-muted)]">{t.pin_lock_desc}</p>
        </div>
        <input
          type="password"
          inputMode="numeric"
          autoFocus
          maxLength={12}
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
          placeholder="••••"
          className="w-full h-12 text-center tracking-[0.4em] text-lg font-mono bg-[var(--bg-subtle)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl text-[var(--text-primary)] focus:outline-none"
        />
        {error && <p className="text-xs text-rose-500">{error}</p>}
        <Button type="submit" variant="primary" size="md" disabled={submitting || pin.length < 4} className="w-full">
          {t.pin_unlock}
        </Button>
      </form>
    </div>
  );
};
