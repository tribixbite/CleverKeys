package tribixbite.cleverkeys.gif

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for GifCategory enum.
 *
 * Covers enum value properties, fromId lookups, browsableCategories filtering,
 * and searchCategories query matching.
 */
class GifCategoryTest {

    // =========================================================================
    // Enum values & count
    // =========================================================================

    @Test
    fun `all 19 categories are defined`() {
        // 17 emotions + RECENTLY_USED + ALL = 19
        assertThat(GifCategory.entries).hasSize(19)
    }

    @Test
    fun `RECENTLY_USED has id 0`() {
        assertThat(GifCategory.RECENTLY_USED.id).isEqualTo(0)
    }

    @Test
    fun `ALL has id negative 1`() {
        assertThat(GifCategory.ALL.id).isEqualTo(-1)
    }

    @Test
    fun `emotion categories have positive ids from 1 to 17`() {
        val emotionCategories = GifCategory.entries.filter {
            it != GifCategory.RECENTLY_USED && it != GifCategory.ALL
        }
        assertThat(emotionCategories).hasSize(17)
        val ids = emotionCategories.map { it.id }
        assertThat(ids).containsExactlyElementsIn(1..17)
    }

    @Test
    fun `all categories have non-empty display names`() {
        for (category in GifCategory.entries) {
            assertThat(category.displayName).isNotEmpty()
        }
    }

    @Test
    fun `all categories have non-empty icons`() {
        for (category in GifCategory.entries) {
            assertThat(category.icon).isNotEmpty()
        }
    }

    @Test
    fun `all categories have non-empty keywords`() {
        for (category in GifCategory.entries) {
            assertThat(category.keywords).isNotEmpty()
        }
    }

    // =========================================================================
    // Specific category properties
    // =========================================================================

    @Test
    fun `AMUSEMENT has correct display name and keywords`() {
        assertThat(GifCategory.AMUSEMENT.displayName).isEqualTo("Funny")
        assertThat(GifCategory.AMUSEMENT.keywords).contains("laughing")
        assertThat(GifCategory.AMUSEMENT.keywords).contains("funny")
        assertThat(GifCategory.AMUSEMENT.keywords).contains("lol")
    }

    @Test
    fun `LOVE has correct display name and keywords`() {
        assertThat(GifCategory.LOVE.displayName).isEqualTo("Love")
        assertThat(GifCategory.LOVE.keywords).contains("heart")
        assertThat(GifCategory.LOVE.keywords).contains("romantic")
    }

    @Test
    fun `APPROVAL has correct display name and keywords`() {
        assertThat(GifCategory.APPROVAL.displayName).isEqualTo("Approve")
        assertThat(GifCategory.APPROVAL.keywords).contains("yes")
        assertThat(GifCategory.APPROVAL.keywords).contains("thumbs up")
    }

    @Test
    fun `SURPRISE has correct id`() {
        assertThat(GifCategory.SURPRISE.id).isEqualTo(16)
    }

    @Test
    fun `APPROVAL has id 17`() {
        assertThat(GifCategory.APPROVAL.id).isEqualTo(17)
    }

    // =========================================================================
    // fromId
    // =========================================================================

    @Test
    fun `fromId returns correct category for valid ids`() {
        assertThat(GifCategory.fromId(0)).isEqualTo(GifCategory.RECENTLY_USED)
        assertThat(GifCategory.fromId(-1)).isEqualTo(GifCategory.ALL)
        assertThat(GifCategory.fromId(1)).isEqualTo(GifCategory.AMUSEMENT)
        assertThat(GifCategory.fromId(2)).isEqualTo(GifCategory.ANGER)
        assertThat(GifCategory.fromId(11)).isEqualTo(GifCategory.LOVE)
        assertThat(GifCategory.fromId(17)).isEqualTo(GifCategory.APPROVAL)
    }

    @Test
    fun `fromId returns null for non-existent id`() {
        assertThat(GifCategory.fromId(99)).isNull()
        assertThat(GifCategory.fromId(-2)).isNull()
        assertThat(GifCategory.fromId(18)).isNull()
        assertThat(GifCategory.fromId(100)).isNull()
    }

    @Test
    fun `fromId returns null for Integer MIN and MAX`() {
        assertThat(GifCategory.fromId(Int.MIN_VALUE)).isNull()
        assertThat(GifCategory.fromId(Int.MAX_VALUE)).isNull()
    }

