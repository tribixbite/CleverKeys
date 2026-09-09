package tribixbite.cleverkeys.onnx

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pins the optimized-model (`.ort`) cache contract of [ModelLoader] after the 2026-09-07
 * audit's dead-write finding.
 *
 * Builds prior to 2026-09-09 called `SessionOptions.setOptimizedModelFilePath` on every
 * load, making ORT serialize an optimized copy of each model into
 * `cacheDir/onnx_optimized_<session>.ort` — and then NEVER read it back:
 * [ModelLoader.loadModel] always feeds `createSession` the original model bytes. The
 * comment promised "faster subsequent loads"; the reality was pure wasted I/O and disk
 * (tens of MB for the encoder) on every keyboard start. Reading the cache back was
 * rejected deliberately: the optimized graph bakes in the execution providers that were
 * registered when it was written (XNNPACK/NNAPI/CPU attachment varies per device and per
 * settings), and freshness cannot be keyed safely for asset models (no mtime) or
 * content-URI models. So the contract is: never write the file, and purge any stale copies
 * a previous build left behind.
 */
class ModelLoaderOrtCacheTest {

    private val loaderSource =
        File("src/main/kotlin/tribixbite/cleverkeys/onnx/ModelLoader.kt")

    private fun loaderLines(): List<String> {
        assertWithMessage(
            "ModelLoader.kt not found at ${loaderSource.absolutePath} — " +
                "run from the project root (source-scan convention of DeadPlumbingDriftTest)."
        ).that(loaderSource.isFile).isTrue()
        return loaderSource.readLines()
    }

    /** Comment-only lines are legal tombstones; the contract constrains executable code. */
    private fun isCommentOnly(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    // ------------------------------------------------------------- source contract

    @Test
    fun `loader never configures an optimized-model file write`() {
        val hits = loaderLines().mapIndexedNotNull { index, line ->
            if (!isCommentOnly(line) && line.contains("setOptimizedModelFilePath")) {
                "ModelLoader.kt:${index + 1}: ${line.trim()}"
            } else null
        }
        assertWithMessage(
            "ModelLoader must not call SessionOptions.setOptimizedModelFilePath — the .ort " +
                "file it produces is written on EVERY load and read back by NOTHING " +
                "(createSession always gets the original bytes), so it is pure wasted I/O " +
                "and cache-dir disk. If a real read-back cache is ever built, it must key " +
                "freshness to the source model AND to the attached execution providers; " +
                "until then, do not reintroduce the write.\nFound:\n" + hits.joinToString("\n")
        ).that(hits).isEmpty()
    }

    @Test
    fun `loader purges stale ort cache files from the cache dir`() {
        val wired = loaderLines().any { line ->
            !isCommentOnly(line) &&
                line.contains("deleteStaleOptimizedModelCache(context.cacheDir)")
        }
        assertWithMessage(
            "ModelLoader must invoke deleteStaleOptimizedModelCache(context.cacheDir) so " +
                "the onnx_optimized_*.ort files written by pre-2026-09-09 builds are " +
                "reclaimed instead of sitting in cacheDir forever."
        ).that(wired).isTrue()
    }

    // ------------------------------------------------------------- purge behaviour

    private fun withScratchDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ck-ort-cache").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stale ort cache files are deleted and reported`() {
        withScratchDir { dir ->
            val encoder = File(dir, "onnx_optimized_encoder.ort").apply { writeText("stale") }
            val decoder = File(dir, "onnx_optimized_decoder.ort").apply { writeText("stale") }

            val deleted = deleteStaleOptimizedModelCache(dir)

            assertThat(deleted).containsExactly(encoder.name, decoder.name)
            assertThat(encoder.exists()).isFalse()
            assertThat(decoder.exists()).isFalse()
        }
    }

    @Test
    fun `unrelated cache entries survive the purge`() {
        withScratchDir { dir ->
            // Wrong prefix, wrong suffix, and a directory that merely matches the name
            // shape — none of these belong to the dead .ort write and none may be touched.
            val wrongPrefix = File(dir, "other_encoder.ort").apply { writeText("keep") }
            val wrongSuffix = File(dir, "onnx_optimized_encoder.txt").apply { writeText("keep") }
            val directory = File(dir, "onnx_optimized_dir.ort").apply { check(mkdir()) }
            val innocent = File(dir, "webview_cache.bin").apply { writeText("keep") }

            val deleted = deleteStaleOptimizedModelCache(dir)

            assertThat(deleted).isEmpty()
            assertThat(wrongPrefix.exists()).isTrue()
            assertThat(wrongSuffix.exists()).isTrue()
            assertThat(directory.isDirectory).isTrue()
            assertThat(innocent.exists()).isTrue()
        }
    }

    @Test
    fun `missing or empty cache dir is a safe no-op`() {
        withScratchDir { dir ->
            assertThat(deleteStaleOptimizedModelCache(dir)).isEmpty()
            val ghost = File(dir, "does-not-exist")
            assertThat(deleteStaleOptimizedModelCache(ghost)).isEmpty()
        }
    }
}
