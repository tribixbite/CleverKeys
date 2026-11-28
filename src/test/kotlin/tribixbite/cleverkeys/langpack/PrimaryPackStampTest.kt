package tribixbite.cleverkeys.langpack

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [PrimaryPackStamp]: when a language-packs broadcast must reload the PRIMARY dictionary
 * (CleverKeysService.onLanguagePacksChanged, audit B5 2026-11-26). Was pinned only by a source
 * scan (LearningWiringDriftTest); test-gap audit 2026-11-28, item 12.
 */
class PrimaryPackStampTest {

    private fun stamp(language: String, path: String?, length: Long = 1_000, modified: Long = 10_000) =
        PrimaryPackStamp(language, path?.let { PrimaryPackStamp.fingerprint(it, length, modified) })

    @Test
    fun theFirstObservationReloadsNothing() {
        assertWithMessage("no stamp yet").that(PrimaryPackStamp.primaryNeedsReload(null, stamp("en", "/p/en"))).isFalse()
    }

    @Test
    fun anUnchangedPackReloadsNothing() {
        assertWithMessage("same file").that(PrimaryPackStamp.primaryNeedsReload(stamp("en", "/p/en"), stamp("en", "/p/en"))).isFalse()
        assertWithMessage("bundled, still bundled").that(PrimaryPackStamp.primaryNeedsReload(stamp("en", null), stamp("en", null))).isFalse()
    }

    @Test
    fun aReimportedPackReloadsEvenAtTheSameSize() {
        val before = stamp("en", "/p/en", length = 1_000, modified = 10_000)
        assertWithMessage("same size, new file time").that(
            PrimaryPackStamp.primaryNeedsReload(before, stamp("en", "/p/en", length = 1_000, modified = 10_001))
        ).isTrue()
        assertWithMessage("another size").that(
            PrimaryPackStamp.primaryNeedsReload(before, stamp("en", "/p/en", length = 1_001, modified = 10_000))
        ).isTrue()
    }

    @Test
    fun anImportedOrRemovedPackReloads() {
        assertWithMessage("bundled → pack").that(PrimaryPackStamp.primaryNeedsReload(stamp("en", null), stamp("en", "/p/en"))).isTrue()
        assertWithMessage("pack → bundled").that(PrimaryPackStamp.primaryNeedsReload(stamp("en", "/p/en"), stamp("en", null))).isTrue()
    }

    @Test
    fun aLanguageChangeIsLeftToTheLanguageSync() {
        // ActiveLanguageSync reloads a changed primary; a second reload here would be a duplicate.
        assertWithMessage("en → fr").that(PrimaryPackStamp.primaryNeedsReload(stamp("en", "/p/en"), stamp("fr", "/p/fr"))).isFalse()
    }

    /**
     * The same-size re-import case the stamp relies on file times for: the importer copies every
     * member with Kotlin's `File.copyTo` (LanguagePackManager.stageAndSwap), which does NOT keep
     * the source's modification time, so the installed dictionary's time is the copy's time and
     * a later re-import of a same-size file has a later one. Pinned here because the stamp (and
     * the swipe engines' pack fingerprints, which use the same idiom) depends on it.
     */
    @Test
    fun aReimportCopyGetsAFreshModificationTime() {
        val dir = Files.createTempDirectory("pack-stamp").toFile()
        try {
            val source = File(dir, "dictionary.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
            val old = 1_600_000_000_000L
            check(source.setLastModified(old)) { "this file system cannot set a modification time" }
            val installed = source.copyTo(File(dir, "installed.bin"), overwrite = true)
            assertWithMessage("the copy's time is not the source's").that(installed.lastModified()).isNotEqualTo(old)
            assertWithMessage("so the stamps differ at the same size").that(
                PrimaryPackStamp.primaryNeedsReload(
                    PrimaryPackStamp.of("en", installed.path, installed.length(), old),
                    PrimaryPackStamp.of("en", installed.path, installed.length(), installed.lastModified()),
                )
            ).isTrue()
        } finally {
            dir.deleteRecursively()
        }
    }
}
