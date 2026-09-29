package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.SwipeCorrectionPolicy
import tribixbite.cleverkeys.SwipeCorrectionTracker
import tribixbite.cleverkeys.UserWordFrequency
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import java.io.File

/**
 * "Prefer “Y” when swiping?" for an apostrophe or hyphen word (learning-system audit
 * 2026-09-26, Resolution → joiner follow-up). The offer's mechanism is "accepting adds Y to the
 * personal dictionary". For a joiner word that entry is now read as a DISPLAY preference
 * ([UserJoinerPreference]) that [ContractionOverlay] honours: the decoded surface (`shed`) is
 * shown as the user's form (`she'd`) at the surface's rank, with the surface kept one slot
 * behind when it is a real word.
 *
 * Until 2026-09-29 this test pinned the opposite: the user word only lifted `shed` in the trie
 * and the overlay's frequency rule left `shed` as the auto-insert, so joiner words were kept
 * out of the offer. The composition below mirrors `CtcEngineAdapter` (EN_JSON and CKDT
 * branches) with the real pure pieces; `theAdaptersWireThePreference` pins the adapters.
 */
class SwipePreferJoinerWordTest {

    private val alphabet = ('a'..'z').toList().toCharArray()
    private val alphabetSet = alphabet.toHashSet()

    /** en_enhanced.json byte scale: floor 134, `shed` 188, `she` 250 (skill §6c table). */
    private val base = listOf(
        "the" to 255.0, "she" to 250.0, "shed" to 188.0, "id" to 196.0, "coop" to 150.0, "zebra" to 134.0,
    )

    private val enPaired = mapOf("shed" to listOf("she'd"), "id" to listOf("i'd"))
    private val enPairFreq = mapOf(("shed" to "she'd") to 189, ("id" to "i'd") to 211)

    /** `CtcEngineAdapter` EN_JSON branch: merge → ordinals → pairing base freqs → preferences → overlay. */
    private fun decodeEn(slate: List<String>, userWords: List<Pair<String, Int>>): List<String> {
        val merged = CtcLexiconMerge.merge(base, userWords, emptySet())
        val ordinals = CtcLexiconMerge.ordinals(merged)
        val baseFreqs = PairingBaseFrequencies.select(merged, enPaired.keys)
        val prefs = UserJoinerPreference.build(
            userWords, { UserJoinerPreference.stripToAlphabet(it, alphabetSet) }, { ordinals.containsKey(it) }
        )
        return ContractionOverlay.apply(
            words = slate,
            scores = slate.indices.map { 100 - it },
            pairedVariants = { enPaired[it] },
            nonPairedMapping = { null },
            wordOrdinal = { ordinals[it] },
            pairedVariantFrequency = { b, v -> enPairFreq[b to v] },
            baseFrequency = { baseFreqs[it] },
            userPreferredForm = { prefs[it] },
        ).first
    }

    private val preferShed = listOf("she'd" to UserWordFrequency.DEFAULT)

    // ── The offer ─────────────────────────────────────────────────────────────────────────

    @Test
    fun theOfferConsidersAJoinerWord() {
        val swipe = SwipeCorrectionTracker.SwipeRecord("shed", listOf("shed", "she'd"), null)
        assertThat(SwipeCorrectionPolicy.isPlausible(swipe, "she'd") { true }).isTrue()
        val hyphen = SwipeCorrectionTracker.SwipeRecord("coop", listOf("coop"), null)
        assertWithMessage("typed co-op after an undone coop: same letters at both ends")
            .that(SwipeCorrectionPolicy.isPlausible(hyphen, "co-op") { true }).isTrue()
    }

    // ── EN CTC: prefer she'd ──────────────────────────────────────────────────────────────

    @Test
    fun withoutThePreferenceShedIsTheAutoInsert() {
        assertWithMessage("she'd 189 vs shed 188: inside PROMOTION_MARGIN, base first")
            .that(decodeEn(listOf("shed", "she"), emptyList())).containsExactly("shed", "she'd", "she").inOrder()
    }

