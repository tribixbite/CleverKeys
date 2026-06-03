package tribixbite.cleverkeys.autocorrect

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for the English inflection stemmer used by the autocorrect
 * morphological guard (issue: valid plurals like "immunizations" were being
 * autocorrected to distant words like "organizations" because the plural was
 * missing from the bundled dictionary while the singular stem was present).
 */
class MorphologyTest {

    @Test
    fun regularPlural_s_yieldsStem() {
        // immunizations → immunization (the reported case)
        assertThat(Morphology.inflectionStems("immunizations")).contains("immunization")
        assertThat(Morphology.inflectionStems("cats")).contains("cat")
        assertThat(Morphology.inflectionStems("realizations")).contains("realization")
    }

    @Test
    fun plural_es_yieldsStem() {
        // boxes → box, watches → watch, buses → bus
        assertThat(Morphology.inflectionStems("boxes")).contains("box")
        assertThat(Morphology.inflectionStems("watches")).contains("watch")
        // -es words also expose the bare -s strip as a candidate (boxe) — fine,
        // the dictionary check filters those out.
    }

    @Test
    fun plural_ies_yieldsYStem() {
        // parties → party, cities → city
        assertThat(Morphology.inflectionStems("parties")).contains("party")
        assertThat(Morphology.inflectionStems("cities")).contains("city")
    }

    @Test
    fun past_ed_yieldsStems() {
        // walked → walk; used → use; tried → try
        assertThat(Morphology.inflectionStems("walked")).contains("walk")
        assertThat(Morphology.inflectionStems("used")).contains("use")
        assertThat(Morphology.inflectionStems("tried")).contains("try")
    }

    @Test
    fun gerund_ing_yieldsStems() {
        // walking → walk; using → use
        assertThat(Morphology.inflectionStems("walking")).contains("walk")
        assertThat(Morphology.inflectionStems("using")).contains("use")
    }

    @Test
    fun comparativeSuperlativeAdverb_yieldStems() {
        assertThat(Morphology.inflectionStems("faster")).contains("fast")
        assertThat(Morphology.inflectionStems("fastest")).contains("fast")
        assertThat(Morphology.inflectionStems("quickly")).contains("quick")
    }

    @Test
    fun noSuffix_yieldsEmpty() {
        // Words with no recognizable inflectional suffix produce no stems.
        assertThat(Morphology.inflectionStems("the")).isEmpty()
        assertThat(Morphology.inflectionStems("question")).isEmpty()
        assertThat(Morphology.inflectionStems("wuestion")).isEmpty()
    }

    @Test
    fun doubleS_notTreatedAsPlural() {
        // "ss" endings are not plurals — "kiss" is not "kis"+s, "class" not "clas"+s.
        // The bare -s strip should NOT be offered for -ss words.
        assertThat(Morphology.inflectionStems("class")).doesNotContain("clas")
        assertThat(Morphology.inflectionStems("kiss")).doesNotContain("kis")
    }

    @Test
    fun stems_areLowercaseAndNonTrivial() {
        // Never return empty or 1-char stems.
        Morphology.inflectionStems("immunizations").forEach {
            assertThat(it.length).isAtLeast(2)
            assertThat(it).isEqualTo(it.lowercase())
        }
    }
}
