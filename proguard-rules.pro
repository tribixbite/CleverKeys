# CleverKeys ProGuard Rules
# Theory #3: Ensure InputMethodService subclasses aren't stripped

# Keep all InputMethodService implementations
-keep class * extends android.inputmethodservice.InputMethodService {
    public *;
    protected *;
}

# Keep our specific services (FIXED: was tribixbite.keyboard2)
-keep class tribixbite.cleverkeys.CleverKeysService { *; }
-keep class tribixbite.cleverkeys.MinimalTestService { *; }

# Keep all CleverKeys ONNX/neural classes
-keep class tribixbite.cleverkeys.onnx.** { *; }
-dontwarn tribixbite.cleverkeys.onnx.**

# Keep all neural prediction related classes
-keep class tribixbite.cleverkeys.SwipeTrajectoryProcessor { *; }
-keep class tribixbite.cleverkeys.SwipeTrajectoryProcessor$** { *; }
-keep class tribixbite.cleverkeys.SwipePredictionCandidate { *; }
-keep class tribixbite.cleverkeys.Vocabulary { *; }
-keep class tribixbite.cleverkeys.CharacterTokenizer { *; }

# Keep KeyValue class and ALL its enums - critical for swipe prediction
# Without these rules, enum ordinals get obfuscated and kind checking fails
-keep class tribixbite.cleverkeys.KeyValue { *; }
-keep class tribixbite.cleverkeys.KeyValue$Kind { *; }
-keep class tribixbite.cleverkeys.KeyValue$Event { *; }
-keep class tribixbite.cleverkeys.KeyValue$Modifier { *; }
-keep class tribixbite.cleverkeys.KeyValue$Editing { *; }
-keep class tribixbite.cleverkeys.KeyValue$Placeholder { *; }
-keep class tribixbite.cleverkeys.KeyValue$Slider { *; }
-keep class tribixbite.cleverkeys.KeyValue$Macro { *; }
-keep class tribixbite.cleverkeys.KeyValue$Companion { *; }

# Keep SwipeInput class for prediction input handling
-keep class tribixbite.cleverkeys.SwipeInput { *; }

# Keep KeyboardData and Key class for swipe detection
-keep class tribixbite.cleverkeys.KeyboardData { *; }
-keep class tribixbite.cleverkeys.KeyboardData$Key { *; }

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
