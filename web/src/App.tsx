import React, { useState, useEffect } from 'react';
import { Target } from './types';
import { api } from './api/client';
import { HeaderBanner } from './components/common/HeaderBanner';
import { TargetCard } from './components/targets/TargetCard';
import { AddTargetModal } from './components/targets/AddTargetModal';
import { ProfileDetail } from './components/profile/ProfileDetail';
import { SettingsView } from './components/settings/SettingsView';
import { UserPlus, AlertCircle, RefreshCw } from 'lucide-react';
import { Button } from './components/common/Button';
import { useI18n } from './i18n';

export const App: React.FC = () => {
  const { t } = useI18n();
  const [targets, setTargets] = useState<Target[]>([]);
  const [selectedTarget, setSelectedTarget] = useState<Target | null>(null);
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [liveAlert, setLiveAlert] = useState<string | null>(null);

  const fetchTargets = async () => {
    try {
      const list = await api.getTargets();
      setTargets(list);
    } catch (e) {
      console.error('Failed to fetch targets:', e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTargets();

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/api/ws`;
    const ws = new WebSocket(wsUrl);

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'change_detected') {
          setLiveAlert(`@${msg.username}: ${msg.event} (${msg.old} → ${msg.new})`);
          setTimeout(() => setLiveAlert(null), 5000);
          fetchTargets();
        }
      } catch (e) {
        console.error('WebSocket parse error:', e);
      }
    };

    return () => {
      ws.close();
    };
  }, []);

  const handleToggleTurbo = async (target: Target, turbo: boolean) => {
    await api.toggleTurbo(target.id, turbo);
    await fetchTargets();
  };

  const handleCheckNow = async (target: Target) => {
    await api.checkNow(target.id);
    await fetchTargets();
  };

  if (isSettingsOpen) {
    return <SettingsView onBack={() => setIsSettingsOpen(false)} />;
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
          api.getTarget(selectedTarget.id).then(setSelectedTarget);
        }}
      />
    );
  }

  return (
    <div className="min-h-screen bg-[#F2F4F7] pb-24 transition-colors duration-300">
      {/* Live Toast Alert Banner */}
      {liveAlert && (
        <div className="fixed top-4 left-4 right-4 z-50 max-w-md mx-auto bg-gray-900/95 backdrop-blur-md text-white text-xs font-semibold px-4 py-3 rounded-2xl shadow-xl flex items-center gap-2.5 border border-gray-800 animate-toast">
          <AlertCircle size={16} className="text-[#0C7FF2] shrink-0 animate-pulse-subtle" />
          <span className="flex-1 truncate">{liveAlert}</span>
        </div>
      )}

      {/* Main Header */}
      <HeaderBanner
        activeCount={targets.length}
        onSettingsClick={() => setIsSettingsOpen(true)}
      />

      {/* Main Content Area */}
      <main className="max-w-2xl mx-auto px-4 space-y-4">
        <div className="flex items-center justify-between px-2">
          <h2 className="text-sm font-bold text-gray-500 uppercase tracking-wider">
            {t.targets_badge} ({targets.length})
          </h2>
          <Button
            size="sm"
            variant="primary"
            icon={<UserPlus size={16} />}
            onClick={() => setIsAddModalOpen(true)}
          >
            {t.add_target}
          </Button>
        </div>

        {loading ? (
          <div className="py-20 flex flex-col items-center justify-center gap-3 text-gray-400">
            <RefreshCw size={28} className="animate-spin text-[#0C7FF2]" />
            <p className="text-xs font-semibold">Loading...</p>
          </div>
        ) : targets.length === 0 ? (
          <div className="bg-white rounded-3xl p-8 text-center border border-[#E5E8EC] shadow-sm space-y-4 animate-scale-in">
            <div className="w-16 h-16 rounded-full bg-blue-50 text-[#0C7FF2] flex items-center justify-center mx-auto shadow-xs">
              <UserPlus size={28} />
            </div>
            <div>
              <h3 className="text-lg font-bold text-gray-900">{t.no_targets_title}</h3>
              <p className="text-xs text-gray-500 mt-1 max-w-sm mx-auto">
                {t.no_targets_desc}
              </p>
            </div>
            <Button
              variant="primary"
              size="md"
              icon={<UserPlus size={16} />}
              onClick={() => setIsAddModalOpen(true)}
            >
              {t.add_target}
            </Button>
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {targets.map((target, idx) => (
              <TargetCard
                key={target.id}
                target={target}
                index={idx}
                onSelect={(t) => setSelectedTarget(t)}
                onToggleTurbo={handleToggleTurbo}
                onCheckNow={handleCheckNow}
              />
            ))}
          </div>
        )}
      </main>

      {/* Floating Add Action Button on Mobile */}
      <div className="fixed bottom-6 right-6 sm:hidden z-40">
        <button
          onClick={() => setIsAddModalOpen(true)}
          className="w-14 h-14 rounded-full bg-[#0C7FF2] text-white shadow-xl hover:bg-[#006CE6] active:scale-95 flex items-center justify-center transition-all duration-200"
          title={t.add_target}
        >
          <UserPlus size={24} />
        </button>
      </div>

      {/* Add Target Modal */}
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
