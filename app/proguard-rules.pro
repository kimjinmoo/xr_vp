# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# Media3 Codec Extensions
-keep class androidx.media3.decoder.av1.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.decoder.vp9.** { *; }
-keep class androidx.media3.decoder.opus.** { *; }
-keep class androidx.media3.decoder.flac.** { *; }