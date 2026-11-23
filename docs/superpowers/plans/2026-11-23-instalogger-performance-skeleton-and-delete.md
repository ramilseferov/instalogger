# InstaLogger UI/UX Əsaslı Təkmilləşdirmə və Problem Həlli Planı

> **Hədəf:** Profil əlavə edərkən donma hissinin ləğvi (Skeleton/Shimmer), paylaşımların sayının düzgün əks olunması (həm Android, həm Web, həm Backend), ani və animasiyalı profil silinməsi, aşağı çəkmə (pull-to-refresh) və izləyici auditinin mükəmməlləşdirilməsi.

---

## 🔍 Mövcud Problemlərin Dərin Təhlili

1. **Profil Əlavə Edərkən Donma və Gözləmə (No Loading Feedback):**
   - İstifadəçi profil adını daxil edib "Əlavə et" düyməsini basdıqda dialoq bağlanır, lakin ekranda heç bir dəyişiklik baş vermir. Şəbəkə sorğusu (`inspectTarget` + `inspectHighlightsAndPosts`) 5-10 saniyə arxa planda işləyir. Bu müddətdə istifadəçi tətbiqin donduğunu və ya əməliyyatın baş tutmadığını zənn edir.
   - Həmçinin `ProfileDetailScreen` açıldıqda paylaşımlar yüklənənədək dərhal "Heç bir paylaşım tapılmadı" boş ekranı göstərilir ki, bu da vizual olaraq səhv təəssürat yaradır.

2. **Paylaşım Sayının 0 Göstərilməsi Xətası (Post Count Shows 0):**
   - `InstagramEngine.kt` daxilində `parseFormattedNumber` funksiyası `12.5K`, `1.2M` kimi qısaltmaları tanımır və `null` (`0L`) qaytarır.
   - HTML meta təsvirində regex yalnız böyük hərflərlə `"([\\d,.]+)\\s+Followers,\\s+([\\d,.]+)\\s+Following,\\s+([\\d,.]+)\\s+Posts"` yoxlayır; qısaltma və ya fərqli dilli başlıqlarda uyğunluq tapmır.
   - `ProfileInspector.kt` daxilində `inspectTarget` bazadakı mövcud post sayını (`postDao`) nəzərə almadan `snapshot.mediaCount` (0) saxlayır.
   - Web interfeysində və Rust backend-də arxivlənmiş media sayı cədvəldə mövcud olsa da, `target.media_count` 0 qaldıqda sayğac 0 göstərir.

3. **Hesabı Silərkən Gecikmə və Animasiyasızlıq (Delete Frozen / Resurrecting):**
   - `ProfileInspector.deleteTarget` funksiyasında əvvəlcə Rust daemon-a (`http://127.0.0.1:3000`) HTTP DELETE göndərilir (timeout 8 saniyəyə qədərdir), yalnız bundan SONRA Room DB təmizlənir. Bu müddətdə ekran donmuş qalır.
   - `ProfileDetailScreen` daxilində sil düyməsi basıldıqda `deleteTarget` bitənədək `onBack()` çağırılmır.
   - `DashboardScreen`-də silinmə üçün `AnimatedVisibility` (fade out + shrink) yoxdur.

4. **Aşağı Çəkmək Olmur (Pull-to-Refresh Gesture Blocked):**
   - `DashboardScreen`-də hədəf olmadıqda `UnifiedEmptyState` scrollable deyil (`Modifier.verticalScroll` yoxdur), buna görə `PullToRefreshBox` aşağı çəkilməni qəbul etmir.
   - `ProfileDetailScreen`-də boş və ya qısa məzmun olduqda scroll kilidlənir.

5. **İzləyicilərin Göstərilməməsi (Followers List Empty):**
   - `FollowerAuditBottomSheet` yalnız yerli `followers.json` faylını oxuyur. Əgər sessiya olmadan profil əlavə edilibsə və ya fayl hələ yaranmayıbsa, heç vaxt izləyiciləri gətirmir və vərəq daxilində manual yeniləmə düyməsi yoxdur.

---

## 🛠️ Təklif Olunan Həllər və Dəyişikliklər

### Task 1: Skeleton və Shimmer Komponentlərinin Yaradılması
**Fayl:** `android/app/src/main/java/com/instalogger/app/ui/components/SkeletonComponents.kt` [NEW]
- `Modifier.shimmerEffect()`: Sonsuz `rememberInfiniteTransition` gradient parıltı animasiyası.
- `SkeletonTargetCard()`: `TargetCard` ölçülərinə 100% uyğun, dairəvi avatar, ad, bio və status barlarının parıltılı skeleton təsviri.
- `SkeletonProfileHero()`: `ProfileDetailScreen` hero kartının shimmer versiyası.
- `SkeletonPostGrid()`: Paylaşımlar yüklənərkən 3×3 ölçülü kvadrat formalı shimmer toru.

