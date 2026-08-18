# CleverKeys ProGuard Rules
# NOTE: R8 in AGP 8.x is deterministic by default. No special flags needed.
#
# WARNING (2026-07-20 R8 audit): shrinkResources must stay FALSE unless a
# res/raw keep.xml is added — R.raw.numeric / R.raw.pin / R.raw.version_info
# are loaded via resources.getIdentifier() and look unused to the shrinker.

# Theory #3: Ensure InputMethodService subclasses aren't stripped

# Keep all InputMethodService implementations
-keep class * extends android.inputmethodservice.InputMethodService {
    public *;
    protected *;
}

# Keep our specific services (FIXED: was tribixbite.keyboard2)
-keep class tribixbite.cleverkeys.CleverKeysService { *; }

# Keep the ONNX session loader (onnx/ModelLoader) — the CTC encoder builds its
# OrtSession through it. The rest of the onnx package was deleted with the transformer
# engine on 2026-08-18.
-keep class tribixbite.cleverkeys.onnx.** { *; }
-dontwarn tribixbite.cleverkeys.onnx.**

# CRITICAL: Keep gesture recognizer types
-keep class tribixbite.cleverkeys.SwipeResult { *; }

# CRITICAL: Keep PredictionResult - THE main return type for swipe predictions
-keep class tribixbite.cleverkeys.PredictionResult { *; }

# Keep dictionary loading classes
-keep class tribixbite.cleverkeys.DictionaryWord { *; }
-keep class tribixbite.cleverkeys.WordSource { *; }
-keep class tribixbite.cleverkeys.BigramModel { *; }
-keep class tribixbite.cleverkeys.BigramModel$** { *; }
-keep class tribixbite.cleverkeys.BinaryDictionaryLoader { *; }
-keep class tribixbite.cleverkeys.BinaryContractionLoader { *; }
-keep class tribixbite.cleverkeys.MainDictionarySource { *; }
-keep class tribixbite.cleverkeys.UserDictionarySource { *; }
-keep class tribixbite.cleverkeys.DictionaryManager { *; }
-keep class tribixbite.cleverkeys.DictionaryManager$** { *; }

# Keep Config Defaults object
-keep class tribixbite.cleverkeys.Defaults { *; }
-keep class tribixbite.cleverkeys.Config { *; }
-keep class tribixbite.cleverkeys.Config$** { *; }

# Keep the prediction coordinator and the tap-typing predictor
-keep class tribixbite.cleverkeys.PredictionCoordinator { *; }
-keep class tribixbite.cleverkeys.WordPredictor { *; }
-keep class tribixbite.cleverkeys.WordPredictor$** { *; }

# Keep contraction manager
-keep class tribixbite.cleverkeys.ContractionManager { *; }

# Keep probabilistic key detector
-keep class tribixbite.cleverkeys.ProbabilisticKeyDetector { *; }

# Keep swipe processing classes
-keep class tribixbite.cleverkeys.SwipeResampler { *; }
-keep class tribixbite.cleverkeys.SwipeResampler$** { *; }
-keep class tribixbite.cleverkeys.KeyboardDimensionsHelper { *; }

# CRITICAL: Keep KeyboardGrid - used for nearest key detection during swipe
-keep class tribixbite.cleverkeys.KeyboardGrid { *; }

# Keep ML data classes and store
-keep class tribixbite.cleverkeys.ml.** { *; }
-keep class tribixbite.cleverkeys.ml.**$** { *; }
-dontwarn tribixbite.cleverkeys.ml.**

# Keep gesture recognizer classes
# (SwipeGestureRecognizer/ContinuousSwipeGestureRecognizer deleted in the
#  2026-07-18 dead-code purge — rules removed)
-keep class tribixbite.cleverkeys.EnhancedSwipeGestureRecognizer { *; }
-keep class tribixbite.cleverkeys.ImprovedSwipeGestureRecognizer { *; }
-keep class tribixbite.cleverkeys.SwipeDetector { *; }
-keep class tribixbite.cleverkeys.SwipeDetector$** { *; }

# Keep Pointers class and nested classes (critical for touch handling)
-keep class tribixbite.cleverkeys.Pointers { *; }
-keep class tribixbite.cleverkeys.Pointers$** { *; }

# Keep Gesture class and enums
-keep class tribixbite.cleverkeys.Gesture { *; }
-keep class tribixbite.cleverkeys.Gesture$** { *; }

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

