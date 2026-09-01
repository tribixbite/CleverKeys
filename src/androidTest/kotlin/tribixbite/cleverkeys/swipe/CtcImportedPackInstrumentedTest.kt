package tribixbite.cleverkeys.swipe

import android.content.Context
import android.graphics.PointF
import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.ContractionCollisionScanner
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.PredictionResult
import tribixbite.cleverkeys.TestConfigHelper
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.langpack.ImportResult
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.swipe.ctc.CtcImportedPackSupport
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The imported-language-pack path, end to end on a device: build a real language pack, import it
 * through the shipping [LanguagePackManager], and check that the CTC engine picks it up — then
 * remove it and check that it lets go.
 *
 * Everything about the POLICY is already pinned in `runPureTests`
 * (`CtcImportedPackSupportTest`). What only a device can answer is the plumbing between the pack
 * on disk and the engine:
 *
 *  1. the imported file lands where [CtcLanguageSupport.langpackRelativePath] says it does;
 *  2. [CtcInstalledPacks] can read and measure it out of the real `filesDir`, and its verdict
 *     reaches the STATIC gate `CtcEngineAdapter.supportsLanguage` that
 *     `InputCoordinator.performCtcSwipeTyping` consults before every swipe;
 *  3. the production merge path really builds a trie from it (an eligible pack that produces no
 *     trie would leave the suggestion bar empty — the failure mode every gate here exists to
 *     prevent);
 *  4. deleting the pack unserves the language again, in the same process.
 *
 * ## And the collision-warning path, which has never been reachable on an emulator
 *
 * `ContractionCollisionScanner`'s pack branch — the one that raises the warning dialog — needs an
 * imported pack contributing a contraction that rewrites a real word of another ACTIVE language.
 * No emulator has ever had a pack installed, so that branch has never executed anywhere but in
 * unit tests with hand-built inputs. `theCollisionScanSeesAnImportedPacksContractions` builds
 * exactly that situation from a fixture pack, which makes the dialog's precondition testable in
 * CI for the first time. (The DIALOG itself is Compose and still needs the maintainer's device;
 * what is closed here is everything up to `report.hasPackCollisions`.)
 *
 * The fixture language is `zz` — unassigned in ISO 639-1, so it can never collide with a real
 * pack the maintainer has installed, and `@After` removes it either way.
 */
@RunWith(AndroidJUnit4::class)
class CtcImportedPackInstrumentedTest {

    private lateinit var context: Context

