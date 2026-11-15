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

/**
 * Review 2026-11-14 M14: [ContractionManager.loadTypingMappings] loads the primary language,
 * then (for an English-capable setup) the English base. Two defects in that merge, measured on
 * the shipped assets:
 *
 * 1. The English base's paired-base reclassification removed EVERY non-paired key that is also
 *    an English paired base — including the primary's own REPLACE entries: fr `men → m'en`
 *    (English pairs `men → men's`), `li → l'i`, `day → d'ay`, it `mama → m'ama`. They were gone
 *    before the cross-language demotion could move them to the paired map, so the elision was
 *    never offered.
 * 2. The English base was merged later-wins, so its accent-free forms replaced the primary's
 *    display forms: fr `detre → d'être` became `d'etre`, `detat → d'état` became `d'etat`.
 *
 * Real [ContractionManager] over the real asset files (an AssetManager mock that serves
 * `src/main/assets`); no language packs.
 */
class ContractionEnglishBaseMergeTest {

    private lateinit var manager: ContractionManager

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
        every { assets.open(any()) } answers { FileInputStream(File("src/main/assets", firstArg<String>())) }
        val context = mockk<Context>(relaxed = true)
        every { context.assets } returns assets
        every { context.applicationContext } returns context

        val packs = mockk<LanguagePackManager>(relaxed = true)
        every { packs.getContractionsPath(any()) } returns null
        mockkObject(LanguagePackManager.Companion)
        every { LanguagePackManager.getInstance(any()) } returns packs

        manager = ContractionManager(context)
    }

    @After
    fun teardown() = unmockkAll()

    /** Is [form] offered for [key], as a replacement or as a paired variant? */
    private fun offers(key: String, form: String): Boolean =
        manager.getNonPairedMapping(key) == form || manager.getPairedContractions(key)?.contains(form) == true

    @Test
    fun aFrenchElisionWhoseKeyIsAnEnglishPairedBaseSurvives() {
        manager.loadTypingMappings("fr", "en")

        for ((key, elision) in listOf("men" to "m'en", "li" to "l'i", "day" to "d'ay")) {
            assertWithMessage("fr+en: `$key` must still offer `$elision`").that(offers(key, elision)).isTrue()
        }
        assertWithMessage("the English possessive stays too").that(offers("men", "men's")).isTrue()
        assertWithMessage("`men` is an English word: offered beside, never replaced")
            .that(manager.getNonPairedMapping("men")).isNull()
    }

    @Test
    fun theEnglishBaseNeverOverwritesThePrimarysAccentedDisplayForm() {
        manager.loadTypingMappings("fr", "en")

        // `detre` is also listed as an English lexicon word (contraction_collisions_fr.json), so
        // the cross-language demotion offers the elision beside the word instead of replacing
        // it; either way the French display form is the one offered, never the English `d'etre`.
        for ((key, french, english) in listOf(Triple("detre", "d'être", "d'etre"), Triple("detat", "d'état", "d'etat"))) {
            assertWithMessage("$key offers $french").that(offers(key, french)).isTrue()
            assertWithMessage("$key never offers $english").that(offers(key, english)).isFalse()
        }
    }

    @Test
    fun englishAloneStillGetsItsOwnReplacementsAndPairs() {
        // Control: an English-only setup is unchanged by the merge rule.
        manager.loadTypingMappings("en", null)

        assertWithMessage("dont").that(manager.getNonPairedMapping("dont")).isEqualTo("don't")
        assertWithMessage("well is a paired base, never a replacement")
            .that(manager.getNonPairedMapping("well")).isNull()
        assertWithMessage("well offers we'll").that(offers("well", "we'll")).isTrue()
    }
}
