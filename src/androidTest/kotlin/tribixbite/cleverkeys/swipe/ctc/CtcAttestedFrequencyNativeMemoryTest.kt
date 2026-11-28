package tribixbite.cleverkeys.swipe.ctc

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reading the French attested-elision table (`contraction_frequencies_fr.json`, 104 KB, 5,404
 * keys) on ART must not grow the native heap.
 *
 * Until 2026-11-28 [CtcContractionKeys.parseAttestedFrequencies] ran a Kotlin `Regex.findAll`
 * over the whole file. Kotlin's `MatchResult.next()` makes a NEW `Matcher` per match, and on
 * Android a `Matcher` is an ICU4C regex that holds its own native copy of the input (UTF-16,
 * ~208 KB here), released only when the GC runs the Matcher's cleaner. One French trie build
 * therefore churned ~1 GB of native memory; the native heap peaked near 600 MB and Scudo kept it
 * mapped (Note10+, API 31: native heap 43 → 598 MB, 563 MB of it free, against 29 MB
 * allocated). HotSpot's `java.util.regex` is pure Java and shares the input, so no JVM test sees
 * this; it is why `CtcImportedPackInstrumentedTest`'s rotation test went from ~100 MB to ~560 MB.
 *
 * Measured with [Debug.getNativeHeapSize] — the allocator's mapped heap, which is what a peak
 * leaves behind and what the process's PSS carries — not the allocated bytes, which return to
 * baseline once the cleaners run and so would hide the peak.
 */
@RunWith(AndroidJUnit4::class)
class CtcAttestedFrequencyNativeMemoryTest {

    @Test
    fun parsingTheFrenchAttestedTableDoesNotGrowTheNativeHeap() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val text = assets.open("dictionaries/contraction_frequencies_fr.json")
            .use { it.readBytes().decodeToString() }
        settle()
        val before = Debug.getNativeHeapSize()
        val table = CtcContractionKeys.parseAttestedFrequencies(text)
        settle()
        val after = Debug.getNativeHeapSize()
        val growthKb = (after - before) / 1024
        Log.i(TAG, "fr attested keys=${table.size} nativeHeapKb before=${before / 1024} " +
            "after=${after / 1024} growthKb=$growthKb")
        assertTrue("the French table parsed: ${table.size} keys", table.size > 5_000)
        // The input itself is 104 KB; 16 MiB is two orders of magnitude of headroom over any
        // parser that does not copy the input per entry, and ~35x below the regex's growth.
        assertTrue("parsing the French attested table grew the native heap by $growthKb KiB",
            growthKb < 16L * 1024L)
    }

    private fun settle() {
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(120L)
        }
    }

    private companion object {
        const val TAG = "CtcAttestedNativeMem"
    }
}