    @Test
    fun preferringShedPutsSheDAtRankZeroOnTheNextShedSwipe() {
        assertThat(decodeEn(listOf("shed", "she"), preferShed)).containsExactly("she'd", "shed", "she").inOrder()
    }

    @Test
    fun thePreferenceAppliesAtWhateverRankTheSurfaceDecodes() {
        assertThat(decodeEn(listOf("she", "shed"), preferShed)).containsExactly("she", "she'd", "shed").inOrder()
    }

    @Test
    fun theUserWordStillRaisesItsSurfaceInTheTrie() {
        val without = CtcLexiconTrie.loadStrippingNonAlphabet(alphabet, CtcLexiconMerge.merge(base, emptyList(), emptySet()))
        val with = CtcLexiconTrie.loadStrippingNonAlphabet(alphabet, CtcLexiconMerge.merge(base, preferShed, emptySet()))
        assertWithMessage("the trace the user keeps swiping decodes to the surface more readily")
            .that(with.logFrequencyOf("shed")!!).isGreaterThan(without.logFrequencyOf("shed")!!)
    }

    // ── The reverse preference and conflicts ──────────────────────────────────────────────

    @Test
    fun preferringTheBaseStillBeatsAPromotedVariant() {
        assertWithMessage("no user word: i'd promoted").that(decodeEn(listOf("id"), emptyList()).first()).isEqualTo("i'd")
        assertWithMessage("user id kills the promotion (ContractionPromotionUserWordTest)")
            .that(decodeEn(listOf("id"), listOf("id" to UserWordFrequency.DEFAULT)).first()).isEqualTo("id")
    }

    @Test
    fun whenTheUserClaimedBothReadingsTheTracedLiteralKeepsItsSlot() {
        val both = listOf("she'd" to UserWordFrequency.DEFAULT, "shed" to UserWordFrequency.DEFAULT)
        assertThat(decodeEn(listOf("shed"), both).first()).isEqualTo("shed")
        assertThat(UserJoinerPreference.build(both, { UserJoinerPreference.stripToAlphabet(it, alphabetSet) }, { true }))
            .isEmpty()
    }

    @Test
    fun undoingTheAddRestoresTheOldSlate() {
        // Undo removes the word from custom_words_<lang>; the next lexicon build sees the list without it.
        val afterAccept = decodeEn(listOf("shed", "she"), preferShed)
        val afterUndo = decodeEn(listOf("shed", "she"), emptyList())
        assertThat(afterAccept.first()).isEqualTo("she'd")
        assertThat(afterUndo).containsExactly("shed", "she'd", "she").inOrder()
    }

    @Test
    fun theHigherFrequencyJoinerWordWinsASharedSurface() {
        val prefs = UserJoinerPreference.build(
            listOf("s-hed" to 100, "she'd" to 200),
            { UserJoinerPreference.stripToAlphabet(it, alphabetSet) }, { true },
        )
        assertThat(prefs["shed"]?.form).isEqualTo("she'd")
    }

    // ── Hyphen words ──────────────────────────────────────────────────────────────────────

    @Test
    fun aHyphenUserWordIsShownAheadOfItsRealWordSurface() {
        val coop = listOf("co-op" to UserWordFrequency.DEFAULT)
        assertThat(CtcLexiconTrie.loadStrippingNonAlphabet(alphabet, CtcLexiconMerge.merge(base, coop, emptySet()))
            .contains("coop")).isTrue()
        assertThat(decodeEn(listOf("coop"), coop)).containsExactly("co-op", "coop").inOrder()
    }

    @Test
    fun aJoinerUserWordWhoseSurfaceIsNoWordReplacesIt() {
        val xray = listOf("x-ray" to UserWordFrequency.DEFAULT)
        val trie = CtcLexiconTrie.loadStrippingNonAlphabet(alphabet, CtcLexiconMerge.merge(base, xray, emptySet()))
        assertWithMessage("reachable only through the user word").that(trie.contains("xray")).isTrue()
        assertWithMessage("the non-word surface is not offered beside it")
            .that(decodeEn(listOf("xray", "the"), xray)).containsExactly("x-ray", "the").inOrder()
    }

