import React, { useState } from 'react';
import { X, UserPlus, Zap } from 'lucide-react';
import { Button } from '../common/Button';

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
  const [username, setUsername] = useState('');
  const [turbo, setTurbo] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
      setError(err.message || 'Hədəfi əlavə edərkən xəta baş verdi');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/45 backdrop-blur-md p-4 transition-opacity duration-300"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md bg-white rounded-4xl sm:rounded-3xl p-6 shadow-2xl border border-gray-100 animate-scale-in"
      >
        <div className="flex items-center justify-between pb-4 border-b border-gray-100">
          <div className="flex items-center gap-2.5">
            <div className="p-2.5 rounded-full bg-blue-50 text-[#0C7FF2] shadow-xs">
              <UserPlus size={20} />
            </div>
            <h2 className="text-xl font-bold text-gray-900">
              Yeni Profil İzlə
            </h2>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-full text-gray-400 hover:text-gray-600 hover:bg-gray-100 active:scale-90 transition-all duration-150"
          >
            <X size={20} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="mt-5 flex flex-col gap-5">
          {error && (
            <div className="p-3.5 rounded-2xl bg-rose-50 text-rose-700 text-xs font-medium border border-rose-100 animate-toast">
              {error}
            </div>
          )}

          <div>
            <label className="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-2">
              Instagram İstifadəçi Adı
            </label>
            <div className="relative flex items-center">
              <span className="absolute left-4 text-gray-400 font-bold select-none">@</span>
              <input
                type="text"
                required
                autoFocus
                placeholder="məsələn: example_profile"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="w-full bg-[#F2F4F7] rounded-2xl pl-8 pr-4 py-3.5 text-sm font-medium text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-[#0C7FF2] focus:bg-white border border-transparent focus:border-transparent transition-all duration-200"
              />
            </div>
          </div>

          <div className="flex items-center justify-between p-4 bg-[#F8F9FA] rounded-2xl border border-gray-100">
            <div className="flex items-center gap-3">
              <div className={`p-2 rounded-full transition-colors duration-200 ${turbo ? 'bg-blue-100 text-[#0C7FF2]' : 'bg-gray-200 text-gray-500'}`}>
                <Zap size={18} />
              </div>
              <div>
                <p className="text-sm font-bold text-gray-900">Turbo Rejim</p>
                <p className="text-xs text-gray-500">Hər 30-60 saniyədən bir yoxla</p>
              </div>
            </div>

            <button
              type="button"
              onClick={() => setTurbo(!turbo)}
              className={`w-12 h-7 rounded-full p-1 transition-colors duration-200 ${
                turbo ? 'bg-[#0C7FF2]' : 'bg-gray-300'
              }`}
            >
              <div
                className={`w-5 h-5 rounded-full bg-white shadow-xs transition-transform duration-200 ease-out ${
                  turbo ? 'translate-x-5' : 'translate-x-0'
                }`}
              />
            </button>
          </div>

          <div className="flex items-center gap-3 pt-2">
            <Button
              type="button"
              variant="secondary"
              className="flex-1"
              onClick={onClose}
              disabled={loading}
            >
              Ləğv et
            </Button>
            <Button
              type="submit"
              variant="primary"
              className="flex-1"
              disabled={loading || !username.trim()}
            >
              {loading ? 'Yoxlanılır...' : 'İzləməyə Başla'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};
