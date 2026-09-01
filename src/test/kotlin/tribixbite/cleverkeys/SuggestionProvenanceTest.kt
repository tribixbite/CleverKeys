package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.swipe.SwipeEngineRouter
import java.util.Locale
import kotlin.math.ln1p

/**
 * Pure-JVM tests for the pipeline-transparency substrate (Task B, audit §2):
 * the [UnifiedScore] combiner (single source of truth for the unified-score
 * formula + breakdown), origin tagging helpers, and the provenance formatter.
 */
class SuggestionProvenanceTest {

    private fun combine(
        prefixScore: Int = 800,
        adaptation: Float = 1.0f,
        static: Float = 1.0f,
        dynamic: Float = 1.0f,
        source: String = UnifiedScore.SOURCE_BOTH,
        boost: Float = 0f,
        weight: Float = 1.0f,
        frequency: Int = 1000,
        frequencyScale: Float = 100.0f,
        contextBoost: Float = 0.5f
    ) = UnifiedScore.combine(
        prefixScore, adaptation, static, dynamic, source,
        boost, weight, frequency, frequencyScale, contextBoost
    )

    private fun strings() = ProvenanceFormatter.Strings(
        locale = Locale.US,
        originLabels = SuggestionOrigin.values().associateWith { "Origin ${it.name}" },
        unknown = "Unknown",
        source = "Source: %1\$s",
        score = "Score: %1\$d",
        scoreComponents = "Score components:",
        prefixMatch = "• Prefix match: %1\$d",
        adaptation = "• Adaptation: %1\$.2f×",
        contextBuiltIn = "• Context (built-in): %1\$.2f×",
        contextLearned = "• Context (learned): %1\$.2f×",
        contextWinnerLearned = "• Context winner: your learned patterns",
        contextWinnerBuiltIn = "• Context winner: built-in model",
        contextNoBoost = "• Context: no boost",
        contextBoostSetting = "• Context boost setting: %1\$.1f×",
        personalization = "• Personalization: %1\$.2f → %2\$.2f×",
        frequencyFactor = "• Frequency factor: %1\$.2f×",
        finalScore = "= Final score: %1\$d",
        personalUsage = "Personal usage:",
        // Fake of the Android layer's getQuantityString(R.plurals.provenance_usage_count, n, n)
        // under English CLDR rules (one → "time", other → "times").
        usageCount = { n -> if (n == 1) "Used $n time" else "Used $n times" },
        frequencyScore = "Frequency score: %1\$.2f",
        recencyScore = "Recency score: %1\$.2f",
        baseBoost = "Base boost: %1\$.2f",
        aggression = "Aggression: %1\$s (%2\$.2f×)",
        finalBoost = "Final boost: %1\$.2f",
        promotedByLearnedContext = "Promoted by learned context",
        nextWordBuiltIn = "After “%1\$s”: common continuation (built-in, not learned)",
        nextWordLearned = "After “%1\$s”: seen %2\$d×, %3\$d%%",
        typedWordUndo = "Your typed word (tap to undo)",
        autocorrectedFrom = "Autocorrected from “%1\$s”"
    )

    // ------------------------------------------------------------- formula

    @Test
    fun `final score matches the documented formula exactly`() {
        val b = combine(
            prefixScore = 850, adaptation = 1.2f, static = 1.5f, dynamic = 2.0f,
            boost = 2.0f, weight = 1.0f, frequency = 500, frequencyScale = 100f,
            contextBoost = 0.5f
        )
        val expectedPersonalization = 1.0f + (2.0f * 1.0f / 4.0f)
        val expectedFreqFactor = 1.0f + ln1p((500 / 100f).toDouble()).toFloat()
        val expected = (850 * 1.2f * expectedPersonalization *
            (1.0f + (2.0f - 1.0f) * 0.5f) * expectedFreqFactor).toInt()

        assertEquals(expected, b.finalScore)
        assertEquals(expectedPersonalization, b.personalizationMultiplier, 1e-6f)
        assertEquals(expectedFreqFactor, b.frequencyFactor, 1e-6f)
    }

    @Test
    fun `neutral signals leave only prefix and frequency in play`() {
        val b = combine()
        val expected = (800 * (1.0f + ln1p(10.0).toFloat())).toInt()
        assertEquals(expected, b.finalScore)
        assertEquals(ContextWinner.NONE, b.contextWinner)
    }

