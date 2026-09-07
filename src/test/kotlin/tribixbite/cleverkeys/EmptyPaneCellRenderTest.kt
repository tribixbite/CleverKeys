package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Audit 2026-09-06, H-8 (#77 residual): the pane `loc`-strip can null EVERY slot of a
 * key — numeric.xml's `<key width="0.75" key0="loc switch_greekmath"/>` with the
 * Greek/Math checkbox at its default (off) — but `Keyboard2View.onDraw` drew
 * `drawKeyFrame` unconditionally for every key. Default installs therefore showed a
 * blank dead key-coloured rect where the toggle used to be, instead of empty space.
 *
 * No shipped layout has an intentionally key0-less cell (auditor's scripted check over
 * all 86 layouts + panes: zero), and the other `loc` cells on the numeric pane
 * (`loc esc`, `loc tab`) keep non-null corner slots when stripped — so "all nine slots
 * null and no indication" is exactly the strip artefact and nothing else.
 *
 * Contract pinned here:
 *  - the strip really does leave an all-null Key behind (premise — `mapKeys` keeps the
 *    cell's width/shift, it cannot drop the key);
 *  - [Keyboard2View] classifies such a cell as empty ([keyCellIsEmpty]) and onDraw
 *    consults that classification before drawing the key frame (source scan — onDraw
 *    itself needs a device Canvas).
 *
 * Red (pre-fix): onDraw calls drawKeyFrame with no empty-cell guard.
 */
class EmptyPaneCellRenderTest {

    // ------------------------------------------------------------------ premise

    /** The #77 strip (MapKeyValues → null) leaves the cell as an all-null Key. */
    @Test
    fun locStrip_leavesAnAllNullKeyBehind() {
        val greekmath = KeyValue.getKeyByName("switch_greekmath")
        val cell = KeyboardData.Key(
            listOf(greekmath, null, null, null, null, null, null, null, null),
            null, KeyboardData.Key.F_LOC, 0.75f, 0f, null
        )
        val row = KeyboardData.Row(listOf(cell), 1f, 0f)

        val stripped = row.mapKeys(object : KeyboardData.MapKeyValues() {
            override fun apply(key: KeyValue, localized: Boolean): KeyValue? =
                null // what modify_numpad's strip does for the unchecked toggle
        })

        val strippedCell = stripped.keys.single()
        assertWithMessage("mapKeys keeps the cell (width intact) with every slot null")
            .that(strippedCell.keys.all { it == null }).isTrue()
        assertThat(strippedCell.width).isEqualTo(0.75f)
    }

    // ------------------------------------------------------------------ the render guard

    /**
     * H-8 red: onDraw must skip the key frame (and the whole per-key render pass) for
     * empty cells. Source scan because onDraw needs a real Canvas; the classification
     * itself is pinned behaviourally below.
     */
    @Test
    fun onDraw_consultsTheEmptyCellGuardBeforeDrawingTheKeyFrame() {
        val src = File("src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt").readText()
        val onDrawStart = src.indexOf("override fun onDraw(")
        assertWithMessage("Keyboard2View.onDraw must exist").that(onDrawStart).isGreaterThan(-1)
        val frameCall = src.indexOf("drawKeyFrame(canvas", onDrawStart)
        assertWithMessage("onDraw must call drawKeyFrame").that(frameCall).isGreaterThan(-1)
        val guard = src.indexOf("keyCellIsEmpty(", onDrawStart)
        assertWithMessage(
            "onDraw must consult keyCellIsEmpty before drawKeyFrame — an unconditional " +
                "frame renders the #77-stripped numeric-pane cell as a blank dead key " +
                "(audit H-8)"
        ).that(guard).isGreaterThan(-1)
        assertWithMessage("the guard must run before the frame is drawn")
            .that(guard).isLessThan(frameCall)
    }

    // ------------------------------------------------------------------ the classification

    @Test
    fun strippedAllNullCell_classifiesAsEmpty() {
        val greekmath = KeyValue.getKeyByName("switch_greekmath")
        val cell = KeyboardData.Key(
            listOf(greekmath, null, null, null, null, null, null, null, null),
            null, KeyboardData.Key.F_LOC, 0.75f, 0f, null
        )
        val stripped = KeyboardData.Row(listOf(cell), 1f, 0f)
            .mapKeys(object : KeyboardData.MapKeyValues() {
                override fun apply(key: KeyValue, localized: Boolean): KeyValue? = null
            }).keys.single()

        assertWithMessage("the #77-stripped cell must be classified empty (no frame)")
            .that(Keyboard2View.keyCellIsEmpty(stripped)).isTrue()
    }

    @Test
    fun ordinaryCells_areNeverClassifiedEmpty() {
        val plain = KeyboardData.Key(
            listOf(KeyValue.makeCharKey('a'), null, null, null, null, null, null, null, null),
            null, 0, 1f, 0f, null
        )
        assertThat(Keyboard2View.keyCellIsEmpty(plain)).isFalse()

        // A key0-less cell with a live corner slot (numeric's `loc esc` cell after a
        // strip keeps key2/key4) still renders.
        val cornerOnly = KeyboardData.Key(
            listOf(null, null, KeyValue.makeCharKey('~'), null, null, null, null, null, null),
            null, 0, 1f, 0f, null
        )
        assertThat(Keyboard2View.keyCellIsEmpty(cornerOnly)).isFalse()

        // An indication-only cell (defensive: `indication` is drawn even without keys).
        val indicationOnly = KeyboardData.Key(
            listOf(null, null, null, null, null, null, null, null, null),
            null, 0, 1f, 0f, "abc"
        )
        assertThat(Keyboard2View.keyCellIsEmpty(indicationOnly)).isFalse()
    }
}
