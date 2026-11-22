# ProGuard rules for InstaLogger Android App
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.instalogger.app.data.local.** { *; }
-keep class com.instalogger.app.data.remote.dto.** { *; }
