package tribixbite.cleverkeys

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcPackModel
import tribixbite.cleverkeys.swipe.ctc.CtcScriptSupport

/**
 * Pins the root `NOTICE` against the set of ONNX models CleverKeys actually ships.
 *
 * ARC-090: `NOTICE` named only `ctc_swipe_encoder.onnx` while
 * `ru_synth_v3_ch80_fp16w.onnx` had been shipping since `da012ded`. Nothing caught it,
 * because model attribution was prose that no build step reads. Adding a second script
 * model is a one-line APK change and a one-line `NOTICE` change, and only the first of
 * those is forced by the compiler — so this test forces the second.
 *
 * Both directions are asserted:
 *
 *  - **every** shipped model must be named in `NOTICE` (a new script model without
 *    attribution fails here);
 *  - **every** model filename `NOTICE` names must still ship (a removed model leaves stale
 *    attribution behind, which is its own accuracy defect).
 *
 * Filenames are matched verbatim, so a rename (e.g. a `_v4` generation bump) fails on
 * both sides at once and cannot be papered over by editing only one of the two places.
 *
 * ## "Shipped" is two routes, not one (2026-09-10)
 *
 * The six per-script encoders left `src/main/assets/models/` and now travel inside their
 * language packs. They ship no less than before — the same bytes reach the same users,
 * just to *only* those users, since none of those six languages can decode without its pack
 * anyway. Scoping this test to the assets directory would therefore have quietly deleted
 * six models' provenance the moment they moved, which is exactly the failure ARC-090 was
 * about. So the shipped set is the union: APK assets plus the artifact each
 * [CtcScriptSupport] row delivers through its pack.
 *
 * Runs with the project root as CWD (same convention as [ReleaseMetadataDriftTest]).
 */
class NoticeModelAttributionDriftTest {

    private val modelsDir = File("src/main/assets/models")
    private val packDir = File("scripts/dictionaries")
    private val notice = File("NOTICE")

    /**
     * `model.onnx` is the pack MEMBER name — the container slot, identical across all six
     * packs — not an artifact name. It appears in `NOTICE` prose and as the prefix of every
     * pack-delivered line, and matching it as an attributable filename would make the
     * stale-attribution direction unsatisfiable.
     */
    private val packMemberName = CtcPackModel.PACK_MODEL_FILE

    /** Every model filename shipped in the APK itself, e.g. `ctc_swipe_encoder.onnx`. */
    private fun packagedModelFiles(): List<String> {
        assertTrue(
            "${modelsDir.absolutePath} not found — drift test must run with project root as CWD",
            modelsDir.isDirectory
        )
        return modelsDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "onnx" }
            .map { it.name }
            .sorted()
    }

    /**
     * Every model filename delivered by a shipped language pack — the artifact name from
     * [CtcScriptSupport], for each row whose pack actually carries a `model.onnx`. Reading the
     * zip (rather than trusting the table) is what keeps this honest: a pack rebuilt without
     * its model stops being attributable here, and `CtcPackModelTest` fails at the same time.
     */
    private fun packDeliveredModelFiles(): List<String> =
        CtcScriptSupport.SCRIPTS.mapNotNull { (language, wiring) ->
            val asset = wiring.modelAsset ?: return@mapNotNull null
            val zip = File(packDir, "langpack-$language.zip")
            if (!zip.isFile) return@mapNotNull null
            val carriesModel = ZipFile(zip).use { it.getEntry(packMemberName) != null }
            if (carriesModel) File(asset).name else null
        }.sorted()

    /** Everything CleverKeys ships, by either route. */
    private fun shippedModelFiles(): List<String> {
        val shipped = (packagedModelFiles() + packDeliveredModelFiles()).sorted()
        assertTrue(
            "no .onnx found in the APK assets or the shipped language packs — the swipe engine " +
                "ships at least the Latin encoder, so an empty list means the scan (or the " +
                "packaging) broke",
            shipped.isNotEmpty()
        )
        return shipped
    }

    @Test
    fun everyShippedOnnxModelIsAttributedInNotice() {
        assertTrue("NOTICE not found at ${notice.absolutePath}", notice.isFile)
        val text = notice.readText()
        for (model in shippedModelFiles()) {
            assertTrue(
                "NOTICE does not name the shipped model '$model'. Every shipped script model " +
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
        val shipped = shippedModelFiles().toSet()
        val namedInNotice = Regex("""[\w.-]+\.onnx""").findAll(notice.readText())
            .map { it.value }
            .filterNot { it == packMemberName } // container slot, not an artifact — see KDoc
            .toSortedSet()
        assertTrue(
            "NOTICE has no .onnx filenames at all — the per-model attribution section was " +
                "removed or reworded away from filenames, and this ratchet stops working",
            namedInNotice.isNotEmpty()
        )
        val stale = namedInNotice - shipped
        assertTrue(
            "NOTICE attributes model(s) that are no longer shipped: $stale. Remove the stale " +
                "provenance line(s) rather than leaving attribution for bytes that do not ship.",
            stale.isEmpty()
        )
    }
}
