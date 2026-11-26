package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.langpack.LanguagePackManager
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 2026-11-26 (debug-build memory/log analysis, `build/nov26c-saga/logcat-langchange-en.txt`): one
 * primary-language change read and parsed the English contraction base (`contractions.bin` +
 * `contraction_pairings.json`) once per [ContractionManager] that wanted English — the typing
 * manager, the CTC adapter's `en` manager, the static LM's alias manager — and once more per
 * retired keyboard service still listening to preference changes (six reads in 45 ms on the Saga).
 *
 * The English base is fixed for the life of the process (it ships in the APK; an update restarts
 * the process), so it is parsed once ([EnglishContractionBase]) and every manager copies the
 * parsed tables. This test counts the asset opens across the three managers one primary change
 * builds, over the real shipped assets, and checks that the managers still hold the same mappings
 * as before (a shared parse must not change what any manager serves).
 */
class ContractionEnglishBaseLoadCountTest {

    private val opens = ConcurrentHashMap<String, AtomicInteger>()
    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        val assets = mockk<AssetManager>()
        every { assets.open(any()) } answers {
            val name = firstArg<String>()
            opens.getOrPut(name) { AtomicInteger() }.incrementAndGet()
            FileInputStream(File("src/main/assets", name))
        }
        context = mockk(relaxed = true)
        every { context.assets } returns assets
        every { context.applicationContext } returns context

        val packs = mockk<LanguagePackManager>(relaxed = true)
        every { packs.getContractionsPath(any()) } returns null
        mockkObject(LanguagePackManager.Companion)
        every { LanguagePackManager.getInstance(any()) } returns packs

        resetEnglishBaseMemo()
    }

    @After
    fun teardown() {
        resetEnglishBaseMemo()
        unmockkAll()
    }

    /**
     * Clears the process-wide parse through reflection, so this class also RUNS (and fails on its
     * counts) against a build that has no memo yet — that is the fail-first evidence.
     */
    private fun resetEnglishBaseMemo() {
        val memo = runCatching { Class.forName("tribixbite.cleverkeys.EnglishContractionBase") }.getOrNull() ?: return
        val instance = memo.getField("INSTANCE").get(null)
        memo.getMethod("clearForTest").invoke(instance)
    }

    private fun opened(name: String): Int = opens[name]?.get() ?: 0

    /** What one primary change to English builds: the typing, CTC-swipe and LM-alias managers. */
    private fun onePrimaryChangeToEnglish(): List<ContractionManager> {
        val typing = ContractionManager(context).apply { loadTypingMappings("en", null) }
        val swipe = ContractionManager(context).apply { loadSwipeDisplayMappings("en") }
        val lmAliases = ContractionManager(context).apply { loadSwipeDisplayMappings("en") }
        return listOf(typing, swipe, lmAliases)
    }

    @Test
    fun onePrimaryChangeReadsTheEnglishBaseOnce() {
        onePrimaryChangeToEnglish()

        assertWithMessage("contractions.bin opens for one primary change")
            .that(opened("dictionaries/contractions.bin")).isEqualTo(1)
        assertWithMessage("contraction_pairings.json opens for one primary change")
            .that(opened("dictionaries/contraction_pairings.json")).isEqualTo(1)
    }

    @Test
    fun aLaterChangeReusesTheParseOfTheFirst() {
        onePrimaryChangeToEnglish()
        // Away to German and back to English: a second English load in the same process.
        ContractionManager(context).loadTypingMappings("de", null)
        onePrimaryChangeToEnglish()

        assertWithMessage("contractions.bin opens for two changes to English")
            .that(opened("dictionaries/contractions.bin")).isEqualTo(1)
        assertWithMessage("contraction_pairings.json opens for two changes to English")
            .that(opened("dictionaries/contraction_pairings.json")).isEqualTo(1)
    }

    @Test
    fun sharedParseServesTheSameMappingsAsAFreshOne() {
        val (typing, swipe, _) = onePrimaryChangeToEnglish()
        // The second load of each kind comes from the memo; it must equal the first.
        val typingAgain = ContractionManager(context).apply { loadTypingMappings("en", null) }
        val swipeAgain = ContractionManager(context).apply { loadSwipeDisplayMappings("en") }

        for ((first, second) in listOf(typing to typingAgain, swipe to swipeAgain)) {
            assertWithMessage("known count").that(second.getTotalKnownCount()).isEqualTo(first.getTotalKnownCount())
            for (key in listOf("dont", "cant", "wholl", "well", "were", "its", "id", "ill", "shed")) {
                assertWithMessage("non-paired $key").that(second.getNonPairedMapping(key)).isEqualTo(first.getNonPairedMapping(key))
                assertWithMessage("paired $key").that(second.getPairedContractions(key)).isEqualTo(first.getPairedContractions(key))
                for (variant in first.getPairedContractions(key).orEmpty()) {
                    assertWithMessage("frequency $key/$variant")
                        .that(second.getPairedVariantFrequency(key, variant))
                        .isEqualTo(first.getPairedVariantFrequency(key, variant))
                }
            }
        }
        // The published fixes still hold on the memoized path.
        assertWithMessage("well stays a paired base").that(typing.getNonPairedMapping("well")).isNull()
        assertWithMessage("dont maps").that(typingAgain.getNonPairedMapping("dont")).isEqualTo("don't")
        assertWithMessage("i'll listed once").that(typingAgain.getPairedContractions("ill")?.count { it == "i'll" }).isEqualTo(1)
    }

    @Test
    fun aManagerMayNotChangeTheSharedTables() {
        val first = ContractionManager(context).apply { loadTypingMappings("en", null) }
        // A German-primary manager with English second loads German first and the base after it;
        // the merge and the demotions it runs must work on its own copy.
        ContractionManager(context).loadTypingMappings("de", "en")
        val after = ContractionManager(context).apply { loadTypingMappings("en", null) }

        assertWithMessage("known count after another manager's merge")
            .that(after.getTotalKnownCount()).isEqualTo(first.getTotalKnownCount())
        assertWithMessage("im still maps for English alone").that(after.getNonPairedMapping("im")).isEqualTo(first.getNonPairedMapping("im"))
    }
}
