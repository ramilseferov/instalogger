# 📱💻 InstaLogger — Instagram Monitoring, Archival & Analytics Platform

<p align="center">
  <img src="web/public/instalogger-icon.svg" width="96" height="96" alt="InstaLogger Logo" />
</p>

<p align="center">
  <b>Avtomatlaşdırılmış Instagram İzləmə, Oflayn Arxivləşdirmə, Takibçi Auditi və İnteraktiv Analitika Sistemi</b><br/>
  <i>Automated Instagram Profile Monitoring, Offline Archival, Unfollower Audit & Interactive Analytics</i><br/>
  <i>Автоматический мониторинг Instagram, офлайн-архивация, аудит отписчиков и аналитика</i>
</p>

<p align="center">
  <a href="#-azərbaycan-dili">🇦🇿 Azərbaycan Dili</a> •
  <a href="#-english">🇬🇧 English</a> •
  <a href="#-русский-язык">🇷🇺 Русский Язык</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Release-v1.0.10--b11-0C7FF2.svg?style=flat-square" alt="Release" />
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84.svg?style=flat-square&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Language-AZ%20%7C%20EN%20%7C%20RU-FFB800.svg?style=flat-square" alt="Multi-Language" />
  <img src="https://img.shields.io/badge/License-MIT-gray.svg?style=flat-square" alt="License" />
</p>

---

## 🇦🇿 Azərbaycan Dili

### 📌 İcmal
**InstaLogger** hədəf Instagram profillərinin status dəyişikliklərini, paylaşımlarını, hekayələrini və takibçi hərəkətlərini fasiləsiz müşahidə edən və məlumatları oflayn diskdə təhlükəsiz saxlayan peşəkar monitorinq ekosistemidir.

### ⚡ Əsas İmkanlar
- **🔴 Takibdən Çıxanlar və Takib Auditi:** Hədəfi takib etməyi dayandıranlar (`follower_lost`), hədəfin takibdən çıxardıqları (`following_lost`), yeni takibçilər və yeni izlənilənlər anında qeydə alınır və avatar/zaman damğası ilə göstərilir.
- **📈 İnteraktiv Takibçi Say Diaqramı (`FollowerGrowthChart`):** Jetpack Compose Canvas üzərində iki zərif Bezier əyrisi ilə canlı artım/azalma qrafiki, touch-scrubber ilə dəqiq nöqtə tooltipi və xalis statistik göstəricilər.
- **🛡️ Gizli/Açıq Keçid Davamlılığı:** Hesab gizlidən (`is_private: true`) açığa (`false`) keçdiyi anda əvvəlki yerli arxivlə (`followers.json`, `following.json`) müqayisə aparılaraq aralıqda baş vermiş bütün dəyişikliklər 100% bərpa olunur.
- **⚡ Highlights və Tam Ekran Story Viewer:** Profilin bütün Önə Çıxarılan Hekayələri (foto və MP4 video) arxivlənir, dairəvi Instagram qabarcıqları və yuxarıda seqmentli dolan 5 saniyəlik progress bar ilə təqdim olunur.
- **🖼️ 3 Sütunlu Kvadrat Post Grid-i:** Profilin son post və reelləri klassik Instagram düzülüşü ilə tam ölçülü ön baxış imkanı verir.
- **🔐 İkiqat SHA-256 Kriptoqrafik Həş Yoxlanışı:** Instagram CDN token yenilənmələrini əsl profil şəkli dəyişikliyindən fərqləndirir və saxta bildirişlərin qarşısını alır.
- **🌐 100% Çoxdilli Sistem (3 Dildə):** Həm Android tətbiqində, həm Web İdarəetmə Panelində, həm də terminal skriptlərində (`AZ`, `EN`, `RU`) tam lokal dəstək.

### 🏗️ Arxitektura
- **Android Tətbiqi:** Kotlin + Jetpack Compose + Room DB (v6) + OkHttp + Coil.
- **Veb İdarəetmə Paneli:** React 19 + TypeScript + Tailwind CSS + WebSocket Hub.
- **Arxa Plan Xidməti:** Rust (Axum, SQLite WAL) + Python 3.11 GraphQL mühərriki.

### 💻 Terminal və CLI İdarəetməsi
Terminal skriptlərini istədiyiniz dildə işə salmaq mümkündür:
```bash
# Azərbaycan dilində build və release:
./build.sh --lang=az
python3 scripts/publish_release.py --lang=az --item "Yenilik 1"

# English:
./build.sh --lang=en
python3 scripts/publish_release.py --lang=en --item "Changelog 1"

# Русский:
./build.sh --lang=ru
python3 scripts/publish_release.py --lang=ru --item "Новинка 1"
```

---

## 🇬🇧 English

