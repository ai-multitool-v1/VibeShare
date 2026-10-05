# VibeShare R8 rules (minification is disabled by default; rules kept for safety)
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.setbd.vibeshare.** {
    kotlinx.serialization.KSerializer serializer(...);
}
