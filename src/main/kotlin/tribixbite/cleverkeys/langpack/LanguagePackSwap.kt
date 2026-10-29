package tribixbite.cleverkeys.langpack

import java.io.File

/**
 * The four filesystem operations the language-pack install swap and its recovery perform.
 *
 * A seam, not an abstraction for its own sake: every step of the swap must be individually
 * failable in a pure JVM test (a rename that returns false, a delete that stops half way, a
 * process death between two steps), and `java.io.File` offers no way to make one specific
 * call fail. Production always uses [Real].
 */
interface PackFileOps {
    fun exists(file: File): Boolean
    fun list(dir: File): List<File>
    fun rename(from: File, to: File): Boolean
    fun deleteRecursively(file: File): Boolean

    /** The plain `java.io.File` behaviour. */
    object Real : PackFileOps {
        override fun exists(file: File): Boolean = file.exists()
        override fun list(dir: File): List<File> = dir.listFiles()?.toList().orEmpty()
        override fun rename(from: File, to: File): Boolean = from.renameTo(to)
        override fun deleteRecursively(file: File): Boolean = file.deleteRecursively()
    }
}

/**
 * Installs a fully staged language pack over `<root>/<code>` without ever having zero copies
 * of the pack on disk, and repairs whatever an interrupted swap left behind.
 *
 * ## Why the old swap could lose the pack (todo item 11)
 *
 * The importer used to `deleteRecursively()` the installed directory and then `renameTo` the
 * staged one into its place. Between those two calls nothing was installed: a process death,
 * a rename that returned false, or a delete that stopped half way (leaving a non-empty
 * directory, onto which `rename(2)` fails with ENOTEMPTY) all cost the user the pack they
 * already had, and the half-done state was never repaired.
 *
 * ## The swap
 *
 *  1. rename `<code>` → `.backup-<code>` (the old pack, intact, now invisible to listings);
 *  2. rename `.staging-<code>-<n>` → `<code>` (the new pack, complete by construction: the
 *     importer copies the manifest into staging LAST);
 *  3. delete `.backup-<code>`.
 *
 * Every step is a rename within one directory (one filesystem, so each is atomic) or the
 * deletion of a directory that is no longer the installed copy. If step 2 fails the backup is
 * renamed straight back. A process death anywhere leaves at least one complete copy, and
 * [recover] — run before any pack is enumerated or loaded — turns that into exactly one.
 *
 * ## Recovery rules (idempotent; each step is itself crash-safe)
 *
 *  - `.backup-<code>`: if `<code>` is complete the backup is stale and is deleted; otherwise
 *    the backup is the user's pack and is renamed back (an incomplete `<code>` is removed
 *    first — only a legacy partial delete can produce one).
 *  - `.staging-<code>-<n>`: deleted, except when `<code>` is still absent after the backup
 *    pass and the staging directory is complete — then the newest such directory is promoted.
 *    That rescues a first install interrupted at its final rename, and the one state the OLD
 *    swap could leave (installed copy deleted, complete replacement still staged).
 *  - `.trash-<code>-<n>` (a [remove] interrupted mid-delete): deleted.
 *
 * Pure JVM (no `android.util.Log`): actions are returned as text for the caller to log, so the
 * whole state machine is testable by the pure suite with a fault-injecting [PackFileOps].
 */
