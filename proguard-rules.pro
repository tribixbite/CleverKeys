# CleverKeys ProGuard Rules — LIVE as of 2026-08-29 (ARC-008).
#
# These rules are consumed by the RELEASE build only (`minifyEnabled true` +
# `shrinkResources true`, build.gradle release block). Debug stays unminified so
# the ew-cli instrumented suite keeps testing un-obfuscated code.
#
# R8 in AGP 8.x is deterministic by default; determinism was re-verified on
# 2026-08-29 by hashing classes*.dex across two clean `assembleRelease` runs.
#
# CORRECTION (supersedes the 2026-07-20 note that blocked shrinkResources):
# the claim that R.raw.numeric / R.raw.pin / R.raw.version_info are reached via
# `resources.getIdentifier()` is FALSE. The only `getIdentifier` calls in
# production are Keyboard2View.kt:244/:263 and they resolve *framework*
# (`"android"` package) identifiers — `config_showNavigationBar`,
# `navigation_bar_height` — which the app resource shrinker never touches.
# Every app raw resource is reached by a real `R.raw.*` constant
# (LayoutManager.kt:206/:209, KeyboardReceiver.kt:198, Emoji.kt:27,
# SettingsInfoCards.kt:231, LayoutsPreference.kt:143) or by an `@raw/…` entry in
# the generated `res/values/layouts.xml` `layout_ids` integer-array, so no
# keep.xml is required. Retention is asserted empirically after each release
# build — see the ARC-008 entry in memory/HANDOFF.md.

# =============================================================================
# Android entry points
# =============================================================================

# Keep all InputMethodService implementations
-keep class * extends android.inputmethodservice.InputMethodService {
    public *;
    protected *;
}

# Keep our specific services (FIXED: was tribixbite.keyboard2).
# LOAD-BEARING, not belt-and-braces: CleverKeysService.kt:1059 passes
# `javaClass.name` into IMEStatusHelper.checkAndPromptDefaultIME, which compares
# it against the InputMethodManager's enabled-IME list. That list carries the
# manifest name, so an obfuscated class name would silently break the
# "set CleverKeys as your default keyboard" prompt.
-keep class tribixbite.cleverkeys.CleverKeysService { *; }

# Custom Views inflated by name from res/layout/*.xml. aapt2 auto-generates
# constructor keeps for these, but they are the IME's own panes — pin them so a
# future aapt2/AGP change cannot quietly drop the inflation constructors.
-keep class tribixbite.cleverkeys.ClipboardHistoryView { *; }
-keep class tribixbite.cleverkeys.EmojiGridView { *; }
-keep class tribixbite.cleverkeys.EmojiGroupButtonsBar { *; }
-keep class tribixbite.cleverkeys.gif.GifGroupButtonsBar { *; }

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
# (SwipeResampler deleted before 2026-08-29 — dead rule removed, ARC-008)
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
# (SwipeDetector deleted before 2026-08-29 — dead rule removed, ARC-008)
-keep class tribixbite.cleverkeys.EnhancedSwipeGestureRecognizer { *; }
-keep class tribixbite.cleverkeys.ImprovedSwipeGestureRecognizer { *; }

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

# -----------------------------------------------------------------------------
# CONSERVATIVE BLANKET KEEPS — deliberately retained for the FIRST minified
# release (ARC-008, 2026-08-29), not because they are required.
#
# androidx.lifecycle and androidx.savedstate both ship their own correct
# consumer proguard.txt inside their AARs (verified 2026-08-29), so these
# `**{*;}` keeps are redundant; androidx.compose.** is the single largest
# retained blob in the DEX. Narrowing them is the remaining shrink headroom,
# but each one widens what the one-maintainer soak has to cover, so it is
# deferred to a follow-up with its own soak rather than folded into the
# enable-R8 change. See memory/HANDOFF.md ARC-008.
# -----------------------------------------------------------------------------

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
# Force deterministic ServiceLoader behavior.
#
# ARC-062: this rule is why `Dispatchers.Main` in a RELEASE build resolves through
# `java.util.ServiceLoader` reading META-INF/services/...MainDispatcherFactory out of the
# APK — with R8 on, the flag folds to false and the FastServiceLoader (Class.forName) path
# is gone from the shipped DEX entirely (baksmali-verified). Two consequences, both of
# which look fine at compile time and fail only at runtime, only in release:
#   - the `-keep` / `-keepnames` above must stay (they keep
#     kotlinx.coroutines.android.AndroidDispatcherFactory's NAME, which is the string
#     inside that service file);
#   - build.gradle must not exclude META-INF/services/kotlinx.coroutines.** from packaging
#     (see the comment at that spot).
# Deleting THIS rule is the safe direction: it restores the Class.forName path.
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

# CRITICAL: Keep enums used in swipe detection.
# SwipeDirection and ActionType were listed here under the ROOT package and
# matched nothing — both actually live in `tribixbite.cleverkeys.customization`
# (customization/SwipeDirection.kt:7, customization/ActionType.kt:6) and are
# already covered by the `customization.**` keep further down. Dead rules
# removed 2026-08-29 (ARC-008).
-keep enum tribixbite.cleverkeys.PredictionSource { *; }
-keep enum tribixbite.cleverkeys.NumberLayout { *; }

# Keep DirectBootAwarePreferences singleton
-keep class tribixbite.cleverkeys.DirectBootAwarePreferences { *; }

# Keep compose key handling
-keep class tribixbite.cleverkeys.ComposeKey { *; }
-keep class tribixbite.cleverkeys.ComposeKeyData { *; }

# ========== JNI/ONNX SPECIFIC RULES ==========
#
# The onnxruntime-android 1.20.0 AAR ships NO consumer proguard.txt (verified
# 2026-08-29 by listing the AAR: only jni/*/lib*.so + R.txt). The
# `-keep class ai.onnxruntime.** { *; }` above is therefore the ONLY thing
# standing between R8 and libonnxruntime4j_jni.so's FindClass/GetFieldID
# lookups. Do not narrow it without re-running the soak.
#
# The three rules that used to live here were all redundant or dead and were
# removed 2026-08-29 (ARC-008):
#   - a verbatim duplicate of the `native <methods>` keep above;
#   - `-keepnames`/`-keepclassmembers` on `onnx.**`, subsumed by the
#     `-keep class tribixbite.cleverkeys.onnx.** { *; }` near the top;
#   - `onnx.SessionConfigurator`, a class deleted with the transformer engine
#     on 2026-08-18 (the package holds only ModelLoader.kt today).

# =============================================================================
# Audit artifacts — regenerated on every release build, all under build/ so
# nothing lands in the repo. `full-r8-config.txt` is the MERGED configuration
# (our rules + the AGP default file + every AAR's consumer rules + aapt2's
# generated manifest/layout keeps); AGP additionally writes usage.txt (what was
# stripped), seeds.txt (what matched a keep) and mapping.txt beside it.
# =============================================================================
-printconfiguration build/outputs/mapping/release/full-r8-config.txt

# Ensure Kotlin metadata is preserved for proper reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
