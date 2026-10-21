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

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---- Used when building with -Pshrink (R8). ----
# The app's own classes are kept whole: Moshi/Gson read them by reflection, WorkManager and Room
# instantiate them by name. The size win comes from unused library code, not from these.
-keep class com.example.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, Exceptions
-keep class kotlin.Metadata { *; }

# Native engines are called through JNI, so their Java side must keep its names.
-keep class com.k2fsa.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# Gson TypeToken generic signatures.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Optional dependencies referenced by libraries but not shipped.
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
