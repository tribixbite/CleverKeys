package tribixbite.cleverkeys

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.swipe.GeometricEngineAdapter
import tribixbite.cleverkeys.swipe.SwipeEngineRouter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WP9 R-1 step 9 — geometric-engine oracle (instrumented leg).
 *
 * Pins the NEW behavior steps 7-8 introduce: a non-QWERTY layout (Dvorak here — non-QWERTY
 * by routing, English dictionary bundled so no langpack import is needed) yields swipe
 * suggestions through the REAL adapter + engine + bundled `en_enhanced.bin`, and those
 * results ride the SAME SuggestionHandler seam as neural results — so the parity pins
 * (password guard, shift transform, commit engine) are asserted against real geo output.
 *
 * Routing itself is pinned by SwipeEngineRouterTest (pure JVM); this class re-pins it once
 * against real KeyboardData instances.
 *
 * Perf gate: p95 of adapter-convert + engine-decode round-trips (warm) must stay well under
 * the swipe-to-suggestion latency budget. The engine core measured ~2-3 ms warm at 98k words
 * on JVM; the gate here is deliberately loose (150 ms) to absorb emulator scheduling noise
 * while still catching an accidental synchronous rebuild per decode.
 */
@RunWith(AndroidJUnit4::class)
class GeometricSwipeOracleTest {

    private lateinit var context: Context

    private class TestInputConnection(target: EditText) : BaseInputConnection(target, true) {
        fun bufferText(): String = editable?.toString() ?: ""
    }

    private class Harness(
        val config: Config,
        val contextTracker: PredictionContextTracker,
        val suggestionBar: SuggestionBar,
        val suggestionHandler: SuggestionHandler,
        val inputCoordinator: InputCoordinator,
        val inputConnection: TestInputConnection,
        val resources: android.content.res.Resources,
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    private fun onMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        var thrown: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try { block() } catch (t: Throwable) { thrown = t } finally { latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
        thrown?.let { throw it }
    }

    private fun textEditor() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "com.example.app"
    }

