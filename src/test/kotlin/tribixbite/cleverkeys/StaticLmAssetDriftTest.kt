package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Drift pins for EVERY shipped static context LM (`src/main/assets/lm/<lang>.cklm`) and everything
 * that must travel with it: the builder's sidecar, the attribution the corpus licences require,
 * and the memory/size budget the design approved (docs/audit/2026-09-10-memory-oom-root-cause.md
 * is why the heap budget is a test, not a comment).
 *
 * Languages are discovered from the asset directory, so a newly shipped model is covered without
 * editing this file; `en` is additionally required to exist (it is the shipped default).
 *
 * Project root as CWD (same convention as [StaticBigramSeedTest]).
 */
class StaticLmAssetDriftTest {

    private val languages by lazy { StaticLmLanguageData.shippedLanguages() }
    private val bytes = HashMap<String, ByteArray>()
    private val models = HashMap<String, StaticContextLm>()

    private fun bytesOf(lang: String) = bytes.getOrPut(lang) { StaticLmLanguageData.asset(lang).readBytes() }
    private fun lmOf(lang: String) = models.getOrPut(lang) { StaticContextLm.parse(bytesOf(lang)) }
    private val aliasMaps = HashMap<String, Map<String, String>>()
    private fun replaceOf(lang: String) = aliasMaps.getOrPut(lang) { StaticLmLanguageData.replaceAliases(lang) }

    /** The model as `BigramModel` installs it on the device: parsed + REPLACE aliases. */
    private fun installedOf(lang: String) = lmOf(lang).withReplaceAliases(replaceOf(lang))

    @Test
    fun `english ships and every model has a sidecar`() {
        assertThat(languages).contains("en")
        for (lang in languages) {
            assertWithMessage("lm/$lang.json sidecar").that(File(StaticLmLanguageData.LM_DIR, "$lang.json").exists()).isTrue()
        }
        // Nothing else lives in lm/: an orphan sidecar would mean a model was deleted without it.
        val stray = StaticLmLanguageData.LM_DIR.listFiles()!!.map { it.name }
            .filterNot { n -> languages.any { n == "$it.cklm" || n == "$it.json" } }
        assertWithMessage("unexpected files in assets/lm").that(stray).isEmpty()
    }

    @Test
    fun `header and version match the loader`() {
        for (lang in languages) {
            val side = StaticLmLanguageData.sidecar(lang)
            assertThat(String(bytesOf(lang), 0, 4, Charsets.US_ASCII)).isEqualTo(StaticContextLm.MAGIC)
            assertWithMessage("$lang.cklm header language").that(lmOf(lang).language).isEqualTo(lang)
            assertThat(side.getString("language")).isEqualTo(lang)
            assertThat(side.getString("format")).isEqualTo(StaticContextLm.MAGIC)
            assertThat(side.getInt("version")).isEqualTo(StaticContextLm.FORMAT_VERSION)
        }
    }

    @Test
    fun `counts, size and sha256 equal the sidecar the builder wrote`() {
        for (lang in languages) {
            val side = StaticLmLanguageData.sidecar(lang)
            val lm = lmOf(lang)
            assertThat(lm.vocabSize).isEqualTo(side.getInt("vocab"))
            assertThat(lm.prevCount).isEqualTo(side.getInt("prevs"))
            assertThat(lm.pairCount).isEqualTo(side.getInt("pairs"))
            assertThat(bytesOf(lang).size).isEqualTo(side.getInt("bytes"))
            val sha = MessageDigest.getInstance("SHA-256").digest(bytesOf(lang)).joinToString("") { "%02x".format(it) }
            assertWithMessage("$lang.cklm was edited without rerunning scripts/build_static_lm.py")
                .that(sha).isEqualTo(side.getString("sha256"))
        }
    }

    @Test
    fun `every word the model names is a word the app can show`() {
        for (lang in languages) {
            val allowed = StaticLmLanguageData.lexiconWords(lang) + StaticLmLanguageData.contractionForms(lang)
            val outside = ArrayList<String>()
            lmOf(lang).forEachWord { w, _ -> if (w !in allowed) outside.add(w) }
            assertWithMessage("$lang: words outside lexicon ∪ contraction forms").that(outside).isEmpty()
        }
    }

