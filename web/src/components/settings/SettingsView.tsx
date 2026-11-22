import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { SystemSettings } from '../../types';
import { Card } from '../common/Card';
import { Button } from '../common/Button';
import { Shield, Bell, Send, Key, CheckCircle, ArrowLeft } from 'lucide-react';

interface SettingsViewProps {
  onBack: () => void;
}

export const SettingsView: React.FC<SettingsViewProps> = ({ onBack }) => {
  const [settings, setSettings] = useState<SystemSettings | null>(null);
  const [sessionCookie, setSessionCookie] = useState('');
  const [tgToken, setTgToken] = useState('');
  const [tgChatId, setTgChatId] = useState('');
  const [enableTermux, setEnableTermux] = useState(true);
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api.getSettings().then((s) => {
      setSettings(s);
      setEnableTermux(s.enable_termux_api);
      setTgToken(s.telegram_bot_token);
      setTgChatId(s.telegram_chat_id);
    });
  }, []);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      await api.updateSettings({
        enable_termux_api: enableTermux,
        telegram_bot_token: tgToken,
        telegram_chat_id: tgChatId,
        session_cookie: sessionCookie || undefined,
      });
      setSaved(true);
      setTimeout(() => setSaved(false), 2500);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-6">
      <div className="flex items-center gap-3">
        <button
          onClick={onBack}
          className="p-2 rounded-full hover:bg-white text-gray-700 transition-colors"
        >
          <ArrowLeft size={20} />
        </button>
        <div>
          <h2 className="text-2xl font-bold text-gray-900">Sistem Tənzimləmələri</h2>
          <p className="text-xs text-gray-500 font-medium">Bildirişlər və Instagram sessiyası</p>
        </div>
      </div>

      <form onSubmit={handleSave} className="space-y-5">
        {/* Termux Push Notifications */}
        <Card className="space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="p-2.5 rounded-full bg-blue-50 text-[#0C7FF2]">
                <Bell size={20} />
              </div>
              <div>
                <h3 className="font-bold text-sm text-gray-900">Android Termux Bildirişləri</h3>
                <p className="text-xs text-gray-500">Termux:API vasitəsilə telefonun bildiriş panelinə xəbərdarlıq</p>
              </div>
            </div>

            <button
              type="button"
              onClick={() => setEnableTermux(!enableTermux)}
              className={`w-12 h-7 rounded-full p-1 transition-colors ${
                enableTermux ? 'bg-[#0C7FF2]' : 'bg-gray-300'
              }`}
            >
              <div
                className={`w-5 h-5 rounded-full bg-white transition-transform ${
                  enableTermux ? 'translate-x-5' : 'translate-x-0'
                }`}
              />
            </button>
          </div>
        </Card>

        {/* Telegram Bot Alerts */}
        <Card className="space-y-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-full bg-blue-50 text-[#0C7FF2]">
              <Send size={20} />
            </div>
            <div>
              <h3 className="font-bold text-sm text-gray-900">Telegram Bot Bildirişləri</h3>
              <p className="text-xs text-gray-500">Profil açılan anda Telegram-a dərhal mesaj göndərin</p>
            </div>
          </div>

          <div className="space-y-3 pt-2">
            <div>
              <label className="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1">
                Telegram Bot Token
              </label>
              <input
                type="password"
                placeholder="məsələn: 123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
                value={tgToken}
                onChange={(e) => setTgToken(e.target.value)}
                className="w-full bg-[#F2F4F7] rounded-2xl px-4 py-3 text-xs font-mono text-gray-900 focus:outline-none focus:ring-2 focus:ring-[#0C7FF2] focus:bg-white border border-transparent"
              />
            </div>

            <div>
              <label className="block text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1">
                Telegram Chat ID
              </label>
              <input
                type="text"
                placeholder="məsələn: 987654321 və ya @kanal_adi"
                value={tgChatId}
                onChange={(e) => setTgChatId(e.target.value)}
                className="w-full bg-[#F2F4F7] rounded-2xl px-4 py-3 text-xs font-mono text-gray-900 focus:outline-none focus:ring-2 focus:ring-[#0C7FF2] focus:bg-white border border-transparent"
              />
            </div>
          </div>
        </Card>

        {/* Instagram Session Cookie */}
        <Card className="space-y-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-full bg-blue-50 text-[#0C7FF2]">
              <Key size={20} />
            </div>
            <div>
              <h3 className="font-bold text-sm text-gray-900">Instagram Sessiya Cookie (İstəyə görə)</h3>
              <p className="text-xs text-gray-500">Hesabınızı izləyən gizli profillər üçün `sessionid` cookie</p>
            </div>
          </div>

          <div>
            <textarea
              rows={3}
              placeholder="sessionid=123456789%3A..."
              value={sessionCookie}
              onChange={(e) => setSessionCookie(e.target.value)}
              className="w-full bg-[#F2F4F7] rounded-2xl p-4 text-xs font-mono text-gray-900 focus:outline-none focus:ring-2 focus:ring-[#0C7FF2] focus:bg-white border border-transparent resize-none"
            />
            <p className="text-[11px] text-gray-400 mt-1">
              Qeyd: İctimai profillər üçün sessiya daxil etmək məcburi deyil.
            </p>
          </div>
        </Card>

        {/* Actions */}
        <div className="flex items-center justify-end gap-3 pt-2">
          {saved && (
            <span className="flex items-center gap-1.5 text-xs font-semibold text-emerald-600 bg-emerald-50 px-3 py-1.5 rounded-full border border-emerald-100">
              <CheckCircle size={14} /> Tənzimləmələr yadda saxlanıldı
            </span>
          )}
          <Button type="submit" variant="primary" disabled={saving}>
            {saving ? 'Saxlanılır...' : 'Yadda Saxla'}
          </Button>
        </div>
      </form>
    </div>
  );
};
