package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Unit behaviour of [ContractionCollisionDemotion] on synthetic maps.
 *
 * Synthetic rather than shipped data on purpose: this pins the RULE (intersect the key's
 * colliding languages against the ACTIVE set, move the survivor into the paired bucket), while
 * `ContractionCollisionDataTest` pins the shipped tables the rule is applied to. Mixing them
 * would mean a data regeneration could silently change what the rule appears to do.
 */
class ContractionCollisionDemotionTest {

    private fun nonPaired(vararg pairs: Pair<String, String>) = mutableMapOf(*pairs)
    private fun paired() = mutableMapOf<String, MutableList<String>>()

    @Test
    fun `a key colliding with an active language is demoted into the paired bucket`() {
        val replace = nonPaired("rendezvous" to "rendez-vous", "questce" to "qu'est-ce")
        val append = paired()

        val demoted = ContractionCollisionDemotion.demote(
            nonPaired = replace,
            paired = append,
            collisionsByKey = mapOf("rendezvous" to setOf("de", "en")),
            activeLanguages = setOf("fr", "en"),
        )

        assertThat(demoted).isEqualTo(1)
        assertWithMessage(
            "a demoted key must LEAVE the replace map — while it is there, the tap path " +
                "substitutes the display form and the real English word is destroyed in its slot"
        ).that(replace).doesNotContainKey("rendezvous")
        assertWithMessage("the elision must stay reachable as an APPEND variant, not vanish")
            .that(append["rendezvous"]).containsExactly("rendez-vous")
        assertWithMessage("a non-colliding key must be untouched")
            .that(replace["questce"]).isEqualTo("qu'est-ce")
    }

    @Test
    fun `a monolingual user is unaffected because the intersection is empty`() {
        val replace = nonPaired("rendezvous" to "rendez-vous")
        val append = paired()

        val demoted = ContractionCollisionDemotion.demote(
            nonPaired = replace,
            paired = append,
            collisionsByKey = mapOf("rendezvous" to setOf("de", "en")),
            activeLanguages = setOf("fr"),
        )

        assertThat(demoted).isEqualTo(0)
        assertWithMessage(
            "an fr-only user must still get the REPLACE — the mapping is correct French and " +
                "nothing of theirs collides with it"
        ).that(replace["rendezvous"]).isEqualTo("rendez-vous")
        assertThat(append).isEmpty()
    }

    @Test
    fun `collisions with a language the user has NOT enabled do not demote`() {
        // The reason the sidecar stores WHICH languages rather than a boolean. `cest` is an
        // obscure English lexicon entry; an fr+es user has not enabled English, so demoting on
        // "collides with something" would cost them a correct French REPLACE for no reason.
        val replace = nonPaired("cest" to "c'est")
        val append = paired()

        val demoted = ContractionCollisionDemotion.demote(
            nonPaired = replace,
            paired = append,
            collisionsByKey = mapOf("cest" to setOf("en")),
            activeLanguages = setOf("fr", "es"),
        )

        assertThat(demoted).isEqualTo(0)
        assertThat(replace["cest"]).isEqualTo("c'est")
    }

    @Test
    fun `the English base's own keys are demoted for the other language too`() {
        // The bug is bidirectional. `im` is an English REPLACE key AND a common German word, so
        // a de+en user typing German `im` was getting `I'm`. Fixing only the non-English side
        // would have left this, which is the higher-frequency half.
        val replace = nonPaired("im" to "i'm", "dont" to "don't", "cant" to "can't")
        val append = paired()

        val demoted = ContractionCollisionDemotion.demote(
            nonPaired = replace,
            paired = append,
            collisionsByKey = mapOf(
                "im" to setOf("de", "es", "fr", "it", "pt", "sv"),
                "dont" to setOf("fr"),
            ),
            activeLanguages = setOf("de", "en"),
        )

        assertThat(demoted).isEqualTo(1)
        assertThat(replace).doesNotContainKey("im")
        assertThat(append["im"]).containsExactly("i'm")
        assertWithMessage("`dont` collides only with fr, which this user has not enabled")
            .that(replace["dont"]).isEqualTo("don't")
        assertWithMessage("`cant` has no collision entry at all")
            .that(replace["cant"]).isEqualTo("can't")
    }

    @Test
    fun `demotion appends to an existing paired entry without duplicating`() {
        val replace = nonPaired("lune" to "l'une")
        val append = paired().apply { put("lune", mutableListOf("l'un")) }

        ContractionCollisionDemotion.demote(
            nonPaired = replace, paired = append,
            collisionsByKey = mapOf("lune" to setOf("en")),
            activeLanguages = setOf("fr", "en"),
        )
        assertWithMessage("an existing variant list must be extended, never replaced")
            .that(append["lune"]).containsExactly("l'un", "l'une").inOrder()
    }

    @Test
    fun `demotion is idempotent`() {
        val replace = nonPaired("rendezvous" to "rendez-vous")
        val append = paired()
        val collisions = mapOf("rendezvous" to setOf("en"))

        val first = ContractionCollisionDemotion.demote(replace, append, collisions, setOf("fr", "en"))
        val second = ContractionCollisionDemotion.demote(replace, append, collisions, setOf("fr", "en"))

        assertThat(first).isEqualTo(1)
        assertWithMessage("the key is already gone from the replace map, so there is nothing left to do")
            .that(second).isEqualTo(0)
        assertWithMessage("a second pass must not append a duplicate variant")
            .that(append["rendezvous"]).containsExactly("rendez-vous")
    }

    @Test
    fun `empty inputs are no-ops`() {
        val replace = nonPaired("questce" to "qu'est-ce")
        val append = paired()
        assertThat(ContractionCollisionDemotion.demote(replace, append, emptyMap(), setOf("fr", "en")))
            .isEqualTo(0)
        assertThat(ContractionCollisionDemotion.demote(replace, append, mapOf("questce" to setOf("en")), emptySet()))
            .isEqualTo(0)
        assertThat(replace).hasSize(1)
    }

    @Test
    fun `a collision entry for a key that was never loaded is skipped safely`() {
        // The sidecar covers a whole language's REPLACE file, but a language PACK can replace
        // that file wholesale, so a collision key may simply not be present. That must not throw
        // and must not fabricate a paired entry for a mapping the user does not have.
        val replace = nonPaired("questce" to "qu'est-ce")
        val append = paired()

        val demoted = ContractionCollisionDemotion.demote(
            nonPaired = replace, paired = append,
            collisionsByKey = mapOf("rendezvous" to setOf("en")),
            activeLanguages = setOf("fr", "en"),
        )

        assertThat(demoted).isEqualTo(0)
        assertWithMessage("no paired entry may be invented for a key that was never loaded")
            .that(append).isEmpty()
    }

    @Test
    fun `the asset name matches the shipped sidecar convention`() {
        assertThat(ContractionCollisionDemotion.assetName("fr"))
            .isEqualTo("dictionaries/contraction_collisions_fr.json")
    }
}