### Task 2: Dashboard Ekranında Optimistik Skeleton və Silinmə Animasiyası
**Fayl:** `android/app/src/main/java/com/instalogger/app/ui/screens/DashboardScreen.kt` [MODIFY]
- **Əlavə etmə axını:**
  - `onConfirm` çağırılan kimi dərhal `pendingTargetUsernames` siyahısına həmin istifadəçi adı əlavə olunur.
  - Siyahının ən başında `SkeletonTargetCard(username = cleanUser)` göstərilir.
  - `inspectTarget` və `inspectHighlightsAndPosts` tamamlandıqda skeleton real karta çevrilir.
- **Silinmə axını:**
  - Hər bir `TargetCard` `AnimatedVisibility(visible = target.username !in deletingUsernames, exit = fadeOut(tween(250)) + shrinkVertically(tween(250)))` daxilinə alınır.
  - Sil təsdiqlənən kimi `deletingUsernames` yenilənir, kart dərhal vizual olaraq yığılır və silinir.
- **Aşağı çəkmə (Pull-to-refresh) düzəlişi:**
  - `UnifiedEmptyState` `Modifier.verticalScroll(rememberScrollState())` ilə təmin edilir ki, boş ekranda da aşağı çəkib yeniləmək mümkün olsun.

### Task 3: Profil Ekranında Skeleton və Dərhal Çıxış
**Fayl:** `android/app/src/main/java/com/instalogger/app/ui/screens/ProfileDetailScreen.kt` [MODIFY]
- Paylaşımlar yüklənən müddətdə `posts.isEmpty()` olduqda dərhal "Heç bir paylaşım tapılmadı" əvəzinə `SkeletonPostGrid()` göstərilir.
- Silmə təsdiqləndikdə: dialoq bağlanır, `onBack()` **dərhal** çağırılır, arxa planda silinmə icra olunur (ekranda ilişib qalma tam aradan qaldırılır).
- Paylaşım sayğacında `maxOf(target.mediaCount, posts.size.toLong())` istifadə edilir ki, bazada postlar olduğu halda 0 göstərilməsin.

### Task 4: Paylaşım Sayının Düzəldilməsi (Android, Web, Backend)
**Fayllar:**
1. `android/app/src/main/java/com/instalogger/app/engine/InstagramEngine.kt` [MODIFY]:
   - `parseFormattedNumber` funksiyasına `K`, `M`, `B` dəstəyi əlavə edilir (məs. `12.5K` -> `12500L`, `1.2M` -> `1200000L`).
   - Regex-lər `Pattern.CASE_INSENSITIVE` edilir və çoxdilli (Followers, Following, Posts / Gönderi / Paylaşım / Публикаций) axtarış təkmilləşdirilir.
2. `android/app/src/main/java/com/instalogger/app/engine/ProfileInspector.kt` [MODIFY]:
   - `inspectTarget` zamanı əgər `snapshot.mediaCount <= 0` olarsa, Room DB-də mövcud olan postların real sayını (`database.postDao().getPostsCountForUser(cleanUser)`) yoxlayıb mənimsədir.
   - `deleteTarget` daxilində: **Room DB-dən silinmə və Tombstone qeydiyyatı İLK növbədə (0ms) icra edilir**, sonra isə daemon-a qısa timeout ilə bildiriş göndərilir.
3. `web/src/components/targets/TargetCard.tsx` & `web/src/components/profile/ProfileDetail.tsx` [MODIFY]:
   - Sayğacda `target.media_count || media.length` göstərilir.
4. `backend/src/scheduler.rs` [MODIFY]:
   - `trigger_full_archive` tamamlandıqda `target.media_count = blitz.items.len()` yenilənir.

### Task 5: İzləyici Siyahısı və Audit Vərəqinin Təkmilləşdirilməsi
**Fayl:** `android/app/src/main/java/com/instalogger/app/ui/components/FollowerAuditBottomSheet.kt` [MODIFY]
- Vərəqin başlığında manual **"Yenilə"** düyməsi əlavə olunur.
- Əgər siyahı boşdursa, istifadəçiyə bir toxunuşla izləyiciləri yenidən sorğulamaq imkanı verilir.

---

## 🧪 Yoxlama və Test Planı
- Android APK yığımı: `bash build.sh` ilə 70 GB RAM build serverində xətasız kompilyasiya və cihaza quraşdırılma.
- Canlı test: Hədəf əlavəsi, silinmə animasiyası, post sayğacı və pull-to-refresh yoxlanışı.
