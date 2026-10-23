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

/**
 * Pins the dedicated half-space (ZWNJ, U+200C) key on the Persian layouts
 * (docs/research/2026-10-21-persian-zwnj.md §1 and §7a).
 *
 * Every open-source Android keyboard that handles Persian (AOSP, OpenBoard, HeliBoard, FUTO) puts
 * the half-space on its own key right of the space bar, with ZWJ (U+200D) on a secondary gesture.
 * CleverKeys appends ONE shared bottom row (`res/xml/bottom_row.xml`) to every layout, so the key
 * cannot go there without reaching every language. Instead `arab_pc_ir.xml` ("Persian PC") and
 * `arab_pc_ckb_fa.xml` declare `bottom_row="false"` and carry their own copy of the bottom row with
 * the half-space key inserted after the space bar (the `latn_neo2.xml` pattern).
 *
 * Mock tier: `android.util.Xml` is a stub off-device, so kxml2 is substituted and every file goes
 * through the real production parser ([KeyboardData.load_string_exn]).
 *
 * What is pinned:
 * - the key sits immediately right of the space bar, types U+200C, and has ZWJ on swipe-up;
 * - apart from that key, the embedded row is the shared bottom row key for key (values and `loc`
 *   flags), so a later edit of `bottom_row.xml` that is not mirrored here fails;
 * - the embedded row is exactly as wide as the letter rows (no stretching or gap);
 * - the existing corner gestures (ت on Persian PC, ج on the Kurdish board) are kept;
 * - the shared bottom row and the Arabic `arab_pc.xml` gain no half-space key.
 */
class PersianHalfSpaceKeyTest {

