package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The scene render's memory (review 2026-11-14 M19) and the render thread's handling of a
 * request ([PaintingJob]): a stale request is skipped, an out-of-memory render is retried once at
 * half the resolution.
 *
 * The peak is measured, not estimated: a child JVM renders the scene with a fixed heap cap, the
 * way `BackdropDrawable.render` does (paint, then true black on the same arrays), and either fits
 * or throws OutOfMemoryError. Before the fix the stream at full resolution ("Crisp") on a
 * 2560 × 800 keyboard (a tablet in landscape) needed a 132 MB heap; the cap here is 96 MB.
 * What this does not cover: the Bitmap copies `BackdropDrawable.render` makes afterwards (native
 * memory on Android 8+, not the Java heap) and ART's own collector.
 */
class SceneRenderMemoryTest {

    @Test
    fun aRequestForASizeNoLongerShownIsSkippedBeforeItRenders() {
        var renders = 0
        val stored = mutableListOf<String>()
        val outcome = PaintingJob.run(
            "2560x800",
            isCurrent = { false },
            cached = { null },
            store = { k, _ -> stored += k },
            render = { renders++; "painting" },
            smaller = { null },
            onFailure = { },
        )
        assertThat(outcome).isSameInstanceAs(PaintingOutcome.Stale)
        assertWithMessage("no second of CPU for a size nobody sees").that(renders).isEqualTo(0)
        assertWithMessage("and no cache entry evicting the one in use").that(stored).isEmpty()

        val current = PaintingJob.run("1280x400", { true }, { null }, { k, _ -> stored += k }, { "painting $it" }, { null }, { })
        assertThat((current as PaintingOutcome.Done).painting).isEqualTo("painting 1280x400")
        assertThat(stored).containsExactly("1280x400")
        val cached = PaintingJob.run("1280x400", { true }, { "cached" }, { _, _ -> error("not stored again") }, { error("not rendered") }, { null }, { })
        assertThat((cached as PaintingOutcome.Done).painting).isEqualTo("cached")
    }

    @Test
    fun aRenderThatRunsOutOfMemoryIsRetriedOnceAtHalfTheResolution() {
        val failures = mutableListOf<Throwable>()
        val stored = mutableMapOf<String, String>()
        val outcome = PaintingJob.run(
            "full",
            isCurrent = { true },
            cached = { stored[it] },
            store = { k, r -> stored[k] = r },
            render = { k -> if (k == "full") throw OutOfMemoryError("Failed to allocate 32 MB") else "painted $k" },
            smaller = { k -> if (k == "full") "half" else null },
            onFailure = { failures += it },
        )
        assertThat((outcome as PaintingOutcome.Done).painting).isEqualTo("painted half")
        assertWithMessage("kept for the size asked for: it is not tried at full size again")
            .that(stored).containsExactly("full", "painted half")
        assertThat(failures).hasSize(1)

        // A bug is not retried, nor a second out-of-memory: the base colour shows.
        var renders = 0
        val bug = PaintingJob.run<String, String>("full", { true }, { null }, { _, _ -> }, { renders++; error("bug") }, { "half" }, { })
        assertThat((bug as PaintingOutcome.Done).painting).isNull()
        assertThat(renders).isEqualTo(1)
        val twice = PaintingJob.run<String, String>("full", { true }, { null }, { _, _ -> }, { throw OutOfMemoryError() }, { if (it == "full") "half" else null }, { })
        assertThat((twice as PaintingOutcome.Done).painting).isNull()
    }

    @Test
    fun aCrispStreamOnATabletKeyboardRendersInA96MbHeap() {
        for (trueBlack in listOf(false, true)) {
            val (code, out) = probe(HEAP_MB, SceneKind.STREAM, 2560, 800, 2.0f, trueBlack)
            assertWithMessage("stream 2560 × 800 at full resolution, true black $trueBlack, -Xmx${HEAP_MB}m: $out")
                .that(code).isEqualTo(0)
            assertThat(out).startsWith("OK")
        }
    }

    private fun probe(heapMb: Int, kind: SceneKind, w: Int, h: Int, density: Float, trueBlack: Boolean): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cmd = listOf(
            java, "-Xmx${heapMb}m", "-Xms8m", "-XX:+UseSerialGC",
            "-cp", System.getProperty("java.class.path"),
            SceneRenderPeakProbe::class.java.name, kind.name, "$w", "$h", "$density", if (trueBlack) "1" else "0",
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "probe did not finish" }
        return process.exitValue() to out.lines().lastOrNull().orEmpty()
    }

    private companion object {
        const val HEAP_MB = 96
    }
}

/**
 * The child JVM of [SceneRenderMemoryTest]: renders one scene as `BackdropDrawable.render` does
 * and prints OK (with the pixel count) or OOM. args: SCENE width height density trueBlack(0/1).
 */
object SceneRenderPeakProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val kind = SceneKind.valueOf(args[0])
        val w = args[1].toInt()
        val h = args[2].toInt()
        val density = args[3].toFloat()
        try {
            val painted = SceneRaster.paint(kind, w, h, density)
            val shown = if (args[4] == "1") SceneTrueBlack.apply(painted, kind, density, inPlace = true) else painted
            println("OK ${shown.pixelCount} px")
        } catch (e: OutOfMemoryError) {
            println("OOM $kind")
            System.exit(3)
        }
    }
}
