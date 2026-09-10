package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The load-side half of "the script models travel in their language packs" (APK diet,
 * 2026-09-10).
 *
 * The six per-script encoders left `src/main/assets/models/` and now arrive inside
 * `langpack-<code>.zip`. A pack is a file the USER supplies, so the bytes reaching ORT would
 * otherwise be attacker-controlled — an ONNX parser is a large native attack surface and this
 * change must add *none* of it. The rule that keeps it at zero:
 *
 * > A pack-provided model is loaded **only** when its sha256 equals the hash this app pins for
 * > that language. A pinned hash is byte-identity with what the APK used to ship, so ORT parses
 * > exactly the bytes it parsed before and nothing else. Any mismatch is treated as
 * > model-absent, which the existing gates already handle by falling through to the geometric
 * > engine.
 *
 * This test pins both halves: that every ROUTED script HAS a pin (a row that reached ROUTED
 * without one could load anything), and that [CtcPackModel] actually enforces it — including
 * the size cap that bounds how much a hostile pack can make the app read before the hash can
 * possibly disagree.
 */
class CtcPackModelTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    // ── The pins ────────────────────────────────────────────────────────────────────

    /**
     * Rule 4's fourth leg, added by the pack-delivery change: a ROUTED script whose model is
     * pack-delivered and unpinned would hand ORT whatever bytes the pack contained.
     */
    @Test
    fun everyRoutedScriptPinsItsModelSha256() {
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            if (wiring.status != CtcScriptSupport.Status.ROUTED) continue
            val pin = wiring.modelSha256
            assertWithMessage(
                "$language is ROUTED but pins no model sha256 — its model is delivered by an " +
                    "importable language pack, so without a pin the app would hand ORT " +
                    "user-supplied bytes"
            ).that(pin).isNotNull()
            assertWithMessage("$language: a sha256 pin is 64 lowercase hex characters")
                .that(pin).matches("[0-9a-f]{64}")
        }
    }

    /** The pin is reachable by language, and only for languages that have a script row. */
    @Test
    fun expectedModelSha256ResolvesPerLanguage() {
        assertThat(CtcScriptSupport.expectedModelSha256("ru"))
            .isEqualTo(CtcScriptSupport.SCRIPTS.getValue("ru").modelSha256)
        // Region subtags normalize away, exactly as every other lookup on this table does.
        assertThat(CtcScriptSupport.expectedModelSha256("ru_RU"))
            .isEqualTo(CtcScriptSupport.SCRIPTS.getValue("ru").modelSha256)
        // A Latin language has no script row, so a pack may NOT supply it a model: the Latin
        // encoder is the APK's own asset and an imported Latin pack must never displace it.
        assertThat(CtcScriptSupport.expectedModelSha256("en")).isNull()
        assertThat(CtcScriptSupport.expectedModelSha256("fr")).isNull()
        assertThat(CtcScriptSupport.expectedModelSha256(null)).isNull()
    }

    /**
     * The pin IS the shipped artifact. While the six models are still in the APK this is a
     * direct byte check against them; once they are pack-only the same identity is checked
     * against the pack zips (`CtcPackZipModelTest`).
     */
    @Test
    fun eachPinMatchesTheArtifactItNames() {
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            val asset = wiring.modelAsset ?: continue
            val onDisk = File("src/main/assets/$asset")
            if (!onDisk.isFile) continue // pack-only delivery; CtcPackZipModelTest owns it
            assertWithMessage(
                "$language: the pinned sha256 must be the sha256 of $asset — a pin that names " +
                    "bytes the project does not have is a pin against nothing"
            ).that(sha256(onDisk.readBytes())).isEqualTo(wiring.modelSha256)
        }
    }

    // ── The gate ────────────────────────────────────────────────────────────────────

    @Test
    fun verifiedBytesReturnsTheBytesOnAnExactHashMatch() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val file = temp.newFile("model.onnx").apply { writeBytes(bytes) }
        val got = CtcPackModel.verifiedBytes(file, sha256(bytes))
        assertThat(got).isEqualTo(bytes)
    }

    @Test
    fun verifiedBytesRefusesAMismatchedModel() {
        val file = temp.newFile("model.onnx").apply { writeBytes(ByteArray(4096) { 7 }) }
        // One flipped nibble is the whole point: byte-identity or nothing.
        val wrong = sha256(ByteArray(4096) { 8 })
        assertWithMessage(
            "a pack model whose sha256 is not the pinned one must be treated as ABSENT — " +
                "never handed to ORT"
        ).that(CtcPackModel.verifiedBytes(file, wrong)).isNull()
    }

    @Test
    fun verifiedBytesRefusesAnUnpinnedOrMissingModel() {
        val file = temp.newFile("model.onnx").apply { writeBytes(ByteArray(4096) { 7 }) }
        assertThat(CtcPackModel.verifiedBytes(file, null)).isNull()
        assertThat(CtcPackModel.verifiedBytes(null, sha256(ByteArray(4096) { 7 }))).isNull()
        assertThat(
            CtcPackModel.verifiedBytes(File(temp.root, "absent.onnx"), sha256(ByteArray(0)))
        ).isNull()
    }

    /**
     * The cap bounds the read BEFORE the hash can disagree. Without it, a pack naming a 2 GB
     * `model.onnx` would be fully read into a byte array on the decode thread and only then
     * rejected — an OOM the hash gate cannot prevent because it runs after the read.
     */
    @Test
    fun verifiedBytesRefusesAModelOverTheSizeCap() {
        val oversize = CtcPackModel.MAX_PACK_MODEL_BYTES.toInt() + 1
        val bytes = ByteArray(oversize)
        val file = temp.newFile("model.onnx").apply { writeBytes(bytes) }
        assertWithMessage(
            "a model larger than MAX_PACK_MODEL_BYTES must be refused on LENGTH, before its " +
                "bytes are read"
        ).that(CtcPackModel.verifiedBytes(file, sha256(bytes))).isNull()
    }

    @Test
    fun theSizeCapLeavesHeadroomOverEveryShippedScriptModel() {
        // The six generation-4 graphs are 589,406 B each; the cap must clear them comfortably
        // while staying far below anything that could pressure the decode thread's heap.
        assertThat(CtcPackModel.MAX_PACK_MODEL_BYTES).isAtLeast(4L * 1024 * 1024)
        assertThat(CtcPackModel.MAX_PACK_MODEL_BYTES).isAtMost(16L * 1024 * 1024)
    }

    /** The pack member name is one constant, shared by the importer and the loader. */
    @Test
    fun theModelMemberNameIsASingleConstant() {
        assertThat(CtcPackModel.PACK_MODEL_FILE).isEqualTo("model.onnx")
        assertThat(CtcPackModel.packModelRelativePath("ru")).isEqualTo("langpacks/ru/model.onnx")
    }

    /**
     * The load path must notice that the file underneath it changed, so that importing a newer
     * pack un-latches a language whose model previously failed to load. Length+mtime is the
     * same fingerprint the lexicon memo already keys on.
     */
    @Test
    fun theSourceFingerprintTracksTheFileOnDisk() {
        val file = temp.newFile("model.onnx").apply { writeBytes(ByteArray(64) { 1 }) }
        val before = CtcPackModel.sourceFingerprint(file)
        assertThat(CtcPackModel.sourceFingerprint(file)).isEqualTo(before)
        file.writeBytes(ByteArray(128) { 2 })
        assertWithMessage("a re-imported pack must produce a different fingerprint")
            .that(CtcPackModel.sourceFingerprint(file)).isNotEqualTo(before)
        assertThat(CtcPackModel.sourceFingerprint(null))
            .isEqualTo(CtcPackModel.sourceFingerprint(File(temp.root, "absent.onnx")))
    }

    // ── Per-language resolution (the seam the adapter calls) ────────────────────────

    /**
     * The whole point, end to end and with the REAL artifact: a pack whose `model.onnx` is the
     * pinned Russian encoder resolves; the same bytes filed under Greek do not, because a pin is
     * per language and a Cyrillic graph under `he/` would decode Hebrew against a Russian
     * emission head — the class-KDoc footgun, arriving through a file instead of a table edit.
     */
    @Test
    fun theRealScriptModelResolvesOnlyForItsOwnLanguage() {
        val ru = shippedScriptModelBytes("ru")
        val filesDir = temp.newFolder("files")
        writePackModel(filesDir, "ru", ru)
        writePackModel(filesDir, "el", ru)

        assertWithMessage("ru's pack model is byte-identical to ru's pin, so it must load")
            .that(CtcPackModel.verifiedPackModel(filesDir, "ru")).isEqualTo(ru)
        assertWithMessage(
            "the Russian encoder installed under el/ must NOT load — every script graph is " +
                "589,406 B, so only the per-language pin can tell them apart"
        ).that(CtcPackModel.verifiedPackModel(filesDir, "el")).isNull()
    }

    @Test
    fun aLatinLanguageNeverResolvesAPackModel() {
        val filesDir = temp.newFolder("files")
        // A hostile pack can name itself `en` and carry any model.onnx it likes.
        writePackModel(filesDir, "en", shippedScriptModelBytes("ru"))
        assertWithMessage(
            "en pins no model, so no pack may supply one — the Latin encoder is the APK's own " +
                "asset and an imported pack must never displace it"
        ).that(CtcPackModel.verifiedPackModel(filesDir, "en")).isNull()
    }

    @Test
    fun anAbsentOrTamperedPackModelResolvesToNull() {
        val filesDir = temp.newFolder("files")
        assertWithMessage("no pack installed at all")
            .that(CtcPackModel.verifiedPackModel(filesDir, "ru")).isNull()

        val tampered = shippedScriptModelBytes("ru").copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()
        writePackModel(filesDir, "ru", tampered)
        assertWithMessage(
            "one flipped byte in the pack's model must be refused — this is the assertion that " +
                "keeps ORT off user-controlled bytes"
        ).that(CtcPackModel.verifiedPackModel(filesDir, "ru")).isNull()
    }

    @Test
    fun theResolvedPathIsTheOneTheImporterWritesTo() {
        val filesDir = temp.newFolder("files")
        assertThat(CtcPackModel.packModelFile(filesDir, "ru_RU")?.path)
            .isEqualTo(File(filesDir, "langpacks/ru/model.onnx").path)
        assertThat(CtcPackModel.packModelFile(filesDir, "en")).isNull()
    }

    // ── The shipped packs (scripts/dictionaries/langpack-*.zip) ─────────────────────

    /**
     * The delivery side of the pin: every ROUTED script's shipped pack must actually CARRY the
     * artifact the app pins, and must declare it in its manifest.
     *
     * This is the assertion that makes the whole change safe to ship. The app has no fallback
     * once the assets are gone: a pack rebuilt without its model installs perfectly cleanly and
     * then silently never decodes — the language simply drops to geometric with nothing on
     * screen to say why. Rebuilding a pack without passing `--model` is a one-flag mistake, so
     * it needs a gate, and this is it.
     */
    @Test
    fun everyRoutedScriptPackCarriesItsPinnedModel() {
        for ((language, wiring) in CtcScriptSupport.SCRIPTS) {
            if (wiring.status != CtcScriptSupport.Status.ROUTED) continue
            val zip = File("scripts/dictionaries/langpack-$language.zip")
            assertWithMessage("expected ${zip.path} (run from project root)")
                .that(zip.isFile).isTrue()
            java.util.zip.ZipFile(zip).use { zf ->
                val entry = zf.getEntry(CtcPackModel.PACK_MODEL_FILE)
                assertWithMessage(
                    "$language: ${zip.name} carries no ${CtcPackModel.PACK_MODEL_FILE}. The " +
                        "encoder is pack-delivered — a pack without it installs fine and then " +
                        "never decodes. Rebuild with build_langpack.py --model."
                ).that(entry).isNotNull()
                val bytes = zf.getInputStream(entry).use { it.readBytes() }
                assertWithMessage(
                    "$language: sha256 of the pack's ${CtcPackModel.PACK_MODEL_FILE} must equal " +
                        "CtcScriptSupport's pin, or the app will refuse to load it"
                ).that(sha256(bytes)).isEqualTo(wiring.modelSha256)

                val manifest = org.json.JSONObject(
                    zf.getInputStream(zf.getEntry("manifest.json")).use { it.readBytes() }
                        .toString(Charsets.UTF_8)
                )
                val declared = manifest.optJSONObject("model")
                assertWithMessage(
                    "$language: the pack must DECLARE its model — an undeclared model.onnx is " +
                        "dropped on import, because nothing can verify it"
                ).that(declared).isNotNull()
                assertThat(declared.getString("file")).isEqualTo(CtcPackModel.PACK_MODEL_FILE)
                assertThat(declared.getString("sha256").lowercase()).isEqualTo(wiring.modelSha256)
            }
        }
    }

    /**
     * The saving, pinned. `src/main/assets/models/` holds the Latin encoder and nothing else.
     *
     * A script model reappearing here would be silent: the load path prefers the pack and only
     * falls back to the asset, so every test above would stay green while the APK quietly
     * regained 3.1 MB. Re-adding one would also mean the same bytes shipped twice, and the APK
     * copy would mask a pack that had been rebuilt without its model — the exact failure the
     * pack-side pin exists to catch.
     */
    @Test
    fun theApkPackagesOnlyTheLatinEncoder() {
        val dir = File("src/main/assets/models")
        assertWithMessage("expected ${dir.path} (run from project root)").that(dir.isDirectory)
            .isTrue()
        val packaged = dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "onnx" }
            .map { it.name }
            .sorted()
        assertWithMessage(
            "only the Latin encoder is an APK asset — the six script encoders are " +
                "pack-delivered (APK diet, 2026-09-10). Re-adding one silently undoes the " +
                "saving and lets the APK copy mask a pack rebuilt without its model."
        ).that(packaged).containsExactly("ctc_swipe_encoder.onnx")
    }

    /**
     * The other direction, and the reason it matters: a Latin pack must NOT declare a model.
     * Latin languages decode against the APK's own encoder, so a pack-supplied one could only
     * ever be an attempt to displace it — which the load side already refuses, and which no
     * pack this project builds should be asking for in the first place.
     */
    @Test
    fun aLatinPackDeclaresNoModel() {
        for (code in listOf("en", "fr", "de", "es", "it", "pt", "sv", "nl", "tr")) {
            val zip = File("scripts/dictionaries/langpack-$code.zip")
            if (!zip.isFile) continue
            java.util.zip.ZipFile(zip).use { zf ->
                assertWithMessage("$code is Latin-script and must ship no pack model")
                    .that(zf.getEntry(CtcPackModel.PACK_MODEL_FILE)).isNull()
            }
        }
    }

    /**
     * `memory/HANDOFF.md`'s deferred "langpack manifest-version normalize on next pack
     * rebuild". uk/bg/mk/he were stamped `version: 1` only because ARC-056 built them after
     * the other packs had moved to 2, and the byte-identity rule held the fix until a rebuild
     * was happening anyway. The 2026-09-10 model rebuild was that rebuild, so the six are now
     * uniform and this pins them there.
     */
    @Test
    fun theSixScriptPacksAgreeOnTheirManifestVersion() {
        for (language in CtcScriptSupport.SCRIPTS.keys) {
            val zip = File("scripts/dictionaries/langpack-$language.zip")
            if (!zip.isFile) continue
            val version = java.util.zip.ZipFile(zip).use { zf ->
                org.json.JSONObject(
                    zf.getInputStream(zf.getEntry("manifest.json")).use { it.readBytes() }
                        .toString(Charsets.UTF_8)
                ).optInt("version", -1)
            }
            assertWithMessage(
                "$language: script packs are stamped manifest version 2 (the normalization " +
                    "HANDOFF deferred to the next rebuild)"
            ).that(version).isEqualTo(2)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    /**
     * The real generation-4 bytes for [language], from wherever they currently ship: the APK
     * asset while it still exists, otherwise `model.onnx` inside the shipped pack zip. Both
     * sources must satisfy the pin, which is exactly what makes the transition safe.
     */
    private fun shippedScriptModelBytes(language: String): ByteArray {
        val wiring = CtcScriptSupport.SCRIPTS.getValue(language)
        val asset = File("src/main/assets/${wiring.modelAsset}")
        if (asset.isFile) return asset.readBytes()
        val zip = File("scripts/dictionaries/langpack-$language.zip")
        check(zip.isFile) { "expected ${zip.path} (run from project root)" }
        return java.util.zip.ZipFile(zip).use { zf ->
            val entry = checkNotNull(zf.getEntry(CtcPackModel.PACK_MODEL_FILE)) {
                "${zip.path} carries no ${CtcPackModel.PACK_MODEL_FILE} — the script models are " +
                    "pack-delivered, so a pack without one serves nothing"
            }
            zf.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun writePackModel(filesDir: File, code: String, bytes: ByteArray) {
        val dir = File(filesDir, "langpacks/$code").apply { mkdirs() }
        File(dir, CtcPackModel.PACK_MODEL_FILE).writeBytes(bytes)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
