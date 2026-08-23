package tribixbite.cleverkeys

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport

class ReleaseMetadataDriftTest {
    private val releaseFiles = listOf(
        File("RELEASE_NOTES.md"),
        File("fastlane/metadata/android/en-US/changelogs/106001.txt"),
        File("fastlane/metadata/android/en-US/changelogs/106002.txt"),
        File("fastlane/metadata/android/en-US/changelogs/106003.txt"),
    )

    @Test
    fun releaseChannelsAgreeWithRuntimeCtcPolicy() {
        val texts = releaseFiles.map { it.readText() }
        assertEquals("ABI release notes must be byte-identical", 1, texts.drop(1).distinct().size)
        for ((file, text) in releaseFiles.zip(texts)) {
            assertTrue("${file.path} must say CTC is default", text.contains("CTC is the default"))
            assertTrue("${file.path} must list validated languages", text.contains("en/fr/de/es validated"))
            assertTrue("${file.path} must list provisional languages", text.contains("it/pt/sv") && text.contains("provisional"))
            assertTrue("${file.path} must explain fallback", text.contains("geometric fallback"))
            assertFalse("${file.path} must not call CTC opt-in", text.contains("CTC engine (opt-in)"))
        }
        assertEquals(setOf("en", "fr", "de", "es", "it", "pt", "sv"), CtcLanguageSupport.SUPPORTED.keys)
        assertEquals(setOf("it", "pt", "sv"), CtcLanguageSupport.PROVISIONAL)
        assertEquals("ctc", Defaults.SWIPE_ENGINE_MODE)
    }
}