### 📌 Overview
**InstaLogger** is an automated, continuous Instagram profile observation, offline archiving, follower audit, and visual analytics platform designed with a local-first philosophy.

### ⚡ Key Features
- **🔴 Unfollower & Follow Audit Tracker:** Immediately detects who unfollowed the target account (`follower_lost`), who the target unfollowed (`following_lost`), newly gained followers, and new followings with full timestamps and profile pictures.
- **📈 Interactive Follower Growth Chart (`FollowerGrowthChart`):** Hardware-accelerated Jetpack Compose Canvas graph with dual smooth Bezier curves, touch-scrubbing inspection tooltip, and net gain/loss metrics.
- **🛡️ Private-to-Public Resilience:** When a private profile toggles to public, local differential reconciliation against previous archives (`followers.json`, `following.json`) immediately restores full audit logs.
- **⚡ Highlights Archiving & Fullscreen Story Viewer:** Downloads all highlight reels (photos & MP4 videos), rendered in authentic circular story bubbles with segmented 5-second progress bars and gestures.
- **🖼️ 3-Column Square Post Grid:** Displays recent posts and reels with video overlays and full-screen preview modals.
- **🔐 Cryptographic SHA-256 Digest Verification:** Distinguishes genuine avatar updates from ephemeral CDN token rotations.
- **🌐 Full Tri-Lingual Support (AZ, EN, RU):** Fully localized across Android app, Web management dashboard, and terminal CLI utilities.

### 🏗️ System Architecture
- **Mobile Client:** Android Native (Kotlin, Jetpack Compose, Room DB v6, OkHttp, Coil).
- **Web Console:** React 19, TypeScript, Tailwind CSS, Real-time WebSocket streaming.
- **Backend Daemon:** Rust (Axum, SQLite in WAL mode) + Python 3.11 GraphQL engine.

### 💻 CLI & Terminal Usage
Run terminal scripts with your preferred language flag:
```bash
# English:
./build.sh --lang=en
python3 scripts/publish_release.py --lang=en --item "Feature 1" --item "Feature 2"

# Azerbaijani:
./build.sh --lang=az
python3 scripts/publish_release.py --lang=az --item "Yenilik 1"

# Russian:
./build.sh --lang=ru
python3 scripts/publish_release.py --lang=ru --item "Обновление 1"
```

---

## 🇷🇺 Русский Язык

### 📌 Обзор
**InstaLogger** — это высокоточная система непрерывного мониторинга профилей Instagram, локальной архивации медиафайлов, аудита отписчиков и интерактивной графической аналитики.

### ⚡ Основные возможности
- **🔴 Аудит отписчиков и подписок:** Мгновенно выявляет пользователей, которые отписались от профиля (`follower_lost`), тех, кого отписал сам профиль (`following_lost`), а также новых подписчиков и подписки с фиксацией времени и аватаров.
- **📈 Интерактивный график роста подписчиков (`FollowerGrowthChart`):** Плавные кривые Безье на Jetpack Compose Canvas, интерактивный просмотр значений при касании (touch-scrubber tooltip) и учет динамики (+/-).
- **🛡️ Устойчивость к переключению приватности:** При открытии закрытого аккаунта локальное дифференциальное сравнение с `followers.json` и `following.json` полностью восстанавливает историю изменений.
- **⚡ Архивация актуального (Highlights) и Story Viewer:** Скачивание всех историй (фото и MP4 видео), круговой трей и полноэкранный плеер с 5-секундными сегментными полосами прогресса.
- **🖼️ 3-колоночная сетка публикаций:** Классическая квадратная сетка постов Instagram с индикатором видео и полноразмерным просмотром.
- **🔐 Двухслойная проверка SHA-256:** Предотвращает ложные уведомления при плановой ротации токенов Instagram CDN.
- **🌐 Полная 3-язычная поддержка (AZ, EN, RU):** В мобильном приложении Android, веб-интерфейсе и терминальных утилитах.

### 🏗️ Архитектура системы
- **Android-клиент:** Kotlin, Jetpack Compose, Room DB (v6), OkHttp, Coil.
- **Веб-панель:** React 19, TypeScript, Tailwind CSS, WebSocket Hub.
- **Серверный демон:** Rust (Axum, SQLite WAL) + Python 3.11 GraphQL worker.

### 💻 Использование в терминале
Запуск скриптов с поддержкой нужного языка:
```bash
# Русский:
./build.sh --lang=ru
python3 scripts/publish_release.py --lang=ru --item "Новинка 1"

# Английский:
./build.sh --lang=en
python3 scripts/publish_release.py --lang=en --item "Feature 1"

# Азербайджанский:
./build.sh --lang=az
python3 scripts/publish_release.py --lang=az --item "Yenilik 1"
```

---

<p align="center">
  <b>InstaLogger — Designed for privacy, precision, and local-first resilience.</b>
</p>
