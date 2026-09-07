package tribixbite.cleverkeys

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * I-4 (comprehensive audit 2026-09-06): `first_stat_timestamp` had no writer anywhere —
 * "Days tracked" was permanently 0 in the perf-stats viewer and every JSON export while
 * total selections kept advancing. [SwipePerformanceStats.recordSelection] is the one
 * live stat writer (the inference/model-load writers died with the neural engine and are
 * documented as such), so it must seed the anchor on the first recorded stat and leave
 * an existing anchor untouched.
 *
 * The stats object is allocated without its constructor (Objenesis) because the real
 * constructor reaches SharedPreferences + `PrivacyManager.getInstance` — android.jar
 * stubs under `runMockTests`. The two fields the tested method reads are seeded and the
 * REAL method body runs (same pattern as `MLDataCollectionToggleTest`).
 */
class SwipePerformanceStatsTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var privacy: PrivacyManager

    private val stored = mutableMapOf<String, Long>()

    @Before
    fun setup() {
        stored.clear()
        editor = mockk(relaxed = true)
        every { editor.putLong(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        prefs = mockk()
        every { prefs.edit() } returns editor
        every { prefs.getLong(any(), any()) } answers { stored[firstArg()] ?: secondArg() }

        privacy = mockk()
        every { privacy.canCollectPerformanceData() } returns true
    }

    @After
    fun teardown() = unmockkAll()

    private fun stats(): SwipePerformanceStats {
        val s = ObjenesisStd().newInstance(SwipePerformanceStats::class.java)
        for ((name, value) in mapOf("prefs" to prefs, "privacyManager" to privacy)) {
            val field = SwipePerformanceStats::class.java.declaredFields.firstOrNull { it.name == name }
                ?: throw AssertionError(
                    "field '$name' not found on SwipePerformanceStats — renamed/removed?"
                )
            field.isAccessible = true
            field.set(s, value)
        }
        return s
    }

    @Test
    fun theFirstRecordedSelectionSeedsTheDaysTrackedAnchor() {
        val s = stats()
        val before = System.currentTimeMillis()

        s.recordSelection(0)

        assertWithMessage(
            "recordSelection on fresh prefs must write first_stat_timestamp — without a " +
                "writer, 'Days tracked' is 0 forever while selections keep advancing"
        ).that(s.getFirstStatTimestamp()).isAtLeast(before)
        assertThat(s.getTotalSelections()).isEqualTo(1L)
    }

    @Test
    fun anExistingAnchorIsNeverOverwritten() {
        stored["first_stat_timestamp"] = 111L

        stats().recordSelection(1)

        assertWithMessage("a later selection must not move the start-of-tracking anchor")
            .that(stored["first_stat_timestamp"]).isEqualTo(111L)
        verify(exactly = 0) { editor.putLong("first_stat_timestamp", neq(111L)) }
    }

    @Test
    fun nothingIsSeededWhenPerformanceCollectionIsOff() {
        every { privacy.canCollectPerformanceData() } returns false

        stats().recordSelection(0)

        assertThat(stored).isEmpty()
    }
}
