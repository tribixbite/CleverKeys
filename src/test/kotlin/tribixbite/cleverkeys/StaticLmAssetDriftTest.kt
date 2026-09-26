package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Drift pins for the SHIPPED static context LM (`src/main/assets/lm/en.cklm`) and everything that
 * must travel with it: the builder's sidecar, the attribution the corpus licences require, and the
 * memory/size budget the design approved (docs/audit/2026-09-10-memory-oom-root-cause.md is why
 * the heap budget is a test, not a comment).
 *
 * Project root as CWD (same convention as [StaticBigramSeedTest]).
 */
class StaticLmAssetDriftTest {

    private val asset = File("src/main/assets/lm/en.cklm")
    private val sidecar = File("src/main/assets/lm/en.json")
    private val bytes by lazy { asset.readBytes() }
    private val side by lazy { JSONObject(sidecar.readText()) }
    private val lm by lazy { StaticContextLm.parse(bytes) }

    @Test
    fun `header and version match the loader`() {
        assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo(StaticContextLm.MAGIC)
        assertThat(lm.language).isEqualTo("en")
        assertThat(side.getString("format")).isEqualTo(StaticContextLm.MAGIC)
        assertThat(side.getInt("version")).isEqualTo(StaticContextLm.FORMAT_VERSION)
    }

    @Test
    fun `counts, size and sha256 equal the sidecar the builder wrote`() {
        assertThat(lm.vocabSize).isEqualTo(side.getInt("vocab"))
        assertThat(lm.prevCount).isEqualTo(side.getInt("prevs"))
        assertThat(lm.pairCount).isEqualTo(side.getInt("pairs"))
        assertThat(bytes.size).isEqualTo(side.getInt("bytes"))
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertWithMessage("en.cklm was edited without rerunning scripts/build_static_lm.py")
            .that(sha).isEqualTo(side.getString("sha256"))
    }

    @Test
    fun `every word the model names is a word the app can show`() {
        val dir = File("src/main/assets/dictionaries")
        val allowed = HashSet<String>()
        JSONObject(File(dir, "en_enhanced.json").readText()).keys().forEach { allowed.add(it.lowercase()) }
        for (name in listOf("contractions_en.json", "contractions_non_paired.json")) {
            val o = JSONObject(File(dir, name).readText())
            o.keys().forEach { allowed.add(o.getString(it).lowercase()) }
        }
        val pairings = JSONObject(File(dir, "contraction_pairings.json").readText())
        pairings.keys().forEach { k ->
            val arr = pairings.getJSONArray(k)
            for (i in 0 until arr.length()) allowed.add(arr.getJSONObject(i).getString("contraction").lowercase())
        }
        val outside = ArrayList<String>()
        lm.forEachWord { w, _ -> if (w !in allowed) outside.add(w) }
        assertWithMessage("words outside lexicon ∪ contraction forms").that(outside).isEmpty()
    }

    @Test
    fun `continuations respect the cap and form a sub-distribution`() {
        var prevs = 0
        lm.forEachWord { w, p ->
            assertThat(p).isAtMost(1f)
            if (!lm.hasContext(w)) return@forEachWord
            prevs++
            val all = lm.top(w, Int.MAX_VALUE)
            assertThat(all.size).isAtMost(StaticContextLm.MAX_CONTINUATIONS)
            // Quantisation rounds each term by at most 1/32 nat (~3%), so allow that slack.
            assertWithMessage("continuations of '$w' sum past 1").that(all.sumOf { it.probability.toDouble() })
                .isAtMost(1.035)
        }
        assertThat(prevs).isEqualTo(lm.prevCount)
    }

    @Test
    fun `size and heap stay inside the approved budget`() {
        assertThat(bytes.size).isAtMost(SIZE_CAP_BYTES)
        println("[static-lm] asset ${bytes.size} B, retained heap ${lm.retainedBytes()} B")
        assertWithMessage("retained heap of the loaded model").that(lm.retainedBytes()).isAtMost(HEAP_CAP_BYTES)
    }

    /**
     * Load cost and heap, REPORTED (not asserted — this box's load average makes wall-clock
     * thresholds flaky; the S2 gate reads these lines together with `uptime`). The retained-byte
     * figure above is exact array accounting; the GC delta here is the cross-check.
     */
    @Test
    fun `report load time and heap delta`() {
        val times = DoubleArray(LOAD_ROUNDS) {
            val t0 = System.nanoTime()
            StaticContextLm.parse(asset.readBytes())
            (System.nanoTime() - t0) / 1e6
        }
        val sorted = times.sorted()
        println("[static-lm] read+parse ms: first=%.1f min=%.1f median=%.1f (n=%d)".format(
            times[0], sorted.first(), sorted[sorted.size / 2], LOAD_ROUNDS))
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
        val before = used()
        val held = StaticContextLm.parse(asset.readBytes())
        val after = used()
        println("[static-lm] GC-measured heap delta ${after - before} B (array accounting ${held.retainedBytes()} B)")
        assertThat(held.pairCount).isGreaterThan(0) // keeps `held` reachable across the second measure
    }

    @Test
    fun `attribution required by the corpus licences is present`() {
        val notice = File("NOTICE").readText()
        assertThat(notice).contains("Leipzig Corpora Collection")
        assertThat(notice).contains("Goldhahn")
        assertThat(notice).contains("CC BY 4.0")
        assertThat(notice).contains("Tatoeba")
        assertThat(notice).contains("CC BY 2.0 FR")
        assertThat(notice).contains("scripts/data/tatoeba-contributors-en.txt")
        val contributors = File("scripts/data/tatoeba-contributors-en.txt")
        assertThat(contributors.exists()).isTrue()
        val names = contributors.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertThat(names.size).isEqualTo(side.getInt("tatoebaContributors"))
        val provenance = File("scripts/data/PROVENANCE.md").readText()
        val sources = side.getJSONArray("sources")
        for (i in 0 until sources.length()) {
            val s = sources.getJSONObject(i)
            assertWithMessage("source ${s.getString("key")} built UNPINNED").that(s.getBoolean("pinned")).isTrue()
            assertWithMessage("PROVENANCE.md must record ${s.getString("key")}'s sha256")
                .that(provenance).contains(s.getString("sha256"))
        }
    }

    private companion object {
        const val SIZE_CAP_BYTES = 512 * 1024
        /** Design budget: ≈1 MB heap for en, and the S2 gate is +1.5 MB. */
        const val HEAP_CAP_BYTES = 1_500_000L
        const val LOAD_ROUNDS = 15
    }
}