    private fun passwordEditor() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        packageName = "com.example.app"
    }

    // ── layout + trace fixtures ─────────────────────────────────────────────────────

    private val frameW = 1080f
    private val frameH = 640f

    private fun loadLayout(layoutName: String): KeyboardData {
        // Layout definitions ship as RAW resources (build.gradle copies src/main/layouts/*.xml
        // into build/generated/layouts/res/raw); KeyboardData.load handles raw streams.
        val id = context.resources.getIdentifier(layoutName, "raw", context.packageName)
        assertTrue("layout resource $layoutName must exist", id != 0)
        val kd = KeyboardData.load(context.resources, id)
        assertTrue("layout $layoutName must parse", kd != null)
        return kd!!
    }

    private fun paramsFor(kd: KeyboardData) = KeyboardGeometry.Params(
        keyWidth = frameW / kd.keysWidth,
        rowHeight = frameH / kd.keysHeight,
        marginTop = 0f,
        marginLeft = 0f,
    )

    /** Center of the letter key whose primary value is [c], in frame pixels. */
    private fun letterCenter(kd: KeyboardData, params: KeyboardGeometry.Params, c: Char): PointF {
        val rect = KeyboardGeometry.computeKeyRects(kd, params).firstOrNull { r ->
            r.kv.getKind() == KeyValue.Kind.Char && r.kv.getChar().lowercaseChar() == c
        } ?: throw AssertionError("no key for '$c' on layout")
        return PointF(
            (rect.bounds.left + rect.bounds.right) / 2f,
            (rect.bounds.top + rect.bounds.bottom) / 2f,
        )
    }

    /** Straight-line trace through the letter-key centers of [word], ~12 pts per segment. */
    private fun traceFor(kd: KeyboardData, params: KeyboardGeometry.Params, word: String):
        Pair<List<PointF>, List<Long>> {
        val centers = word.map { letterCenter(kd, params, it) }
        val points = ArrayList<PointF>()
        val times = ArrayList<Long>()
        var t = 0L
        for (i in 0 until centers.size - 1) {
            val a = centers[i]; val b = centers[i + 1]
            val steps = 12
            for (s in 0 until steps) {
                val f = s / steps.toFloat()
                points.add(PointF(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
                times.add(t); t += 15
            }
        }
        points.add(centers.last()); times.add(t)
        return points to times
    }

    private fun decodeBlocking(
        adapter: GeometricEngineAdapter,
        kd: KeyboardData,
        params: KeyboardGeometry.Params,
        word: String,
    ): PredictionResult {
        val (path, times) = traceFor(kd, params, word)
        var result: PredictionResult? = null
        val latch = CountDownLatch(1)
        onMain {
            adapter.decodeAsync(kd, params, frameW, frameH, path, times, "en") {
                result = it
                latch.countDown()
            }
        }
        assertTrue("decode must complete", latch.await(30, TimeUnit.SECONDS))
        return result!!
    }

    // ── SH-seam harness (condensed from PipelineCharacterizationTest — no WordPredictor:
    //    the geo path must behave with a predictor-less coordinator too) ───────────────

    private fun harness(): Harness {
        val config = Config.globalConfig()
        config.swipe_typing_enabled = true
        config.swipe_engine_mode = "hybrid"
        config.swipe_final_autocorrect_enabled = false
        config.swipe_on_password_fields = false
        config.auto_space_after_suggestion = true
        config.auto_space_before_suggestion = true
        config.termux_mode_enabled = false
        config.swipe_show_debug_scores = false
        config.haptic_enabled = false

        val contextTracker = PredictionContextTracker()
        val stubReceiver = object : KeyEventHandler.IReceiver {
            override fun handle_event_key(ev: KeyValue.Event) {}
            override fun set_shift_state(state: Boolean, lock: Boolean) {}
            override fun set_compose_pending(pending: Boolean) {}
            override fun selection_state_changed(selectionIsOngoing: Boolean) {}
            override fun getCurrentInputConnection(): InputConnection? = null
            override fun getHandler(): Handler = Handler(Looper.getMainLooper())
            override fun handle_text_typed(text: String) {}
        }
        val keyEventHandler = KeyEventHandler(stubReceiver)
        val contractionManager = ContractionManager(context).apply { loadMappings() }
        val predCoord = PredictionCoordinator(context, config)

        val built = arrayOfNulls<Any>(3)
        onMain {
            val bar = SuggestionBar(context)
            val kbView = Keyboard2View(context)
            val edit = EditText(context)
            built[0] = bar; built[1] = kbView; built[2] = TestInputConnection(edit)
        }
        val bar = built[0] as SuggestionBar
        val kbView = built[1] as Keyboard2View
        val ic = built[2] as TestInputConnection

        val suggestionHandler = SuggestionHandler(
            context, config, contextTracker, predCoord, contractionManager, keyEventHandler
        ).apply { setSuggestionBar(bar) }
        val inputCoordinator = InputCoordinator(
            context, config, contextTracker, predCoord, bar, kbView
        )
        inputCoordinator.setSwipeResultDelegate(suggestionHandler)
        inputCoordinator.setCursorSyncDelegate(suggestionHandler)

        return Harness(config, contextTracker, bar, suggestionHandler, inputCoordinator, ic, context.resources)
    }

    private fun drainMainThread() {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }

    // ═════════════════════════════════ tests ═════════════════════════════════

    /** Router re-pin against REAL KeyboardData across all three modes. */
    @Test
    fun oracle_geo_router_realLayouts() {
        val dvorak = loadLayout("latn_dvorak")
        val qwerty = loadLayout("latn_qwerty_us")
        val hybrid = SwipeEngineRouter.Mode.HYBRID
        val neural = SwipeEngineRouter.Mode.NEURAL
        val geometric = SwipeEngineRouter.Mode.GEOMETRIC
        assertEquals(SwipeEngineRouter.Engine.GEOMETRIC, SwipeEngineRouter.route(dvorak, hybrid))
        assertEquals(SwipeEngineRouter.Engine.NONE, SwipeEngineRouter.route(dvorak, neural))
        assertEquals(SwipeEngineRouter.Engine.GEOMETRIC, SwipeEngineRouter.route(dvorak, geometric))
        assertEquals(SwipeEngineRouter.Engine.NEURAL, SwipeEngineRouter.route(qwerty, hybrid))
        assertEquals(SwipeEngineRouter.Engine.NEURAL, SwipeEngineRouter.route(qwerty, neural))
        assertEquals(SwipeEngineRouter.Engine.GEOMETRIC, SwipeEngineRouter.route(qwerty, geometric))
    }

    /** NEW-behavior pin: a Dvorak swipe decodes usable suggestions from the real engine. */
    @Test
    fun oracle_geo_dvorakSwipe_yieldsTargetWord() {
        val kd = loadLayout("latn_dvorak")
        val params = paramsFor(kd)
        val adapter = GeometricEngineAdapter(context)
        try {
            val result = decodeBlocking(adapter, kd, params, "world")
            assertTrue(
                "'world' must be in the top-3 geo predictions. Got: ${result.words.take(5)}",
                result.words.take(3).any { it.equals("world", ignoreCase = true) }
            )
            // Engine-relative scores are softmax×1000 and sorted descending.
            assertEquals(result.words.size, result.scores.size)
        } finally {
            adapter.shutdown()
        }
    }

    /** Parity pin: real geo results ride the unified SH seam — top word commits with
     *  trailing space, NEURAL_SWIPE tracking, bar re-displayed (same as neural). */
    @Test
    fun oracle_geo_resultsThroughSeam_commitAndBar() {
        val kd = loadLayout("latn_dvorak")
        val params = paramsFor(kd)
        val adapter = GeometricEngineAdapter(context)
        try {
            val result = decodeBlocking(adapter, kd, params, "world")
            assumeTrue("need non-empty geo predictions", result.words.isNotEmpty())
            val h = harness()
            onMain {
                h.contextTracker.setWasLastInputSwipe(true)
                h.inputCoordinator.handlePredictionResults(
                    result.words, result.scores, h.inputConnection, textEditor(), h.resources
                )
            }
            drainMainThread()

            val top = h.suggestionBar.getCurrentSuggestions().firstOrNull() ?: result.words.first()
            assertEquals("$top ", h.inputConnection.bufferText())
            assertEquals(PredictionSource.NEURAL_SWIPE, h.contextTracker.getLastCommitSource())
            assertTrue(h.suggestionBar.getCurrentSuggestions().isNotEmpty())
        } finally {
            adapter.shutdown()
        }
    }

    /** Parity pin (D2 on the geo path): password field suppresses geo swipe results. */
    @Test
    fun oracle_geo_passwordField_suppressed() {
        val kd = loadLayout("latn_dvorak")
        val params = paramsFor(kd)
        val adapter = GeometricEngineAdapter(context)
        try {
            val result = decodeBlocking(adapter, kd, params, "world")
            assumeTrue("need non-empty geo predictions", result.words.isNotEmpty())
            val h = harness()
            h.config.swipe_on_password_fields = false
            onMain {
                h.contextTracker.setWasLastInputSwipe(true)
                h.inputCoordinator.handlePredictionResults(
                    result.words, result.scores, h.inputConnection, passwordEditor(), h.resources
                )
            }
            drainMainThread()

            assertEquals("", h.inputConnection.bufferText())
            assertFalse(h.suggestionBar.getCurrentSuggestions().any { w ->
                result.words.any { it.equals(w, ignoreCase = true) }
            })
        } finally {
            adapter.shutdown()
        }
    }

    /** Parity pin (shift transform on the geo path): shift-at-swipe-start capitalizes. */
    @Test
    fun oracle_geo_shiftAtStart_capitalizesCommit() {
        val kd = loadLayout("latn_dvorak")
        val params = paramsFor(kd)
        val adapter = GeometricEngineAdapter(context)
        try {
            val result = decodeBlocking(adapter, kd, params, "world")
            assumeTrue("need non-empty geo predictions", result.words.isNotEmpty())
            val h = harness()
            onMain {
                h.contextTracker.setWasLastInputSwipe(true)
                h.inputCoordinator.handlePredictionResults(
                    result.words, result.scores, h.inputConnection, textEditor(), h.resources,
                    shiftActive = true, shiftLocked = false
                )
            }
            drainMainThread()

            val buffer = h.inputConnection.bufferText()
            assertTrue(
                "shift+geo-swipe must commit a capitalized word. Got: '$buffer'",
                buffer.isNotEmpty() && buffer.first().isUpperCase()
            )
        } finally {
            adapter.shutdown()
        }
    }

    /** Perf gate: p95 warm adapter+engine round-trip must stay far under budget. */
    @Test
    fun oracle_geo_perf_p95WarmDecodeUnderBudget() {
        val kd = loadLayout("latn_dvorak")
        val params = paramsFor(kd)
        val adapter = GeometricEngineAdapter(context)
        try {
            // Cold pass absorbs the one-time Tier-A index + dictionary build.
            decodeBlocking(adapter, kd, params, "world")

            val words = listOf("world", "there", "found", "storm", "pride")
            val samples = ArrayList<Long>(20)
            repeat(20) { i ->
                val word = words[i % words.size]
                val startNs = System.nanoTime()
                decodeBlocking(adapter, kd, params, word)
                samples.add((System.nanoTime() - startNs) / 1_000_000)
            }
            samples.sort()
            val p95 = samples[18] // 19th of 20
            assertTrue(
                "p95 warm geo decode must be < 150 ms (got ${p95}ms, samples=$samples)",
                p95 < 150
            )
        } finally {
            adapter.shutdown()
        }
    }
}
