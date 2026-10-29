package tribixbite.cleverkeys.stt

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared builders for the voice-pack tests: a valid manifest as a mutable [JSONObject] (each test
 * breaks one field), and an in-memory ZIP whose members are given as name → bytes.
 */
object VoicePackTestFixtures {

    const val APP_VERSION = 1_000

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A fake engine registered under [id]; [load] is never called by the import tests. */
    class FakeFactory(override val engineId: String = "fake-engine", override val versions: IntRange = 1..2) : SpeechEngineFactory {
        override fun load(packDir: File, options: SpeechEngineOptions): SpeechEngine =
            throw UnsupportedOperationException("not loaded in import tests")
    }

    val REGISTRY = SpeechEngineRegistry(listOf(FakeFactory()))

    /** Model files of a small fake pack. */
    val FILES: Map<String, ByteArray> = linkedMapOf(
        "encoder.ort" to ByteArray(4096) { (it * 7).toByte() },
        "decoder.ort" to ByteArray(2048) { (it * 13 + 1).toByte() },
        "tokenizer.bin" to "abc".toByteArray(),
    )

    fun manifest(
        files: Map<String, ByteArray> = FILES,
        id: String = "en-fake",
        engineId: String = "fake-engine",
        engineVersion: Int = 1,
    ): JSONObject = JSONObject()
        .put("kind", "voice")
        .put("format", 1)
        .put("id", id)
        .put("name", "English (fake)")
        .put("version", 3)
        .put("engine", JSONObject().put("id", engineId).put("version", engineVersion))
        .put("languages", JSONArray(listOf("en")))
        .put("sampleRate", 16_000)
        .put("minAppVersionCode", 0)
        .put(
            "files",
            JSONArray(files.map { (name, bytes) ->
                JSONObject().put("path", name).put("sha256", sha256(bytes)).put("size", bytes.size)
            })
        )
        .put("license", "MIT")
        .put("attribution", "Fake model, MIT")
        .put("source", "https://example.invalid/fake")

    /** ZIP bytes of [members] in order (a null value makes a directory entry). */
    fun zip(members: List<Pair<String, ByteArray?>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in members) {
                zos.putNextEntry(ZipEntry(name))
                if (bytes != null) zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A complete, valid pack ZIP (manifest + files + notices). */
    fun validPack(
        manifest: JSONObject = manifest(),
        files: Map<String, ByteArray> = FILES,
        extra: List<Pair<String, ByteArray?>> = emptyList(),
    ): ByteArray = zip(
        listOf("manifest.json" to manifest.toString().toByteArray()) +
            files.map { it.key to it.value } +
            listOf("LICENSE" to "MIT License".toByteArray(), "NOTICE.txt" to "Fake notice".toByteArray()) +
            extra
    )
}