    // ── fr (CKDT source): l'une never destroys lune ──────────────────────────────────────

    /** fr CKDT scale (`255 − rank`), identity calibration; pairs file carries no frequency. */
    private val frBase = listOf("la" to 254.0, "une" to 250.0, "lune" to 200.0, "lui" to 240.0)
    private val frPaired = mapOf("lune" to listOf("l'une"))

    /** `CtcEngineAdapter` CKDT branch: merge → projection → canonical display → overlay. */
    private fun decodeFr(slate: List<String>, userWords: List<Pair<String, Int>>): List<String> {
        val merged = CtcLexiconMerge.merge(frBase, userWords, emptySet())
        val ordinals = CtcLexiconMerge.ordinals(merged)
        val projected = UserJoinerPreference.projectWithoutJoinerDisplay(
            merged, userWords, CtcAzProjection::projectLexicon, CtcAzProjection::project
        )
        val prefs = UserJoinerPreference.build(
            userWords,
            { w -> CtcAzProjection.project(w)?.let { projected.display[it] ?: it } },
            { ordinals.containsKey(it) },
        )
        val displayed = slate.map { projected.display[it] ?: it }
        return ContractionOverlay.apply(
            words = displayed,
            scores = displayed.indices.map { 100 - it },
            pairedVariants = { frPaired[it] },
            nonPairedMapping = { null },
            wordOrdinal = { ordinals[it] },
            userPreferredForm = { prefs[it] },
        ).first
    }

    @Test
    fun frLuneIsUnchangedWithoutAUserWord() {
        assertWithMessage("no frequency on fr pairs: the elision stays at the tail")
            .that(decodeFr(listOf("lune", "lui"), emptyList())).containsExactly("lune", "lui", "l'une").inOrder()
    }

    @Test
    fun frAUserLUneGoesAheadButLuneSurvivesBehindIt() {
        val slate = decodeFr(listOf("lune", "lui"), listOf("l'une" to UserWordFrequency.DEFAULT))
        assertThat(slate).containsExactly("l'une", "lune", "lui").inOrder()
    }

    @Test
    fun frTheUserWordNoLongerTakesTheSurfacesDisplaySlot() {
        val userWords = listOf("l'une" to UserWordFrequency.DEFAULT)
        val merged = CtcLexiconMerge.merge(frBase, userWords, emptySet())
        val old = CtcAzProjection.projectLexicon(merged)
        assertWithMessage("the pre-fix projection: user l'une (255) wins lune's display — lune destroyed")
            .that(old.display["lune"]).isEqualTo("l'une")
        val fixed = UserJoinerPreference.projectWithoutJoinerDisplay(
            merged, userWords, CtcAzProjection::projectLexicon, CtcAzProjection::project
        )
        assertThat(fixed.display).doesNotContainKey("lune")
        assertWithMessage("the surface keeps the user frequency (max retention)").that(fixed.freqs["lune"]).isEqualTo(255.0)
    }

    // ── Wiring ────────────────────────────────────────────────────────────────────────────

    @Test
    fun theAdaptersWireThePreference() {
        val ctc = File("src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")
        check(ctc.exists()) { "run with the project root as CWD" }
        val ctcText = ctc.readText()
        assertThat(ctcText).contains("UserJoinerPreference.projectWithoutJoinerDisplay(")
        assertThat(ctcText).contains("UserJoinerPreference.stripToAlphabet(")
        assertThat(ctcText).contains("userPreferredForm = { userPreferences[it] }")
        val geo = File("src/main/kotlin/tribixbite/cleverkeys/swipe/GeometricEngineAdapter.kt").readText()
        assertThat(geo).contains("UserJoinerPreference.build(")
        assertThat(geo).contains("userPreferredForm = { userPreferences[it] }")
    }
}
