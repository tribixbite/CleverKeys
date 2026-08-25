package tribixbite.cleverkeys

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport

/**
 * Pins the release-facing copy (root `RELEASE_NOTES.md` + the three per-ABI fastlane
 * changelogs) against the runtime CTC policy it describes.
 *
 * CK-150-032a closed two blind spots in the original version of this test:
 *
 *  1. the byte-parity assertion ran over `texts.drop(1)`, so `RELEASE_NOTES.md` — the
 *     human-facing source of truth — was compared to nothing. All FOUR files are now in
 *     the identity check.
 *  2. the changelog paths were hardcoded to `10600{1,2,3}.txt`. A version bump left the
 *     test green while asserting against the PREVIOUS release's notes. The paths are now
 *     derived from `build.gradle`, so bumping the version without writing fresh changelog
 *     files fails here (missing file) instead of shipping stale notes.
 */
class ReleaseMetadataDriftTest {

    private val buildGradle = File("build.gradle")

    /**
     * The per-ABI versionCode packing, read from the single source of truth.
     *
     * `build.gradle` (~:83-121) declares:
     * ```
     * ext.versionCode  = MAJOR * 10000 + MINOR * 100 + PATCH
     * ext.abiCodes     = ['armeabi-v7a': 1, 'arm64-v8a': 2, 'x86_64': 3]
     * output.versionCodeOverride = variant.versionCode * 10 + abiCode
     * ```
     * so 1.6.0 → base 10600 → `106001` (armv7), `106002` (arm64), `106003` (x86_64).
     * F-Droid monotonicity depends on MINOR/PATCH staying < 100; build.gradle throws on
     * that, and this test would silently start reading a colliding file, so it is
     * asserted here too.
     */
    private fun versionPart(name: String, text: String): Int {
        val m = Regex("""ext\.VERSION_$name\s*=\s*(\d+)""").find(text)
            ?: throw AssertionError("build.gradle no longer declares ext.VERSION_$name")
        return m.groupValues[1].toInt()
    }

    private val abiChangelogFiles: List<File> by lazy {
        val text = buildGradle.readText()
        val major = versionPart("MAJOR", text)
        val minor = versionPart("MINOR", text)
        val patch = versionPart("PATCH", text)
        assertTrue(
            "versionCode packing collides once MINOR/PATCH reach 100 (build.gradle guard)",
            minor < 100 && patch < 100
        )
        val baseCode = major * 10000 + minor * 100 + patch
        // ABI suffixes in build.gradle's ext.abiCodes order: armv7=1, arm64=2, x86_64=3.
        (1..3).map {
            File("fastlane/metadata/android/en-US/changelogs/${baseCode * 10 + it}.txt")
        }
    }

    private val releaseFiles: List<File> by lazy { listOf(File("RELEASE_NOTES.md")) + abiChangelogFiles }

    @Test
    fun releaseChannelsAgreeWithRuntimeCtcPolicy() {
        for (file in releaseFiles) {
            assertTrue(
                "${file.path} is missing — a version bump must ship fresh release notes for " +
                    "every ABI (derived from build.gradle's VERSION_MAJOR/MINOR/PATCH)",
                file.isFile
            )
        }
        val texts = releaseFiles.map { it.readText() }
        assertEquals(
            "RELEASE_NOTES.md and all three ABI changelogs must be byte-identical",
            1, texts.distinct().size
        )
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
