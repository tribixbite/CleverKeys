package tribixbite.cleverkeys

import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.swipe.geometric.GeoLayoutFixtures
import tribixbite.cleverkeys.swipe.geometric.LayoutProjection

/**
 * Maintainer decisions of 2026-10-25 for the two Persian-letter layouts:
 *
 * 1. The hamza letters أ إ ؤ (172 words of `langpack-fa.zip` use them; Persian PC had no key for
 *    them) are corner/edge gestures: أ and إ on ا, ؤ on و. Every existing gesture stays.
 *    - `arab_pc_ir.xml`: all four corners of ا are taken (آ - _ ء), so أ is swipe-up (key7) and
 *      إ swipe-down (key8), hamza above / below; ؤ on the top-right of و (key2), where ی has ئ.
 *    - `arab_pc_ckb_fa.xml`: أ top-left (key1) and إ bottom-left (key3) of ا, as `arab_pc.xml`
 *      (أ key1) and `arab_hamvaj_tly.xml` (إ key3) place them; ؤ top-left of و.
 *    The geometric engine treats each one as its host key ([LayoutProjection] tier 4), so a word
 *    containing them swipes over ا / و.
 * 2. Persian PC declares `language="fa"` (the per-layout language binding, GH #186/#61); the
 *    Kurdish board, which is Central Kurdish, declares nothing. Layout Manager now reads a
 *    built-in layout's declaration ([DeclaredLayoutLanguages]), so the chip shows it and
 *    "Follow Multi-Language settings" stores an explicit unbind.
 *
 * Mock tier: `android.util.Xml` is a stub off-device, so kxml2 is substituted and the shipped
 * XML goes through the real production parser ([KeyboardData.load_string_exn]).
 */
class PersianLayoutHamzaAndLanguageTest {

    private companion object {
        val LAYOUT_DIR = File("src/main/layouts")
        const val PERSIAN_PC = "arab_pc_ir"
        const val KURDISH_PERSIAN = "arab_pc_ckb_fa"

        // Slots in this layout notation (KeyboardData.Key.parse): 1 nw, 2 ne, 3 sw, 4 se,
        // 5 w, 6 e, 7 n (swipe up), 8 s (swipe down).
        const val NW = 1
        const val NE = 2
        const val SW = 3
        const val SE = 4
        const val N = 7
        const val S = 8

        const val ALEF = "ا"
        const val WAW = "و"
        const val HAMZA_LETTERS = "أإؤ"
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
    }

    @After
    fun teardown() = unmockkAll()

    private fun parse(id: String): KeyboardData =
        KeyboardData.load_string_exn(File(LAYOUT_DIR, "$id.xml").readText())

    private fun kv(name: String): KeyValue = KeyValue.getKeyByName(name)

    private fun keyOf(kw: KeyboardData, center: String): KeyboardData.Key =
        kw.rows.flatMap { it.keys }.single { it.getKeyValue(0) == kv(center) }

    /** Slot → value of a key, for "nothing else changed" checks. */
    private fun slots(k: KeyboardData.Key): Map<Int, KeyValue?> = (1..8).associateWith { k.getKeyValue(it) }

    // ── 1. hamza letters ─────────────────────────────────────────────────────────────

    @Test
    fun persianPcHasTheHamzaLettersOnFreeEdgesAndKeepsEveryCorner() {
        val kw = parse(PERSIAN_PC)
        val alef = slots(keyOf(kw, ALEF))
        assertThat(alef[NW]).isEqualTo(kv("آ"))
        assertThat(alef[NE]).isEqualTo(kv("-"))
        assertThat(alef[SW]).isEqualTo(kv("_"))
        assertThat(alef[SE]).isEqualTo(kv("ء"))
        assertWithMessage("أ (hamza above) on swipe-up of ا").that(alef[N]).isEqualTo(kv("أ"))
        assertWithMessage("إ (hamza below) on swipe-down of ا").that(alef[S]).isEqualTo(kv("إ"))

        val waw = slots(keyOf(kw, WAW))
        assertWithMessage("ؤ on the top-right of و, mirroring ئ on ی").that(waw[NE]).isEqualTo(kv("ؤ"))
        assertThat(slots(keyOf(kw, "ی"))[NE]).isEqualTo(kv("ئ"))
        assertWithMessage("و gained nothing else").that(waw.filterKeys { it != NE }.values.all { it == null }).isTrue()
    }

    @Test
    fun theKurdishPersianBoardHasTheHamzaLettersOnFreeCornersAndKeepsItsEdges() {
        val kw = parse(KURDISH_PERSIAN)
        val alef = slots(keyOf(kw, ALEF))
        assertThat(alef[N]).isEqualTo(kv("٦"))
        assertThat(alef[S]).isEqualTo(kv("آ"))
        assertWithMessage("أ top-left of ا, as arab_pc.xml").that(alef[NW]).isEqualTo(kv("أ"))
        assertWithMessage("إ bottom-left of ا, as arab_hamvaj_tly.xml").that(alef[SW]).isEqualTo(kv("إ"))

        val waw = slots(keyOf(kw, WAW))
        assertThat(waw[N]).isEqualTo(kv("ۉ"))
        assertWithMessage("ؤ top-left of و").that(waw[NW]).isEqualTo(kv("ؤ"))
    }

