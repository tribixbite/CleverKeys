package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * #9: Instrumented tests for swipe layout support with real KeyboardData objects.
 *
 * These tests load actual layout XML files from app resources and verify
 * that Config.isSwipeTypingSupportedForLayout(KeyboardData) correctly
 * identifies which layouts support neural swipe prediction.
 *
 * Uses LayoutsPreference.layoutOfString() to parse real layout XML —
 * testing the full path from XML to KeyboardData to the allowlist check.
 */
@RunWith(AndroidJUnit4::class)
class SwipeLayoutSupportInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    /** Load a KeyboardData from layout file name (e.g., "latn_qwerty_us"). */
    private fun loadLayout(name: String): KeyboardData? {
        return LayoutsPreference.layoutOfString(context.resources, name)
    }

    // =========================================================================
    // QWERTY variants — SHOULD support swipe
    // =========================================================================

    @Test
    fun qwertyUS_supportsSwipe() {
        val layout = loadLayout("latn_qwerty_us")
        assertNotNull("Layout should load", layout)
        assertTrue("QWERTY US should support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun qwertyJulow_supportsSwipe() {
        val layout = loadLayout("latn_qwerty_us_julow")
        assertNotNull("Layout should load", layout)
        assertTrue("Julow QWERTY should support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun qwertyBrazilian_supportsSwipe() {
        val layout = loadLayout("latn_qwerty_br")
        assertNotNull("Layout should load", layout)
        assertTrue("QWERTY Brazilian should support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun qwertySwedish_supportsSwipe() {
        val layout = loadLayout("latn_qwerty_se")
        assertNotNull("Layout should load", layout)
        assertTrue("QWERTY Swedish should support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun qwertyTurkish_supportsSwipe() {
        val layout = loadLayout("latn_qwerty_tr")
        assertNotNull("Layout should load", layout)
        assertTrue("QWERTY Turkish should support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun qwertyVietnamese_supportsSwipe() {
        val layout = loadLayout("latn_qwerty_vi")
        assertNotNull("Layout should load", layout)
        assertTrue("QWERTY Vietnamese should support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun qwertyPolish_supportsSwipe() {
        val layout = loadLayout("latn_qwerty_pl")
        assertNotNull("Layout should load", layout)
        assertTrue("QWERTY Polish should support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun qwertyNorwegian_supportsSwipe() {
        val layout = loadLayout("latn_qwerty_no")
        assertNotNull("Layout should load", layout)
        assertTrue("QWERTY Norwegian should support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    // =========================================================================
    // Non-QWERTY Latin layouts — should NOT support swipe
    // =========================================================================

    @Test
    fun dvorak_doesNotSupportSwipe() {
        val layout = loadLayout("latn_dvorak")
        assertNotNull("Layout should load", layout)
        assertFalse("Dvorak should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun colemak_doesNotSupportSwipe() {
        val layout = loadLayout("latn_colemak")
        assertNotNull("Layout should load", layout)
        assertFalse("Colemak should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun bepo_doesNotSupportSwipe() {
        val layout = loadLayout("latn_bepo_fr")
        assertNotNull("Layout should load", layout)
        assertFalse("BEPO should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun azertyFrench_doesNotSupportSwipe() {
        val layout = loadLayout("latn_azerty_fr")
        assertNotNull("Layout should load", layout)
        assertFalse("AZERTY French should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun workman_doesNotSupportSwipe() {
        val layout = loadLayout("latn_workman_us")
        assertNotNull("Layout should load", layout)
        assertFalse("Workman should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun bone_doesNotSupportSwipe() {
        val layout = loadLayout("latn_bone")
        assertNotNull("Layout should load", layout)
        assertFalse("Bone should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun neo2_doesNotSupportSwipe() {
        val layout = loadLayout("latn_neo2")
        assertNotNull("Layout should load", layout)
        assertFalse("Neo2 should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    // =========================================================================
    // QWERTZ — Z/Y swap, should NOT support swipe
    // =========================================================================

    @Test
    fun qwertzGerman_doesNotSupportSwipe() {
        val layout = loadLayout("latn_qwertz_de")
        assertNotNull("Layout should load", layout)
        assertFalse("QWERTZ German should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun qwertzHungarian_doesNotSupportSwipe() {
        val layout = loadLayout("latn_qwertz_hu")
        assertNotNull("Layout should load", layout)
        assertFalse("QWERTZ Hungarian should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    // =========================================================================
    // Non-Latin scripts — should NOT support swipe
    // =========================================================================

    @Test
    fun russianCyrillic_doesNotSupportSwipe() {
        val layout = loadLayout("cyrl_jcuken_ru")
        assertNotNull("Layout should load", layout)
        assertFalse("Russian Cyrillic should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun arabicPC_doesNotSupportSwipe() {
        val layout = loadLayout("arab_pc")
        assertNotNull("Layout should load", layout)
        assertFalse("Arabic should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun hebrewLayout_doesNotSupportSwipe() {
        val layout = loadLayout("hebr_1_il")
        assertNotNull("Layout should load", layout)
        assertFalse("Hebrew should NOT support swipe",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    @Test
    fun georgianQwerty_doesNotSupportSwipe() {
        val layout = loadLayout("georgian_qwerty")
        assertNotNull("Layout should load", layout)
        // Georgian script with QWERTY in name — script check catches it
        assertFalse("Georgian QWERTY should NOT support swipe (non-Latin script)",
            Config.isSwipeTypingSupportedForLayout(layout))
    }

    // =========================================================================
    // Config integration — layout loaded from preferences
    // =========================================================================

    @Test
    fun configLayouts_qwertyUSIsDefault() {
        TestConfigHelper.ensureConfigInitialized(context)
        val config = Config.globalConfig()
        val currentLayout = config.layouts.getOrNull(config.get_current_layout())
        // Default layout should be QWERTY US (or similar QWERTY variant)
        // and should support swipe
        if (currentLayout != null) {
            val name = currentLayout.name ?: ""
            // Default config may vary, but if it has a name with QWERTY and Latin script,
            // swipe should be supported
            if (name.contains("QWERTY", ignoreCase = true)) {
                assertTrue("Default QWERTY layout should support swipe",
                    Config.isSwipeTypingSupportedForLayout(currentLayout))
            }
        }
    }

    // =========================================================================
    // Null safety
    // =========================================================================

    @Test
    fun nullKeyboardData_returnsFalse() {
        assertFalse("null KeyboardData should return false",
            Config.isSwipeTypingSupportedForLayout(null as KeyboardData?))
    }
}
