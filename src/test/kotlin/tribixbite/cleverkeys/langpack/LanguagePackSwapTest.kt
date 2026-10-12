package tribixbite.cleverkeys.langpack

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Fault-injection coverage for [LanguagePackSwap] (todo item 11): every step of the install
 * swap and of its recovery is made to fail, or the "process" is killed between two steps, and
 * the directory must always end with exactly one complete copy of the pack once [recover]
 * has run — never zero, never a half-swapped mix.
 *
 * Pure JVM: real files in a temp dir, with [FaultOps] wrapping [PackFileOps.Real] to refuse or
 * crash individual calls. A simulated crash throws [Crash] and makes every later call a no-op,
 * so nothing after the crash point can tidy up (as a real process death could not); "the next
 * app start" is a fresh [LanguagePackSwap] with real ops on the same directory.
 */
class LanguagePackSwapTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ck-packswap").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ fixtures

    private class Crash : Error("simulated process death")

    /**
     * Real filesystem behaviour, except: the mutating call numbered [crashAfter] (0-based)
     * throws [Crash] — after deleting a single file first when [partialOnCrash] and the call
     * is a delete — and every call after a crash is refused; [refuseRename] / [refuseDelete]
     * make a specific call return false (a refused delete first removes one file when
     * [partialDelete], the way `deleteRecursively` stops part way).
     */
    private class FaultOps(
        private val crashAfter: Int = Int.MAX_VALUE,
        private val partialOnCrash: Boolean = false,
        private val refuseRename: (File, File) -> Boolean = { _, _ -> false },
        private val refuseDelete: (File) -> Boolean = { false },
        private val partialDelete: Boolean = false,
    ) : PackFileOps {
        var crashed = false
            private set
        var mutations = 0
            private set

        override fun exists(file: File) = PackFileOps.Real.exists(file)
        override fun list(dir: File) = PackFileOps.Real.list(dir)

        override fun rename(from: File, to: File): Boolean = mutate(null) {
            if (refuseRename(from, to)) false else PackFileOps.Real.rename(from, to)
        }

        override fun deleteRecursively(file: File): Boolean = mutate(file) {
            if (refuseDelete(file)) {
                if (partialDelete) deleteOneFile(file)
                false
            } else {
                PackFileOps.Real.deleteRecursively(file)
            }
        }

        private fun mutate(deleting: File?, op: () -> Boolean): Boolean {
            if (crashed) return false
            if (mutations == crashAfter) {
                crashed = true
                if (partialOnCrash && deleting != null) deleteOneFile(deleting)
                throw Crash()
            }
            mutations++
            return op()
        }

        private fun deleteOneFile(dir: File) {
            File(dir, LanguagePackSwap.DICTIONARY_FILE).delete()
        }
    }

    /** A complete pack directory: manifest and dictionary both naming [version]. */
    private fun pack(dir: File, version: String): File {
        dir.mkdirs()
        File(dir, LanguagePackSwap.MANIFEST_FILE).writeText("""{"code":"el","version":"$version"}""")
        File(dir, LanguagePackSwap.DICTIONARY_FILE).writeText("dict-$version")
        File(dir, "unigrams.txt").writeText("uni-$version")
        return dir
    }

    private val final get() = File(root, "el")

    /** The version installed as `el`, asserting manifest and dictionary agree. */
    private fun installedVersion(): String? {
        val manifest = File(final, LanguagePackSwap.MANIFEST_FILE)
        val dictionary = File(final, LanguagePackSwap.DICTIONARY_FILE)
        if (!manifest.isFile || !dictionary.isFile) return null
        val version = Regex("\"version\":\"(\\w+)\"").find(manifest.readText())!!.groupValues[1]
        assertWithMessage("manifest and dictionary must come from the same pack version")
            .that(dictionary.readText()).isEqualTo("dict-$version")
        assertWithMessage("every member must come from the same pack version")
            .that(File(final, "unigrams.txt").readText()).isEqualTo("uni-$version")
        return version
    }

    private fun dotEntries(): List<String> = root.list().orEmpty().filter { it.startsWith(".") }.sorted()

    private fun realSwap() = LanguagePackSwap(root)

    private fun staged(version: String, stamp: Long = 1L) = pack(realSwap().stagingDir("el", stamp), version)

    // ------------------------------------------------------------------ plain swaps

    @Test
    fun firstInstallMovesTheStagedPackIntoPlace() {
        val staging = staged("v2")
        assertThat(realSwap().install(staging, "el")).isEqualTo(LanguagePackSwap.Outcome.Installed(false))
        assertThat(installedVersion()).isEqualTo("v2")
        assertThat(dotEntries()).isEmpty()
    }

    @Test
    fun updateReplacesThePackAndDiscardsTheOldCopy() {
        pack(final, "v1")
        assertThat(realSwap().install(staged("v2"), "el")).isEqualTo(LanguagePackSwap.Outcome.Installed(false))
        assertThat(installedVersion()).isEqualTo("v2")
        assertThat(dotEntries()).isEmpty()
        assertThat(realSwap().recover()).isEmpty()
    }

    // ------------------------------------------------------------------ refused steps

    @Test
    fun refusedRenameOfTheOldPackLeavesItUntouched() {
        pack(final, "v1")
        val staging = staged("v2")
        val ops = FaultOps(refuseRename = { from, _ -> from == final })
        assertThat(LanguagePackSwap(root, ops).install(staging, "el")).isEqualTo(LanguagePackSwap.Outcome.Refused)
        assertThat(installedVersion()).isEqualTo("v1")
        assertWithMessage("on Refused the caller still owns the staging dir").that(staging.isDirectory).isTrue()
    }

    /** The todo item 11 case: the old code had already deleted v1 by the time this rename failed. */
    @Test
    fun refusedRenameOfTheStagedPackRollsTheOldOneBack() {
        pack(final, "v1")
        val staging = staged("v2")
        val ops = FaultOps(refuseRename = { from, _ -> from == staging })
        assertThat(LanguagePackSwap(root, ops).install(staging, "el")).isEqualTo(LanguagePackSwap.Outcome.Refused)
        assertThat(installedVersion()).isEqualTo("v1")
        assertThat(dotEntries()).containsExactly(staging.name)
    }

    @Test
    fun refusedRenameOnAFirstInstallChangesNothing() {
        val staging = staged("v2")
        val ops = FaultOps(refuseRename = { _, _ -> true })
        assertThat(LanguagePackSwap(root, ops).install(staging, "el")).isEqualTo(LanguagePackSwap.Outcome.Refused)
        assertThat(final.exists()).isFalse()
    }

    @Test
    fun failedRollbackIsReportedAndRecoveryRestoresTheOldPack() {
        pack(final, "v1")
        val staging = staged("v2")
        // Refuses the staged rename AND the rollback rename (both target the final dir).
        val ops = FaultOps(refuseRename = { from, to -> to == final && from != final })
        assertThat(LanguagePackSwap(root, ops).install(staging, "el"))
            .isEqualTo(LanguagePackSwap.Outcome.RollbackPending)
        assertWithMessage("the old pack is intact under its backup name")
            .that(File(root, ".backup-el/dictionary.bin").readText()).isEqualTo("dict-v1")

        staging.deleteRecursively()  // what the importer's finally does
        assertThat(realSwap().recover()).containsExactly("el: restored backup")
        assertThat(installedVersion()).isEqualTo("v1")
        assertThat(dotEntries()).isEmpty()
    }

    @Test
    fun refusedBackupDeleteStillInstallsAndRecoveryFinishesTheCleanup() {
        pack(final, "v1")
        val ops = FaultOps(refuseDelete = { it.name == ".backup-el" }, partialDelete = true)
        assertThat(LanguagePackSwap(root, ops).install(staged("v2"), "el"))
            .isEqualTo(LanguagePackSwap.Outcome.Installed(backupLeft = true))
        assertThat(installedVersion()).isEqualTo("v2")
        assertThat(dotEntries()).containsExactly(".backup-el")

        assertThat(realSwap().recover()).containsExactly("el: discarded stale backup")
        assertThat(installedVersion()).isEqualTo("v2")
        assertThat(dotEntries()).isEmpty()
    }

    // ------------------------------------------------------------------ process death

    /**
     * Kill the "process" before each mutating call of an update in turn (and, for deletes,
     * part way through one), then start again. Before the staged rename lands the user keeps
     * v1; after it, v2. Either way exactly one complete pack and no leftovers.
     */
    @Test
    fun processDeathAtEveryStepOfAnUpdateRecoversToOneCompletePack() {
        val seen = mutableListOf<String>()
        for (partial in listOf(false, true)) {
            var crashAfter = 0
            while (true) {
                root.deleteRecursively(); root.mkdirs()
                pack(final, "v1")
                val ops = FaultOps(crashAfter = crashAfter, partialOnCrash = partial)
                val outcome = runCatching { LanguagePackSwap(root, ops).install(staged("v2"), "el") }
                if (!ops.crashed) {
                    assertThat(outcome.getOrThrow()).isEqualTo(LanguagePackSwap.Outcome.Installed(false))
                    break
                }
                assertThat(outcome.exceptionOrNull()).isInstanceOf(Crash::class.java)

                realSwap().recover()
                val version = installedVersion()
                assertWithMessage("crash before mutation $crashAfter (partial=$partial): a pack survives")
                    .that(version).isNotNull()
                assertWithMessage("crash before mutation $crashAfter (partial=$partial): no leftovers")
                    .that(dotEntries()).isEmpty()
                assertWithMessage("recovery is idempotent").that(realSwap().recover()).isEmpty()
                seen += "$crashAfter:$version"
                crashAfter++
            }
        }
        // Three mutations: rename aside, rename into place, delete backup.
        assertThat(seen).containsExactly("0:v1", "1:v1", "2:v2", "0:v1", "1:v1", "2:v2").inOrder()
    }

    @Test
    fun processDeathDuringAFirstInstallEitherInstallsOrLeavesNothing() {
        var crashAfter = 0
        while (true) {
            root.deleteRecursively(); root.mkdirs()
            val ops = FaultOps(crashAfter = crashAfter)
            val outcome = runCatching { LanguagePackSwap(root, ops).install(staged("v2"), "el") }
            if (!ops.crashed) {
                assertThat(outcome.getOrThrow()).isEqualTo(LanguagePackSwap.Outcome.Installed(false))
                break
            }
            realSwap().recover()
            // The staged dir was complete, so recovery finishes the interrupted install.
            assertThat(installedVersion()).isEqualTo("v2")
            assertThat(dotEntries()).isEmpty()
            crashAfter++
        }
        assertThat(crashAfter).isEqualTo(1)
    }

    /** Recovery itself can be killed at any step; running it again must still converge. */
    @Test
    fun processDeathDuringRecoveryConvergesOnTheNextRun() {
        for (partial in listOf(false, true)) {
            var crashAfter = 0
            while (true) {
                root.deleteRecursively(); root.mkdirs()
                // The worst interrupted state: old pack aside, new one still staged, plus trash.
                pack(File(root, ".backup-el"), "v1")
                staged("v2", stamp = 5L)
                pack(File(root, ".trash-el-3"), "v0")
                val ops = FaultOps(crashAfter = crashAfter, partialOnCrash = partial)
                val outcome = runCatching { LanguagePackSwap(root, ops).recover() }
                realSwap().recover()
                assertWithMessage("recovery killed before mutation $crashAfter (partial=$partial)")
                    .that(installedVersion()).isEqualTo("v1")
                assertThat(dotEntries()).isEmpty()
                if (!ops.crashed) {
                    assertThat(outcome.isSuccess).isTrue()
                    break
                }
                crashAfter++
            }
            assertThat(crashAfter).isEqualTo(3)
        }
    }

    // ------------------------------------------------------------------ recovery rules

    @Test
    fun recoveryRestoresABackupWhenThePackIsMissing() {
        pack(File(root, ".backup-el"), "v1")
        assertThat(realSwap().recover()).containsExactly("el: restored backup")
        assertThat(installedVersion()).isEqualTo("v1")
    }

    @Test
    fun recoveryReplacesAnIncompletePackWithACompleteBackup() {
        pack(File(root, ".backup-el"), "v1")
        final.mkdirs()
        File(final, "unigrams.txt").writeText("orphan")  // no manifest, no dictionary
        assertThat(realSwap().recover()).containsExactly("el: restored backup")
        assertThat(installedVersion()).isEqualTo("v1")
        assertThat(dotEntries()).isEmpty()
    }

    /** The state the OLD swap left after dying between its delete and its rename. */
    @Test
    fun recoveryInstallsACompleteStagedPackLeftByTheOldSwap() {
        staged("v2", stamp = 10L)
        staged("v1", stamp = 5L)
        File(realSwap().stagingDir("el", 20L), "dictionary.bin").apply { parentFile.mkdirs(); writeText("half") }
        assertThat(realSwap().recover()).containsExactly(
            "el: installed interrupted staged pack .staging-el-10",
            "el: discarded staging .staging-el-20",
            "el: discarded staging .staging-el-5",
        )
        assertWithMessage("the newest COMPLETE staged pack wins; a manifest-less one never installs")
            .that(installedVersion()).isEqualTo("v2")
        assertThat(dotEntries()).isEmpty()
    }

    @Test
    fun recoveryDiscardsStagingWhenAPackIsInstalled() {
        pack(final, "v1")
        staged("v2")
        assertThat(realSwap().recover()).containsExactly("el: discarded staging .staging-el-1")
        assertThat(installedVersion()).isEqualTo("v1")
    }

    @Test
    fun recoveryParsesHyphenatedCodesAndIgnoresUnrelatedOrInvalidNames() {
        pack(realSwap().stagingDir("en-web-50k", 7L), "v1")
        File(root, ".backup-..").mkdirs()
        File(root, ".DS_Store").writeText("x")
        File(root, "fr").mkdirs()
        assertThat(realSwap().recover()).containsExactly(
            "en-web-50k: installed interrupted staged pack .staging-en-web-50k-7"
        )
        assertThat(File(root, "en-web-50k/dictionary.bin").readText()).isEqualTo("dict-v1")
        assertThat(dotEntries()).containsExactly(".DS_Store", ".backup-..")
        assertThat(File(root, "fr").isDirectory).isTrue()
    }

    @Test
    fun installSettlesAStaleBackupBeforeSwapping() {
        // Stale backup beside a complete pack: discarded, then the update proceeds.
        pack(final, "v1")
        pack(File(root, ".backup-el"), "v0")
        assertThat(realSwap().install(staged("v2"), "el")).isEqualTo(LanguagePackSwap.Outcome.Installed(false))
        assertThat(installedVersion()).isEqualTo("v2")
        assertThat(dotEntries()).isEmpty()

        // Backup that is the ONLY copy: restored, then replaced through the normal swap.
        root.deleteRecursively(); root.mkdirs()
        pack(File(root, ".backup-el"), "v1")
        assertThat(realSwap().install(staged("v2"), "el")).isEqualTo(LanguagePackSwap.Outcome.Installed(false))
        assertThat(installedVersion()).isEqualTo("v2")
        assertThat(dotEntries()).isEmpty()
    }

    // ------------------------------------------------------------------ removal

    @Test
    fun removeDeletesThePackAndAnInterruptedRemoveIsFinishedByRecovery() {
        pack(final, "v1")
        assertThat(realSwap().remove("el", 1L)).isTrue()
        assertThat(final.exists()).isFalse()
        assertThat(dotEntries()).isEmpty()
        assertThat(realSwap().remove("el", 2L)).isFalse()

        pack(final, "v1")
        val ops = FaultOps(crashAfter = 1)  // dies after the rename, before the delete
        assertThat(runCatching { LanguagePackSwap(root, ops).remove("el", 3L) }.exceptionOrNull())
            .isInstanceOf(Crash::class.java)
        assertWithMessage("after the rename the pack is already gone from its own name")
            .that(final.exists()).isFalse()
        assertThat(realSwap().recover()).containsExactly("discarded .trash-el-3")
        assertThat(dotEntries()).isEmpty()
    }

    @Test
    fun removeFallsBackToDeletingInPlaceWhenTheRenameIsRefused() {
        pack(final, "v1")
        val ops = FaultOps(refuseRename = { _, _ -> true })
        assertThat(LanguagePackSwap(root, ops).remove("el", 1L)).isTrue()
        assertThat(final.exists()).isFalse()
    }
}