# Keep Keyboard2View wholesale. The reflection consumer that motivated this
# (the layout helper's reflection into the private _keyboard field) was deleted with the
# transformer engine on 2026-08-18; the keep is retained because the view
# is the IME's inflated root and is referenced from XML.
-keep class tribixbite.cleverkeys.Keyboard2View { *; }
-keepclassmembers class tribixbite.cleverkeys.Keyboard2View {
    private ** _keyboard;
    private ** _keyboard2;
    <fields>;
}
-keepnames class tribixbite.cleverkeys.Keyboard2View { *; }

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

# =============================================================================
# REPRODUCIBILITY: Disable R8 ServiceLoader optimization for deterministic builds
# R8's ServiceLoader optimization creates non-deterministic class ordering
# which breaks F-Droid reproducible builds. These rules disable the optimization.
# See: https://f-droid.org/docs/Reproducible_Builds/
# =============================================================================
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
# Force deterministic ServiceLoader behavior
-assumenosideeffects class kotlinx.coroutines.internal.MainDispatcherLoader {
    boolean FAST_SERVICE_LOADER_ENABLED return false;
}

# Keep Compose runtime
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep Gson (CRITICAL: custom words, langpack manifests, clipboard tags and the
# settings backup format all round-trip through it; R8 field renaming corrupts them)
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

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

# ========== ADDITIONAL RULES FROM COMPREHENSIVE SCAN ==========

# Keep all Short Swipe Customization classes for JSON serialization
-keep class tribixbite.cleverkeys.customization.** { *; }
-keep class tribixbite.cleverkeys.customization.**$** { *; }

# Keep all theme classes for JSON serialization
-keep class tribixbite.cleverkeys.theme.** { *; }
-keep class tribixbite.cleverkeys.theme.**$** { *; }

# Keep backup/restore result classes (nested data classes with @JvmField)
-keep class tribixbite.cleverkeys.BackupRestoreManager { *; }
-keep class tribixbite.cleverkeys.BackupRestoreManager$** { *; }

# Keep personalization data classes
-keep class tribixbite.cleverkeys.PersonalizationManager { *; }
-keep class tribixbite.cleverkeys.PersonalizationManager$** { *; }

# CRITICAL: UserVocabulary Gson-deserializes List<UserWordUsage> (typed TypeToken);
# without this keep, R8 field renaming silently corrupts vocabulary load/import.
-keep class tribixbite.cleverkeys.personalization.** { *; }
-keep class tribixbite.cleverkeys.personalization.**$** { *; }

# Keep additional singletons and utilities
-keep class tribixbite.cleverkeys.Logs { *; }
-keep class tribixbite.cleverkeys.Utils { *; }
-keep class tribixbite.cleverkeys.KeyModifier { *; }
-keep class tribixbite.cleverkeys.KeyValueParser { *; }
-keep class tribixbite.cleverkeys.LayoutModifier { *; }
-keep class tribixbite.cleverkeys.EditorInfoHelper { *; }
-keep class tribixbite.cleverkeys.IMEStatusHelper { *; }
-keep class tribixbite.cleverkeys.WindowLayoutUtils { *; }

# Keep LauncherActivity inner classes (animation data classes)
-keep class tribixbite.cleverkeys.LauncherActivity$** { *; }

# CRITICAL: Keep enums used in swipe detection
-keep enum tribixbite.cleverkeys.SwipeDirection { *; }
-keep enum tribixbite.cleverkeys.ActionType { *; }
-keep enum tribixbite.cleverkeys.PredictionSource { *; }
-keep enum tribixbite.cleverkeys.NumberLayout { *; }

# Keep DirectBootAwarePreferences singleton
-keep class tribixbite.cleverkeys.DirectBootAwarePreferences { *; }

# Keep compose key handling
-keep class tribixbite.cleverkeys.ComposeKey { *; }
-keep class tribixbite.cleverkeys.ComposeKeyData { *; }

# ========== JNI/ONNX SPECIFIC RULES ==========

# Prevent R8 from breaking JNI method links with ONNX Runtime
-keepclassmembers class * {
    native <methods>;
}

# Keep all classes that interact with ONNX tensors (prevent JNI obfuscation)
-keepnames class tribixbite.cleverkeys.onnx.**
-keepclassmembers class tribixbite.cleverkeys.onnx.** {
    *;
}

# Keep ONNX session configurator
-keep class tribixbite.cleverkeys.onnx.SessionConfigurator { *; }

# Ensure Kotlin metadata is preserved for proper reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
