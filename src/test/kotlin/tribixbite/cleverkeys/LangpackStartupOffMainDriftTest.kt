package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Drift pins for issue #179 (4–10 s IME startup with an imported language pack).
 *
 * ## What actually happened
 *
 * The reporter ran v1.5.0, where `PredictionCoordinator.initialize()` still built the neural
 * transformer SYNCHRONOUSLY on the main thread during IME `onCreate` (two ONNX sessions plus the
 * 98k-word OptimizedVocabulary + trie — the bulk of the 4–10 s, removed wholesale by ADR-011).
 * But two O(pack) main-thread costs survived that removal, and BOTH are conditioned on exactly
 * the reporter's configuration (an imported pack + `pref_enable_multilang`, which the
 * auto-detect toggle requires):
 *
 *  1. **The async primary-dictionary loader did not know about language packs.**
 *     `AsyncDictionaryLoader` probed only the bundled assets
 *     (`dictionaries/<lang>_enhanced.bin` / `.json`), so for a pack-only language the
 *     background load ALWAYS failed — and `WordPredictor`'s `onLoadFailed` fallback re-ran the
 *     whole load (`loadDictionary`, which does know about packs) synchronously ON THE MAIN
 *     THREAD, on every IME process create. The "async" path was a guaranteed detour into a
 *     main-thread parse of the entire pack.
 *
 *  2. **The secondary dictionary loaded synchronously on the main thread** in
 *     `PredictionCoordinator.initializeWordPredictor()` (and on every multilang pref change via
 *     `reloadWordPredictorSecondaryDictionary`) — a full pack read + NormalizedPrefixIndex
 *     build inside `onCreate`.
 *
 * These tests scan source text (same pattern as [CoreImeHygieneDriftTest]) because the
 * invariant is about WHICH THREAD does the work, which no pure behavioural test can observe
 * without an Android main looper.
 *
 * ## What is deliberately NOT changed (fallback preservation)
 *
 * A swipe or keystroke arriving before the deferred load completes behaves exactly like the
 * pre-existing cold path for bundled languages: the primary dictionary is empty until the
 * background swap lands (predictions simply absent, `isLoading()` true), and the swipe engines
 * are untouched — the CTC adapter builds its own lexicon on the decode thread, and an
 * unmeasured/unserved pack still falls through to the geometric engine
 * (`InputCoordinator.performCtcSwipeTyping` → `performGeometricSwipeTyping`, pinned by
 * [CoreImeHygieneDriftTest]'s ARC-065 companion and the adapter gates).
 */
class LangpackStartupOffMainDriftTest {

    private val mainKotlin = File("src/main/kotlin")

    private fun source(relative: String): String {
        val f = File(mainKotlin, relative)
        assertWithMessage("expected source file to exist: ${f.path} (run from project root)")
            .that(f.isFile).isTrue()
        return f.readText()
    }

    /**
     * Pin #1: the background loader must probe the installed language pack BEFORE the bundled
     * assets, mirroring `WordPredictor.loadDictionary`'s precedence (the issue #63 fix). If the
     * pack probe disappears, every pack-only language silently returns to the fail-async →
     * reload-synchronously-on-main path that produced #179's per-create pack parse.
     */
    @Test
    fun asyncDictionaryLoaderProbesTheLanguagePackFirst() {
        val loader = source("tribixbite/cleverkeys/AsyncDictionaryLoader.kt")

        assertWithMessage(
            "AsyncDictionaryLoader must resolve the installed language pack via " +
                "LanguagePackManager.getDictionaryPath — without it, a pack-only language " +
                "always fails the async load and WordPredictor.onLoadFailed re-parses the " +
                "pack synchronously on the MAIN thread on every IME create (issue #179)."
        ).that(loader).contains("getDictionaryPath")

        assertWithMessage(
            "AsyncDictionaryLoader must load the pack file through " +
                "BinaryDictionaryLoader.loadDictionaryWithPrefixIndexFromFile, the same reader " +
                "the synchronous path uses, so async and sync loads agree on the pack format."
        ).that(loader).contains("loadDictionaryWithPrefixIndexFromFile")

        val packProbe = loader.indexOf("getDictionaryPath")
        val assetProbe = loader.indexOf("dictionaries/\${language}_enhanced.bin")
        assertWithMessage(
            "the language-pack probe must come BEFORE the bundled-asset probe, matching " +
                "WordPredictor.loadDictionary's pack-first precedence (issue #63): an installed " +
                "pack overrides a bundled dictionary of the same code."
        ).that(assetProbe).isGreaterThan(packProbe)
    }

    /**
     * Pin #2: nothing on the coordinator's create/pref-change path may call the BLOCKING
     * secondary-dictionary load. `initializeWordPredictor` runs inside the IME's `onCreate`
     * (KeyboardComponentGraph.wireSwipeTypingComponents → predictionCoordinator.initialize()),
     * and `reloadWordPredictorSecondaryDictionary` runs on the main-thread preference listener —
     * both must go through the async variant.
     */
    @Test
    fun predictionCoordinatorNeverBlocksOnTheSecondaryDictionary() {
        val coordinator = source("tribixbite/cleverkeys/PredictionCoordinator.kt")

        // Word-boundary regex: must not flag unloadSecondaryDictionary( (cheap field writes)
        // or loadSecondaryDictionaryAsync( (the required replacement).
        val blockingCall = Regex("""\bloadSecondaryDictionary\(""")
        assertWithMessage(
            "PredictionCoordinator must not call the blocking loadSecondaryDictionary( — " +
                "it runs a full language-pack read + NormalizedPrefixIndex build on the MAIN " +
                "thread during IME onCreate for every multilang user (issue #179). Use " +
                "loadSecondaryDictionaryAsync."
        ).that(blockingCall.containsMatchIn(coordinator)).isFalse()

        assertWithMessage(
            "PredictionCoordinator must load the secondary dictionary via " +
                "loadSecondaryDictionaryAsync( so the pack parse happens on the shared " +
                "dictionary-loader thread."
        ).that(coordinator).contains("loadSecondaryDictionaryAsync(")
    }

    /**
     * Pin #3: with the secondary load off the main thread, its published fields are written on
     * the loader thread and read on the prediction path — both must stay `@Volatile` so a
     * finished load is visible to the next keystroke without synchronization.
     */
    @Test
    fun secondaryDictionaryPublicationFieldsStayVolatile() {
        val predictor = source("tribixbite/cleverkeys/WordPredictor.kt")

        for (field in listOf("secondaryIndex", "secondaryLanguageCode")) {
            val decl = Regex("""@Volatile\s*\n\s*private var $field""")
            assertWithMessage(
                "WordPredictor.$field is written by the async secondary-dictionary load " +
                    "(background thread) and read by the prediction path — the declaration " +
                    "must keep its @Volatile annotation."
            ).that(decl.containsMatchIn(predictor)).isTrue()
        }
    }

    /**
     * Pin #4 (fallback preservation): the deferred load must not have grown a wait — the
     * failure fallback in WordPredictor still exists (a genuinely unloadable language degrades
     * to the synchronous best-effort scan, which is trivial once no pack/asset matches), and
     * the coordinator's create path stays free of any blocking wait on the loader.
     */
    @Test
    fun deferredLoadsIntroduceNoMainThreadWaits() {
        val coordinator = source("tribixbite/cleverkeys/PredictionCoordinator.kt")
        for (token in listOf("Thread.sleep", "CountDownLatch", ".join()", ".get(")) {
            assertWithMessage(
                "PredictionCoordinator must not block on background dictionary work " +
                    "(found '$token') — the v1.5.0 startup stall came from exactly this " +
                    "class synchronously waiting out heavy init on the main thread."
            ).that(coordinator).doesNotContain(token)
        }
    }
}