    /** Unassigned ISO 639-1 code: safe to create and destroy on a real device. */
    private val code = "zz"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        removeFixturePack()
        // Production does this from CleverKeysService/SettingsActivity; an instrumented test
        // reaching the adapter directly is the third bind site.
        CtcInstalledPacks.bind(context)
        assertTrue(
            "Config must initialize before ARC-064 decode coverage",
            TestConfigHelper.ensureConfigInitialized(context)
        )
    }

    @After
    fun tearDown() {
        removeFixturePack()
    }

    private fun removeFixturePack() {
        LanguagePackManager.getInstance(context).deletePack(code)
        CtcInstalledPacks.invalidate(context, code)
        File(context.cacheDir, "langpack-$code.zip").delete()
    }

    // ── The fixture pack ──────────────────────────────────────────────────────────────

    /**
     * A CKDT v2 canonical section, written by hand so the test does not depend on the ML-side
     * packaging tools. Layout per [tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader]'s
     * KDoc: a 48-byte little-endian header, then `length uint16 · UTF-8 · rank uint8` per word.
     *
     * Ranks ascend with the word order, so [words] is also the frequency order the reader
     * returns — which is what [CtcImportedPackSupport]'s head check reads.
     */
    private fun ckdtBytes(language: String, words: List<String>): ByteArray {
        val encoded = words.map { it.toByteArray(Charsets.UTF_8) }
        val size = 48 + encoded.sumOf { 2 + it.size + 1 }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x54444B43)              // "CKDT"
        buf.putInt(2)                       // version
        val lang = ByteArray(4)
        language.toByteArray(Charsets.UTF_8).copyInto(lang, 0, 0, minOf(4, language.length))
        buf.put(lang)
        buf.putInt(words.size)
        buf.putInt(48)                      // canonicalOffset
        buf.putInt(0)                       // normalizedOffset — unread by both engines
        buf.putInt(0)                       // accentMapOffset — unread by both engines
        while (buf.position() < 48) buf.put(0)
        for ((i, w) in encoded.withIndex()) {
            buf.putShort(w.size.toShort())
            buf.put(w)
            buf.put((i * 254 / encoded.size).toByte())
        }
        return buf.array()
    }

    /** A distinct a–z word per index — digits have no a–z projection and would skew the check. */
    private fun azWord(i: Int): String {
        val sb = StringBuilder("z")
        var n = i
        repeat(4) {
            sb.append('a' + (n % 26))
            n /= 26
        }
        return sb.toString()
    }

    /**
     * Writes a valid language pack zip to the cache dir and imports it through the shipping
     * importer, returning the manifest on success.
     *
     * @param contractions optional `contractions.json` body — the pack's REPLACE mappings.
     */
    private fun importFixturePack(
        words: List<String> = List(1_200) { azWord(it) },
        contractions: String? = null,
    ): ImportResult {
        val zip = File(context.cacheDir, "langpack-$code.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(
                """{"code":"$code","name":"Fixture","version":1,"wordCount":${words.size}}"""
                    .toByteArray(Charsets.UTF_8)
            )
            out.closeEntry()
            out.putNextEntry(ZipEntry("dictionary.bin"))
            out.write(ckdtBytes(code, words))
            out.closeEntry()
            if (contractions != null) {
                out.putNextEntry(ZipEntry("contractions.json"))
                out.write(contractions.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return LanguagePackManager.getInstance(context).importLanguagePack(Uri.fromFile(zip))
    }

    private fun importNamedPack(language: String, words: List<String>): ImportResult {
        val zip = File(context.cacheDir, "langpack-" + language + ".zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            val manifest = JSONObject().apply {
                put("code", language)
                put("name", "ARC-058 fixture")
                put("version", 1)
                put("wordCount", words.size)
            }
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(manifest.toString().toByteArray(Charsets.UTF_8))
            out.closeEntry()
            out.putNextEntry(ZipEntry("dictionary.bin"))
            out.write(ckdtBytes(language, words))
            out.closeEntry()
        }
        return LanguagePackManager.getInstance(context).importLanguagePack(Uri.fromFile(zip))
    }

    private fun russianWord(index: Int): String {
        val alphabet = "абвгдежзийклмнопрстуфхцчшщыьэюя"
        var value = index
        val out = StringBuilder("п")
        repeat(4) {
            out.append(alphabet[value % alphabet.length])
            value /= alphabet.length
        }
        return out.toString()
    }

    private fun settledPssKb(): Long {
        repeat(2) {
            Runtime.getRuntime().gc()
            Thread.sleep(120L)
        }
        return Debug.getPss()
    }

    private fun loadLayout(name: String): KeyboardData {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        assertTrue("layout resource missing: " + name, id != 0)
        return KeyboardData.load(context.resources, id)
            ?: throw AssertionError("layout failed to parse: " + name)
    }

    private fun paramsFor(kd: KeyboardData) = KeyboardGeometry.Params(
        keyWidth = 1080f / kd.keysWidth,
        rowHeight = 640f / kd.keysHeight,
        marginTop = 0f,
        marginLeft = 0f,
    )

    private fun traceFor(
        kd: KeyboardData,
        params: KeyboardGeometry.Params,
        word: String,
    ): Pair<List<PointF>, List<Long>> {
        val rects = KeyboardGeometry.computeKeyRects(kd, params)
        val centers = word.map { wanted ->
            val rect = rects.firstOrNull {
                it.kv.getKind() == KeyValue.Kind.Char &&
                    it.kv.getChar().lowercaseChar() == wanted
            } ?: throw AssertionError("layout has no centre key for " + wanted)
            PointF(
                (rect.bounds.left + rect.bounds.right) / 2f,
                (rect.bounds.top + rect.bounds.bottom) / 2f,
            )
        }
        val points = ArrayList<PointF>()
        val times = ArrayList<Long>()
        var time = 0L
        for (index in 0 until centers.lastIndex) {
            val start = centers[index]
            val end = centers[index + 1]
            repeat(12) { step ->
                val fraction = step / 12f
                points.add(PointF(
                    start.x + (end.x - start.x) * fraction,
                    start.y + (end.y - start.y) * fraction,
                ))
                times.add(time)
                time += 16L
            }
        }
        points.add(centers.last())
        times.add(time)
        return points to times
    }

    private fun decodeBlocking(
        adapter: CtcEngineAdapter,
        kd: KeyboardData,
        word: String,
        language: String,
        secondaryLanguage: String? = null,
    ): PredictionResult {
        val params = paramsFor(kd)
        val (path, times) = traceFor(kd, params, word)
        val latch = CountDownLatch(1)
        var result: PredictionResult? = null
        var failed = false
        Handler(Looper.getMainLooper()).post {
            adapter.decodeAsync(
                keyboard = kd,
                params = params,
                frameWidthPx = 1080f,
                frameHeightPx = 640f,
                swipePath = path,
                timestamps = times,
                language = language,
                secondaryLanguage = secondaryLanguage,
                onDecodeFailure = { failed = true; latch.countDown() },
                onResult = { result = it; latch.countDown() },
            )
        }
        assertTrue("decode timed out", latch.await(60, TimeUnit.SECONDS))
        assertFalse("CTC handed the fixture trace to geometric fallback", failed)
        return result ?: throw AssertionError("decode completed without a result")
    }

    // ── 1–4: the pack reaches the engine, and lets go ─────────────────────────────────

    @Test
    fun anImportedLatinPackIsServedByCtcAndUnservedAgainWhenRemoved() {
        assertFalse(
            "the fixture language must not be served before its pack exists",
            CtcEngineAdapter.supportsLanguage(code)
        )
        assertNull(
            "no pack, no measurement",
            CtcInstalledPacks.evaluateNow(context, code)
        )

        val result = importFixturePack()
        assertTrue("import failed: $result", result is ImportResult.Success)

        // The file landed exactly where the pure table says the trie build will look for it.
        val expected = File(
            context.filesDir,
            CtcLanguageSupport.candidateLangpackRelativePath(code)!!
        )
        assertTrue("pack dictionary not at ${expected.absolutePath}", expected.isFile)

        val report = CtcInstalledPacks.evaluateNow(context, code)
        assertNotNull("an installed pack must be measurable", report)
        assertEquals(
            "an all-a–z fixture must be eligible: $report",
            CtcImportedPackSupport.Verdict.ELIGIBLE,
            report!!.verdict
        )

        // THE gate: the static function InputCoordinator.performCtcSwipeTyping calls before it
        // ever constructs an adapter.
        assertTrue(
            "CtcEngineAdapter.supportsLanguage must see the measured pack",
            CtcEngineAdapter.supportsLanguage(code)
        )
        assertEquals(
            CtcLanguageSupport.LexiconSource.CKDT_LANGPACK,
            CtcLanguageSupport.sourceFor(code)
        )
        assertTrue(
            "an imported pack is provisional by construction",
            CtcLanguageSupport.isProvisional(code)
        )
        assertEquals(
            "an imported pack is CKDT v2 on the 255−rank scale, so it decodes at the CKDT λ",
            CtcScoringParams.presetFor("fr").lambda,
            CtcScoringParams.presetFor(code).lambda,
            0.0
        )

        val adapter = CtcEngineAdapter(context)
        try {
            assertTrue(
                "the langpack lexicon source must be present on disk",
                adapter.hasLexiconSource(code)
            )
            // The production merge path, not a re-implementation: an eligible pack that yields no
            // trie is exactly the empty-slate failure every gate here exists to prevent.
            val trie = adapter.trieFor(code)
            assertNotNull("the imported pack must build a CTC trie", trie)
            assertTrue(
                "expected the fixture's 1,200 words in the trie, got ${trie!!.wordCount}",
                trie.wordCount >= 1_200
            )

            // …and the uninstall direction, in the same process: a language served only by a pack
            // must stop being served the moment the pack goes, or the dispatcher routes a swipe to
            // an engine with no lexicon.
            assertTrue(LanguagePackManager.getInstance(context).deletePack(code))
            assertFalse(
                "removing the pack must unserve the language",
                CtcEngineAdapter.supportsLanguage(code)
            )
            assertFalse(adapter.hasLexiconSource(code))
        } finally {
            adapter.shutdown()
        }
    }

    /**
     * A pack whose words have no a–z spelling must be refused even though it imports cleanly —
     * the projectability check is the script gate, and it runs on the device's real file.
     */
    @Test
    fun aNonProjectablePackImportsButIsNotServed() {
        val cyrillic = List(1_200) { "слово${azWord(it)}" }
        assertTrue(importFixturePack(words = cyrillic) is ImportResult.Success)

        val report = CtcInstalledPacks.evaluateNow(context, code)
        assertNotNull(report)
        assertEquals(
            "a Cyrillic word list projects onto a–z at 0%: $report",
            CtcImportedPackSupport.Verdict.NOT_AZ_PROJECTABLE,
            report!!.verdict
        )
        assertEquals(0, report.projectable)
        assertFalse(
            "an unswipeable pack must leave the language on the geometric engine",
            CtcEngineAdapter.supportsLanguage(code)
        )
    }

    /**
     * A reimport must be re-measured, not answered from the stored verdict: both the eligibility
     * cache and the adapter's trie content-hash key on the pack file's length+mtime.
     */
    @Test
    fun aReimportedPackIsMeasuredAgain() {
        assertTrue(importFixturePack() is ImportResult.Success)
        assertTrue(CtcInstalledPacks.evaluateNow(context, code)!!.eligible)
        assertTrue(CtcEngineAdapter.supportsLanguage(code))

        // Same language, different (unswipeable) content — the verdict must follow the FILE.
        assertTrue(
            importFixturePack(words = List(1_200) { "слово${azWord(it)}" }) is ImportResult.Success
        )
        val after = CtcInstalledPacks.evaluateNow(context, code)!!
        assertEquals(
            "the stale ELIGIBLE verdict must not survive a reimport: $after",
            CtcImportedPackSupport.Verdict.NOT_AZ_PROJECTABLE,
            after.verdict
        )
        assertFalse(CtcEngineAdapter.supportsLanguage(code))
    }

    // ── ARC-064: imported-pack dispatch and trie edges ────────────────────────────────

    @Test
    fun importedPackParticipatesInSecondaryLanguageDualDecode() {
        val target = "tambien"
        val words = listOf(target) + List(1_199) { azWord(it) }
        assertTrue(importFixturePack(words = words) is ImportResult.Success)
        assertTrue(CtcInstalledPacks.evaluateNow(context, code)!!.eligible)

        val keyboard = loadLayout("latn_qwerty_us")
        val adapter = CtcEngineAdapter(context)
        try {
            assertFalse("control: target must be absent from English", adapter.trieFor("en")!!.contains(target))
            assertTrue("target must come from the imported pack", adapter.trieFor(code)!!.contains(target))

            val primaryOnly = decodeBlocking(adapter, keyboard, target, "en")
            assertTrue("English control decode must be non-empty", primaryOnly.words.isNotEmpty())
            assertFalse("English control unexpectedly contains imported target", primaryOnly.words.contains(target))

            val dual = decodeBlocking(adapter, keyboard, target, "en", secondaryLanguage = code)
            assertTrue(
                "dual decode must surface the imported-pack target; slate=" + dual.words,
                dual.words.contains(target)
            )
            assertEquals(
                "secondary pack must not replace primary rank one",
                primaryOnly.words.first(),
                dual.words.first()
            )
        } finally {
            adapter.shutdown()
        }
    }

    @Test
    fun importedLatinPackIsRejectedOnANonLatinBoard() {
        assertTrue(importFixturePack() is ImportResult.Success)
        assertTrue(CtcInstalledPacks.evaluateNow(context, code)!!.eligible)
        val keyboard = loadLayout("cyrl_jcuken_ru")
        val params = paramsFor(keyboard)
        val adapter = CtcEngineAdapter(context)
        try {
            assertEquals(
                "the metadata router recognizes the routed Cyrillic script",
                SwipeEngineRouter.Engine.CTC,
                SwipeEngineRouter.route(keyboard, SwipeEngineRouter.Mode.CTC)
            )
            assertTrue("the imported pack itself is a served CTC language", CtcEngineAdapter.supportsLanguage(code))
            assertFalse(
                "dispatch-time alphabet gate must reject an a-z pack on a Cyrillic board",
                adapter.supportsLayout(keyboard, params, 1080f, 640f, code)
            )
        } finally {
            adapter.shutdown()
        }
    }

    @Test
    fun importedPackContractionAliasIsInjectedIntoTheCtcTrie() {
        val alias = "wouldve"
        assertTrue(
            importFixturePack(contractions = """{"wouldve":"would-ve"}""")
                is ImportResult.Success
        )
        assertTrue(CtcInstalledPacks.evaluateNow(context, code)!!.eligible)

        val adapter = CtcEngineAdapter(context)
        try {
            val trie = adapter.trieFor(code)!!
            assertTrue("fixture dictionary must not already contain the alias", alias !in List(1_200) { azWord(it) })
            assertTrue("pack contraction key must be reachable by the beam", trie.contains(alias))
            assertTrue(
                "injected pseudo-word must rank below a real pack word",
                trie.logFrequencyOf(alias)!! < trie.logFrequencyOf(azWord(0))!!
            )
        } finally {
            adapter.shutdown()
        }
    }

    // ── ARC-058: multi-script model + trie rotation memory ───────────────────────────

    @Test
    fun russianPrimaryThreeLanguageRotationKeepsMemoryAndMemosBounded() {
        val ruWords = listOf("привет") + List(1_199) { russianWord(it) }
        val manager = LanguagePackManager.getInstance(context)
        manager.deletePack("ru")
        try {
            val imported = importNamedPack("ru", ruWords)
            assertTrue("synthetic Russian pack failed: " + imported, imported is ImportResult.Success)
            CtcInstalledPacks.invalidate(context, "ru")

            val adapter = CtcEngineAdapter(context)
            try {
                val baselinePss = settledPssKb()
                val enTrie = adapter.trieFor("en")
                assertNotNull("English trie must build", enTrie)
                decodeBlocking(adapter, loadLayout("latn_qwerty_us"), "keyboard", "en")
                val latinPss = settledPssKb()
                assertEquals(
                    listOf(CtcEngineAdapter.modelAssetFor("en")),
                    adapter.liveModelAssetsForTest()
                )

                decodeBlocking(adapter, loadLayout("cyrl_jcuken_ru"), "привет", "ru")
                val russianPss = settledPssKb()
                assertEquals(
                    setOf(
                        CtcEngineAdapter.modelAssetFor("en"),
                        CtcEngineAdapter.modelAssetFor("ru")
                    ),
                    adapter.liveModelAssetsForTest().toSet()
                )
                assertEquals("two language tries must fill the memo", listOf("en", "ru"), adapter.trieLanguagesForTest())

                val ruTrie = adapter.trieFor("ru")
                assertNotNull("Russian trie must remain resident", ruTrie)
                assertNotNull("French trie must build for the third rotation leg", adapter.trieFor("fr"))
                assertEquals(
                    "third language must evict least-recent English, not grow the memo",
                    listOf("ru", "fr"),
                    adapter.trieLanguagesForTest()
                )
                assertNotSame(
                    "returning to English after a three-language rotation must rebuild the evicted trie",
                    enTrie,
                    adapter.trieFor("en")
                )
                val rotatedPss = settledPssKb()

                val secondSessionDelta = russianPss - latinPss
                val totalRotationDelta = rotatedPss - baselinePss
                Log.i(
                    "CtcRotationMemory",
                    "pssKb baseline=" + baselinePss + " latin=" + latinPss +
                        " russian=" + russianPss + " rotated=" + rotatedPss +
                        " secondSessionDelta=" + secondSessionDelta +
                        " totalDelta=" + totalRotationDelta
                )
                assertTrue(
                    "second ORT session retained more than 64 MiB: " + secondSessionDelta + " KiB",
                    secondSessionDelta < 64L * 1024L
                )
                assertTrue(
                    "bounded two-trie/two-session rotation retained more than 128 MiB: " +
                        totalRotationDelta + " KiB",
                    totalRotationDelta < 128L * 1024L
                )
            } finally {
                adapter.shutdown()
            }
        } finally {
            manager.deletePack("ru")
            CtcInstalledPacks.invalidate(context, "ru")
            File(context.cacheDir, "langpack-ru.zip").delete()
        }
    }

    // ── The collision-warning precondition ────────────────────────────────────────────

    /**
     * The pack branch of [ContractionCollisionScanner] — the dialog's precondition — reached on a
     * device for the first time.
     *
     * The fixture pack declares a REPLACE mapping `were → we're`. `were` is a real English word,
     * English is the other active language, and no shipped `contraction_collisions_zz.json` can
     * exist for an imported pack — which is precisely the case the scanner was written for and the
     * only one that raises the warning.
     */
    @Test
    fun theCollisionScanSeesAnImportedPacksContractions() {
        assertTrue(
            importFixturePack(contractions = """{"were":"we're"}""") is ImportResult.Success
        )

        val report = ContractionCollisionScanner.scan(context, setOf("en", code))
        assertTrue(
            "the imported pack's contraction rewrites a real English word, which is exactly " +
                "what the collision warning exists to announce — report=$report",
            report.hasPackCollisions
        )
        assertTrue(
            "expected 'were' among ${report.packCollisions.keys}",
            report.packCollisions.containsKey("were")
        )
        assertEquals(setOf("en"), report.packCollisions["were"])
        assertTrue(
            "the dialog renders examples; the scan must supply at least one",
            report.examples.isNotEmpty()
        )

        // Removing the pack removes the collision — the scan reads the live pack, not a cache.
        assertTrue(LanguagePackManager.getInstance(context).deletePack(code))
        assertFalse(ContractionCollisionScanner.scan(context, setOf("en", code)).hasPackCollisions)
    }
}
