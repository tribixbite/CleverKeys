package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The stored form of user swipe priority (2026-10-08): parse/serialize, the editing helpers
 * every writer shares, the user-word restriction both swipe adapters apply, and the backup
 * section. Pure JVM.
 */
class SwipePriorityTest {

    @Test
    fun storedValuesAreStableAndUnknownReadsAsNormal() {
        // Persisted integers — never renumber.
        assertThat(SwipePriority.NORMAL.storedValue).isEqualTo(0)
        assertThat(SwipePriority.HIGH.storedValue).isEqualTo(1)
        assertThat(SwipePriority.HIGHEST.storedValue).isEqualTo(2)
        assertThat(SwipePriority.fromStored(2)).isEqualTo(SwipePriority.HIGHEST)
        for (bad in listOf(null, -1, 3, 99)) {
            assertWithMessage("stored $bad").that(SwipePriority.fromStored(bad)).isEqualTo(SwipePriority.NORMAL)
        }
        assertThat(SwipePriority.NORMAL.next()).isEqualTo(SwipePriority.HIGH)
        assertThat(SwipePriority.HIGHEST.next()).isNull()
    }

    @Test
    fun roundTripKeepsOnlyRaisedLevels() {
        val map = linkedMapOf(
            "adb" to SwipePriority.HIGHEST, "wet" to SwipePriority.HIGH, "git" to SwipePriority.NORMAL,
        )
        val json = SwipePriority.toJson(map)
        assertThat(json).doesNotContain("git")
        assertThat(SwipePriority.parseMap(json))
            .containsExactly("adb", SwipePriority.HIGHEST, "wet", SwipePriority.HIGH)
    }

    @Test
    fun absentOrDamagedStoreIsEmptyNeverAThrow() {
        for (raw in listOf(null, "", "   ", "{}", "[1,2]", "not json", "{\"a\":\"high\"}", "{\"a\":{}}")) {
            assertWithMessage("raw=$raw").that(SwipePriority.parseMap(raw)).isEmpty()
        }
        // One bad entry does not cost the good ones.
        assertThat(SwipePriority.parseMap("{\"x\":\"?\",\"adb\":2,\"\":1,\"y\":7}"))
            .containsExactly("adb", SwipePriority.HIGHEST)
    }

    @Test
    fun editingHelpers() {
        val start = mapOf("adb" to SwipePriority.HIGH)
        assertThat(SwipePriority.withLevel(start, "wet", SwipePriority.HIGHEST))
            .containsExactly("adb", SwipePriority.HIGH, "wet", SwipePriority.HIGHEST)
        assertWithMessage("NORMAL removes the entry")
            .that(SwipePriority.withLevel(start, "adb", SwipePriority.NORMAL)).isEmpty()
        assertThat(SwipePriority.without(start, listOf("adb"))).isEmpty()
        assertThat(SwipePriority.without(start, emptyList())).isEqualTo(start)
        assertThat(SwipePriority.renamed(start, "adb", "ADB")).containsExactly("ADB", SwipePriority.HIGH)
        assertWithMessage("renaming a word with no level changes nothing")
            .that(SwipePriority.renamed(start, "wet", "wit")).isEqualTo(start)
    }

    @Test
    fun aLevelOnlyActsForAPersonalDictionaryWord() {
        val levels = mapOf("adb" to SwipePriority.HIGH, "gone" to SwipePriority.HIGHEST, "Wet" to SwipePriority.HIGH)
        val active = SwipePriority.forUserWords(levels, listOf("adb", "wet", "kept"))
        assertWithMessage("`gone` was removed from the dictionary; `wet` matches its stored casing")
            .that(active).containsExactly("adb", SwipePriority.HIGH, "wet", SwipePriority.HIGH)
        assertThat(SwipePriority.forUserWords(emptyMap(), listOf("adb"))).isEmpty()
    }

    @Test
    fun backupSectionRoundTripsThroughTheStoredForm() {
        val section = SwipePriority.toBackupSection(
            mapOf(
                "fr" to mapOf("ouais" to SwipePriority.HIGH),
                "en" to mapOf("adb" to SwipePriority.HIGHEST, "git" to SwipePriority.NORMAL),
                "de" to mapOf("x" to SwipePriority.NORMAL),
            )
        )
        assertWithMessage("languages sorted, nothing-raised languages omitted")
            .that(section.keySet().toList()).containsExactly("en", "fr").inOrder()
        assertThat(SwipePriority.parseMap(section.get("en").toString()))
            .containsExactly("adb", SwipePriority.HIGHEST)
    }
}
