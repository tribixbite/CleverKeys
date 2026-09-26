package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.UserWordFrequency
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import java.io.File

/**
 * Swipe-correction offer, contraction case (learning-system audit 2026-09-26, Resolution):
 * when a PAIRED variant is promoted over its base (`I'd` auto-inserted for an `id` swipe) and
 * the user keeps correcting it back to `id`, the offer adds `id` to the personal dictionary.
 * That only helps if a user-dictionary base actually kills the promotion.
 *
 * It does: `CtcEngineAdapter` hands `ContractionOverlay` each base's frequency looked up in the
 * MERGED lexicon, where a custom word carries its calibrated user frequency (stored 255 → the
 * scale ceiling). `i'd` (pairing 211) then no longer beats `id` by
 * [ContractionOverlay.PROMOTION_MARGIN], so the traced literal keeps rank 0. This test composes
 * the two pure pieces exactly as the adapter wires them, and pins the adapter's lookup.
 */
class ContractionPromotionUserWordTest {

    /** A slice of en_enhanced.json's byte scale: floor 134, `id` 196 (skill §6c table). */
    private val base = listOf("the" to 255.0, "id" to 196.0, "zebra" to 134.0)

    private fun decode(custom: List<Pair<String, Int>>): List<String> {
        val merged = CtcLexiconMerge.merge(base, custom, emptySet())
        val ordinals = CtcLexiconMerge.ordinals(merged)
        // CtcEngineAdapter: `for (base in bases) merged[base]?.let { put(base, it.toInt()) }`
        val pairingBaseFrequencies = mapOf("id" to merged.getValue("id").toInt())
        return ContractionOverlay.apply(
            words = listOf("id", "the"),
            scores = listOf(100, 50),
            pairedVariants = { if (it == "id") listOf("i'd") else null },
            nonPairedMapping = { null },
            wordOrdinal = { ordinals[it] },
            pairedVariantFrequency = { b, v -> if (b == "id" && v == "i'd") 211 else null },
            baseFrequency = { pairingBaseFrequencies[it] },
        ).first
    }

    @Test
    fun theVariantIsPromotedWhileTheBaseIsOnlyALexiconWord() {
        assertWithMessage("i'd 211 beats id 196 by ≥ the margin")
            .that(decode(emptyList()).first()).isEqualTo("i'd")
    }

    @Test
    fun addingTheBaseToThePersonalDictionaryKillsThePromotion() {
        val slate = decode(listOf("id" to UserWordFrequency.DEFAULT))
        assertWithMessage("the traced literal is the auto-insert target again").that(slate.first()).isEqualTo("id")
        assertWithMessage("the variant is still one slot away").that(slate).contains("i'd")
    }

    /** The adapter must keep reading base frequencies from the MERGED map (user words included). */
    @Test
    fun theAdapterLooksBaseFrequenciesUpInTheMergedLexicon() {
        val adapter = File("src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")
        check(adapter.exists()) { "run with the project root as CWD" }
        assertThat(adapter.readText()).contains("for (base in bases) merged[base]?.let { put(base, it.toInt()) }")
    }
}