    // -------------------------------------------------------- context source

    @Test
    fun `context source both takes the max of static and learned`() {
        val learnedWins = combine(static = 1.3f, dynamic = 2.2f)
        val staticWins = combine(static = 2.5f, dynamic = 1.4f)

        assertEquals(ContextWinner.LEARNED, learnedWins.contextWinner)
        assertEquals(ContextWinner.STATIC, staticWins.contextWinner)
        // The applied multiplier is the max — verify via score equality with an
        // explicit single-source run
        assertEquals(
            combine(static = 1.0f, dynamic = 2.2f, source = UnifiedScore.SOURCE_LEARNED_ONLY).finalScore,
            learnedWins.finalScore
        )
    }

    @Test
    fun `learned_only ignores the static model`() {
        val b = combine(static = 5.0f, dynamic = 1.0f, source = UnifiedScore.SOURCE_LEARNED_ONLY)
        // Static 5.0 ignored → no context contribution at all
        assertEquals(combine().finalScore, b.finalScore)
        assertEquals(ContextWinner.NONE, b.contextWinner)
    }

    @Test
    fun `static_only ignores the learned model`() {
        val b = combine(static = 1.0f, dynamic = 5.0f, source = UnifiedScore.SOURCE_STATIC_ONLY)
        assertEquals(combine().finalScore, b.finalScore)
        assertEquals(ContextWinner.NONE, b.contextWinner)

        val active = combine(static = 2.0f, dynamic = 5.0f, source = UnifiedScore.SOURCE_STATIC_ONLY)
        assertEquals(ContextWinner.STATIC, active.contextWinner)
    }

    // ------------------------------------------------- personalization weight

    @Test
    fun `personalization weight slides the multiplier continuously`() {
        val off = combine(boost = 4.0f, weight = 0.0f)
        val half = combine(boost = 4.0f, weight = 0.5f)
        val double = combine(boost = 4.0f, weight = 2.0f)

        assertEquals(1.0f, off.personalizationMultiplier, 1e-6f)
        assertEquals(1.5f, half.personalizationMultiplier, 1e-6f)
        assertEquals(3.0f, double.personalizationMultiplier, 1e-6f)
        assertTrue(off.finalScore < half.finalScore && half.finalScore < double.finalScore)
    }

    // -------------------------------------------------------- origin helpers

    @Test
    fun `swipe engine mode maps to the right origin`() {
        assertEquals(SuggestionOrigin.GEOMETRIC, SuggestionOrigin.forSwipeEngineMode("geometric"))
        assertEquals(SuggestionOrigin.CTC, SuggestionOrigin.forSwipeEngineMode("ctc"))
        // The removed neural/hybrid modes (and any other junk a backup import can supply)
        // resolve to the ctc default in SwipeEngineRouter.Mode.fromPref, so the mode-derived
        // origin fallback must agree with that resolution rather than name a dead engine.
        assertEquals(SuggestionOrigin.CTC, SuggestionOrigin.forSwipeEngineMode("neural"))
        assertEquals(SuggestionOrigin.CTC, SuggestionOrigin.forSwipeEngineMode("hybrid"))
        assertEquals(SuggestionOrigin.CTC, SuggestionOrigin.forSwipeEngineMode(null))
    }

    /**
     * Audit M2: the marker/long-press sheet must report the engine that ACTUALLY
     * decoded, not the configured mode. [SuggestionOrigin.forRoutedEngine] is the
     * routed-engine derivation InputCoordinator threads through
     * `handleSwipePredictionResults` — a geometric decode under ctc mode (M1's
     * unsupported-language fallthrough, or a non-Latin layout) tags GEOMETRIC.
     */
    @Test
    fun `routed engine maps to the origin of the engine that actually decoded`() {
        assertEquals(
            SuggestionOrigin.GEOMETRIC,
            SuggestionOrigin.forRoutedEngine(SwipeEngineRouter.Engine.GEOMETRIC)
        )
        assertEquals(
            SuggestionOrigin.CTC,
            SuggestionOrigin.forRoutedEngine(SwipeEngineRouter.Engine.CTC)
        )
        // The mapping must stay total: every routable engine has a bar-reachable origin.
        assertEquals(
            SwipeEngineRouter.Engine.values().size,
            SwipeEngineRouter.Engine.values().map { SuggestionOrigin.forRoutedEngine(it) }
                .toSet().size
        )
    }

