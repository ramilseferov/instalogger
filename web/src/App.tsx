import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Target } from './types';
import { api } from './api/client';
import { HeaderBanner } from './components/common/HeaderBanner';
import { TargetCard } from './components/targets/TargetCard';
import { AddTargetModal } from './components/targets/AddTargetModal';
import { ProfileDetail } from './components/profile/ProfileDetail';
import { SettingsView } from './components/settings/SettingsView';
import { DeveloperLogsView } from './components/logs/DeveloperLogsView';
import { UserPlus, AlertCircle, RefreshCw, Search, CheckCircle2 } from 'lucide-react';
import { Button } from './components/common/Button';
import { useI18n } from './i18n';

export const App: React.FC = () => {
  const { t } = useI18n();
  const [targets, setTargets] = useState<Target[]>([]);
  const [selectedTarget, setSelectedTarget] = useState<Target | null>(null);
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [activeView, setActiveView] = useState<'dashboard' | 'settings' | 'dev_logs'>('dashboard');
  const [loading, setLoading] = useState(true);
  const [refreshingAll, setRefreshingAll] = useState(false);
  const [checkingId, setCheckingId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [wsConnected, setWsConnected] = useState(false);
  const [toast, setToast] = useState<{ kind: 'info' | 'err'; text: string } | null>(null);

  const showToast = useCallback((kind: 'info' | 'err', text: string) => {
    setToast({ kind, text });
    window.setTimeout(() => {
      setToast((prev) => (prev?.text === text ? null : prev));
    }, 4500);
  }, []);

  const fetchTargets = useCallback(async () => {
    try {
      const list = await api.getTargets();
      setTargets(list);
    } catch (e: any) {
      showToast('err', e?.message || t.toast_check_failed);
    } finally {
      setLoading(false);
    }
  }, [showToast, t.toast_check_failed]);

  useEffect(() => {
    fetchTargets();

    let ws: WebSocket | null = null;
    try {
      if (window.location.protocol === 'http:' || window.location.protocol === 'https:') {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/api/ws`;
        ws = new WebSocket(wsUrl);
        ws.onopen = () => setWsConnected(true);
        ws.onclose = () => setWsConnected(false);
        ws.onerror = () => setWsConnected(false);
        ws.onmessage = (event) => {
          try {
            const msg = JSON.parse(event.data);
            if (msg.type === 'change_detected') {
              showToast('info', `@${msg.username}: ${msg.old} -> ${msg.new}`);
              fetchTargets();
            }
          } catch {}
        };
      }
    } catch {
      setWsConnected(false);
    }

    return () => {
      try {
        ws?.close();
      } catch {}
    };
  }, [fetchTargets, showToast]);

  const filteredTargets = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return targets;
    return targets.filter(
      (item) =>
        (item.username || '').toLowerCase().includes(q) ||
        (item.full_name || '').toLowerCase().includes(q)
    );
  }, [targets, searchQuery]);

  const handleToggleTurbo = async (target: Target, turbo: boolean) => {
    try {
      await api.toggleTurbo(target.id, turbo);
      await fetchTargets();
    } catch (e: any) {
      showToast('err', e?.message || t.toast_check_failed);
    }
  };

  const handleCheckNow = async (target: Target) => {
    setCheckingId(target.id);
    try {
      await api.checkNow(target.id);
      await fetchTargets();
      showToast('info', `@${target.username} ${t.toast_target_updated}`);
    } catch (e: any) {
      showToast('err', `@${target.username}: ${e?.message || t.toast_check_failed}`);
    } finally {
      setCheckingId(null);
    }
  };

  const handleRefreshAll = async () => {
    if (refreshingAll || targets.length === 0) return;
    setRefreshingAll(true);
    let ok = 0;
    let fail = 0;
    for (const item of targets) {
      try {
        await api.checkNow(item.id);
        ok++;
      } catch {
        fail++;
      }
    }
    await fetchTargets();
    setRefreshingAll(false);
    if (fail === 0) {
      showToast('info', `${t.toast_refresh_done} (${ok}/${targets.length})`);
    } else {
      showToast('err', `${t.toast_refresh_partial}: ${ok}/${targets.length}`);
    }
  };

  if (activeView === 'dev_logs') {
    return (
      <div key="view-dev-logs" className="animate-view-enter">
        <DeveloperLogsView onBack={() => setActiveView('dashboard')} />
      </div>
    );
  }

  if (activeView === 'settings') {
    return (
      <div key="view-settings" className="animate-view-enter">
        <SettingsView
          onBack={() => setActiveView('dashboard')}
          onOpenDevLogs={() => setActiveView('dev_logs')}
        />
      </div>
    );
  }

  if (selectedTarget) {
    return (
      <div key={`view-profile-${selectedTarget.id}`} className="animate-view-enter">
        <ProfileDetail
          target={selectedTarget}
          onBack={() => {
            setSelectedTarget(null);
            fetchTargets();
          }}
          onOpenSettings={() => {
            setSelectedTarget(null);
            setActiveView('settings');
          }}
          onTargetUpdated={() => {
            fetchTargets();
            api
              .getTarget(selectedTarget.id)
              .then(setSelectedTarget)
              .catch(() => {});
          }}
        />
      </div>
    );
  }

  return (
    <div key="view-dashboard" className="min-h-screen bg-[var(--bg-canvas)] pb-20 animate-view-enter">
      {toast && (
        <div
          role="status"
          className={`fixed top-4 left-4 right-4 z-50 max-w-md mx-auto px-4 py-3 rounded-xl border shadow-lg backdrop-blur-md text-xs font-medium flex items-center gap-2.5 animate-toast ${
            toast.kind === 'err'
              ? 'bg-[var(--bg-surface)] text-rose-600 dark:text-rose-400 border-rose-500/30'
              : 'bg-[var(--bg-surface)] text-[var(--text-primary)] border-[var(--border-strong)]'
          }`}
        >
          {toast.kind === 'err' ? (
            <AlertCircle size={16} strokeWidth={1.75} className="text-rose-500 shrink-0" />
          ) : (
            <CheckCircle2 size={16} strokeWidth={1.75} className="text-emerald-500 shrink-0" />
          )}
          <span className="flex-1 truncate">{toast.text}</span>
        </div>
      )}

      <HeaderBanner
        activeCount={targets.length}
        wsConnected={wsConnected}
        onDevLogsClick={() => setActiveView('dev_logs')}
        onSettingsClick={() => setActiveView('settings')}
      />

      <main className="max-w-4xl mx-auto px-4 sm:px-6 pt-5 space-y-5">
        {/* Toolbar */}
        <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
          <div className="relative flex-1">
            <Search
              size={15}
              strokeWidth={1.75}
              className="absolute left-3.5 top-1/2 -translate-y-1/2 text-[var(--text-muted)] pointer-events-none"
            />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t.search_targets}
              className="w-full h-10 bg-[var(--bg-surface)] border border-[var(--border-subtle)] focus:border-[#0C7FF2] rounded-xl pl-10 pr-3.5 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:outline-none transition-colors duration-150"
            />
          </div>

          <div className="grid grid-cols-2 sm:flex items-center gap-2">
            <Button
              size="md"
              variant="secondary"
              icon={<RefreshCw size={15} strokeWidth={1.75} className={refreshingAll ? 'animate-spin' : ''} />}
              onClick={handleRefreshAll}
              disabled={refreshingAll || targets.length === 0}
            >
              {refreshingAll ? t.checking : t.check_now}
            </Button>

            <Button
              size="md"
              variant="primary"
              icon={<UserPlus size={15} strokeWidth={1.75} />}
              onClick={() => setIsAddModalOpen(true)}
            >
              {t.add_target}
            </Button>
          </div>
        </div>

        {loading ? (
          <div className="py-20 flex flex-col items-center justify-center gap-3 text-[var(--text-muted)]">
            <RefreshCw size={22} strokeWidth={1.75} className="animate-spin text-[#0C7FF2]" />
            <p className="text-xs font-medium">{t.loading_targets}</p>
          </div>
        ) : filteredTargets.length === 0 ? (
          <div className="bg-[var(--bg-surface)] rounded-2xl p-10 text-center border border-[var(--border-subtle)] space-y-4">
            <div className="w-12 h-12 rounded-xl bg-[var(--accent-soft)] text-[#0C7FF2] dark:text-[#58A6FF] flex items-center justify-center mx-auto">
              <UserPlus size={22} strokeWidth={1.75} />
            </div>
            <div className="space-y-1">
              <h2 className="text-base font-semibold text-[var(--text-primary)]">{t.no_targets_title}</h2>
              <p className="text-xs text-[var(--text-secondary)] max-w-sm mx-auto leading-relaxed">
                {t.no_targets_desc}
              </p>
            </div>
            <Button
              variant="primary"
              size="md"
              icon={<UserPlus size={15} strokeWidth={1.75} />}
              onClick={() => setIsAddModalOpen(true)}
            >
              {t.add_target}
            </Button>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-3">
            {filteredTargets.map((target, idx) => (
              <TargetCard
                key={target.id}
                target={target}
                index={idx}
                checking={checkingId === target.id}
                onSelect={(item) => setSelectedTarget(item)}
                onToggleTurbo={handleToggleTurbo}
                onCheckNow={handleCheckNow}
              />
            ))}
          </div>
        )}
      </main>

      <AddTargetModal
        isOpen={isAddModalOpen}
        onClose={() => setIsAddModalOpen(false)}
        onAdd={async (username, turbo) => {
          const res = await api.addTarget(username, turbo);
          await fetchTargets();
          if (res.warning) {
            showToast('err', res.warning);
          } else {
            showToast('info', `@${res.target.username} ${t.toast_target_updated}`);
          }
        }}
      />
    </div>
  );
};

export default App;
