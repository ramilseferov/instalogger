import React, { useState, useEffect, useMemo } from 'react';
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
  const [darkMode, setDarkMode] = useState<boolean>(() => {
    try {
      const saved = localStorage.getItem('instalogger_theme');
      if (saved === 'dark') return true;
      if (saved === 'light') return false;
      return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
    } catch {
      return false;
    }
  });

  useEffect(() => {
    const root = document.documentElement;
    if (darkMode) {
      root.classList.add('dark');
      root.style.colorScheme = 'dark';
      try { localStorage.setItem('instalogger_theme', 'dark'); } catch {}
    } else {
      root.classList.remove('dark');
      root.style.colorScheme = 'light';
      try { localStorage.setItem('instalogger_theme', 'light'); } catch {}
    }
  }, [darkMode]);

  const showToast = (kind: 'info' | 'err', text: string) => {
    setToast({ kind, text });
    window.setTimeout(() => setToast(null), 4500);
  };

  const fetchTargets = async () => {
    try {
      const list = await api.getTargets();
      setTargets(list);
    } catch (e: any) {
      showToast('err', `Failed to load targets: ${e?.message || 'Daemon unreachable'}`);
    } finally {
      setLoading(false);
    }
  };

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
              showToast('info', `@${msg.username}: ${msg.event} (${msg.old} -> ${msg.new})`);
              fetchTargets();
            }
          } catch {
          }
        };
      }
    } catch {
      setWsConnected(false);
    }

    return () => {
      try { ws?.close(); } catch {}
    };
  }, []);

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
      showToast('err', e?.message || 'Failed to toggle turbo mode');
    }
  };

  const handleCheckNow = async (target: Target) => {
    setCheckingId(target.id);
    try {
      await api.checkNow(target.id);
      await fetchTargets();
      showToast('info', `@${target.username} updated`);
    } catch (e: any) {
      showToast('err', `@${target.username} check failed: ${e?.message || 'Error'}`);
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
      showToast('info', `All targets checked (${ok}/${targets.length})`);
    } else {
      showToast('err', `Refresh finished: ${ok} updated, ${fail} failed (see Developer Logs)`);
    }
  };

  if (activeView === 'dev_logs') {
    return <DeveloperLogsView onBack={() => setActiveView('dashboard')} />;
  }

  if (activeView === 'settings') {
    return (
      <SettingsView
        onBack={() => setActiveView('dashboard')}
        onOpenDevLogs={() => setActiveView('dev_logs')}
      />
    );
  }

  if (selectedTarget) {
    return (
      <ProfileDetail
        target={selectedTarget}
        onBack={() => {
          setSelectedTarget(null);
          fetchTargets();
        }}
        onTargetUpdated={() => {
          fetchTargets();
          api.getTarget(selectedTarget.id).then(setSelectedTarget).catch(() => {});
        }}
      />
    );
  }

  return (
    <div className="min-h-screen bg-[#F2F4F7] dark:bg-slate-950 pb-24 transition-colors">
      {toast && (
        <div
          className={`fixed top-4 left-4 right-4 z-50 max-w-md mx-auto backdrop-blur-md text-xs font-semibold px-4 py-3 rounded-xl shadow-lg flex items-center gap-2.5 border ${
            toast.kind === 'err'
              ? 'bg-rose-950/95 text-rose-100 border-rose-700'
              : 'bg-slate-900/95 text-white border-slate-700'
          }`}
        >
          {toast.kind === 'err' ? (
            <AlertCircle size={16} className="text-rose-400 shrink-0" />
          ) : (
            <CheckCircle2 size={16} className="text-emerald-400 shrink-0" />
          )}
          <span className="flex-1 truncate">{toast.text}</span>
        </div>
      )}

      <HeaderBanner
        activeCount={targets.length}
        wsConnected={wsConnected}
        darkMode={darkMode}
        onToggleDarkMode={() => setDarkMode((d) => !d)}
        onDevLogsClick={() => setActiveView('dev_logs')}
        onSettingsClick={() => setActiveView('settings')}
      />

      <main className="max-w-3xl mx-auto px-4 space-y-4">
        {/* Toolbar: Search + Refresh All + Add Target */}
        <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2.5">
          <div className="relative flex-1">
            <Search size={15} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={`Search ${targets.length} ${t.targets_badge.toLowerCase()}...`}
              className="w-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl pl-10 pr-3.5 py-2 text-xs text-slate-900 dark:text-white placeholder:text-slate-400 focus:outline-none focus:border-[#0C7FF2]"
            />
          </div>

          <div className="flex items-center gap-2">
            <Button
              size="sm"
              variant="secondary"
              icon={<RefreshCw size={14} className={refreshingAll ? 'animate-spin' : ''} />}
              onClick={handleRefreshAll}
              disabled={refreshingAll || targets.length === 0}
            >
              {refreshingAll ? 'Checking...' : t.check_now}
            </Button>

            <Button
              size="sm"
              variant="primary"
              icon={<UserPlus size={15} />}
              onClick={() => setIsAddModalOpen(true)}
            >
              {t.add_target}
            </Button>
          </div>
        </div>

        {loading ? (
          <div className="py-20 flex flex-col items-center justify-center gap-3 text-slate-400">
            <RefreshCw size={26} className="animate-spin text-[#0C7FF2]" />
            <p className="text-xs font-semibold">Loading targets...</p>
          </div>
        ) : filteredTargets.length === 0 ? (
          <div className="bg-white dark:bg-slate-900 rounded-2xl p-8 text-center border border-slate-200 dark:border-slate-800 space-y-4">
            <div className="w-14 h-14 rounded-full bg-blue-500/10 text-[#0C7FF2] flex items-center justify-center mx-auto">
              <UserPlus size={24} />
            </div>
            <div>
              <h3 className="text-base font-bold text-slate-900 dark:text-white">{t.no_targets_title}</h3>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 max-w-sm mx-auto">
                {t.no_targets_desc}
              </p>
            </div>
            <Button
              variant="primary"
              size="md"
              icon={<UserPlus size={15} />}
              onClick={() => setIsAddModalOpen(true)}
            >
              {t.add_target}
            </Button>
          </div>
        ) : (
          <div className="flex flex-col gap-3">
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
          await api.addTarget(username, turbo);
          await fetchTargets();
        }}
      />
    </div>
  );
};

export default App;
