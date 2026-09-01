package tribixbite.cleverkeys

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the root `NOTICE` against the set of ONNX models actually packaged in the APK.
 *
 * ARC-090: `NOTICE` named only `ctc_swipe_encoder.onnx` while
 * `ru_synth_v3_ch80_fp16w.onnx` had been shipping since `da012ded`. Nothing caught it,
 * because model attribution was prose that no build step reads. Adding a second script
 * model is a one-line APK change and a one-line `NOTICE` change, and only the first of
 * those is forced by the compiler — so this test forces the second.
 *
 * Both directions are asserted:
 *
 *  - **every** `*.onnx` under `src/main/assets/models/` must be named in `NOTICE`
 *    (a new script model without attribution fails here);
 *  - **every** model filename `NOTICE` names must still exist on disk (a removed model
 *    leaves stale attribution behind, which is its own accuracy defect).
 *
 * Filenames are matched verbatim, so a rename (e.g. a `_v4` generation bump) fails on
 * both sides at once and cannot be papered over by editing only one of the two places.
 *
 * Runs with the project root as CWD (same convention as [ReleaseMetadataDriftTest]).
 */
class NoticeModelAttributionDriftTest {

    private val modelsDir = File("src/main/assets/models")
    private val notice = File("NOTICE")

    /** Every packaged model filename, e.g. `ctc_swipe_encoder.onnx`. */
    private fun packagedModelFiles(): List<String> {
        assertTrue(
            "${modelsDir.absolutePath} not found — drift test must run with project root as CWD",
            modelsDir.isDirectory
        )
        val models = modelsDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "onnx" }
            .map { it.name }
            .sorted()
        assertTrue(
            "no .onnx assets found under ${modelsDir.path} — the swipe engine ships at least " +
                "the Latin encoder, so an empty list means the scan (or the packaging) broke",
            models.isNotEmpty()
        )
        return models
    }

    @Test
    fun everyPackagedOnnxModelIsAttributedInNotice() {
        assertTrue("NOTICE not found at ${notice.absolutePath}", notice.isFile)
        val text = notice.readText()
        for (model in packagedModelFiles()) {
            assertTrue(
                "NOTICE does not name the packaged model '$model'. Every shipped script model " +
                    "needs its own provenance line under the \"Shipped CTC script models\" " +
                    "section — training corpora and their licences, and (for a synthesized " +
                    "model) the statement that no real gesture data of that script was used. " +
                    "Do not satisfy this test by deleting the model.",
                text.contains(model)
            )
        }
    }

    @Test
    fun noticeDoesNotAttributeModelsThatNoLongerShip() {
        assertTrue("NOTICE not found at ${notice.absolutePath}", notice.isFile)
        val packaged = packagedModelFiles().toSet()
        val namedInNotice = Regex("""[\w.-]+\.onnx""").findAll(notice.readText())
            .map { it.value }
            .toSortedSet()
        assertTrue(
            "NOTICE has no .onnx filenames at all — the per-model attribution section was " +
                "removed or reworded away from filenames, and this ratchet stops working",
            namedInNotice.isNotEmpty()
        )
        val stale = namedInNotice - packaged
        assertTrue(
            "NOTICE attributes model(s) that are no longer packaged: $stale. Remove the stale " +
                "provenance line(s) rather than leaving attribution for bytes that do not ship.",
            stale.isEmpty()
        )
    }
}
