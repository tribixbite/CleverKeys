package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import tribixbite.cleverkeys.stt.VoicePackTestFixtures.APP_VERSION
import tribixbite.cleverkeys.stt.VoicePackTestFixtures.manifest

/**
 * [VoicePackManifest.parse]: the valid manifest round-trips, and every malformed or hostile
 * field is refused with the failure the settings screen will show (docs/specs/voice-input.md).
 */
class VoicePackManifestTest {

    private fun parse(o: JSONObject, app: Int = APP_VERSION) = VoicePackManifest.parse(o.toString(), app)

    private fun failureOf(o: JSONObject, app: Int = APP_VERSION): VoicePackImportFailure {
        val r = parse(o, app)
        assertWithMessage("expected a refusal for $o").that(r).isInstanceOf(VoicePackManifest.Result.Invalid::class.java)
        return (r as VoicePackManifest.Result.Invalid).failure
    }

    @Test
    fun validManifestParses() {
        val r = parse(manifest())
        assertThat(r).isInstanceOf(VoicePackManifest.Result.Valid::class.java)
        val m = (r as VoicePackManifest.Result.Valid).manifest
        assertThat(m.id).isEqualTo("en-fake")
        assertThat(m.name).isEqualTo("English (fake)")
        assertThat(m.version).isEqualTo(3)
        assertThat(m.engineId).isEqualTo("fake-engine")
        assertThat(m.engineVersion).isEqualTo(1)
        assertThat(m.languages).containsExactly("en")
        assertThat(m.sampleRate).isEqualTo(16_000)
        assertThat(m.files.map { it.path }).containsExactly("encoder.ort", "decoder.ort", "tokenizer.bin").inOrder()
        assertThat(m.totalBytes).isEqualTo(4096L + 2048 + 3)
        assertThat(m.license).isEqualTo("MIT")
        assertThat(m.source).isEqualTo("https://example.invalid/fake")
    }

    @Test
    fun notJsonIsAnInvalidManifest() {
        assertThat(VoicePackManifest.parse("{nope", APP_VERSION))
            .isInstanceOf(VoicePackManifest.Result.Invalid::class.java)
        val f = (VoicePackManifest.parse("[]", APP_VERSION) as VoicePackManifest.Result.Invalid).failure
        assertThat(f).isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
    }

    @Test
    fun aLanguagePackManifestIsNotAVoicePack() {
        // A language pack manifest: code/name/version, no kind.
        val lang = JSONObject().put("code", "de").put("name", "German").put("version", 1)
        assertThat(failureOf(lang)).isEqualTo(VoicePackImportFailure.NotAVoicePack)
        assertThat(failureOf(manifest().put("kind", "language"))).isEqualTo(VoicePackImportFailure.NotAVoicePack)
    }

    @Test
    fun aNewerFormatNeedsANewerApp() {
        assertThat(failureOf(manifest().put("format", 2))).isInstanceOf(VoicePackImportFailure.NeedsNewerApp::class.java)
        assertThat(failureOf(manifest().put("format", 0))).isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
        assertThat(failureOf(manifest().put("format", "1"))).isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
    }

    @Test
    fun minAppVersionAboveThisBuildNeedsANewerApp() {
        assertThat(failureOf(manifest().put("minAppVersionCode", APP_VERSION + 1)))
            .isInstanceOf(VoicePackImportFailure.NeedsNewerApp::class.java)
        assertThat(parse(manifest().put("minAppVersionCode", APP_VERSION)))
            .isInstanceOf(VoicePackManifest.Result.Valid::class.java)
    }

    @Test
    fun theIdMustBeASafeDirectoryName() {
        for (bad in listOf("", "..", ".", "../x", "en/x", "EN", "e", "en.fake", "x".repeat(80), "en\\x")) {
            assertWithMessage("id \"$bad\"").that(failureOf(manifest(id = bad)))
                .isInstanceOf(VoicePackImportFailure.InvalidId::class.java)
        }
        assertThat(failureOf(manifest().put("id", 7))).isInstanceOf(VoicePackImportFailure.InvalidId::class.java)
    }