    private companion object {
        val LAYOUT_DIR = File("src/main/layouts")
        val SHARED_BOTTOM_ROW = File("res/xml/bottom_row.xml")

        const val PERSIAN_PC = "arab_pc_ir"
        const val KURDISH_PERSIAN = "arab_pc_ckb_fa"
        val LAYOUTS = listOf(PERSIAN_PC, KURDISH_PERSIAN)

        const val ZWNJ = '‌'
        const val ZWJ = '‍'

        /** Key-value slots of a key: 0 = centre, 1..8 = corners and edges. */
        val SLOTS = 0..8

        /** Slot 7 is the swipe-up edge in this layout notation (bottom_row.xml: `key7="up"`). */
        const val SWIPE_UP = 7

        /** Both boards are 11 keys wide; the bottom-row widths are float sums, hence the tolerance. */
        const val LETTER_ROW_WIDTH = 11f
        const val WIDTH_TOLERANCE = 1e-4f
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

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private fun parse(id: String): KeyboardData =
        KeyboardData.load_string_exn(File(LAYOUT_DIR, "$id.xml").readText())

    /** The shared bottom row, parsed by the same parser inside a one-row keyboard. */
    private fun sharedBottomRow(): KeyboardData.Row {
        val rowXml = SHARED_BOTTOM_ROW.readText().replace(Regex("""<\?xml[^>]*\?>"""), "")
        return KeyboardData.load_string_exn("<keyboard bottom_row=\"false\">$rowXml</keyboard>").rows.single()
    }

    private fun key(name: String): KeyValue = KeyValue.getKeyByName(name)

    private fun isHalfSpace(k: KeyboardData.Key): Boolean = k.getKeyValue(0) == key("halfspace")

    /** A key's values and `loc` flags, slot by slot: what it does, independent of width. */
    private fun behaviour(k: KeyboardData.Key): List<Pair<KeyValue?, Boolean>> =
        SLOTS.map { i -> k.getKeyValue(i) to k.keyHasFlag(i, KeyboardData.Key.F_LOC) }

    // ── the key ──────────────────────────────────────────────────────────────────────

    @Test
    fun halfSpaceAndZwjKeyValuesTypeTheJoinerControls() {
        val halfSpace = key("halfspace")
        assertThat(halfSpace.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(halfSpace.getChar()).isEqualTo(ZWNJ)
        // `halfspace` and `zwnj` are one key value, so the a11y label and the command are shared.
        assertThat(halfSpace).isEqualTo(key("zwnj"))
        assertThat(key("zwj").getChar()).isEqualTo(ZWJ)
    }

    @Test
    fun eachPersianLayoutHasTheHalfSpaceKeyRightOfTheSpaceBar() {
        for (id in LAYOUTS) {
            val kw = parse(id)
            // Without bottom_row="false" LayoutModifier would append the shared bottom row under
            // the embedded one and the board would have two space bars.
            assertWithMessage("$id must disable the shared bottom row").that(kw.bottom_row).isFalse()
            val bottom = kw.rows.last().keys
            val space = bottom.indexOfFirst { it.getKeyValue(0) == key("space") }
            assertWithMessage("$id bottom row has a space bar").that(space).isAtLeast(0)
            val right = bottom.getOrNull(space + 1)
            assertWithMessage("$id: the key right of space").that(right).isNotNull()
            assertWithMessage("$id: the key right of space is the half-space")
                .that(isHalfSpace(right!!)).isTrue()
            assertWithMessage("$id: swipe up on the half-space key types ZWJ")
                .that(right.getKeyValue(SWIPE_UP)).isEqualTo(key("zwj"))
            assertWithMessage("$id has exactly one dedicated half-space key")
                .that(kw.rows.flatMap { it.keys }.count(::isHalfSpace)).isEqualTo(1)
        }
    }

    @Test
    fun theEmbeddedBottomRowIsTheSharedOneWithOnlyTheHalfSpaceKeyAdded() {
        val shared = sharedBottomRow().keys.map(::behaviour)
        for (id in LAYOUTS) {
            val embedded = parse(id).rows.last().keys.filterNot(::isHalfSpace).map(::behaviour)
            assertWithMessage("$id: keep the embedded bottom row in sync with res/xml/bottom_row.xml")
                .that(embedded).containsExactlyElementsIn(shared).inOrder()
        }
    }

    @Test
    fun theEmbeddedBottomRowIsAsWideAsTheLetterRows() {
        for (id in LAYOUTS) {
            val kw = parse(id)
            val bottom = kw.rows.last()
            assertWithMessage("$id bottom row width").that(bottom.keysWidth).isWithin(WIDTH_TOLERANCE).of(LETTER_ROW_WIDTH)
            for ((i, row) in kw.rows.dropLast(1).withIndex()) {
                assertWithMessage("$id row ${i + 1} width").that(row.keysWidth).isWithin(WIDTH_TOLERANCE).of(LETTER_ROW_WIDTH)
            }
        }
    }

    // ── what must not change ─────────────────────────────────────────────────────────

    @Test
    fun theExistingCornerGesturesAreKept() {
        val ir = parse(PERSIAN_PC).rows.flatMap { it.keys }.single { it.getKeyValue(0) == key("ت") }
        assertWithMessage("Persian PC: ت keeps the half-space on its top-left corner")
            .that(ir.getKeyValue(1)).isEqualTo(key("halfspace"))
        val ckb = parse(KURDISH_PERSIAN).rows.flatMap { it.keys }.single { it.getKeyValue(0) == key("ج") }
        assertWithMessage("Kurdish (Persian layout): ج keeps the half-space on its swipe-up edge")
            .that(ckb.getKeyValue(SWIPE_UP)).isEqualTo(key("halfspace"))
    }

    @Test
    fun theSharedBottomRowAndTheArabicLayoutGainNoHalfSpaceKey() {
        assertWithMessage("res/xml/bottom_row.xml serves every language; no half-space key there")
            .that(sharedBottomRow().keys.none(::isHalfSpace)).isTrue()
        val arabic = parse("arab_pc")
        assertWithMessage("arab_pc.xml keeps the shared bottom row").that(arabic.bottom_row).isTrue()
        assertWithMessage("arab_pc.xml has no dedicated half-space key")
            .that(arabic.rows.flatMap { it.keys }.none(::isHalfSpace)).isTrue()
    }
}
