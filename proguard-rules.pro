# CleverKeys ProGuard Rules
# NOTE: R8 in AGP 8.x is deterministic by default. No special flags needed.

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
# CRITICAL: Keep inner classes in onnx package (PredictionPostProcessor.Result,
# PredictionPostProcessor.Candidate, BeamSearchEngine.BeamState, etc.)
-keep class tribixbite.cleverkeys.onnx.**$** { *; }
-dontwarn tribixbite.cleverkeys.onnx.**

# Keep all neural prediction related classes
-keep class tribixbite.cleverkeys.SwipeTrajectoryProcessor { *; }
-keep class tribixbite.cleverkeys.SwipeTrajectoryProcessor$** { *; }
-keep class tribixbite.cleverkeys.SwipePredictionCandidate { *; }
-keep class tribixbite.cleverkeys.Vocabulary { *; }
-keep class tribixbite.cleverkeys.CharacterTokenizer { *; }

# CRITICAL: Keep beam search data models - required for prediction pipeline
-keep class tribixbite.cleverkeys.BeamSearchState { *; }
-keep class tribixbite.cleverkeys.IndexValue { *; }
-keep class tribixbite.cleverkeys.BeamSearchCandidate { *; }

# CRITICAL: Keep vocabulary types - prediction results use these
-keep class tribixbite.cleverkeys.WordInfo { *; }
-keep class tribixbite.cleverkeys.CandidateWord { *; }
-keep class tribixbite.cleverkeys.FilteredPrediction { *; }
-keep class tribixbite.cleverkeys.SwipeStats { *; }
-keep class tribixbite.cleverkeys.VocabularyStats { *; }

# CRITICAL: Keep gesture recognizer types
-keep class tribixbite.cleverkeys.SwipeResult { *; }

# CRITICAL: Keep PredictionResult - THE main return type for neural predictions
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

# Keep neural engine and prediction coordinator
-keep class tribixbite.cleverkeys.NeuralSwipeTypingEngine { *; }
-keep class tribixbite.cleverkeys.NeuralSwipeTypingEngine$** { *; }
-keep class tribixbite.cleverkeys.AsyncPredictionHandler { *; }
-keep class tribixbite.cleverkeys.AsyncPredictionHandler$** { *; }
-keep class tribixbite.cleverkeys.PredictionCoordinator { *; }
-keep class tribixbite.cleverkeys.WordPredictor { *; }
-keep class tribixbite.cleverkeys.WordPredictor$** { *; }

# Keep coordinate normalizer and its data classes
-keep class tribixbite.cleverkeys.CoordinateNormalizer { *; }
-keep class tribixbite.cleverkeys.CoordinateNormalizer$** { *; }

# Keep vocabulary classes
-keep class tribixbite.cleverkeys.NeuralVocabulary { *; }
-keep class tribixbite.cleverkeys.OptimizedVocabulary { *; }
-keep class tribixbite.cleverkeys.VocabularyTrie { *; }
-keep class tribixbite.cleverkeys.VocabularyTrie$** { *; }
-keep class tribixbite.cleverkeys.VocabularyCache { *; }
-keep class tribixbite.cleverkeys.VocabularyUtils { *; }

# Keep contraction manager
-keep class tribixbite.cleverkeys.ContractionManager { *; }

# Keep probabilistic key detector
-keep class tribixbite.cleverkeys.ProbabilisticKeyDetector { *; }

# Keep swipe processing classes
-keep class tribixbite.cleverkeys.SwipeResampler { *; }
-keep class tribixbite.cleverkeys.SwipeResampler$** { *; }
-keep class tribixbite.cleverkeys.TrajectoryFeatureCalculator { *; }
-keep class tribixbite.cleverkeys.TrajectoryFeatureCalculator$** { *; }
-keep class tribixbite.cleverkeys.NeuralLayoutBridge { *; }
-keep class tribixbite.cleverkeys.NeuralLayoutHelper { *; }

# CRITICAL: Keep KeyboardGrid - used for nearest key detection during swipe
-keep class tribixbite.cleverkeys.KeyboardGrid { *; }

# CRITICAL: Keep TrajectoryObjectPool - memory pooling for trajectory processing
-keep class tribixbite.cleverkeys.TrajectoryObjectPool { *; }

# Keep ML data classes and store
-keep class tribixbite.cleverkeys.ml.** { *; }
-keep class tribixbite.cleverkeys.ml.**$** { *; }
-dontwarn tribixbite.cleverkeys.ml.**

# Keep gesture recognizer classes
-keep class tribixbite.cleverkeys.SwipeGestureRecognizer { *; }
-keep class tribixbite.cleverkeys.EnhancedSwipeGestureRecognizer { *; }
-keep class tribixbite.cleverkeys.ImprovedSwipeGestureRecognizer { *; }
-keep class tribixbite.cleverkeys.ContinuousSwipeGestureRecognizer { *; }
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

# Keep Keyboard2View fields for reflection access by NeuralLayoutHelper
# NeuralLayoutHelper.extractKeyPositionsFromLayout() uses reflection to access _keyboard field
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

# Keep Gson for tokenizer JSON parsing (CRITICAL for neural predictions)
# Without these rules, tokenizer_config.json parsing fails silently in release builds
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Keep SwipeTokenizer inner classes used for Gson deserialization
-keep class tribixbite.cleverkeys.SwipeTokenizer { *; }
-keep class tribixbite.cleverkeys.SwipeTokenizer$TokenizerConfig { *; }
-keep class tribixbite.cleverkeys.SwipeTokenizer$Companion { *; }
-keepclassmembers class tribixbite.cleverkeys.SwipeTokenizer$TokenizerConfig {
    <fields>;
    <init>(...);
}

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

# Keep N-gram model data classes
-keep class tribixbite.cleverkeys.NgramModel { *; }
-keep class tribixbite.cleverkeys.NgramModel$** { *; }

# Keep additional singletons and utilities
-keep class tribixbite.cleverkeys.Logs { *; }
-keep class tribixbite.cleverkeys.Utils { *; }
-keep class tribixbite.cleverkeys.KeyModifier { *; }
-keep class tribixbite.cleverkeys.KeyValueParser { *; }
-keep class tribixbite.cleverkeys.LayoutModifier { *; }
-keep class tribixbite.cleverkeys.EditorInfoHelper { *; }
-keep class tribixbite.cleverkeys.IMEStatusHelper { *; }
-keep class tribixbite.cleverkeys.WindowLayoutUtils { *; }

# Keep model metadata and version manager
-keep class tribixbite.cleverkeys.NeuralModelMetadata { *; }
-keep class tribixbite.cleverkeys.NeuralModelMetadata$** { *; }
-keep class tribixbite.cleverkeys.ModelVersionManager { *; }
-keep class tribixbite.cleverkeys.ModelVersionManager$** { *; }

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
