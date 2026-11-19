package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Color
import android.os.Process
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device pass 2026-11-18 (Saga, release of e33bfffa), two emoji-pane defects:
 *
 *  1. Under a light runtime theme the text-emoticon tab (`:)`) drew WHITE faces on the light
 *     pane. Runtime themes inflate on a dark base style whose `emoji_color` is white;
 *     [PaneRuntimeTheme.paint] repainted the pane and its bars but not the grid's cells, and the
 *     cells' colour comes only from that base style. The cells must follow the runtime label
 *     colour, also cells created after the repaint (a new category, a search, a reused cell).
 *  2. ~149 `ThemeUtils` error lines per session, one per cell: the cell was an
 *     `AppCompatTextView`, which logs "is an AppCompat widget that can only be used with a
 *     Theme.AppCompat theme" when its context's theme is not AppCompat, and the IME's never is.
 *
 * Instrumented: real inflation of `emoji_pane.xml`, real cells from the grid's adapter, and the
 * process's own logcat.
 */
@RunWith(AndroidJUnit4::class)
class EmojiPaneRuntimeThemeTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Emoji.init(context.resources)
        TestConfigHelper.ensureConfigInitialized(context)
    }

    /** The IME's inflation context: an input-method theme (never AppCompat) plus a keyboard style. */
    private fun imeContext(style: Int): Context =
        ContextThemeWrapper(
            ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_InputMethod), style
        )

    private fun inflatePane(style: Int): ViewGroup =
        View.inflate(imeContext(style), R.layout.emoji_pane, null) as ViewGroup

    private fun paint(pane: ViewGroup, label: Int) {
        PaneRuntimeTheme.paint(
            pane,
            PaneRuntimeTheme.Colors(background = Color.WHITE, key = Color.LTGRAY, label = label, subLabel = Color.GRAY),
            searchBarId = R.id.emoji_search_bar,
            groupBarId = R.id.emoji_group_buttons,
            searchInputId = R.id.emoji_search_input,
            gridId = R.id.emoji_grid,
            noResultsId = R.id.emoji_no_results,
            labelViewIds = intArrayOf(R.id.emoji_search_clear, R.id.emoji_close_button),
        )
    }

    private val textFaceGroup get() = Emoji.getNumGroups() - 1

    private fun cell(grid: EmojiGridView, pos: Int, convertView: View? = null): TextView =
        grid.adapter.getView(pos, convertView, grid) as TextView

    private fun hex(color: Int) = String.format("#%08X", color)

    @Test
    fun aLightRuntimeThemeOnTheDarkBaseDrawsTextFacesInItsLabelColour() {
        val label = Color.rgb(0x24, 0x1A, 0x3E)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Runtime themes inflate on the CleverKeysDark base (white emoji_color).
            val pane = inflatePane(R.style.CleverKeysDark)
            val grid = pane.findViewById<EmojiGridView>(R.id.emoji_grid)
            grid.setEmojiGroup(textFaceGroup)
            assertTrue("the text-face group must have entries", grid.adapter.count > 2)
            val before = cell(grid, 0)

            paint(pane, label)

            assertEquals("a cell that existed before the repaint takes the label colour",
                hex(label), hex(cell(grid, 0, convertView = before).currentTextColor))
            assertEquals("a cell created after the repaint takes the label colour",
                hex(label), hex(cell(grid, 1).currentTextColor))

            // A category switch and a search each install a new adapter with new cells.
            grid.setEmojiGroup(0)
            grid.setEmojiGroup(textFaceGroup)
            assertEquals("cells of a later category take the label colour",
                hex(label), hex(cell(grid, 2).currentTextColor))
            grid.searchEmojis("smile")
            if (grid.adapter.count > 0) {
                assertEquals("search results take the label colour",
                    hex(label), hex(cell(grid, 0).currentTextColor))
            }
        }
    }

    @Test
    fun aDarkRuntimeThemeOnALightBaseDrawsTextFacesInItsLabelColour() {
        val label = Color.rgb(0xEE, 0xEE, 0xEE)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val pane = inflatePane(R.style.Light) // black emoji_color
            val grid = pane.findViewById<EmojiGridView>(R.id.emoji_grid)
            grid.setEmojiGroup(textFaceGroup)
            paint(pane, label)
            assertEquals(hex(label), hex(cell(grid, 0).currentTextColor))
        }
    }

    @Test
    fun withoutARuntimeRepaintCellsKeepTheXmlThemeColour() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val pane = inflatePane(R.style.Light)
            val grid = pane.findViewById<EmojiGridView>(R.id.emoji_grid)
            grid.setEmojiGroup(textFaceGroup)
            assertEquals("Light's emoji_color is black", hex(Color.BLACK), hex(cell(grid, 0).currentTextColor))
        }
    }

    /** Lines in this process's logcat that are the AppCompat theme check's error. */
    private fun appCompatThemeErrors(): Int {
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "brief", "--pid=${Process.myPid()}", "ThemeUtils:E", "*:S")
        )
        val lines = proc.inputStream.bufferedReader().use { it.readLines() }
        proc.waitFor()
        return lines.count { it.contains("Theme.AppCompat theme") }
    }

    @Test
    fun emojiCellsInTheImeContextLogNoAppCompatThemeError() {
        val before = appCompatThemeErrors()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val pane = inflatePane(R.style.CleverKeysDark)
            val grid = pane.findViewById<EmojiGridView>(R.id.emoji_grid)
            grid.setEmojiGroup(0)
            val n = minOf(20, grid.adapter.count)
            assertTrue("the first category must have cells", n > 0)
            for (pos in 0 until n) cell(grid, pos)
        }
        Thread.sleep(500) // let logd deliver what the cells wrote
        val after = appCompatThemeErrors()
        assertEquals("emoji cells must not log the AppCompat theme error (one line per cell before)",
            0, after - before)
    }
}