    /**
     * The 2026-09-29 lookup fix on the REAL English model: the tap candidate `dont` (a dictionary
     * key) scores as the `don't` the bar displays; the PAIRED bases `well`/`hell`/`shell`/`were`
     * are never aliased and keep their own statistics.
     */
    @Test
    fun `english REPLACE keys score as their display form, PAIRED bases keep their own`() {
        val replace = StaticLmLanguageData.replaceAliases("en")
        assertThat(replace).hasSize(107) // contraction-system skill §3
        assertThat(replace.keys).containsNoneOf("well", "hell", "shell", "were", "shed", "wed")
        val raw = lmOf("en")
        val fixed = raw.withReplaceAliases(replace)
        for (prev in listOf("i", "you", "we")) {
            assertWithMessage("$prev → dont").that(fixed.contextRatio(prev, "dont")).isEqualTo(fixed.contextRatio(prev, "don't"))
        }
        assertThat(fixed.contextRatio("i", "dont")).isGreaterThan(1f)
        // The raw model knows `dont` only as the corpus' typo token — no listed boost after "i".
        assertThat(raw.listedProbability("i", "dont")).isEqualTo(0f)
        assertThat(fixed.top("dont", 5)).isEqualTo(fixed.top("don't", 5))
        for (w in listOf("well", "hell", "shell", "were")) {
            assertWithMessage(w).that(fixed.unigram(w)).isEqualTo(raw.unigram(w))
            assertWithMessage(w).that(fixed.contextRatio("very", w)).isEqualTo(raw.contextRatio("very", w))
            assertWithMessage(w).that(fixed.top(w, 5)).isEqualTo(raw.top(w, 5))
        }
    }

    /**
     * Every shipped language: an alias resolves only a REPLACE key, never a PAIRED base (`lune`,
     * the English pairing bases), and a key resolves exactly to its display form's statistics.
     * French additionally pins `cest` → `c'est` and `lune` ≠ `l'une` on the real model.
     */
    @Test
    fun `aliases cover REPLACE keys only, on every shipped model`() {
        for (lang in languages) {
            val raw = lmOf(lang)
            val fixed = installedOf(lang)
            val replace = replaceOf(lang)
            val pairedFile = File(StaticLmLanguageData.DICT_DIR,
                if (lang == "en") "contraction_pairings.json" else "contraction_pairs_$lang.json")
            val paired = if (pairedFile.isFile) org.json.JSONObject(pairedFile.readText()).keys().asSequence()
                .map { it.lowercase() }.toList() else emptyList()
            assertWithMessage("$lang: a PAIRED base in the REPLACE bucket").that(replace.keys.intersect(paired.toSet())).isEmpty()
            for (base in paired) {
                assertWithMessage("$lang PAIRED $base").that(fixed.unigram(base)).isEqualTo(raw.unigram(base))
                assertWithMessage("$lang PAIRED $base").that(fixed.hasContext(base)).isEqualTo(raw.hasContext(base))
            }
            var resolved = 0
            for ((key, display) in replace) {
                if (!raw.contains(display)) continue
                resolved++
                assertWithMessage("$lang $key → $display").that(fixed.wordId(key)).isEqualTo(raw.wordId(display))
            }
            assertWithMessage("$lang alias count").that(fixed.aliasCount).isEqualTo(resolved)
        }
        if ("fr" in languages) {
            val fr = installedOf("fr")
            assertThat(fr.contextRatio("et", "cest")).isEqualTo(fr.contextRatio("et", "c'est"))
            assertThat(fr.top("cest", 5)).isEqualTo(fr.top("c'est", 5))
            assertThat(fr.wordId("lune")).isNotEqualTo(fr.wordId("l'une"))
        }
    }

    @Test
    fun `continuations respect the cap and form a sub-distribution`() {
        for (lang in languages) {
            val lm = lmOf(lang)
            var prevs = 0
            lm.forEachWord { w, p ->
                assertThat(p).isAtMost(1f)
                if (!lm.hasContext(w)) return@forEachWord
                prevs++
                val all = lm.top(w, Int.MAX_VALUE)
                assertThat(all.size).isAtMost(StaticContextLm.MAX_CONTINUATIONS)
                // Quantisation rounds each term by at most 1/32 nat (~3%), so allow that slack.
                assertWithMessage("$lang: continuations of '$w' sum past 1")
                    .that(all.sumOf { it.probability.toDouble() }).isAtMost(1.035)
            }
            assertThat(prevs).isEqualTo(lm.prevCount)
        }
    }

    @Test
    fun `size and heap stay inside the approved budget`() {
        var resident = 0L
        for (lang in languages) {
            val size = bytesOf(lang).size
            val installed = installedOf(lang)
            val heap = installed.retainedBytes()
            resident += heap
            println("[static-lm] $lang: asset $size B, retained heap $heap B " +
                "(${installed.aliasCount} contraction aliases, ${heap - lmOf(lang).retainedBytes()} B of it)")
            assertWithMessage("$lang asset size").that(size).isAtMost(SIZE_CAP_BYTES)
            assertWithMessage("$lang retained heap of the loaded model").that(heap).isAtMost(HEAP_CAP_BYTES)
        }
        // BigramModel keeps every language it has loaded (one per language the user switches to),
        // so the worst case is all of them resident at once.
        println("[static-lm] all ${languages.size} models resident: $resident B")
        assertThat(resident).isAtMost(HEAP_CAP_BYTES * languages.size)
    }

