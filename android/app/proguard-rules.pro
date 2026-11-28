# ProGuard / R8 rules for InstaLogger Android App (120 FPS Release Optimization)

# Preserve stack trace readability & Kotlin/Generic metadata
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# Gson & JSON serialization models (keep fields & constructors for reflective Gson parsing)
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.instalogger.app.data.local.** { *; }
-keep class com.instalogger.app.data.remote.** { *; }
-keep class com.instalogger.app.engine.** { *; }
-keep class com.instalogger.app.update.** { *; }
-keep class com.instalogger.app.util.LogEntry { *; }
-keep class com.instalogger.app.util.LogLevel { *; }
-keep class com.instalogger.app.util.StorageCategoryMetric { *; }
-keep class com.instalogger.app.util.DeviceStorageSnapshot { *; }

# WebView @JavascriptInterface bridges (HeadlessInstagramScraper)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Embedded yt-dlp + FFmpeg native & JNI wrappers
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# Retrofit & OkHttp
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room Database & DAOs
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlin Coroutines & Metadata
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.scheduling.CoroutineScheduler {
    volatile <fields>;
}

# AndroidX Security Crypto, Biometric & Coil
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn androidx.biometric.**
-dontwarn coil.**

# Keep Android entry points declared in AndroidManifest.xml
-keep public class com.instalogger.app.InstaLoggerApp
-keep public class com.instalogger.app.MainActivity
-keep public class com.instalogger.app.ManageSpaceActivity
-keep public class com.instalogger.app.service.** { *; }
-keep public class com.instalogger.app.update.UpdateInstallReceiver { *; }
