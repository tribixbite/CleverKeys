package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * ARC-072 — the enforcement half of the `ConfigSnapshot` read-model
 * (`docs/plans/2026-08-29-arc072-config-snapshot-and-composition-root.md`).
 *
 * Invariants, all source-scanned because the thing being pinned is *structure*:
 *
 *  1. **Per-file zero-pin.** Every file a slice has migrated must read config through a
 *     threaded [tribixbite.cleverkeys.prefs.ConfigSnapshot], never through the mutable
 *     global. The pin list grows one slice at a time — slice 1 pinned `Gesture.kt` and
 *     `GestureClassifier.kt`, slice 2 adds `Pointers.kt`.
 *
 *  2. **Live-member allowlist.** `Keyboard2View` cannot give up its live `Config`: it is
 *     inflated from XML (nothing can inject one) and it dispatches key events through
 *     `Config.handler`. Pinning "zero live reads" there would be a lie, and a blanket
 *     exemption would let a new per-frame field read back in unnoticed. Instead every
 *     surviving `_config.<member>` access is enumerated per file — anything not on the
 *     list goes red. Aliasing (`val c = _config`) is pinned separately so the enumeration
 *     cannot be side-stepped.
 *
 *  3. **Global ceiling (the ratchet).** The number of `src/main/kotlin` files that reach
 *     for the static accessor may only ever go DOWN. A new static consumer goes red
 *     immediately; a slice that removes consumers lowers [MAX_GLOBAL_CONFIG_FILES].
 *
 *  4. **Refresh coherence, pinned structurally.** `Config` cannot be constructed in a pure
 *     JVM test — its constructor needs Android `Resources`/`SharedPreferences` (see
 *     [ConfigNullSafetyTest], which is built on exactly that fact), so the "the snapshot
 *     is rebuilt whenever the 157 vars are re-read" property cannot be asserted
 *     behaviourally here. It is pinned by shape instead: the snapshot field is
 *     `@Volatile`, externally read-only, and assigned in exactly ONE place —
 *     `publishSnapshot()`, which is the last statement of both `refresh()` and `edit()`.
 *     Any new early-return or new mutation path inside `refresh()` breaks that shape.
 *
 *  5. **No stale direct writes.** A `config.<field> = value` write that bypasses `refresh()`
 *     leaves the published snapshot lagging that field until the next refresh. Writing a
 *     snapshot-mirrored field that way is therefore forbidden outright; `Config.edit {}`
 *     is the sanctioned path (its body assigns bare receiver-scoped names, so it does not
 *     match the forbidden shape) and it re-publishes.
 */
class ConfigSnapshotRatchetTest {

    private val mainKotlin = File("src/main/kotlin")

    /**
     * Anti-regression ceiling on static `Config.globalConfig()` consumers.
     *
     * History: 33 at the ARC-072 plan (`3f92dfe0`), 31 after slice 1 migrated
     * `Gesture.kt` + `GestureClassifier.kt`, 30 after slice 2 took `Pointers.kt` off it
     * (its two `@JvmStatic` slider-speed helpers were the last static reads there).
     * **Only ever lower this number.** If a new consumer legitimately needs live config
     * (a settings screen writing back, say), that is a signal to thread a snapshot
     * instead — raising the ceiling defeats it.
     */
    private val MAX_GLOBAL_CONFIG_FILES = 30

    /** Files a landed slice has migrated off the mutable global. Grows per slice. */
    private val migratedHotPathFiles = listOf(
        "tribixbite/cleverkeys/gesture/Gesture.kt",           // slice 1 (moved to gesture/ in slice 3)
        "tribixbite/cleverkeys/gesture/GestureClassifier.kt", // slice 1 (moved to gesture/ in slice 3)
        "tribixbite/cleverkeys/Pointers.kt"           // slice 2 — gesture-scoped capture
    )

    /**
     * Per-file allowlist of live-`Config` MEMBER accesses that survive migration.
     *
     * `Pointers` may only reach the live config to TAKE the snapshot at pointer-down;
     * every threshold, timeout and distance for that pointer then comes from the captured
     * copy. `Keyboard2View` additionally keeps `Config.handler` — a dispatch target into
     * the IME, not configuration state, and deliberately excluded from the read-model
     * (freezing a callback into a value object is a bug waiting to happen).
     *
     * A bare `_config` *pass* (`Theme.Computed(_theme, _config, …)`,
     * `VibratorCompat.vibrate(this, _config, …)`, `Pointers(this, _config, …)`) is not a
     * member access and is not covered here — those are the recorded later-slice residue,
     * held in place by [MAX_GLOBAL_CONFIG_FILES] instead.
     */
    private val allowedLiveConfigMembers = mapOf(
        "tribixbite/cleverkeys/Pointers.kt" to setOf("snapshot"),
        "tribixbite/cleverkeys/Keyboard2View.kt" to setOf("snapshot", "handler")
    )