    @Test
    fun eachHamzaLetterAppearsExactlyOnceOnEachLayout() {
        for (id in listOf(PERSIAN_PC, KURDISH_PERSIAN)) {
            val all = parse(id).rows.flatMap { it.keys }.flatMap { k -> (0..8).mapNotNull { k.getKeyValue(it) } }
            for (letter in HAMZA_LETTERS) {
                assertWithMessage("$id: '$letter' count").that(all.count { it == kv(letter.toString()) }).isEqualTo(1)
            }
        }
    }

    @Test
    fun theGeometricProjectionSwipesEachHamzaLetterOverItsHostKey() {
        for (id in listOf(PERSIAN_PC, KURDISH_PERSIAN)) {
            val layout = GeoLayoutFixtures.load(
                File(LAYOUT_DIR, "$id.xml"),
                includeLocCorners = true, appendBottomRow = false, aspect = GeoLayoutFixtures.DEFAULT_ASPECT,
            )
            fun center(letter: String): Int = layout.chars.getValue(letter.codePointAt(0)).single()
            val alef = center(ALEF)
            val waw = center(WAW)
            assertWithMessage("$id: أ → ا").that(LayoutProjection.projectCodepoint('أ'.code, layout)).isEqualTo(alef)
            assertWithMessage("$id: إ → ا").that(LayoutProjection.projectCodepoint('إ'.code, layout)).isEqualTo(alef)
            assertWithMessage("$id: ؤ → و").that(LayoutProjection.projectCodepoint('ؤ'.code, layout)).isEqualTo(waw)
            // Pack words with hamza letters (they were the 172 untypeable on Persian PC).
            assertWithMessage("$id: مسؤول is typeable").that(LayoutProjection.project("مسؤول", layout)).isNotNull()
            assertThat(LayoutProjection.project("أحمد", layout)!!.toList())
                .isEqualTo(LayoutProjection.project("احمد", layout)!!.toList())
            assertThat(LayoutProjection.project("إسلام", layout)!!.toList())
                .isEqualTo(LayoutProjection.project("اسلام", layout)!!.toList())
        }
    }

    // ── 2. layout language ───────────────────────────────────────────────────────────

    @Test
    fun persianPcDeclaresFaAndTheKurdishBoardDeclaresNothing() {
        val declared = parse(PERSIAN_PC).declared_language
        assertThat(declared).isEqualTo("fa")
        assertThat(LayoutLanguageBinding.xmlLanguageProblem(declared)).isFalse()
        assertWithMessage("Central Kurdish board: no fa binding (ckb is a separate decision)")
            .that(parse(KURDISH_PERSIAN).declared_language).isNull()
    }

    @Test
    fun theDeclarationBindsPersianOnlyWhileTheUserHasNotOverriddenIt() {
        val declared = parse(PERSIAN_PC).declared_language
        assertThat(LayoutLanguageBinding.effective(null, declared)).isEqualTo("fa")
        assertWithMessage("explicit unbind wins over the XML default")
            .that(LayoutLanguageBinding.effective(LayoutLanguageBinding.UNBOUND, declared)).isNull()
        assertThat(LayoutLanguageBinding.effective("ar", declared)).isEqualTo("ar")

        // On the bound layout fa is the single active language; leaving it restores the user's.
        val bindings = listOf(null, LayoutLanguageBinding.effective(null, declared))
        val onPersian = LayoutLanguageBinding.resolve(bindings, 1, "en", true, "es")
        assertThat(onPersian).isEqualTo(ActiveLanguages("fa", null, "fa"))
        val onLatin = LayoutLanguageBinding.resolve(bindings, 0, "en", true, "es")
        assertThat(onLatin).isEqualTo(ActiveLanguages("en", "es", null))
    }

    @Test
    fun layoutManagerReadsTheBuiltInDeclarationOncePerLayout() {
        val parsedNames = ArrayList<String>()
        val lookup = DeclaredLayoutLanguages { name -> parsedNames.add(name); parse(name) }
        val persian = LayoutsPreference.NamedLayout(PERSIAN_PC)

        assertThat(lookup.of(persian)).isEqualTo("fa")
        assertThat(lookup.bindingOf(persian)).isEqualTo("fa")
        assertThat(lookup.of(LayoutsPreference.NamedLayout(KURDISH_PERSIAN))).isNull()
        assertThat(lookup.bindingOf(LayoutsPreference.NamedLayout(KURDISH_PERSIAN))).isNull()
        assertThat(lookup.bindingOf(persian.withLanguage(LayoutLanguageBinding.UNBOUND))).isNull()
        assertWithMessage("each built-in is parsed once, a null declaration included")
            .that(parsedNames).containsExactly(PERSIAN_PC, KURDISH_PERSIAN).inOrder()

        // Choosing "Follow Multi-Language settings" over the XML default must store an explicit
        // unbind; choosing Persian again stores nothing (the entry keeps following the XML).
        assertThat(LayoutLanguageBinding.entryValueFor(null, lookup.of(persian))).isEqualTo(LayoutLanguageBinding.UNBOUND)
        assertThat(LayoutLanguageBinding.entryValueFor("fa", lookup.of(persian))).isNull()
    }

    @Test
    fun aBuiltInThatFailsToLoadDeclaresNothing() {
        val lookup = DeclaredLayoutLanguages { error("resource missing") }
        assertThat(lookup.of(LayoutsPreference.NamedLayout("gone"))).isNull()
        val nullLookup = DeclaredLayoutLanguages { null }
        assertThat(nullLookup.bindingOf(LayoutsPreference.NamedLayout("gone"))).isNull()
        assertThat(nullLookup.of(LayoutsPreference.SystemLayout())).isNull()
    }
}
