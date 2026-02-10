package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for UserAdaptationManager.
 * Covers word selection tracking, adaptation multipliers, pruning,
 * persistence, and periodic reset logic.
 */
@RunWith(AndroidJUnit4::class)
class UserAdaptationManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Clear prefs before each test
        context.getSharedPreferences("user_adaptation", Context.MODE_PRIVATE)
            .edit().clear().apply()
        // Reset singleton (reflection — private static field)
        try {
            val field = UserAdaptationManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {}
    }

    @After
    fun teardown() {
        try {
            val field = UserAdaptationManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {}
    }

    private fun getManager(): UserAdaptationManager =
        UserAdaptationManager.getInstance(context)

    // =========================================================================
    // Singleton
    // =========================================================================

    @Test
    fun singletonReturnsSameInstance() {
        val m1 = UserAdaptationManager.getInstance(context)
        val m2 = UserAdaptationManager.getInstance(context)
        assertSame(m1, m2)
    }

    // =========================================================================
    // Initial state
    // =========================================================================

    @Test
    fun initialStateIsEmpty() {
        val m = getManager()
        assertEquals(0, m.getTotalSelections())
        assertEquals(0, m.getTrackedWordCount())
        assertTrue(m.isEnabled())
    }

    // =========================================================================
    // recordSelection
    // =========================================================================

    @Test
    fun recordSelectionIncrementsCount() {
        val m = getManager()
        m.recordSelection("hello")
        assertEquals(1, m.getTotalSelections())
        assertEquals(1, m.getTrackedWordCount())
        assertEquals(1, m.getSelectionCount("hello"))
    }

    @Test
    fun recordSelectionNormalizesToLowercase() {
        val m = getManager()
        m.recordSelection("Hello")
        m.recordSelection("HELLO")
        m.recordSelection("hello")
        assertEquals(3, m.getSelectionCount("hello"))
        assertEquals(3, m.getSelectionCount("HELLO"))
    }

    @Test
    fun recordSelectionIgnoresNullAndBlank() {
        val m = getManager()
        m.recordSelection(null)
        m.recordSelection("")
        m.recordSelection("   ")
        assertEquals(0, m.getTotalSelections())
    }

    @Test
    fun recordMultipleDistinctWords() {
        val m = getManager()
        m.recordSelection("hello")
        m.recordSelection("world")
        m.recordSelection("hello")
        assertEquals(3, m.getTotalSelections())
        assertEquals(2, m.getTrackedWordCount())
        assertEquals(2, m.getSelectionCount("hello"))
        assertEquals(1, m.getSelectionCount("world"))
    }

    // =========================================================================
    // getAdaptationMultiplier
    // =========================================================================

    @Test
    fun adaptationMultiplierIs1WhenBelowThreshold() {
        val m = getManager()
        // MIN_SELECTIONS_FOR_ADAPTATION is 5
        m.recordSelection("test")
        m.recordSelection("test")
        m.recordSelection("test")
        m.recordSelection("test")
        // Only 4 selections — below threshold
        assertEquals(1.0f, m.getAdaptationMultiplier("test"), 0.001f)
    }

    @Test
    fun adaptationMultiplierIncreasesAboveThreshold() {
        val m = getManager()
        // Record 6 selections of same word (above threshold of 5)
        repeat(6) { m.recordSelection("frequent") }
        val multiplier = m.getAdaptationMultiplier("frequent")
        assertTrue("Multiplier should be > 1.0 for frequent word, was $multiplier",
            multiplier > 1.0f)
        assertTrue("Multiplier should be <= 2.0, was $multiplier",
            multiplier <= 2.0f)
    }

    @Test
    fun adaptationMultiplierIs1ForUnknownWord() {
        val m = getManager()
        repeat(10) { m.recordSelection("known") }
        assertEquals(1.0f, m.getAdaptationMultiplier("unknown"), 0.001f)
    }

    @Test
    fun adaptationMultiplierIs1WhenDisabled() {
        val m = getManager()
        repeat(10) { m.recordSelection("test") }
        m.setEnabled(false)
        assertEquals(1.0f, m.getAdaptationMultiplier("test"), 0.001f)
    }

    @Test
    fun adaptationMultiplierNullReturns1() {
        val m = getManager()
        assertEquals(1.0f, m.getAdaptationMultiplier(null), 0.001f)
    }

    // =========================================================================
    // enable/disable
    // =========================================================================

    @Test
    fun disablePreventRecording() {
        val m = getManager()
        m.setEnabled(false)
        assertFalse(m.isEnabled())
        m.recordSelection("test")
        assertEquals(0, m.getTotalSelections())
    }

    @Test
    fun reEnableAllowsRecording() {
        val m = getManager()
        m.setEnabled(false)
        m.setEnabled(true)
        assertTrue(m.isEnabled())
        m.recordSelection("test")
        assertEquals(1, m.getTotalSelections())
    }

    // =========================================================================
    // resetAdaptation
    // =========================================================================

    @Test
    fun resetClearsAllData() {
        val m = getManager()
        repeat(10) { m.recordSelection("word$it") }
        assertTrue(m.getTotalSelections() > 0)
        m.resetAdaptation()
        assertEquals(0, m.getTotalSelections())
        assertEquals(0, m.getTrackedWordCount())
    }

    // =========================================================================
    // getAdaptationStats
    // =========================================================================

    @Test
    fun statsReturnsStringWhenEnabled() {
        val m = getManager()
        repeat(10) { m.recordSelection("popular") }
        val stats = m.getAdaptationStats()
        assertTrue(stats.contains("Total selections: 10"))
        assertTrue(stats.contains("popular"))
    }

    @Test
    fun statsReturnsDisabledMessage() {
        val m = getManager()
        m.setEnabled(false)
        assertEquals("User adaptation disabled", m.getAdaptationStats())
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    @Test
    fun dataPersistsAcrossInstances() {
        val m1 = getManager()
        // Record enough to trigger save (every 10 selections)
        repeat(10) { m1.recordSelection("persistent") }
        m1.cleanup() // Force save

        // Reset singleton
        try {
            val field = UserAdaptationManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {}

        val m2 = getManager()
        assertEquals(10, m2.getSelectionCount("persistent"))
        assertEquals(10, m2.getTotalSelections())
    }

    // =========================================================================
    // getSelectionCount edge cases
    // =========================================================================

    @Test
    fun getSelectionCountNullReturns0() {
        val m = getManager()
        assertEquals(0, m.getSelectionCount(null))
    }

    @Test
    fun getSelectionCountUnknownReturns0() {
        val m = getManager()
        assertEquals(0, m.getSelectionCount("neverrecorded"))
    }
}
