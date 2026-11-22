import React, { createContext, useContext, useState, useEffect } from 'react';

export type Language = 'en' | 'az' | 'ru';

export interface Translations {
  app_title: string;
  app_subtitle: string;
  monitoring_active: string;
  targets_badge: string;
  settings: string;
  add_target: string;
  turbo_mode: string;
  private: string;
  public: string;
  posts: string;
  no_name: string;
  no_targets_title: string;
  no_targets_desc: string;
  back: string;
  audit_timeline: string;
  avatar_history: string;
  check_now: string;
  delete_target: string;
  confirm_delete_title: string;
  confirm_delete_desc: string;
  cancel: string;
  confirm: string;
  language: string;
  status_active: string;
  status_paused: string;
  followers: string;
  following: string;
  save: string;
  session_settings: string;
}

const translations: Record<Language, Translations> = {
  en: {
    app_title: 'InstaLogger',
    app_subtitle: 'Continuous Instagram Monitoring & Local Archiver',
    monitoring_active: 'Monitoring Active',
    targets_badge: 'Targets',
    settings: 'Settings',
    add_target: 'Add Target',
    turbo_mode: 'Turbo Mode',
    private: 'Private',
    public: 'Public',
    posts: 'posts',
    no_name: 'No display name',
    no_targets_title: 'No Targets Monitored',
    no_targets_desc: 'Add an Instagram profile to start automated tracking and local avatar archiving.',
    back: 'Back',
    audit_timeline: 'Audit Timeline',
    avatar_history: 'Avatar History',
    check_now: 'Check Now',
    delete_target: 'Delete Target',
    confirm_delete_title: 'Confirm Deletion',
    confirm_delete_desc: 'Are you sure you want to delete this target and all stored data?',
    cancel: 'Cancel',
    confirm: 'Confirm',
    language: 'Language',
    status_active: 'Active',
    status_paused: 'Paused',
    followers: 'Followers',
    following: 'Following',
    save: 'Save',
    session_settings: 'Instagram Session & System Settings'
  },
  az: {
    app_title: 'InstaLogger',
    app_subtitle: 'Instagram Fasiləsiz Monitorinq və Lokal Arxivləşdirici',
    monitoring_active: 'Monitorinq Aktivdir',
    targets_badge: 'Hədəf',
    settings: 'Tənzimləmələr',
    add_target: 'Hədəf Əlavə Et',
    turbo_mode: 'Turbo Rejim',
    private: 'Məxfi',
    public: 'Açıq',
    posts: 'paylaşım',
    no_name: 'İstifadəçi adı yoxdur',
    no_targets_title: 'Heç bir hədəf izlənilmir',
    no_targets_desc: 'Avtomatik monitorinq və profil şəkli arxivləşdirməsini başlatmaq üçün yeni profil əlavə edin.',
    back: 'Geri',
    audit_timeline: 'Audit Xronologiyası',
    avatar_history: 'Tarixi Avatarlar',
    check_now: 'İndi Yoxla',
    delete_target: 'Hədəfi Sil',
    confirm_delete_title: 'Silinməni Təsdiq Edin',
    confirm_delete_desc: 'Bu hədəfi və bütün arxiv məlumatlarını silmək istədiyinizdən əminsiniz?',
    cancel: 'Ləğv Et',
    confirm: 'Təsdiq Et',
    language: 'Dil',
    status_active: 'Aktivdir',
    status_paused: 'Dayandırılıb',
    followers: 'İzləyici',
    following: 'İzlənilən',
    save: 'Yadda Saxla',
    session_settings: 'Instagram Sessiyası və Sistem Tənzimləmələri'
  },
  ru: {
    app_title: 'InstaLogger',
    app_subtitle: 'Непрерывный мониторинг Instagram и локальный архиватор',
    monitoring_active: 'Мониторинг активен',
    targets_badge: 'Целей',
    settings: 'Настройки',
    add_target: 'Добавить цель',
    turbo_mode: 'Турбо-режим',
    private: 'Закрытый',
    public: 'Открытый',
    posts: 'публикаций',
    no_name: 'Имя не указано',
    no_targets_title: 'Нет отслеживаемых целей',
    no_targets_desc: 'Добавьте профиль Instagram для автоматического отслеживания и локальной архивации аватаров.',
    back: 'Назад',
    audit_timeline: 'Хронология аудита',
    avatar_history: 'История аватаров',
    check_now: 'Проверить сейчас',
    delete_target: 'Удалить цель',
    confirm_delete_title: 'Подтвердите удаление',
    confirm_delete_desc: 'Вы уверены, что хотите удалить эту цель и все сохраненные данные?',
    cancel: 'Отмена',
    confirm: 'Подтвердить',
    language: 'Язык',
    status_active: 'Активен',
    status_paused: 'Приостановлен',
    followers: 'Подписчики',
    following: 'Подписки',
    save: 'Сохранить',
    session_settings: 'Сессия Instagram и системные настройки'
  }
};

interface I18nContextType {
  language: Language;
  setLanguage: (lang: Language) => void;
  t: Translations;
}

const I18nContext = createContext<I18nContextType>({
  language: 'en',
  setLanguage: () => {},
  t: translations.en
});

export const I18nProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [language, setLanguageState] = useState<Language>(() => {
    const saved = localStorage.getItem('instalogger_lang') as Language;
    return saved && translations[saved] ? saved : 'en';
  });

  const setLanguage = (lang: Language) => {
    localStorage.setItem('instalogger_lang', lang);
    setLanguageState(lang);
  };

  return (
    <I18nContext.Provider value={{ language, setLanguage, t: translations[language] }}>
      {children}
    </I18nContext.Provider>
  );
};

export const useI18n = () => useContext(I18nContext);
