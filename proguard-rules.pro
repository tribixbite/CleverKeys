# CleverKeys ProGuard Rules
# Theory #3: Ensure InputMethodService subclasses aren't stripped

# Keep all InputMethodService implementations
-keep class * extends android.inputmethodservice.InputMethodService {
    public *;
    protected *;
}

# Keep our specific services
-keep class tribixbite.keyboard2.CleverKeysService { *; }
-keep class tribixbite.keyboard2.MinimalTestService { *; }

# Keep AndroidX Lifecycle components
-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# Keep AndroidX SavedState
-keep class androidx.savedstate.** { *; }
-keep interface androidx.savedstate.** { *; }
-dontwarn androidx.savedstate.**

# Keep Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Compose runtime
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep all native methods (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep InputMethod metadata
-keepclassmembers class * {
    @android.view.inputmethod.** *;
}

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