    @Test
    fun engineFieldsAreRequiredAndShaped() {
        assertThat(failureOf(manifest().apply { remove("engine") })).isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
        assertThat(failureOf(manifest(engineId = "Bad Id"))).isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
        assertThat(failureOf(manifest(engineVersion = 0))).isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
    }

    @Test
    fun languagesMustBeANonEmptyListOfCodes() {
        for (bad in listOf(JSONArray(), JSONArray(listOf("English")), JSONArray(listOf("en", "en")), JSONArray(listOf(1)))) {
            assertWithMessage("languages $bad").that(failureOf(manifest().put("languages", bad)))
                .isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
        }
        assertThat(parse(manifest().put("languages", JSONArray(listOf("en", "pt-br")))))
            .isInstanceOf(VoicePackManifest.Result.Valid::class.java)
    }

    @Test
    fun onlySixteenKilohertzIsAccepted() {
        assertThat(failureOf(manifest().put("sampleRate", 8_000))).isEqualTo(VoicePackImportFailure.UnsupportedSampleRate(8_000))
        assertThat(failureOf(manifest().apply { remove("sampleRate") })).isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
    }

    @Test
    fun everyFileEntryIsValidated() {
        fun withFiles(vararg entries: JSONObject) = manifest().put("files", JSONArray(entries.toList()))
        val sha = "a".repeat(64)
        val bad = listOf(
            JSONObject().put("path", "../x.ort").put("sha256", sha).put("size", 1),
            JSONObject().put("path", "dir/x.ort").put("sha256", sha).put("size", 1),
            JSONObject().put("path", ".hidden").put("sha256", sha).put("size", 1),
            JSONObject().put("path", "manifest.json").put("sha256", sha).put("size", 1),
            JSONObject().put("path", "NOTICE.txt").put("sha256", sha).put("size", 1),
            JSONObject().put("path", "x.ort").put("sha256", "A".repeat(64)).put("size", 1),
            JSONObject().put("path", "x.ort").put("sha256", "abc").put("size", 1),
            JSONObject().put("path", "x.ort").put("sha256", sha).put("size", 0),
            JSONObject().put("path", "x.ort").put("sha256", sha).put("size", VoicePackManifest.MAX_FILE_BYTES + 1),
            JSONObject().put("path", "x.ort").put("sha256", sha),
        )
        for (entry in bad) {
            assertWithMessage("file entry $entry").that(failureOf(withFiles(entry)))
                .isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
        }
        val dup = JSONObject().put("path", "x.ort").put("sha256", sha).put("size", 1)
        assertWithMessage("duplicate path").that(failureOf(withFiles(dup, dup)))
            .isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
        assertWithMessage("empty list").that(failureOf(withFiles())).isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
        val tooMany = (0..VoicePackManifest.MAX_FILES).map { JSONObject().put("path", "f$it.ort").put("sha256", sha).put("size", 1) }
        assertWithMessage("too many files").that(failureOf(withFiles(*tooMany.toTypedArray())))
            .isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
        val big = (0 until 3).map {
            JSONObject().put("path", "f$it.ort").put("sha256", sha).put("size", VoicePackManifest.MAX_FILE_BYTES)
        }
        assertWithMessage("total over the cap").that(failureOf(withFiles(*big.toTypedArray())))
            .isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
    }

    @Test
    fun textFieldsAreTrimmedCappedAndTypeChecked() {
        val m = (parse(
            manifest().put("license", "  MIT  ").put("attribution", "x".repeat(5_000)).put("source", 42)
        ) as VoicePackManifest.Result.Valid).manifest
        assertThat(m.license).isEqualTo("MIT")
        assertThat(m.attribution).hasLength(VoicePackManifest.MAX_TEXT_FIELD_CHARS)
        assertWithMessage("a number is not a source").that(m.source).isNull()
        assertThat(failureOf(manifest().put("name", "   "))).isInstanceOf(VoicePackImportFailure.InvalidManifest::class.java)
    }
}