    /**
     * Load cost and heap, REPORTED (not asserted — the timed load is read + parse + alias index;
     * the REPLACE map itself is read once outside the timing, since on the device it comes from
     * `ContractionManager`'s own asset load — this box's load average makes wall-clock
     * thresholds flaky; the S2 gate reads these lines together with `uptime`). The retained-byte
     * figure above is exact array accounting; the GC delta here is the cross-check. "cold" is the
     * first read+parse of that language in this JVM (file cache warm, JIT mostly cold for the
     * first language only); "warm" is the median of the repeats.
     */
    @Test
    fun `report load time and heap delta`() {
        for (lang in languages) {
            val asset = StaticLmLanguageData.asset(lang)
            val replace = replaceOf(lang)
            val times = DoubleArray(LOAD_ROUNDS) {
                val t0 = System.nanoTime()
                StaticContextLm.parse(asset.readBytes()).withReplaceAliases(replace)
                (System.nanoTime() - t0) / 1e6
            }
            val sorted = times.sorted()
            println("[static-lm] $lang read+parse+alias-index ms: cold=%.1f min=%.1f warm-median=%.1f (n=%d)".format(
                times[0], sorted.first(), sorted[sorted.size / 2], LOAD_ROUNDS))
            val rt = Runtime.getRuntime()
            fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
            val before = used()
            val held = StaticContextLm.parse(asset.readBytes()).withReplaceAliases(replace)
            val after = used()
            println("[static-lm] $lang GC-measured heap delta ${after - before} B (array accounting ${held.retainedBytes()} B)")
            assertThat(held.pairCount).isGreaterThan(0) // keeps `held` reachable across the second measure
        }
    }

    @Test
    fun `attribution required by the corpus licences is present`() {
        val notice = File("NOTICE").readText()
        assertThat(notice).contains("Leipzig Corpora Collection")
        assertThat(notice).contains("Goldhahn")
        assertThat(notice).contains("CC BY 4.0")
        assertThat(notice).contains("Tatoeba")
        assertThat(notice).contains("CC BY 2.0 FR")
        val provenance = File("scripts/data/PROVENANCE.md").readText()
        for (lang in languages) {
            val side = StaticLmLanguageData.sidecar(lang)
            val weights = side.getJSONObject("corpus").getJSONObject("weights")
            val sources = side.getJSONArray("sources")
            for (i in 0 until sources.length()) {
                val s = sources.getJSONObject(i)
                val key = s.getString("key")
                assertWithMessage("$lang source $key built UNPINNED").that(s.getBoolean("pinned")).isTrue()
                assertWithMessage("PROVENANCE.md must record $lang $key's sha256")
                    .that(provenance).contains(s.getString("sha256"))
                // Every Leipzig corpus that was COUNTED (a second, mixed corpus too — 2026-09-29
                // retry) needs its CC BY 4.0 credit; a weight-0 corpus contributed nothing.
                if (key.startsWith("leipzig") && weights.getDouble(key) > 0.0) {
                    val corpus = s.getString("url").substringAfterLast('/').removeSuffix(".tar.gz")
                    assertWithMessage("NOTICE must name $lang's Leipzig corpus ($key)").that(notice).contains(corpus)
                }
            }
            val tatoebaWeight = weights.getDouble("tatoeba")
            val path = "scripts/data/tatoeba-contributors-$lang.txt"
            val contributors = File(path)
            val names = if (contributors.exists()) {
                contributors.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            } else {
                emptyList()
            }
            assertThat(names.size).isEqualTo(side.getInt("tatoebaContributors"))
            if (tatoebaWeight > 0.0) {
                // CC BY 2.0 FR: counted sentences need their authors credited.
                assertWithMessage("$lang counts Tatoeba (weight $tatoebaWeight) — contributor list required")
                    .that(names).isNotEmpty()
                assertWithMessage("NOTICE must point at $path").that(notice).contains(path)
            }
        }
    }

    private companion object {
        const val SIZE_CAP_BYTES = 512 * 1024
        /** Design budget: ≈1 MB heap per language, and the S2 gate is +1.5 MB each. */
        const val HEAP_CAP_BYTES = 1_500_000L
        const val LOAD_ROUNDS = 15
    }
}
