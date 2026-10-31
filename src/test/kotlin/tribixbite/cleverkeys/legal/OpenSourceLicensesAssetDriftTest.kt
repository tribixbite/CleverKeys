package tribixbite.cleverkeys.legal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The in-app "Open-source licenses" screen shows exactly what the repository says.
 *
 * Single source of truth: `NOTICE`, `LICENSE` and `LICENSES/<spdx>.txt` are committed;
 * `generateLicenseAssets` copies them to `build/generated/licenseAssets/licenses/`, which
 * build.gradle adds to the main assets (runPureTests depends on the task, so the copy is
 * current when this runs). Pinned here:
 *
 *  1. every licence NOTICE cites on a `License:` / `Code:` / `Data:` line is one of
 *     [OpenSourceLicenses.LICENSES] — a new NOTICE section with a new licence fails until its
 *     text is added;
 *  2. every catalogued licence is cited by NOTICE (no orphan text in the APK) and has its
 *     source text (`LICENSE` for GPL-3.0-only, `LICENSES/<id>.txt` otherwise), and `LICENSES/`
 *     holds nothing else;
 *  3. the packaged assets are byte-identical to those sources, with no extra file;
 *  4. the generated directory really is a main-assets source directory.
 *
 * Project root as CWD, like the other repo-scanning pure tests.
 */
class OpenSourceLicensesAssetDriftTest {

    private fun required(path: String): File = File(path).also {
        check(it.isFile) { "${it.absolutePath} not found — run with the project root as CWD." }
    }

    private val notice = required("NOTICE").readText()

    /** Committed source of each catalogued licence text. */
    private fun sourceOf(license: OpenSourceLicenses.License): File =
        if (license.spdxId == GPL) File("LICENSE") else File("LICENSES/${license.spdxId}.txt")

    @Test
    fun everyLicenceLineInNoticeNamesACataloguedLicence() {
        val licenceLines = notice.lines().filter { LICENCE_LINE.containsMatchIn(it) }
        // NOTICE had 14 such lines on 2026-10-31; a parser that found none would pass vacuously.
        assertWithMessage("License:/Code:/Data: lines found in NOTICE").that(licenceLines.size).isAtLeast(10)
        for (line in licenceLines) {
            assertWithMessage(
                "NOTICE line '${line.trim()}' names no licence in OpenSourceLicenses.LICENSES — " +
                    "add the licence's full text to LICENSES/ and its entry to the catalogue"
            ).that(OpenSourceLicenses.licensesCitedBy(line)).isNotEmpty()
        }
    }

    @Test
    fun everyCataloguedLicenceIsCitedAndHasItsSourceText() {
        val cited = OpenSourceLicenses.licensesCitedBy(notice).map { it.spdxId }
        assertWithMessage("catalogued licences that NOTICE never cites (orphan texts in the APK)")
            .that(OpenSourceLicenses.LICENSES.map { it.spdxId } - cited.toSet()).isEmpty()

        for (license in OpenSourceLicenses.LICENSES) {
            val source = sourceOf(license)
            assertWithMessage("${license.spdxId}: source text ${source.path}").that(source.isFile).isTrue()
            assertWithMessage("${license.spdxId}: ${source.path} is empty").that(source.length()).isGreaterThan(500L)
        }
        // The text files are what their names say (catches a swapped or truncated download).
        mapOf(
            GPL to "GNU GENERAL PUBLIC LICENSE",
            "LGPL-3.0-only" to "GNU LESSER GENERAL PUBLIC LICENSE",
            "Apache-2.0" to "Apache License",
            "MIT" to "MIT License",
            "Unicode-3.0" to "UNICODE LICENSE V3",
            "CC-BY-SA-4.0" to "Attribution-ShareAlike 4.0 International",
            "CC-BY-SA-3.0" to "Attribution-ShareAlike 3.0 Unported",
            "CC-BY-4.0" to "Attribution 4.0 International",
            "CC-BY-2.0-FR" to "Paternité - 2.0",
        ).forEach { (id, heading) ->
            val license = OpenSourceLicenses.LICENSES.single { it.spdxId == id }
            assertWithMessage("$id text starts with its title").that(sourceOf(license).readText().take(400)).contains(heading)
        }

        val committed = File("LICENSES").listFiles { f -> f.name.endsWith(".txt") }!!.map { it.name }.sorted()
        val expected = OpenSourceLicenses.LICENSES.filter { it.spdxId != GPL }.map { "${it.spdxId}.txt" }.sorted()
        assertWithMessage("LICENSES/*.txt must be exactly the catalogue (GPL-3.0-only comes from LICENSE)")
            .that(committed).isEqualTo(expected)
    }

    @Test
    fun packagedAssetsAreByteIdenticalToTheirSources() {
        val dir = File(GENERATED_DIR, OpenSourceLicenses.ASSET_DIR)
        assertWithMessage(
            "${dir.path} missing — generateLicenseAssets did not run (runPureTests depends on it)"
        ).that(dir.isDirectory).isTrue()

        val expected = buildMap {
            put(OpenSourceLicenses.NOTICE_ASSET, File("NOTICE"))
            OpenSourceLicenses.LICENSES.forEach { put(it.assetPath, sourceOf(it)) }
        }
        val packaged = dir.listFiles()!!.map { "${OpenSourceLicenses.ASSET_DIR}/${it.name}" }.sorted()
        assertWithMessage("packaged licence assets").that(packaged).isEqualTo(expected.keys.sorted())
        for ((asset, source) in expected) {
            assertWithMessage("$asset must equal ${source.path} byte for byte")
                .that(File(GENERATED_DIR, asset).readBytes().contentEquals(source.readBytes())).isTrue()
        }
    }

    @Test
    fun generatedDirectoryIsAMainAssetsSourceAndTheScreenIsReachable() {
        val gradle = required("build.gradle").readText()
        val mainAssets = Regex("""(?m)^\s*assets\.srcDirs\s*=\s*\[([^\]]*)]""").find(gradle)?.groupValues?.get(1)
        assertWithMessage("main assets.srcDirs").that(mainAssets).contains("'$GENERATED_DIR'")
        assertThat(gradle).contains("tasks.named(\"preBuild\") { dependsOn 'generateLicenseAssets' }")

        assertThat(required("AndroidManifest.xml").readText())
            .contains("android:name=\"tribixbite.cleverkeys.OpenSourceLicensesActivity\"")
        assertThat(required("src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/HelpSection.kt").readText())
            .contains("openOpenSourceLicenses()")
    }

    private companion object {
        const val GPL = "GPL-3.0-only"
        const val GENERATED_DIR = "build/generated/licenseAssets"

        /** A NOTICE line that states a licence: `License:`, `Licence:`, `Code:` or `Data:`. */
        val LICENCE_LINE = Regex("""^\s*(?:Licen[cs]e|Code|Data)\s*:""")
    }
}
