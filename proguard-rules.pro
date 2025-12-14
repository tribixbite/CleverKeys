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

# Keep swipe processing classes
-keep class tribixbite.cleverkeys.SwipeResampler { *; }
-keep class tribixbite.cleverkeys.TrajectoryFeatureCalculator { *; }
-keep class tribixbite.cleverkeys.NeuralLayoutBridge { *; }
-keep class tribixbite.cleverkeys.NeuralLayoutHelper { *; }

# Keep ML data classes and store
-keep class tribixbite.cleverkeys.ml.** { *; }
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
