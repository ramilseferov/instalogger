import React, { useState, useEffect } from 'react';
import { Target, ChangeLog, ArchivedMedia } from '../../types';
import { api } from '../../api/client';
import { Card } from '../common/Card';
import { StatusBadge } from '../common/StatusBadge';
import { Button } from '../common/Button';
import { TimelineView } from './TimelineView';
import { MediaGallery } from './MediaGallery';
import { AvatarHistoryModal } from './AvatarHistoryModal';
import { HighlightCirclesRow } from './HighlightCirclesRow';
import { InstagramStoryViewerModal } from './InstagramStoryViewerModal';
import {
  ArrowLeft,
  RefreshCw,
  Zap,
  Trash2,
  Layers,
  History,
  User,
} from 'lucide-react';

interface ProfileDetailProps {
  target: Target;
  onBack: () => void;
  onTargetUpdated: () => void;
}

export const ProfileDetail: React.FC<ProfileDetailProps> = ({
  target,
  onBack,
  onTargetUpdated,
}) => {
  const [activeTab, setActiveTab] = useState<'gallery' | 'timeline'>('gallery');
  const [logs, setLogs] = useState<ChangeLog[]>([]);
  const [media, setMedia] = useState<ArchivedMedia[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [isAvatarModalOpen, setIsAvatarModalOpen] = useState(false);
  const [activeStoryGroup, setActiveStoryGroup] = useState<{ title: string; items: ArchivedMedia[] } | null>(null);

  const loadData = async () => {
    setLoading(true);
    try {
      const [l, m] = await Promise.all([
        api.getLogs(target.id),
        api.getMedia(target.id),
      ]);
      setLogs(l);
      setMedia(m);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [target.id]);

  const handleCheckNow = async () => {
    setRefreshing(true);
    try {
      await api.checkNow(target.id);
      await loadData();
      onTargetUpdated();
    } finally {
      setRefreshing(false);
    }
  };

  const handleToggleTurbo = async () => {
    await api.toggleTurbo(target.id, !target.turbo_mode);
    onTargetUpdated();
  };

  const handleDelete = async () => {
    if (confirm(`@${target.username} hesabını monitorinqdən çıxarmaq istədiyinizdən əminsiniz? Endirilmiş arxiv faylları silinməyəcək.`)) {
      await api.deleteTarget(target.id);
      onBack();
      onTargetUpdated();
    }
  };

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-6 animate-fade-up">
      {/* Top Action Bar */}
      <div className="flex items-center justify-between">
        <button
          onClick={onBack}
          className="p-2.5 rounded-full bg-white/70 hover:bg-white text-gray-700 shadow-xs active:scale-90 transition-all duration-200"
        >
          <ArrowLeft size={20} />
        </button>

        <div className="flex items-center gap-2">
          <Button
            variant={target.turbo_mode ? 'primary' : 'secondary'}
            size="sm"
            icon={<Zap size={15} />}
            onClick={handleToggleTurbo}
          >
            {target.turbo_mode ? 'Turbo Aktivdir' : 'Turbo Rejim'}
          </Button>
          <button
            onClick={handleCheckNow}
            disabled={refreshing}
            className="p-2.5 rounded-full bg-white text-gray-700 shadow-xs hover:text-[#0C7FF2] active:scale-90 transition-all duration-200 disabled:opacity-50"
            title="Dərhal Yoxla"
          >
            <RefreshCw size={18} className={refreshing ? 'animate-spin text-[#0C7FF2]' : ''} />
          </button>
          <button
            onClick={handleDelete}
            className="p-2.5 rounded-full bg-white text-rose-600 shadow-xs hover:bg-rose-50 active:scale-90 transition-all duration-200"
            title="Sil"
          >
            <Trash2 size={18} />
          </button>
        </div>
      </div>

      {/* Profile Overview Card */}
      <Card className="space-y-4 hover:shadow-md transition-shadow">
        <div className="flex items-center gap-4">
          <div
            onClick={() => setIsAvatarModalOpen(true)}
            className="w-16 h-16 rounded-full bg-[#E5E8EC] overflow-hidden flex items-center justify-center border-2 border-gray-100 shrink-0 cursor-pointer hover:border-[#0C7FF2] hover:scale-105 active:scale-95 transition-all shadow-xs group"
            title="Bütün arxivlənmiş profil şəkillərinə bax"
          >
            {target.profile_pic_path ? (
              <img
                src={target.profile_pic_path.startsWith('/') ? target.profile_pic_path : `/${target.profile_pic_path}`}
                alt={target.username}
                className="w-full h-full object-cover group-hover:opacity-90"
              />
            ) : (
              <User size={30} className="text-gray-400" />
            )}
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-bold text-gray-900 truncate">
                @{target.username}
              </h2>
              <StatusBadge status={target.is_private ? 'private' : 'public'} />
            </div>
            <p className="text-xs text-gray-500 font-medium mt-0.5">
              {target.full_name || 'Ad qeyd edilməyib'}
            </p>
          </div>
        </div>

        {target.biography && (
          <p className="text-xs text-gray-700 whitespace-pre-line leading-relaxed bg-[#F8F9FA] p-3.5 rounded-2xl border border-gray-100 font-normal">
            {target.biography}
          </p>
        )}

        <div className="grid grid-cols-2 gap-3 pt-2 border-t border-gray-100 text-center">
          <div className="bg-[#F8F9FA] py-2.5 rounded-2xl border border-gray-100">
            <span className="text-lg font-extrabold text-gray-900">{target.media_count}</span>
            <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-wider">Instagram Postları</p>
          </div>
          <div className="bg-[#F8F9FA] py-2.5 rounded-2xl border border-gray-100">
            <span className="text-lg font-extrabold text-[#0C7FF2]">{media.length}</span>
            <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-wider">Lokal Arxiv Faylı</p>
          </div>
        </div>
      </Card>

      {/* Instagram Style Highlights Circles Row */}
      <HighlightCirclesRow
        media={media}
        onSelectHighlight={(title, items) => setActiveStoryGroup({ title, items })}
      />

      {/* Tabs */}
      <div className="flex items-center bg-[#E5E8EC]/80 p-1 rounded-2xl">
        <button
          onClick={() => setActiveTab('gallery')}
          className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 ${
            activeTab === 'gallery'
              ? 'bg-white text-gray-900 shadow-sm'
              : 'text-gray-600 hover:text-gray-900'
          }`}
        >
          <Layers size={16} />
          Arxiv Qalereyası ({media.length})
        </button>
        <button
          onClick={() => setActiveTab('timeline')}
          className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 ${
            activeTab === 'timeline'
              ? 'bg-white text-gray-900 shadow-sm'
              : 'text-gray-600 hover:text-gray-900'
          }`}
        >
          <History size={16} />
          Audit Xronologiyası ({logs.length})
        </button>
      </div>

      {/* View Content */}
      {activeTab === 'gallery' ? (
        <MediaGallery media={media} />
      ) : (
        <TimelineView logs={logs} />
      )}

      {/* Avatar History Modal */}
      <AvatarHistoryModal
        isOpen={isAvatarModalOpen}
        onClose={() => setIsAvatarModalOpen(false)}
        targetId={target.id}
        username={target.username}
      />

      {/* Instagram Story Viewer Modal */}
      {activeStoryGroup && (
        <InstagramStoryViewerModal
          title={activeStoryGroup.title}
          items={activeStoryGroup.items}
          onClose={() => setActiveStoryGroup(null)}
        />
      )}
    </div>
  );
};
