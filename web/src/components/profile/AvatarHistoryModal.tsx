import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { X, Download, Image as ImageIcon, Calendar } from 'lucide-react';

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
  const [avatars, setAvatars] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedAvatar, setSelectedAvatar] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      setLoading(true);
      api.getAvatars(targetId)
        .then(setAvatars)
        .finally(() => setLoading(false));
    }
  }, [isOpen, targetId]);

  if (!isOpen) return null;

  const parseTimestamp = (path: string): string => {
    const filename = path.split('/').pop() || '';
    const match = filename.match(/avatar_(\d{4}-\d{2}-\d{2})_(\d{2}-\d{2}-\d{2})\.jpg/);
    if (match) {
      const date = match[1];
      const time = match[2].replace(/-/g, ':');
      return `${date} ${time}`;
    }
    return 'Cari Şəkil';
  };

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 transition-opacity duration-300"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-lg bg-white rounded-3xl p-6 shadow-2xl border border-gray-100 flex flex-col max-h-[85vh] animate-scale-in"
      >
        <div className="flex items-center justify-between pb-4 border-b border-gray-100">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-full bg-blue-50 text-[#0C7FF2]">
              <ImageIcon size={18} />
            </div>
            <div>
              <h3 className="font-bold text-base text-gray-900">
                Profil Şəkli Tarixçəsi
              </h3>
              <p className="text-xs text-gray-500">@{username} hesabının bütün arxivlənmiş avatarları</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-full text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
          >
            <X size={20} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto py-4 space-y-4">
          {loading ? (
            <p className="text-xs text-center text-gray-400 py-8">Yüklənir...</p>
          ) : avatars.length === 0 ? (
            <p className="text-xs text-center text-gray-400 py-8">Arxivlənmiş profil şəkli tapılmadı.</p>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {avatars.map((path, idx) => {
                const fullUrl = path.startsWith('/') ? path : `/${path}`;
                const label = parseTimestamp(path);

                return (
                  <div
                    key={path}
                    onClick={() => setSelectedAvatar(fullUrl)}
                    className="group relative bg-[#F8F9FA] rounded-2xl overflow-hidden border border-gray-100 p-2 flex flex-col items-center gap-2 cursor-pointer hover:border-gray-300 transition-all shadow-xs hover:shadow"
                  >
                    <div className="w-20 h-20 rounded-full overflow-hidden border border-gray-200/80 bg-gray-100">
                      <img
                        src={fullUrl}
                        alt={`Avatar ${idx + 1}`}
                        className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-200"
                      />
                    </div>
                    <div className="text-center w-full">
                      <span className="text-[11px] font-semibold text-gray-700 truncate block">
                        {label}
                      </span>
                      <a
                        href={fullUrl}
                        download
                        onClick={(e) => e.stopPropagation()}
                        className="inline-flex items-center gap-1 text-[10px] text-[#0C7FF2] hover:underline mt-0.5"
                      >
                        <Download size={11} /> Endir
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
            className="fixed inset-0 z-60 bg-black/80 flex items-center justify-center p-4"
          >
            <div className="relative max-w-sm w-full bg-white rounded-3xl p-4 overflow-hidden shadow-2xl">
              <button
                onClick={() => setSelectedAvatar(null)}
                className="absolute top-3 right-3 p-1.5 rounded-full bg-black/50 text-white"
              >
                <X size={18} />
              </button>
              <img
                src={selectedAvatar}
                alt="Böyüdülmüş Avatar"
                className="w-full h-auto rounded-2xl object-contain max-h-[60vh] mx-auto"
              />
              <div className="mt-3 text-center">
                <a
                  href={selectedAvatar}
                  download
                  className="inline-flex items-center gap-1.5 text-xs font-bold text-[#0C7FF2] bg-blue-50 px-4 py-2 rounded-full"
                >
                  <Download size={14} /> Şəkli Cihaza Endir
                </a>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
