package tribixbite.cleverkeys.customization

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.backup.ScreenMetrics
import tribixbite.cleverkeys.backup.SettingsImportPlanBuilder

/**
 * G-2 (comprehensive audit 2026-09-06): preview/apply agreement for short-swipe imports,
 * through the REAL [ShortSwipeCustomizationManager] over a temp-dir storage file.
 *
 * Two halves:
 *
 *  1. **A flat legacy section must import what the preview counted.** The builder counts
 *     direction entries of a flat (`{keyCode: {direction: {...}}}`) section, but the
 *     applier hands the raw JSON to `importFromJson`, whose Gson model only parses the
 *     wrapped `{version, mappings}` shape — a flat section imported 0. Worse, in REPLACE
 *     mode the clear had already run, so a preview promising N mappings WIPED the user's
 *     existing customizations for a 0-mapping import. The builder now normalizes
 *     flat → wrapped, so the counted shape and the imported shape are the same JSON.
 *
 *  2. **A 0-mapping import must not clear.** Whatever the reason a parse yields no
 *     mappings (empty section, malformed entries), REPLACE must return 0 WITHOUT
 *     touching the existing set — destroying data for an import that delivers nothing
 *     is never right.
 *
 *  3. **G-3 (maintainer decision 2026-09-08): merge collisions are IMPORT-WINS.** When a
 *     merge-imported mapping collides with an existing local mapping for the same
 *     key+direction, the imported one replaces the local one; non-colliding local
 *     mappings survive (it is a merge, not a wholesale replace). The overwrite is not
 *     silent — collisions surface in the Backup & Restore preview as `changed` rows of
 *     the [tribixbite.cleverkeys.backup.ShortSwipeDiff] (pinned in
 *     `backup.SettingsImportPlanBuilderTest`).
 *
 * Mock tier: the manager needs a `Context` for its storage file and logs through
 * `android.util.Log`; Gson/coroutines are real. Run with
 * `scripts/gradle-guard.sh runMockTests -PtestClass=customization.ShortSwipeImportSemanticsTest`.
 */
class ShortSwipeImportSemanticsTest {

    private lateinit var scratch: File
    private lateinit var context: Context
    private lateinit var manager: ShortSwipeCustomizationManager

    private val screen = ScreenMetrics(1080, 2400, 3.0f)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        scratch = Files.createTempDirectory("ck-shortswipe").toFile()
        context = mockk()
        every { context.filesDir } returns scratch
        every { context.applicationContext } returns context

        // The singleton getInstance would leak state between tests — construct directly.
        manager = ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
    }

    @After
    fun teardown() {
        unmockkAll()
        scratch.deleteRecursively()
    }

    private fun existingMapping() = ShortSwipeMapping.textInput("a", SwipeDirection.NE, "@", "@")

    private fun seedExistingMapping() = runBlocking {
        manager.importFromMappings(listOf(existingMapping()), merge = false)
    }

    // ------------------------------------------- half 1: flat section, end to end

    @Test
    fun aFlatSectionImportsExactlyWhatThePreviewCounted() {
        seedExistingMapping()

        // The audit's reproduction shape: flat section, one real direction mapping.
        val backup = """{"preferences":{}, "short_swipe_customizations":
            {"q":{"N":{"displayText":"x","actionType":"TEXT","actionValue":"x"}}}}"""

        val plan = SettingsImportPlanBuilder.fromJson(backup, emptyMap(), screen)
        assertWithMessage("the preview must count the flat section's direction entries")
            .that(plan.shortSwipeImportSize).isEqualTo(1)

        val imported = runBlocking {
            manager.importFromJson(plan.shortSwipeImportRawJson!!, merge = false)
        }

        assertWithMessage(
            "REPLACE-applying a backup the preview counted as 1 mapping must import 1 — " +
                "importing 0 after promising 1 is the G-2 preview/apply divergence"
        ).that(imported).isEqualTo(1)
        assertWithMessage("the imported mapping must be retrievable")
            .that(manager.getMapping("q", SwipeDirection.N)?.actionValue).isEqualTo("x")
    }

    // --------------------------------------- half 2: empty parses must not clear

    @Test
    fun aZeroMappingReplaceImportDoesNotClearExistingCustomizations() {
        seedExistingMapping()
        assertThat(manager.getMapping("a", SwipeDirection.NE)).isNotNull()

        val imported = runBlocking {
            manager.importFromJson("""{"version":2,"mappings":{}}""", merge = false)
        }

        assertThat(imported).isEqualTo(0)
        assertWithMessage(
            "an import that delivers 0 mappings must not have cleared the user's set — " +
                "REPLACE used to clear FIRST and only then discover the file was empty"
        ).that(manager.getMapping("a", SwipeDirection.NE)?.actionValue).isEqualTo("@")
    }

    @Test
    fun aMalformedReplaceImportDoesNotClearExistingCustomizations() {
        seedExistingMapping()

        val imported = runBlocking {
            manager.importFromJson("this is not json", merge = false)
        }

        assertThat(imported).isEqualTo(0)
        assertThat(manager.getMapping("a", SwipeDirection.NE)?.actionValue).isEqualTo("@")
    }

    // ------------------- half 3: G-3 merge-collision semantics (decided: import wins)

    @Test
    fun aMergeImportCollisionIsWonByTheImportedMapping() {
        seedExistingMapping()   // local a:NE → "@"

        val imported = runBlocking {
            manager.importFromJson(
                """{"version":2,"mappings":
                    {"a":{"NE":{"displayText":"%","actionType":"TEXT","actionValue":"%"}}}}""",
                merge = true
            )
        }

        assertThat(imported).isEqualTo(1)
        assertWithMessage(
            "G-3 (decided 2026-09-08): on a key+direction collision the IMPORTED mapping " +
                "wins — the local a:NE→@ must be replaced by the file's a:NE→%"
        ).that(manager.getMapping("a", SwipeDirection.NE)?.actionValue).isEqualTo("%")
    }

    @Test
    fun aMergeImportPreservesNonCollidingLocalMappings() {
        seedExistingMapping()   // local a:NE → "@"

        val imported = runBlocking {
            manager.importFromJson(
                """{"version":2,"mappings":
                    {"b":{"SW":{"displayText":"y","actionType":"TEXT","actionValue":"y"}}}}""",
                merge = true
            )
        }

        assertThat(imported).isEqualTo(1)
        assertWithMessage(
            "merge is a MERGE, not a replace — a non-colliding local mapping survives"
        ).that(manager.getMapping("a", SwipeDirection.NE)?.actionValue).isEqualTo("@")
        assertThat(manager.getMapping("b", SwipeDirection.SW)?.actionValue).isEqualTo("y")
    }

    // ------------------------------------------------ sanity: real replace still works

    @Test
    fun aNonEmptyReplaceImportStillReplacesTheExistingSet() {
        seedExistingMapping()

        val imported = runBlocking {
            manager.importFromJson(
                """{"version":2,"mappings":
                    {"b":{"SW":{"displayText":"y","actionType":"TEXT","actionValue":"y"}}}}""",
                merge = false
            )
        }

        assertThat(imported).isEqualTo(1)
        assertWithMessage("REPLACE with a real payload still clears the previous set")
            .that(manager.getMapping("a", SwipeDirection.NE)).isNull()
        assertThat(manager.getMapping("b", SwipeDirection.SW)?.actionValue).isEqualTo("y")
    }
}
