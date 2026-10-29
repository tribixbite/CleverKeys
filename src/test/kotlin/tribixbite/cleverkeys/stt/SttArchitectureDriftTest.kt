package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The modularity contract of voice input (maintainer decision 2026-10-29: "the engine will be
 * replaced"): only `stt/moonshine/` knows the Moonshine engine; everything else uses the
 * engine-neutral types in `stt/`. Swapping engines must stay a new class + one registry entry
 * + a new pack — this test fails the moment another file starts depending on the current one.
 *
 * Also pins that no speech model ships inside the APK (the pack is an opt-in import).
 */
class SttArchitectureDriftTest {

    private val mainRoot = File("src/main/kotlin/tribixbite/cleverkeys")
    private val engineDir = File(mainRoot, "stt/moonshine")
    private val registryFile = File(mainRoot, "stt/SpeechEngineRegistry.kt")
    private val engineReference = Regex("""stt\.moonshine|Moonshine""")

    private fun kotlinFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun onlyTheEnginePackageAndTheRegistryNameTheEngine() {
        assertWithMessage("run from the project root").that(mainRoot.isDirectory).isTrue()
        val offenders = kotlinFiles(mainRoot)
            .filterNot { it.canonicalPath.startsWith(engineDir.canonicalPath + File.separator) }
            .filterNot { it.canonicalFile == registryFile.canonicalFile }
            .filter { f ->
                // Comments may mention the engine (KDoc history); code may not.
                f.readLines().any { line ->
                    val code = line.substringBefore("//").trim()
                    !code.startsWith("*") && !code.startsWith("/*") && engineReference.containsMatchIn(code)
                }
            }
            .map { it.relativeTo(mainRoot).path }
        assertWithMessage("files outside stt/moonshine that depend on the Moonshine engine").that(offenders).isEmpty()
    }

    @Test
    fun theRegistryNamesTheEngineExactlyOnceInCode() {
        val codeLines = registryFile.readLines().filter { line ->
            val t = line.trim()
            !t.startsWith("*") && !t.startsWith("/*") && !t.startsWith("//") && "Moonshine" in t
        }
        // The import and the DEFAULT_FACTORIES entry.
        assertThat(codeLines).hasSize(2)
        assertThat(codeLines.single { it.trim().startsWith("import") }).contains("stt.moonshine.MoonshineV2StreamingEngine")
    }

    @Test
    fun theEnginePackageDependsOnlyOnTheNeutralSttTypes() {
        // The engine may use stt/ interfaces, ORT and org.json — never IME classes.
        val forbidden = Regex("""^import tribixbite\.cleverkeys\.(?!stt\.)""")
        val offenders = kotlinFiles(engineDir).filter { f -> f.readLines().any { forbidden.containsMatchIn(it) } }
        assertWithMessage("engine files importing IME code").that(offenders.map { it.name }).isEmpty()
    }

    @Test
    fun noSpeechModelShipsInTheApk() {
        val assets = File("src/main/assets")
        val modelLike = assets.walkTopDown()
            .filter { it.isFile }
            .filter { f ->
                val n = f.name.lowercase()
                n.endsWith(".ort") || "moonshine" in n || "voicepack" in n || n == "streaming_config.json" ||
                    (n == "tokenizer.bin")
            }
            .map { it.relativeTo(assets).path }
            .toList()
        assertWithMessage("voice models must be imported packs, never APK assets").that(modelLike).isEmpty()
    }
}