class LanguagePackSwap(
    private val root: File,
    private val ops: PackFileOps = PackFileOps.Real,
    /**
     * The members whose presence makes a directory a COMPLETE pack. Language packs: manifest +
     * dictionary (the default). Voice packs (`stt.VoicePackManager`, 2026-10-29) pass only the
     * manifest: their importer copies the manifest last too, so its presence alone proves the
     * declared files were all copied.
     */
    private val completeMembers: List<String> = listOf(MANIFEST_FILE, DICTIONARY_FILE),
) {

    /** Result of [install]. */
    sealed interface Outcome {
        /**
         * The staged pack is installed. [backupLeft] is true when the previous copy could not
         * be deleted; it is invisible to every listing and the next [recover] retries it.
         */
        data class Installed(val backupLeft: Boolean) : Outcome

        /** Nothing was installed and the previously installed pack (if any) is untouched. */
        data object Refused : Outcome

        /**
         * The new pack could not be renamed into place AND the old one could not be renamed
         * back. The old pack is intact under its backup name; [recover] restores it.
         */
        data object RollbackPending : Outcome
    }

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val DICTIONARY_FILE = "dictionary.bin"
        private const val STAGING_PREFIX = ".staging-"
        private const val BACKUP_PREFIX = ".backup-"
        private const val TRASH_PREFIX = ".trash-"

        /**
         * G-1 (comprehensive audit 2026-09-06): a pack code becomes a path component, so it
         * must be a plain code — a 2-3 letter base plus up to 4 alphanumeric segments joined
         * by `-`/`_`. Never `/`, `\`, `.` or empty. Shared with the importer so recovery only
         * ever acts on names the importer could have produced.
         */
        val VALID_PACK_CODE = Regex("^[a-z]{2,3}(?:[_-][a-z0-9]{1,16}){0,4}$")

        private val STAGING_NAME = Regex("^\\.staging-(.+)-(\\d+)$")
        private val TRASH_NAME = Regex("^\\.trash-(.+)-(\\d+)$")
    }

    /** The staging directory name for [code]; [stamp] keeps concurrent attempts distinct. */
    fun stagingDir(code: String, stamp: Long): File = File(root, "$STAGING_PREFIX$code-$stamp")

    private fun backupDir(code: String) = File(root, "$BACKUP_PREFIX$code")
    private fun finalDir(code: String) = File(root, code)

    /** A pack directory the importer finished: every [completeMembers] file present. */
    private fun isComplete(dir: File): Boolean = completeMembers.all { ops.exists(File(dir, it)) }

    /**
     * Move the complete [staged] directory into place as pack [code]. On anything other than
     * [Outcome.Installed] the caller still owns [staged] and should delete it.
     */
    fun install(staged: File, code: String): Outcome {
        require(VALID_PACK_CODE.matches(code)) { "invalid pack code: $code" }
        val final = finalDir(code)
        val backup = backupDir(code)

        // A backup left by an earlier interrupted swap would make step 1 fail. Settle it the
        // way recovery would: it is either stale (final complete) or the user's only copy.
        if (ops.exists(backup)) {
            val settled = if (isComplete(final)) {
                ops.deleteRecursively(backup)
            } else {
                (!ops.exists(final) || ops.deleteRecursively(final)) && ops.rename(backup, final)
            }
            if (!settled) return Outcome.Refused
        }

        val hadOld = ops.exists(final)
        if (hadOld && !ops.rename(final, backup)) return Outcome.Refused
        if (!ops.rename(staged, final)) {
            if (!hadOld) return Outcome.Refused
            return if (ops.rename(backup, final)) Outcome.Refused else Outcome.RollbackPending
        }
        if (!hadOld) return Outcome.Installed(backupLeft = false)
        return Outcome.Installed(backupLeft = !ops.deleteRecursively(backup))
    }

    /**
     * Remove pack [code]: renamed to a trash name first (one atomic step, after which the pack
     * is gone from every query), then deleted. A delete that stops half way leaves only
     * invisible trash for [recover]. If the rename itself is refused, falls back to deleting
     * in place — the pre-existing behaviour.
     *
     * @return true when the pack directory no longer exists under its own name.
     */
    fun remove(code: String, stamp: Long): Boolean {
        val final = finalDir(code)
        if (!ops.exists(final)) return false
        val trash = File(root, "$TRASH_PREFIX$code-$stamp")
        if (!ops.rename(final, trash)) return ops.deleteRecursively(final)
        ops.deleteRecursively(trash)
        return true
    }

    /**
     * Repair every interrupted swap and removal under [root]. Returns a description of each
     * action taken (empty when there was nothing to do). Safe to run any number of times and
     * to be interrupted itself; must not run concurrently with an [install] of the same root.
     */
    fun recover(): List<String> {
        val actions = mutableListOf<String>()

        // 1. Backups first: they decide whether each code ends up with an installed copy.
        for (dir in ops.list(root)) {
            val name = dir.name
            if (!name.startsWith(BACKUP_PREFIX)) continue
            val code = name.removePrefix(BACKUP_PREFIX)
            if (!VALID_PACK_CODE.matches(code)) continue
            val final = finalDir(code)
            when {
                isComplete(final) -> {
                    val gone = ops.deleteRecursively(dir)
                    actions += "$code: discarded stale backup" + if (gone) "" else " (incomplete delete)"
                }
                isComplete(dir) -> {
                    val cleared = !ops.exists(final) || ops.deleteRecursively(final)
                    val restored = cleared && ops.rename(dir, final)
                    actions += "$code: " + if (restored) "restored backup" else "FAILED to restore backup"
                }
                else -> actions += "$code: left incomplete backup in place (no complete copy)"
            }
        }

        // 2. Staging: promote the newest complete one for a code that still has no install.
        val staging = ops.list(root).mapNotNull { dir ->
            STAGING_NAME.matchEntire(dir.name)?.let { m -> Triple(dir, m.groupValues[1], m.groupValues[2].toLongOrNull() ?: 0L) }
        }
        for ((code, group) in staging.groupBy { it.second }) {
            val ordered = group.sortedByDescending { it.third }.map { it.first }
            var promoted: File? = null
            if (VALID_PACK_CODE.matches(code) && !ops.exists(finalDir(code)) && !ops.exists(backupDir(code))) {
                promoted = ordered.firstOrNull { isComplete(it) && ops.rename(it, finalDir(code)) }
                if (promoted != null) actions += "$code: installed interrupted staged pack ${promoted.name}"
            }
            for (dir in ordered) {
                if (dir == promoted) continue
                ops.deleteRecursively(dir)
                actions += "$code: discarded staging ${dir.name}"
            }
        }

        // 3. Trash from an interrupted removal.
        for (dir in ops.list(root)) {
            if (TRASH_NAME.matches(dir.name)) {
                ops.deleteRecursively(dir)
                actions += "discarded ${dir.name}"
            }
        }
        return actions
    }
}