    /**
     * The mode-derived fallback ([SuggestionOrigin.forSwipeEngineMode]) and the routed
     * derivation must AGREE on each mode's QWERTY-English default routing — the fallback
     * only diverges on the per-layout/-language branches M2 exists to fix.
     */
    @Test
    fun `mode fallback agrees with routed derivation on each mode's default engine`() {
        assertEquals(
            SuggestionOrigin.forSwipeEngineMode("ctc"),
            SuggestionOrigin.forRoutedEngine(SwipeEngineRouter.Engine.CTC)
        )
        assertEquals(
            SuggestionOrigin.forSwipeEngineMode("geometric"),
            SuggestionOrigin.forRoutedEngine(SwipeEngineRouter.Engine.GEOMETRIC)
        )
        // A legacy stored mode resolves to ctc at both seams.
        assertEquals(
            SuggestionOrigin.forSwipeEngineMode("hybrid"),
            SuggestionOrigin.forRoutedEngine(SwipeEngineRouter.Engine.CTC)
        )
    }

    @Test
    fun `every origin has a distinct human label`() {
        val strings = strings()
        val labels = SuggestionOrigin.values().map { ProvenanceFormatter.originLabel(it, strings) }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
    }

    // ------------------------------------------------------------- formatter

    @Test
    fun `formatter renders origin, breakdown components, and personal usage`() {
        val breakdown = combine(
            prefixScore = 900, adaptation = 1.1f, static = 1.2f, dynamic = 1.8f,
            boost = 2.0f, frequency = 400
        )
        val text = ProvenanceFormatter.format(
            word = "hello",
            meta = SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX, breakdown),
            barScore = null,
            personalization = PersonalizationDetails(12, 1.2f, 0.8f, 2.0f, "Balanced", 1.0f, 2.0f),
            strings = strings()
        )

        assertTrue(text.contains("hello"))
        assertTrue(text.contains("Origin DICTIONARY_PREFIX"))
        assertTrue(text.contains("Prefix match: 900"))
        assertTrue(text.contains("learned patterns")) // dynamic 1.8 beat static 1.2
        assertTrue(text.contains("Final score: ${breakdown.finalScore}"))
        assertTrue(text.contains("Used 12 times"))
    }

    /**
     * ARC-109: the usage-count line must be quantity-aware — English says
     * "Used 1 time" (or "once"), never "Used 1 times". Impossible with a single
     * raw template flowing into the pure formatter; the [ProvenanceFormatter.Strings]
     * bundle therefore carries a `(Int) -> String` resolver the Android layer backs
     * with `getQuantityString(R.plurals.provenance_usage_count, n, n)`.
     */
    @Test
    fun `usage count inflects between singular and plural in English`() {
        fun sheet(count: Int) = ProvenanceFormatter.format(
            word = "hello",
            meta = null,
            barScore = null,
            personalization = PersonalizationDetails(count, 1.2f, 0.8f, 2.0f, "Balanced", 1.0f, 2.0f),
            strings = strings()
        )
        assertTrue(sheet(5).contains("Used 5 times"))
        assertTrue(sheet(1).contains("Used 1 time"))
        assertFalse("singular count must not render the plural form", sheet(1).contains("Used 1 times"))
    }

    @Test
    fun `formatter degrades gracefully without breakdown or personalization`() {
        val text = ProvenanceFormatter.format(
            word = "to",
            meta = SuggestionMeta(
                SuggestionOrigin.NEXT_WORD,
                note = ProvenanceNote.NextWord("want", 14, 63, fromStaticSeed = false)
            ),
            barScore = 700,
            personalization = null,
            strings = strings()
        )
        assertTrue(text.contains("Origin NEXT_WORD"))
        assertTrue(text.contains("seen 14×"))
        assertTrue(text.contains("Score: 700"))
        assertFalse(text.contains("Score components"))

        val unknown = ProvenanceFormatter.format("x", null, null, null, strings())
        assertTrue(unknown.contains("Unknown"))
    }
}
