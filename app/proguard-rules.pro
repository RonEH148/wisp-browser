# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/fedex/libs/android_sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

-keep class com.phlox.tvwebbrowser.webengine.webview.WebViewWebEngine { *; }

-keepclassmembers class com.phlox.tvwebbrowser.model.** {
   public *;
}
-keepclassmembers class com.brave.adblock.AdBlockClient {
   public *;
   private *;
}
-keepclassmembers class com.phlox.tvwebbrowser.webengine.webview.AndroidJSInterface {
   public *;
   private *;
}


#-keepclasseswithmembers class com.phlox.tvwebbrowser.model.** {
#    <fields>;
#}
# Lumen: ExoPlayer finds the video effects pipeline (Deband) by reflection
-keep class androidx.media3.effect.** { *; }
-dontwarn androidx.media3.effect.**

# Lumen: Cronet providers are found by reflection (Google Play services / HttpEngine)
-keep class org.chromium.net.** { *; }
-keep class com.google.android.gms.net.** { *; }
-dontwarn org.chromium.net.**

# Lumen: adblock-rust is called through JNI by these method names
-keepclasseswithmembernames class com.phlox.tvwebbrowser.sofa.LumenAdblock {
    native <methods>;
}
-keep class com.phlox.tvwebbrowser.sofa.LumenAdblock { *; }
-dontwarn org.lsposed.hiddenapibypass.**
