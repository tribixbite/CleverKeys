package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * ARC-072 — the enforcement half of the `ConfigSnapshot` read-model
 * (`docs/plans/2026-08-29-arc072-config-snapshot-and-composition-root.md`).
 *
 * Three invariants, all source-scanned because the thing being pinned is *structure*:
 *
 *  1. **Per-file zero-pin.** Every file a slice has migrated must read config through a
 *     threaded [tribixbite.cleverkeys.prefs.ConfigSnapshot], never through the mutable
 *     global. The pin list grows one slice at a time — slice 1 pins `Gesture.kt` and
 *     `GestureClassifier.kt`, slice 2 adds `Pointers.kt` and `Keyboard2View.kt`.
 *
 *  2. **Global ceiling (the ratchet).** The number of `src/main/kotlin` files that reach
 *     for the static accessor may only ever go DOWN. A new static consumer goes red
 *     immediately; a slice that removes consumers lowers [MAX_GLOBAL_CONFIG_FILES].
 *
 *  3. **Refresh coherence, pinned structurally.** `Config` cannot be constructed in a pure
 *     JVM test — its constructor needs Android `Resources`/`SharedPreferences` (see
 *     [ConfigNullSafetyTest], which is built on exactly that fact), so the "the snapshot
 *     is rebuilt whenever the 157 vars are re-read" property cannot be asserted
 *     behaviourally here. It is pinned by shape instead: the snapshot field is
 *     `@Volatile`, externally read-only, and re-assigned in exactly ONE place — the last
 *     statement of `refresh()`. Any new early-return or new mutation path inside
 *     `refresh()` breaks that shape and this test.
 */
class ConfigSnapshotRatchetTest {

    private val mainKotlin = File("src/main/kotlin")

    /**
     * Anti-regression ceiling on static `Config.globalConfig()` consumers.
     *
     * History: 33 at the ARC-072 plan (`3f92dfe0`), 31 after slice 1 migrated
     * `Gesture.kt` + `GestureClassifier.kt`. **Only ever lower this number.** If a new
     * consumer legitimately needs live config (a settings screen writing back, say),
     * that is a signal to thread a snapshot instead — raising the ceiling defeats it.
     */
    private val MAX_GLOBAL_CONFIG_FILES = 31

    /** Files a landed slice has migrated off the mutable global. Grows per slice. */
    private val migratedHotPathFiles = listOf(
        "tribixbite/cleverkeys/Gesture.kt",          // slice 1
        "tribixbite/cleverkeys/GestureClassifier.kt" // slice 1
    )

    private fun source(relative: String): String {
        val f = File(mainKotlin, relative)
        assertWithMessage("expected source file to exist: ${f.path} (run from project root)")
            .that(f.isFile).isTrue()
        return f.readText()
    }

    @Test
    fun migratedHotPathFilesDoNotTouchTheGlobalConfig() {
        assertWithMessage("test must run from the project root").that(mainKotlin.isDirectory).isTrue()

        migratedHotPathFiles.forEach { relative ->
            val text = source(relative)
            assertWithMessage(
                "$relative was migrated to the ConfigSnapshot read-model (ARC-072): it must " +
                    "receive its config values as a threaded snapshot, not read the mutable " +
                    "global. A mid-gesture Config.refresh() rewrites 157 vars in place, so a " +
                    "read here can tear against the values the same gesture already used."
            ).that(text).doesNotContain("globalConfig")
        }
    }

    @Test
    fun migratedHotPathFilesThreadASnapshot() {
        // Positive counterpart to the zero-pin: proves the config value is threaded in,
        // not that it was simply deleted.
        migratedHotPathFiles.forEach { relative ->
            assertWithMessage(
                "$relative must take its config values from a ConfigSnapshot parameter " +
                    "(constructor for per-gesture capture, method arg for per-call capture)."
            ).that(source(relative)).contains("ConfigSnapshot")
        }
    }

