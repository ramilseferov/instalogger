import React from 'react';
import { ChangeLog } from '../../types';
import {
  Lock,
  Unlock,
  User,
  FileText,
  Image,
  AlertCircle,
  Clock,
  Sparkles,
} from 'lucide-react';

interface TimelineViewProps {
  logs: ChangeLog[];
}

export const TimelineView: React.FC<TimelineViewProps> = ({ logs }) => {
  if (logs.length === 0) {
    return (
      <div className="py-12 text-center">
        <Clock className="w-10 h-10 text-gray-300 mx-auto mb-3" />
        <p className="text-sm font-semibold text-gray-600">Hələlik heç bir dəyişiklik qeydə alınmayıb</p>
        <p className="text-xs text-gray-400 mt-1">Sistem profil statusunda və ya məzmununda baş verən bütün dəyişiklikləri burada xronoloji qeyd edəcək.</p>
      </div>
    );
  }

  const renderEventDetails = (log: ChangeLog) => {
    switch (log.event_type) {
      case 'privacy_change': {
        const isNowPublic = log.new_value === 'false';
        return (
          <div className="flex items-center gap-2">
            <span
              className={`inline-flex items-center gap-1 px-3 py-1 rounded-full text-xs font-bold ${
                isNowPublic
                  ? 'bg-emerald-100 text-emerald-800'
                  : 'bg-amber-100 text-amber-800'
              }`}
            >
              {isNowPublic ? <Unlock size={12} /> : <Lock size={12} />}
              {isNowPublic ? 'Gizli → Açıq' : 'Açıq → Gizli'}
            </span>
            <span className="text-xs text-gray-500 font-medium">
              {isNowPublic
                ? 'Hesab açıq oldu — tam arxivləşdirmə başladıldı'
                : 'Hesab yenidən bağlandı — fayllar qorunur'}
            </span>
          </div>
        );
      }
      case 'username_change':
        return (
          <div className="flex flex-col gap-1 text-xs">
            <span className="font-bold text-gray-800">İstifadəçi adı dəyişdirildi</span>
            <div className="flex items-center gap-2 text-gray-500">
              <span className="line-through bg-gray-100 px-2 py-0.5 rounded font-mono">@{log.old_value}</span>
              <span>→</span>
              <span className="font-bold text-[#0C7FF2] bg-blue-50 px-2 py-0.5 rounded font-mono">@{log.new_value}</span>
            </div>
          </div>
        );
      case 'full_name_change':
        return (
          <div className="flex flex-col gap-1 text-xs">
            <span className="font-bold text-gray-800">Profil adı dəyişdirildi</span>
            <div className="flex items-center gap-2 text-gray-500">
              <span className="line-through">{log.old_value || '(yox idi)'}</span>
              <span>→</span>
              <span className="font-semibold text-gray-900">{log.new_value}</span>
            </div>
          </div>
        );
      case 'bio_change':
        return (
          <div className="flex flex-col gap-1 text-xs">
            <span className="font-bold text-gray-800">Bioqrafiya yeniləndi</span>
            <div className="p-2.5 rounded-xl bg-gray-50 border border-gray-100 text-gray-600 mt-1">
              {log.new_value || '(silinib)'}
            </div>
          </div>
        );
      case 'avatar_change': {
        const oldSrc = log.old_value.startsWith('/') ? log.old_value : `/${log.old_value}`;
        const newSrc = log.new_value.startsWith('/') ? log.new_value : `/${log.new_value}`;
        return (
          <div className="flex flex-col gap-2 text-xs">
            <span className="font-bold text-gray-800">Profil şəkli yeniləndi</span>
            <div className="flex items-center gap-3 bg-gray-50 p-2.5 rounded-2xl border border-gray-100 w-fit">
              <div className="w-12 h-12 rounded-full overflow-hidden border border-gray-300 opacity-60">
                <img src={oldSrc} alt="Köhnə" className="w-full h-full object-cover" />
              </div>
              <span className="text-gray-400 font-bold">→</span>
              <div className="w-12 h-12 rounded-full overflow-hidden border-2 border-[#0C7FF2] shadow-xs">
                <img src={newSrc} alt="Yeni" className="w-full h-full object-cover" />
              </div>
            </div>
          </div>
        );
      }
      case 'post_count_change': {
        const oldNum = parseInt(log.old_value) || 0;
        const newNum = parseInt(log.new_value) || 0;
        const diff = newNum - oldNum;
        return (
          <div className="flex items-center gap-2 text-xs">
            <span className="font-bold text-gray-800">Post sayı dəyişdi:</span>
            <span className="font-mono text-gray-500">{log.old_value}</span>
            <span>→</span>
            <span className="font-mono font-bold text-gray-900">{log.new_value}</span>
            <span
              className={`px-2 py-0.5 rounded-full text-[11px] font-bold ${
                diff > 0 ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700'
              }`}
            >
              {diff > 0 ? `+${diff} yeni post` : `${diff} post yoxa çıxıb`}
            </span>
          </div>
        );
      }
      case 'post_vanished':
        return (
          <div className="flex items-center gap-2 text-xs text-rose-700 bg-rose-50 px-3 py-1.5 rounded-xl border border-rose-100">
            <AlertCircle size={14} />
            <span className="font-semibold">Post profildən yoxa çıxıb (arxivdə lokal qorunur)</span>
          </div>
        );
      default:
        return (
          <div className="text-xs text-gray-600">
            <span className="font-bold">{log.event_type}: </span>
            <span>{log.new_value}</span>
          </div>
        );
    }
  };

  const formatTimestamp = (ts: string) => {
    try {
      const d = new Date(ts);
      return d.toLocaleString('az-AZ', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return ts;
    }
  };

  return (
    <div className="relative pl-6 space-y-6 before:absolute before:left-2.5 before:top-3 before:bottom-3 before:w-0.5 before:bg-[#E5E8EC]">
      {logs.map((log) => (
        <div key={log.id} className="relative group">
          <div className="absolute -left-[1.85rem] top-1 w-5 h-5 rounded-full bg-white border-2 border-[#0C7FF2] flex items-center justify-center">
            <div className="w-1.5 h-1.5 rounded-full bg-[#0C7FF2]" />
          </div>

          <div className="bg-white rounded-2xl p-4 border border-[#E5E8EC] shadow-sm">
            <div className="flex items-center justify-between mb-2">
              <span className="text-[11px] font-semibold text-gray-400 font-mono">
                {formatTimestamp(log.timestamp)}
              </span>
            </div>
            {renderEventDetails(log)}
          </div>
        </div>
      ))}
    </div>
  );
};
