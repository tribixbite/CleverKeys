package tribixbite.cleverkeys.swipe

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.os.Handler
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.PredictionResult
import tribixbite.cleverkeys.PredictionTaskRunner
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Review 2026-11-14 (swipe, low items) — the adapter-level edges the pure seams cannot reach:
 *
 *  - an [Error] thrown inside a geometric decode must still answer the swipe (an empty slate),
 *    or a continuous phrase waiting for that word's acknowledgement stalls for good;
 *  - a trace with fewer timestamps than points is a degenerate swipe on BOTH adapters, not a
 *    decode with its tail stamped at t = 0;
 *  - `GeometricEngineAdapter.dictionaryFor` must rethrow a cancellation, as its CTC twin does,
 *    instead of treating it as "no dictionary" and dropping the language's memo slot.
 *
 * Both adapters build a `Handler` in a field initialiser, and under `runMockTests` the Handler
 * CONSTRUCTOR is an android.jar stub that throws (see `ImeTeardownExecutorShutdownTest`), and
 * the CTC adapter also opens an ORT environment whose natives do not load here. So the
 * instances are allocated with Objenesis and only the fields these paths touch are seeded; the
 * production method bodies run unchanged. A field the path reaches that was not seeded fails
 * loudly as an NPE naming it.
 */
class SwipeAdapterEdgeCaseTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val objenesis = ObjenesisStd()
    private val keyboard = mockk<KeyboardData>(relaxed = true)
    private val params = KeyboardGeometry.Params(keyWidth = 100f, rowHeight = 100f, marginTop = 0f, marginLeft = 0f)
    private val path = listOf(PointF(10f, 10f), PointF(50f, 10f), PointF(90f, 10f))

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkObject(KeyboardGeometry)
    }

    /** Every runner a test seeded, shut down afterwards so no worker thread outlives its test. */
    private val runners = ArrayList<PredictionTaskRunner>()

    private fun runner(): PredictionTaskRunner = PredictionTaskRunner().also { runners.add(it) }

    @After
    fun tearDown() {
        runners.forEach { it.shutdown() }
        unmockkAll()
        Thread.interrupted() // never leak a test's interrupt into the runner thread
    }

    // ── Geometric: an Error still answers the swipe ─────────────────────────────────────

    @Test
    fun `an Error thrown in a geometric decode still delivers an empty slate`() {
        every { KeyboardGeometry.computeKeyRects(any<KeyboardData>(), any()) } throws StackOverflowError("layout recursion")
        val adapter = geometricAdapter(runPostsInline = true)
        val delivered = AtomicReference<PredictionResult?>()
        val done = CountDownLatch(1)

        adapter.decodeAsync(keyboard, params, 1000f, 300f, path, listOf(0L, 10L, 20L), "en") {
            delivered.set(it); done.countDown()
        }

        assertWithMessage(
            "the swipe must be answered — a silent Error leaves a continuous phrase waiting forever"
        ).that(done.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(delivered.get()!!.words).isEmpty()
    }

    // ── Both adapters: a timestamp/point length mismatch is a degenerate swipe ──────────

    @Test
    fun `a geometric trace with missing timestamps is answered at once as no swipe`() {
        every { KeyboardGeometry.computeKeyRects(any<KeyboardData>(), any()) } returns emptyList()
        val adapter = geometricAdapter(runPostsInline = false)
        var delivered: PredictionResult? = null

        adapter.decodeAsync(keyboard, params, 1000f, 300f, path, listOf(0L), "en") { delivered = it }

        assertWithMessage("3 points / 1 timestamp must take the synchronous degenerate branch")
            .that(delivered).isNotNull()
        assertThat(delivered!!.words).isEmpty()
    }

    @Test
    fun `a CTC trace with missing timestamps is answered at once as no swipe`() {
        val adapter = objenesis.newInstance(CtcEngineAdapter::class.java).apply {
            setField("decodeGeneration", AtomicLong(0))
            // A runner whose work never matters: the degenerate branch must not reach it.
            setField("tasks", runner())
            setField("mainHandler", mockk<Handler>(relaxed = true))
        }
        var delivered: PredictionResult? = null

        adapter.decodeAsync(keyboard, params, 1000f, 300f, path, listOf(0L, 10L), "en") { delivered = it }

        assertWithMessage("3 points / 2 timestamps must take the synchronous degenerate branch")
            .that(delivered).isNotNull()
        assertThat(delivered!!.words).isEmpty()
    }

    // ── Geometric: dictionaryFor rethrows a cancellation ────────────────────────────────

    @Test
    fun `geometric dictionaryFor rethrows an interruption instead of reporting no dictionary`() {
        val filesDir = temp.newFolder("files")
        val pack = File(filesDir, "langpacks/fr/dictionary.bin")
        pack.parentFile!!.mkdirs()
        File("src/main/assets/dictionaries/fr_enhanced.bin").copyTo(pack)
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers { secondArg() }
        every { prefs.getStringSet(any(), any()) } returns HashSet()
        // @JvmStatic: mockkObject would record against the real static (see PrivacyManagerTest).
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs
        val adapter = geometricAdapter(runPostsInline = false, context = context)
        val dictionaryFor = GeometricEngineAdapter::class.java
            .getDeclaredMethod("dictionaryFor", String::class.java).apply { isAccessible = true }

        // Cancellation arrives as the worker's interrupt flag; the CKDT reader checks it.
        Thread.currentThread().interrupt()
        val thrown = try {
            dictionaryFor.invoke(adapter, "fr")
            null
        } catch (e: InvocationTargetException) {
            e.cause
        } finally {
            Thread.interrupted()
        }

        assertWithMessage(
            "a cancelled load is not a missing dictionary: it must propagate as the cancellation"
        ).that(thrown).isInstanceOf(InterruptedException::class.java)
    }

    // ── Harness ─────────────────────────────────────────────────────────────────────────

    private fun geometricAdapter(
        runPostsInline: Boolean,
        context: Context = mockk(relaxed = true),
    ): GeometricEngineAdapter {
        val handler = mockk<Handler>(relaxed = true)
        if (runPostsInline) {
            every { handler.post(any()) } answers { firstArg<Runnable>().run(); true }
        }
        val adapter = objenesis.newInstance(GeometricEngineAdapter::class.java)
        adapter.setField("context", context)
        adapter.setField("userDictionarySource", { _: String -> UserDictionarySnapshot.EMPTY })
        adapter.setField("tasks", runner())
        adapter.setField("mainHandler", handler)
        adapter.setField("decodeGeneration", AtomicLong(0))
        adapter.setField("dictionaryMemos", newInstanceOfFieldType(adapter, "dictionaryMemos"))
        return adapter
    }

    /**
     * Builds the field's own (anonymous LinkedHashMap) type, so its eviction override is the
     * production one rather than a plain map standing in for it.
     */
    private fun newInstanceOfFieldType(owner: Any, name: String): Any {
        val type = owner.javaClass.getDeclaredField(name).type
        val ctor = type.declaredConstructors.single().apply { isAccessible = true }
        val args = ctor.parameterTypes.map { p ->
            require(p.isInstance(owner)) { "unexpected constructor parameter $p on $type" }
            owner
        }
        return ctor.newInstance(*args.toTypedArray())
    }

    /** Write a private field declared on the receiver's own class (see ImeTeardownExecutorShutdownTest). */
    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName}; declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }
}