    @Test
    fun staticGlobalConfigConsumerCountOnlyRatchetsDown() {
        val consumers = mainKotlin.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("Config.globalConfig()") }
            .map { it.path }
            .sorted()
            .toList()

        assertWithMessage(
            "ARC-072 ratchet: ${consumers.size} files read the static Config.globalConfig(), " +
                "ceiling is $MAX_GLOBAL_CONFIG_FILES. Adding a static consumer is the " +
                "regression this pin exists to catch — thread a ConfigSnapshot instead. " +
                "If a slice REMOVED consumers, lower MAX_GLOBAL_CONFIG_FILES to the new " +
                "count in the same commit.\nConsumers:\n" + consumers.joinToString("\n")
        ).that(consumers.size).isAtMost(MAX_GLOBAL_CONFIG_FILES)
    }

    @Test
    fun configSnapshotReadModelExists() {
        val snapshot = source("tribixbite/cleverkeys/prefs/ConfigSnapshot.kt")
        assertWithMessage("ConfigSnapshot must be an immutable data class — the whole point " +
            "is that a captured value cannot be mutated under its reader.")
            .that(snapshot).contains("data class ConfigSnapshot")
        assertWithMessage("ConfigSnapshot must expose only `val`s; a `var` field would " +
            "reintroduce the torn-read hazard it exists to remove.")
            .that(Regex("""^\s+(?:@\w+\s+)*var\s""", RegexOption.MULTILINE).containsMatchIn(snapshot))
            .isFalse()
    }

    @Test
    fun configRebuildsTheSnapshotAtTheEndOfRefreshAndNowhereElse() {
        val config = source("tribixbite/cleverkeys/Config.kt")

        assertWithMessage(
            "Config.snapshot must be @Volatile: it is published from the settings/config " +
                "thread and read by the touch and draw threads."
        ).that(config).containsMatch("""@Volatile\s+var snapshot: ConfigSnapshot""")

        assertWithMessage(
            "Config.snapshot must be externally read-only (private set) — consumers capture " +
                "it, they never install one."
        ).that(config).containsMatch("""var snapshot: ConfigSnapshot[^\n]*\n\s*private set""")

        // Exactly one re-assignment: the tail of refresh(). The declaration's initializer
        // (`var snapshot: ConfigSnapshot = ...`) deliberately does not match this pattern.
        val reassignments = Regex("""^\s*snapshot = """, RegexOption.MULTILINE)
            .findAll(config).count()
        assertWithMessage(
            "Config.snapshot must be re-assigned in exactly one place (the end of refresh()), " +
                "found $reassignments. Every other config mutation path would leave the " +
                "snapshot stale without an obvious rebuild site."
        ).that(reassignments).isEqualTo(1)

        // Structural stand-in for the pure-JVM refresh test Config's Android deps make
        // impossible: the rebuild is the LAST statement of refresh(), so no field written
        // by refresh() can be missed and no early exit can skip it.
        val lines = config.lines()
        val start = lines.indexOfFirst { it.startsWith("    fun refresh(") }
        assertWithMessage("Config.refresh(res, foldableUnfolded) must exist at member indentation")
            .that(start).isAtLeast(0)
        val end = (start + 1 until lines.size).first { lines[it] == "    }" }
        val body = lines.subList(start + 1, end)
        val lastStatement = body.last { it.isNotBlank() }.trim()
        assertWithMessage(
            "The last statement of Config.refresh() must rebuild the snapshot so the " +
                "captured read-model can never lag the 157 vars it mirrors. Found: " +
                "\"$lastStatement\""
        ).that(lastStatement).isEqualTo("snapshot = buildSnapshot()")

        assertWithMessage(
            "refresh() must have a single exit — an early `return` would skip the snapshot " +
                "rebuild at its tail and publish a stale read-model."
        ).that(body.none { Regex("""^\s*return\b""").containsMatchIn(it) }).isTrue()
    }
}
