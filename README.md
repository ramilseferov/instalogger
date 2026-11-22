<p align="center">
  <img src="instalogger-icon.svg" width="120" height="120" alt="InstaLogger Logo" />
</p>

# InstaLogger

> **Fasiləsiz Instagram Profil Monitorinqi və Avtomatik Rəqəmsal Arxivləşdirici**  
> *Termux (Android, root tələb olunmur), Linux, Windows (PowerShell) və macOS üçün Rust və Python əsaslı açıq mənbəli sistem.*

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Termux%20%7C%20Linux%20%7C%20Windows%20%7C%20macOS%20%7C%20Android-green.svg)](#)
[![Backend](https://img.shields.io/badge/Backend-Rust%20%2B%20Axum%20%2B%20SQLite-orange.svg)](#)
[![Frontend](https://img.shields.io/badge/Frontend-React%2019%20%2B%20TypeScript%20%2B%20Tailwind-blue.svg)](#)
[![Android](https://img.shields.io/badge/Android-Kotlin%20%2B%20Jetpack%20Compose-blue.svg)](#)

---

## 📌 Nədir və Nə Üçün Lazımdır?

**InstaLogger** adi bir Instagram yükləyicisi və ya birdəfəlik post endirici proqram deyil. 

Bu sistemin əsas məqsədi seçilmiş Instagram profillərini **fasiləsiz olaraq 24/7 arxa planda izləmək**, profilin zamanla necə dəyişdiyini xronoloji qeyd etmək və ən əsası — **gizli hesab (Private) hamı üçün açılan (Public) kimi dərhal avtomatik olaraq onun bütün keçmiş və yeni paylaşımlarını, Reels, Hekayə və Highlights kolleksiyalarını lokal yaddaşa endirib arxivləşdirməkdir**.

Hesab bir neçə dəqiqə sonra yenidən gizli olsa belə, əldə edilmiş arxiv faylları **heç vaxt silinmir** və lokal cihazda daimi qorunur.

---

## ✨ Əsas Funksiyalar

- ⚡ **Gizli → Açıq Keçidinin Ani Aşkarlanması:** Profil açıq statusa keçən andaca "İki Mərhələli" (Two-Phase) arxivləşdirmə işə düşür (Mərhələ 1: saniyələr ərzində bütün imzalanmış CDN linkləri və metadata götürülür; Mərhələ 2: fayllar arxa planda fasiləsiz endirilir).
- 📁 **Kateqoriyalı Arxiv Qovluq Strukturu:** Bütün şəkillər, videolar və Highlights kolleksiyaları yaddaşda səliqəli şəkildə kateqoriyalara ayrılır.
- 🕒 **Tam Dəyişiklik Tarixçəsi (Audit Trail):** İstifadəçi adı (username), profil şəkli, ad, bio və post sayındakı bütün dəyişikliklər tarix və saatla `Köhnə → Yeni` şəklində qeydə alınır.
- 🛡️ **Sıfır Məlumat İtkisi (Zero Data Loss):** Profildən silinən və ya arxivə atılan postlar lokal yaddaşdan **silinmir**, "Yoxa Çıxanlar" etiketi ilə qorunur.
- 🎨 **Müasir və Axıcı Veb İnterfeys:** React 19, TypeScript və Tailwind CSS ilə hazırlanmış, 60/120 FPS hamar animasiyalı mobil və masaüstü yönümlü idarəetmə paneli.
- 🔔 **Hibrid Xəbərdarlıq Sistemi:** Hesab açıldıqda və ya mühüm dəyişiklik baş verdikdə həm Android Termux bildirişi (`termux-notification`), həm veb paneldə səsli/vizual xəbərdarlıq, həm də Telegram Bot vasitəsilə push mesaj göndərilir.
- 🌐 **Tam Çarpaz Platforma Dəstəyi:** Android Termux, Linux, Windows (PowerShell) və macOS sistemlərində eyni dərəcədə stabil işləyir.

---

## 🏗️ Arxitektura

```
+-------------------------------------------------------------------------+
|                  Android (Termux) / Linux / Windows / macOS             |
|                                                                         |
|    +---------------------------------------------------------------+    |
|    |            CLI İdarəetməsi: instalogger / instalogger.ps1     |    |
|    |        (arxa plan xidməti, wake-lock, nəzarət mexanizmi)      |    |
|    +-------------------------------+-------------------------------+    |
|                                    |                                    |
|                                    v                                    |
|    +---------------------------------------------------------------+    |
|    |                   Rust Əsas Daemon (Axum)                     |    |
|    |    - SQLite Quraşdırılmış Verilənlər Bazası (WAL mode)        |    |
|    |    - Təsadüfi gecikməli Scheduler & Turbo Rejim (30-60s)      |    |
|    |    - REST API & WebSocket Real-time Hub                       |    |
|    |    - Statik Veb Server (TSX Saytı & Media faylları)           |    |
|    |    - Bildiriş Sistemi (Termux API & Telegram Bot)             |    |
|    +------------------+------------------------------+-------------+    |
|                       |                              |                  |
|         JSON / IPC    |                              | HTTP / WebSocket |
|         Subprocess    v                              v                  |
|    +-----------------------------+    +----------------------------+    |
|    |    Python Worker Mühərriki  |    |     Veb İdarəetmə Paneli   |    |
|    | - Metadata & CDN Blitz      |    | - React 19 + TypeScript    |    |
|    | - Media Hydration Yükləyici |    | - Axıcı Hərəkət Əyriləri   |    |
|    | - Highlights Kolleksiyaları |    | - Canlı Hadisə İzləmə      |    |
|    | - Sessiya & Cookie İdarəsi  |    | - Qalereya və Media Baxış  |    |
|    +-----------------------------+    +----------------------------+    |
+-------------------------------------------------------------------------+
```

---

## 🚀 Quraşdırma və İstifadə

### 📱 Android (Termux — Root Tələb Olunmur)
```bash
pkg update && pkg install -y git rust python nodejs-lts termux-api
pip install requests instaloader urllib3

git clone https://github.com/ramilseferov/instalogger.git
cd instalogger
bash scripts/build.sh
instalogger start
```

### 🐧 Linux (Ubuntu / Debian / Arch / Fedora)
```bash
git clone https://github.com/ramilseferov/instalogger.git
cd instalogger
pip install requests instaloader urllib3
bash scripts/build.sh
./instalogger start
```

### 🪟 Windows (PowerShell)
```powershell
git clone https://github.com/ramilseferov/instalogger.git
cd instalogger
pip install requests instaloader urllib3

# Arxa planda işə salın:
.\instalogger.ps1 start

# Statusu yoxlayın:
.\instalogger.ps1 status
```

Brauzerinizdə daxil olun:  
👉 **[http://localhost:3000](http://localhost:3000)**

---

## 📋 Terminal Komandaları

| Əmr (Bash) | Əmr (PowerShell) | Təsvir |
| :--- | :--- | :--- |
| `instalogger start` | `.\instalogger.ps1 start` | Arxa planda xidməti işə salır |
| `instalogger status` | `.\instalogger.ps1 status` | PID və sağlamlıq statusunu yoxlayır |
| `instalogger logs` | `.\instalogger.ps1 logs` | Canlı sistem loqlarını izləyir |
| `instalogger stop` | `.\instalogger.ps1 stop` | Xidməti dayandırır |
| `instalogger restart` | `.\instalogger.ps1 restart` | Xidməti yenidən başladır |

---

## 📂 Fayl və Arxiv Strukturu

```
instalogger/
├── data/
│   └── instalogger.db            # SQLite verilənlər bazası
├── sessions/
│   └── instagram_session.json   # Əlavə edilmiş Instagram sessiya faylı
├── logs/
│   └── instalogger.log           # Fasiləsiz sistem loqları
└── archives/
    └── <instagram_user_id>/
        ├── posts/                # Şəkillər, videolar və başlıq (.txt) faylları
        ├── reels/                # Bütün Reels videoları
        ├── highlights/<Ad>/      # Hər kolleksiya üzrə qovluqlar
        └── profile/              # Tarixi profil şəkilləri və metadata
```

---

## 📱 Android Mobil Tətbiqi (Kotlin / Jetpack Compose)

InstaLogger sistemi üçün rəsmi native Android tətbiqi `android/` qovluğunda yerləşir:

- **Texnologiya:** Kotlin, Jetpack Compose, Material 3, Room SQLite, Retrofit/OkHttp, OkHttp WebSocket.
- **Arxa Fon Xidməti (Foreground Service):** Cihaz bağlansa belə `InstaLoggerForegroundService` vasitəsilə profil dəyişiklikləri fasiləsiz yoxlanılır və bildiriş göndərilir.
- **Batareya Optimizasiyası:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` inteqrasiyası ilə sistemin arxa fon proseslərini məhdudlaşdırmasının qarşısını alır.
- **Aktiv Bildirişlər və Room Bazası:** Bildiriş panelində birbaşa 'Oxundu' (`ACTION_MARK_READ`) düyməsi təqdim edilir və bütün hadisələr yerli Room bazasında arxivlənir. Tətbiqdə tək-tək və ya toplu silmə dəstəklənir.
- **Instagram Sessiyası:** Tətbiqdaxili təhlükəsiz WebView vasitəsilə rəsmi giriş və avtomatik cookie (`sessionid`) tutulması, habelə əl ilə cookie daxil etmə rejimi.
- **Dizayn:** Rəsmi vektor ikonları, 24-28dp yuvarlaq künclər və veb panellə tam uyğunlaşdırılmış neytral sakit tonlar.

### Android Tətbiqinin Build Edilməsi
```bash
cd android
./gradlew assembleDebug
```
Hazır APK faylı `android/app/build/outputs/apk/debug/app-debug.apk` yolunda yaranacaq.

---

## 📄 Lisenziya

Bu layihə [MIT Lisenziyası](LICENSE) altında açıq mənbəli olaraq paylanılır. Müəllif: [Ramil Səfərov](https://github.com/ramilseferov).