    /**
     * Files that must keep a live `Config` reference but may ACQUIRE it only once.
     *
     * `Keyboard2View` is inflated from XML, so there is no constructor to inject a config
     * into; one `Config.globalConfig()` at init is the seam. A second call anywhere else
     * would be a re-entry into the global from the draw/touch path.
     */
    private val liveConfigAcquisitionLimit = mapOf(
        "tribixbite/cleverkeys/Keyboard2View.kt" to 1
    )

    /** Matches a live-config member access: `_config.x`, `_config?.x`, `config.x`. */
    private val liveConfigMemberAccess = Regex("""\b_?config\??\.(\w+)""")

    /**
     * Blank out comments before scanning for config ACCESS. Prose in a KDoc or a `//` note
     * routinely names `_config` and would otherwise be indistinguishable from a real read
     * (a sentence ending "…never the live config." followed by `val snap = …` matched the
     * member pattern before this existed). Newlines are preserved so reported line numbers
     * stay usable. String literals are not special-cased — a `"…config.x…"` literal in
     * these files would be a false positive, and there are none.
     */
    private fun withoutComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m ->
            m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
        }.replace(Regex("""//[^\n]*"""), "")

    /**
     * Matches a direct field WRITE through a live config reference — the shape that leaves
     * the published snapshot stale. `Config.edit {}` bodies assign bare receiver-scoped
     * names and so do not match.
     */
    private val directConfigFieldWrite =
        Regex("""\b(?:_?config|Config\.globalConfig\(\))\s*\??\.\s*(\w+)\s*=(?!=)""")

    private fun source(relative: String): String {
        val f = File(mainKotlin, relative)
        assertWithMessage("expected source file to exist: ${f.path} (run from project root)")
            .that(f.isFile).isTrue()
        return f.readText()
    }

    /** The field names [tribixbite.cleverkeys.prefs.ConfigSnapshot] mirrors from `Config`. */
    private fun snapshotFieldNames(): Set<String> =
        source("tribixbite/cleverkeys/prefs/ConfigSnapshot.kt")
            .lines()
            .filterNot { it.contains("get()") } // derived properties are not mirrored fields
            .mapNotNull { Regex("""^\s*val\s+(\w+):""").find(it)?.groupValues?.get(1) }
            .toSet()

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
    fun hotPathFilesReadOnlyAllowlistedLiveConfigMembers() {
        allowedLiveConfigMembers.forEach { (relative, allowed) ->
            val found = liveConfigMemberAccess.findAll(withoutComments(source(relative)))
                .map { it.groupValues[1] }
                .toSortedSet()
            val forbidden = found - allowed
            assertWithMessage(
                "$relative reads live Config members $forbidden outside the ARC-072 " +
                    "allowlist $allowed. Config.refresh() rewrites 157 vars in place while a " +
                    "gesture or frame is running, so a live read here can disagree with the " +
                    "values the SAME gesture/frame already used. Capture the snapshot once at " +
                    "the start of the unit of work (pointer-down / draw / measure) and read " +
                    "`snap.<field>`. Only add to the allowlist for something that is genuinely " +
                    "not configuration state (a dispatch callback, say)."
            ).that(forbidden).isEmpty()
        }
    }

    @Test
    fun hotPathFilesDoNotAliasTheLiveConfig() {
        // An alias would make the member-access enumeration above blind: `val c = _config`
        // followed by `c.margin_left` reads live config without ever writing `_config.`.
        val alias = Regex("""\b(?:val|var)\s+\w+\s*=\s*_?config\s*$""", RegexOption.MULTILINE)
        allowedLiveConfigMembers.keys.forEach { relative ->
            assertWithMessage(
                "$relative aliases its live Config into a local. That hides live field reads " +
                    "from the allowlist pin above — capture `_config.snapshot` instead."
            ).that(alias.containsMatchIn(withoutComments(source(relative)))).isFalse()
        }
    }

    @Test
    fun captureSiteFilesAcquireTheLiveConfigAtMostOnce() {
        liveConfigAcquisitionLimit.forEach { (relative, limit) ->
            val acquisitions = Regex("""Config\.globalConfig\(\)""").findAll(source(relative)).count()
            assertWithMessage(
                "$relative may acquire the live Config exactly once (it is inflated from XML, " +
                    "so there is no constructor to inject one), found $acquisitions. Every " +
                    "further read must go through the captured snapshot."
            ).that(acquisitions).isAtMost(limit)
        }
    }

    @Test
    fun pointersCapturesOneSnapshotPerPointerAtTouchDown() {
        val pointers = source("tribixbite/cleverkeys/Pointers.kt")

        assertWithMessage(
            "Pointer must carry the snapshot captured when it was created, as an immutable " +
                "`val` — that IS the gesture scope: every decision for this pointer reads the " +
                "same configuration from down to up."
        ).that(pointers).containsMatch("""val snap: ConfigSnapshot""")

        val lines = pointers.lines()
        val start = lines.indexOfFirst { it.startsWith("    fun onTouchDown(") }
        assertWithMessage("Pointers.onTouchDown must exist at member indentation").that(start).isAtLeast(0)
        val end = (start + 1 until lines.size).first { lines[it] == "    }" }
        val body = lines.subList(start + 1, end).joinToString("\n")
        assertWithMessage(
            "onTouchDown is the pointer-DOWN entry point and must be where the snapshot is " +
                "captured — a Config.refresh() landing mid-gesture then applies from the NEXT " +
                "pointer-down instead of perturbing the gesture in flight."
        ).that(body).contains("_config.snapshot")
    }

    @Test
    fun noDirectWriteToASnapshotMirroredConfigField() {
        val mirrored = snapshotFieldNames()
        assertWithMessage("ConfigSnapshot field names must be parseable from its source")
            .that(mirrored).isNotEmpty()

        val offenders = mainKotlin.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                withoutComments(file.readText()).lines().withIndex().mapNotNull { (i, line) ->
                    val member = directConfigFieldWrite.find(line)?.groupValues?.get(1)
                    if (member != null && member in mirrored) "${file.path}:${i + 1}: ${line.trim()}"
                    else null
                }
            }
            .sorted()
            .toList()

        assertWithMessage(
            "These sites write a ConfigSnapshot-mirrored field directly on a live Config, " +
                "outside refresh(). The published snapshot keeps the OLD value until the next " +
                "refresh, so the touch/draw hot paths run on configuration the user already " +
                "changed. Use `Config.edit { field = value }` — it applies the write and " +
                "re-publishes the snapshot in one step.\n" + offenders.joinToString("\n")
        ).that(offenders).isEmpty()
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

        // Exactly one re-assignment, inside publishSnapshot(). The declaration's initializer
        // (`var snapshot: ConfigSnapshot = ...`) deliberately does not match this pattern.
        // Publication has one implementation and several callers, rather than several
        // assignment sites that could each drift.
        val reassignments = Regex("""^\s*snapshot = """, RegexOption.MULTILINE)
            .findAll(config).count()
        assertWithMessage(
            "Config.snapshot must be assigned in exactly one place (publishSnapshot()), " +
                "found $reassignments. Every other config mutation path would leave the " +
                "snapshot stale without an obvious rebuild site."
        ).that(reassignments).isEqualTo(1)

        // Structural stand-in for the pure-JVM refresh test Config's Android deps make
        // impossible: the rebuild is the LAST statement of each mutation entry point, so
        // no field it writes can be missed and no early exit can skip it.
        val lines = config.lines()
        listOf("    fun refresh(", "    fun edit(").forEach { signature ->
            val start = lines.indexOfFirst { it.startsWith(signature) }
            assertWithMessage("`$signature…` must exist at member indentation").that(start).isAtLeast(0)
            val end = (start + 1 until lines.size).first { lines[it] == "    }" }
            val body = lines.subList(start + 1, end)
            val lastStatement = body.last { it.isNotBlank() }.trim()
            assertWithMessage(
                "The last statement of `$signature…` must publish the snapshot so the " +
                    "captured read-model can never lag the 157 vars it mirrors. Found: " +
                    "\"$lastStatement\""
            ).that(lastStatement).isEqualTo("publishSnapshot()")

            assertWithMessage(
                "`$signature…` must have a single exit — an early `return` would skip the " +
                    "publication at its tail and leave a stale read-model live."
            ).that(body.none { Regex("""^\s*return\b""").containsMatchIn(it) }).isTrue()
        }
    }
}