    // =========================================================================
    // browsableCategories
    // =========================================================================

    @Test
    fun `browsableCategories excludes RECENTLY_USED and ALL`() {
        val browsable = GifCategory.browsableCategories()
        assertThat(browsable).doesNotContain(GifCategory.RECENTLY_USED)
        assertThat(browsable).doesNotContain(GifCategory.ALL)
    }

    @Test
    fun `browsableCategories has 17 emotion categories`() {
        assertThat(GifCategory.browsableCategories()).hasSize(17)
    }

    @Test
    fun `browsableCategories contains all emotion types`() {
        val browsable = GifCategory.browsableCategories()
        assertThat(browsable).contains(GifCategory.AMUSEMENT)
        assertThat(browsable).contains(GifCategory.ANGER)
        assertThat(browsable).contains(GifCategory.LOVE)
        assertThat(browsable).contains(GifCategory.SURPRISE)
        assertThat(browsable).contains(GifCategory.APPROVAL)
    }

    @Test
    fun `browsableCategories preserves enum ordering`() {
        val browsable = GifCategory.browsableCategories()
        // AMUSEMENT (id=1) should come before ANGER (id=2)
        val amusementIdx = browsable.indexOf(GifCategory.AMUSEMENT)
        val angerIdx = browsable.indexOf(GifCategory.ANGER)
        assertThat(amusementIdx).isLessThan(angerIdx)
    }

    // =========================================================================
    // searchCategories
    // =========================================================================

    @Test
    fun `searchCategories matches display name`() {
        val results = GifCategory.searchCategories("Funny")
        assertThat(results).contains(GifCategory.AMUSEMENT)
    }

    @Test
    fun `searchCategories is case insensitive for display name`() {
        val results = GifCategory.searchCategories("funny")
        assertThat(results).contains(GifCategory.AMUSEMENT)
    }

    @Test
    fun `searchCategories matches keywords`() {
        val results = GifCategory.searchCategories("lol")
        assertThat(results).contains(GifCategory.AMUSEMENT)
    }

    @Test
    fun `searchCategories matches partial keywords`() {
        val results = GifCategory.searchCategories("laugh")
        // Should match AMUSEMENT (has "laughing" and "laugh")
        assertThat(results).contains(GifCategory.AMUSEMENT)
    }

    @Test
    fun `searchCategories matches heart keyword to LOVE`() {
        val results = GifCategory.searchCategories("heart")
        assertThat(results).contains(GifCategory.LOVE)
    }

    @Test
    fun `searchCategories with no match returns empty list`() {
        val results = GifCategory.searchCategories("xyznotacategory")
        assertThat(results).isEmpty()
    }

    @Test
    fun `searchCategories trims query whitespace`() {
        val results = GifCategory.searchCategories("  angry  ")
        assertThat(results).contains(GifCategory.ANGER)
    }

    @Test
    fun `searchCategories can match multiple categories`() {
        // "happy" appears as a keyword in both AMUSEMENT and HAPPINESS
        val results = GifCategory.searchCategories("happy")
        assertThat(results).contains(GifCategory.AMUSEMENT)
        assertThat(results).contains(GifCategory.HAPPINESS)
    }

    @Test
    fun `searchCategories matches sad in display name`() {
        // SADNESS displayName is "Sad"
        val results = GifCategory.searchCategories("sad")
        assertThat(results).contains(GifCategory.SADNESS)
    }

    @Test
    fun `searchCategories matches upset across multiple categories`() {
        // "upset" appears in ANGER and SADNESS keywords
        val results = GifCategory.searchCategories("upset")
        assertThat(results).contains(GifCategory.ANGER)
        assertThat(results).contains(GifCategory.SADNESS)
    }

    @Test
    fun `searchCategories matches special category RECENTLY_USED by keyword`() {
        val results = GifCategory.searchCategories("recent")
        assertThat(results).contains(GifCategory.RECENTLY_USED)
    }

    @Test
    fun `searchCategories matches ALL category by keyword`() {
        val results = GifCategory.searchCategories("browse")
        assertThat(results).contains(GifCategory.ALL)
    }

    @Test
    fun `searchCategories with empty query matches everything`() {
        // Empty string is contained in all strings
        val results = GifCategory.searchCategories("")
        assertThat(results).hasSize(GifCategory.entries.size)
    }
}
