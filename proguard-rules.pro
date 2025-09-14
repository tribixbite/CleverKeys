# CleverKeys ProGuard Configuration
# Optimized for neural prediction and gesture recognition

# Keep ONNX Runtime classes - Critical for neural functionality
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-keepnames class ai.onnxruntime.OrtEnvironment
-keepnames class ai.onnxruntime.OrtSession
-keepnames class ai.onnxruntime.OnnxTensor

# Keep CleverKeys neural prediction classes
-keep class juloo.keyboard2.OnnxSwipePredictorImpl { *; }
-keep class juloo.keyboard2.NeuralSwipeEngine { *; }
-keep class juloo.keyboard2.SwipeTrajectoryProcessor { *; }
-keep class juloo.keyboard2.SwipeTokenizer { *; }
-keep class juloo.keyboard2.NeuralPredictionPipeline { *; }

# Keep data classes used in neural operations
-keep class juloo.keyboard2.SwipeInput { *; }
-keep class juloo.keyboard2.PredictionResult { *; }
-keep class juloo.keyboard2.ml.SwipeMLData { *; }
-keep class juloo.keyboard2.ml.SwipeMLData$TracePoint { *; }

# Keep gesture recognition classes
-keep class juloo.keyboard2.SwipeGestureRecognizer { *; }
-keep class juloo.keyboard2.EnhancedSwipeGestureRecognizer { *; }
-keep class juloo.keyboard2.SwipeDetector { *; }
-keep class juloo.keyboard2.AdvancedTemplateMatching { *; }

# Keep configuration classes
-keep class juloo.keyboard2.Config { *; }
-keep class juloo.keyboard2.NeuralConfig { *; }
-keep class juloo.keyboard2.Config$IKeyEventHandler { *; }

# Keep Android Input Method Service components
-keep class juloo.keyboard2.CleverKeysService { *; }
-keep class * extends android.inputmethodservice.InputMethodService { *; }
-keep class * implements android.inputmethodservice.KeyboardView.OnKeyboardActionListener { *; }

# Keep Activities and their lifecycle methods
-keep class juloo.keyboard2.SwipeCalibrationActivity { *; }
-keep class juloo.keyboard2.SettingsActivity { *; }
-keep class juloo.keyboard2.LauncherActivity { *; }
-keep class juloo.keyboard2.CustomLayoutEditor { *; }

# Keep enum classes and their methods
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep KeyValue sealed class hierarchy
-keep class juloo.keyboard2.KeyValue { *; }
-keep class juloo.keyboard2.KeyValue$* { *; }

# Keep exception classes for error handling
-keep class juloo.keyboard2.ErrorHandling$* { *; }

# Keep coroutines classes
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Flow and reactive programming classes
-keep class kotlinx.coroutines.flow.** { *; }
-keepclassmembers class kotlinx.coroutines.flow.** { *; }

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# Keep Android framework integration
-keep class android.view.inputmethod.** { *; }
-keep class android.inputmethodservice.** { *; }
-keep class android.content.res.** { *; }

# Keep accessibility classes
-keep class android.view.accessibility.** { *; }
-keep class android.accessibilityservice.** { *; }

# Keep SharedPreferences classes
-keep class android.content.SharedPreferences { *; }
-keep class android.content.SharedPreferences$* { *; }

# Keep threading and Handler classes
-keep class android.os.Handler { *; }
-keep class android.os.Handler$* { *; }
-keep class android.os.Looper { *; }

# Keep JSON classes for configuration
-keep class org.json.** { *; }
-dontwarn org.json.**

# Keep Window Manager classes for foldable detection
-keep class androidx.window.** { *; }
-dontwarn androidx.window.**

# Optimization settings
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Keep line numbers for debugging
-keepattributes LineNumberTable,SourceFile

# Keep annotations
-keepattributes *Annotation*

# Keep generic signatures for reflection
-keepattributes Signature

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Remove debug logging from CleverKeys
-assumenosideeffects class juloo.keyboard2.ExtensionsKt {
    public static ** logD(...);
    public static ** logW(...);
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep Parcelable classes
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

# Keep custom view constructors
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}