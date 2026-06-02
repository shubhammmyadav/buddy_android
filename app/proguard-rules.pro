# MediaPipe — preserve all runtime-loaded JNI classes
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep data classes used by the UI layer
-keep class com.buddy.app.viewmodel.** { *; }

# Kotlin Coroutines — needed for coroutine reflection
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
