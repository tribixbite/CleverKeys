package tribixbite.cleverkeys.gif

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for the Gif data class.
 *
 * Covers construction, property accessors, path partitioning,
 * keyword extraction, Giphy URL generation, query matching,
 * aspect ratio calculation, and display name formatting.
 */
class GifTest {

    // =========================================================================
    // Construction & defaults
    // =========================================================================

    @Test
    fun `default construction uses sensible defaults`() {
        val gif = Gif(id = 1, width = 200, height = 150)
        assertThat(gif.durationMs).isEqualTo(0)
        assertThat(gif.fileSize).isEqualTo(0)
        assertThat(gif.packId).isEqualTo(0)
        assertThat(gif.isAvailable).isTrue()
        assertThat(gif.searchText).isEmpty()
        assertThat(gif.categories).isEmpty()
    }

    @Test
    fun `construction with all parameters`() {
        val categories = listOf(GifCategory.AMUSEMENT, GifCategory.HAPPINESS)
        val gif = Gif(
            id = 42,
            width = 320,
            height = 240,
            durationMs = 3000,
            fileSize = 150_000,
            packId = 2,
            isAvailable = false,
            searchText = "laughing happy abc123",
            categories = categories
        )
        assertThat(gif.id).isEqualTo(42)
        assertThat(gif.width).isEqualTo(320)
        assertThat(gif.height).isEqualTo(240)
        assertThat(gif.durationMs).isEqualTo(3000)
        assertThat(gif.fileSize).isEqualTo(150_000)
        assertThat(gif.packId).isEqualTo(2)
        assertThat(gif.isAvailable).isFalse()
        assertThat(gif.searchText).isEqualTo("laughing happy abc123")
        assertThat(gif.categories).hasSize(2)
    }

    @Test
    fun `data class equality and copy`() {
        val gif1 = Gif(id = 1, width = 100, height = 100)
        val gif2 = Gif(id = 1, width = 100, height = 100)
        assertThat(gif1).isEqualTo(gif2)

        val gif3 = gif1.copy(width = 200)
        assertThat(gif3.width).isEqualTo(200)
        assertThat(gif3.id).isEqualTo(1)
        assertThat(gif1).isNotEqualTo(gif3)
    }

    // =========================================================================
    // fileName
    // =========================================================================

    @Test
    fun `fileName formats id with zero padding to 6 digits`() {
        assertThat(Gif(id = 1, width = 1, height = 1).fileName).isEqualTo("000001.webp")
        assertThat(Gif(id = 42, width = 1, height = 1).fileName).isEqualTo("000042.webp")
        assertThat(Gif(id = 999, width = 1, height = 1).fileName).isEqualTo("000999.webp")
        assertThat(Gif(id = 1000, width = 1, height = 1).fileName).isEqualTo("001000.webp")
        assertThat(Gif(id = 999999, width = 1, height = 1).fileName).isEqualTo("999999.webp")
    }

    @Test
    fun `fileName for id zero`() {
        assertThat(Gif(id = 0, width = 1, height = 1).fileName).isEqualTo("000000.webp")
    }

    // =========================================================================
    // getThumbnailPath / getFullPath (partitioned paths)
    // =========================================================================

    @Test
    fun `getThumbnailPath partitions by id div 1000`() {
        // id=1 -> partition 000
        val gif1 = Gif(id = 1, width = 1, height = 1)
        assertThat(gif1.getThumbnailPath()).isEqualTo("gifs/thumbs/000/000001.webp")

        // id=999 -> partition 000
        val gif999 = Gif(id = 999, width = 1, height = 1)
        assertThat(gif999.getThumbnailPath()).isEqualTo("gifs/thumbs/000/000999.webp")

        // id=1000 -> partition 001
        val gif1000 = Gif(id = 1000, width = 1, height = 1)
        assertThat(gif1000.getThumbnailPath()).isEqualTo("gifs/thumbs/001/001000.webp")

        // id=1001 -> partition 001
        val gif1001 = Gif(id = 1001, width = 1, height = 1)
        assertThat(gif1001.getThumbnailPath()).isEqualTo("gifs/thumbs/001/001001.webp")
    }

    @Test
    fun `getFullPath partitions by id div 1000`() {
        val gif = Gif(id = 2500, width = 1, height = 1)
        assertThat(gif.getFullPath()).isEqualTo("gifs/full/002/002500.webp")
    }

    @Test
    fun `path partitioning for large ids`() {
        val gif = Gif(id = 123456, width = 1, height = 1)
        assertThat(gif.getThumbnailPath()).isEqualTo("gifs/thumbs/123/123456.webp")
        assertThat(gif.getFullPath()).isEqualTo("gifs/full/123/123456.webp")
    }

    @Test
    fun `path partitioning for id zero`() {
        val gif = Gif(id = 0, width = 1, height = 1)
        assertThat(gif.getThumbnailPath()).isEqualTo("gifs/thumbs/000/000000.webp")
        assertThat(gif.getFullPath()).isEqualTo("gifs/full/000/000000.webp")
    }

    // =========================================================================
    // Companion getPartitionedPath
    // =========================================================================

    @Test
    fun `getPartitionedPath builds correct paths`() {
        assertThat(Gif.getPartitionedPath("gifs/thumbs", 0)).isEqualTo("gifs/thumbs/000/000000.webp")
        assertThat(Gif.getPartitionedPath("gifs/thumbs", 1)).isEqualTo("gifs/thumbs/000/000001.webp")
        assertThat(Gif.getPartitionedPath("gifs/thumbs", 999)).isEqualTo("gifs/thumbs/000/000999.webp")
        assertThat(Gif.getPartitionedPath("gifs/thumbs", 1000)).isEqualTo("gifs/thumbs/001/001000.webp")
        assertThat(Gif.getPartitionedPath("gifs/full", 5678)).isEqualTo("gifs/full/005/005678.webp")
    }

    @Test
    fun `getPartitionedPath works with custom base dir`() {
        assertThat(Gif.getPartitionedPath("custom/dir", 42)).isEqualTo("custom/dir/000/000042.webp")
    }

    @Test
    fun `getPartitionedPath partition boundaries`() {
        // Test boundary between partitions
        assertThat(Gif.getPartitionedPath("d", 999)).isEqualTo("d/000/000999.webp")
        assertThat(Gif.getPartitionedPath("d", 1000)).isEqualTo("d/001/001000.webp")
        assertThat(Gif.getPartitionedPath("d", 1999)).isEqualTo("d/001/001999.webp")
        assertThat(Gif.getPartitionedPath("d", 2000)).isEqualTo("d/002/002000.webp")
    }

    // =========================================================================
    // getKeywords
    // =========================================================================

    @Test
    fun `getKeywords splits search text and drops last token (Giphy ID)`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "laughing happy funny abc123def")
        val keywords = gif.getKeywords()
        assertThat(keywords).containsExactly("laughing", "happy", "funny").inOrder()
    }

    @Test
    fun `getKeywords with single token returns that token`() {
        // Single token has no Giphy ID to drop
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "funny")
        assertThat(gif.getKeywords()).containsExactly("funny")
    }

    @Test
    fun `getKeywords with empty search text returns empty list`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "")
        assertThat(gif.getKeywords()).isEmpty()
    }

    @Test
    fun `getKeywords with blank search text returns empty list`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "   ")
        assertThat(gif.getKeywords()).isEmpty()
    }

    @Test
    fun `getKeywords with two tokens drops last`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "hello abc123")
        assertThat(gif.getKeywords()).containsExactly("hello")
    }

    @Test
    fun `getKeywords filters blank tokens from extra spaces`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "word1  word2   giphyid")
        val keywords = gif.getKeywords()
        assertThat(keywords).containsExactly("word1", "word2").inOrder()
    }

    @Test
    fun `getKeywords excludes the marked ID token and keeps every keyword`() {
        // #149: with an explicit marked ID there is no ambiguity — no keyword
        // needs to be sacrificed to hide the ID from display.
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "cute cat gid:AbC9x")
        assertThat(gif.getKeywords()).containsExactly("cute", "cat").inOrder()
    }

    @Test
    fun `getDisplayName excludes the marked ID token`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "cute cat gid:AbC9x")
        assertThat(gif.getDisplayName()).isEqualTo("Cute Cat")
    }

    // =========================================================================
    // getGiphyId — #149 contract (marked, case-preserved token only)
    //
    // Giphy media IDs are case-sensitive. The old "last token of search_text"
    // convention was doubly broken: the pipeline lowercases the whole slug
    // (case-smashed ID → 404), and keyword extraction appends compound tokens
    // AFTER the ID (the reporter's dead URL ".../cutecdmyfhpeane9ckv6ys/" is
    // the compound "cute"+ID, not even the ID). The ID is now carried only as
    // an explicit case-preserved "gid:"-marked token; anything unmarked yields
    // null so a dead URL can never be constructed from ordinary keywords.
    // =========================================================================

    @Test
    fun `getGiphyId returns case-preserved ID from the marked token`() {
        val gif = Gif(
            id = 1, width = 1, height = 1,
            searchText = "cute cat cutecat gid:CdMYfhPEanE9CkV6Ys"
        )
        assertThat(gif.getGiphyId()).isEqualTo("CdMYfhPEanE9CkV6Ys")
    }

    @Test
    fun `getGiphyId is null for legacy unmarked search text`() {
        // Legacy imported packs: last token is a lowercased compound keyword,
        // NOT a usable Giphy ID (the #149 dead-link signature). Must be null.
        val gif = Gif(
            id = 1, width = 1, height = 1,
            searchText = "cute cat cutecdmyfhpeane9ckv6ys"
        )
        assertThat(gif.getGiphyId()).isNull()
    }

    @Test
    fun `getGiphyId is null for a single unmarked token`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "abc123")
        assertThat(gif.getGiphyId()).isNull()
    }

    @Test
    fun `getGiphyId finds the marked token regardless of position`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "gid:AbC9x funny cat")
        assertThat(gif.getGiphyId()).isEqualTo("AbC9x")
    }

    @Test
    fun `getGiphyId with empty search text returns null`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "")
        assertThat(gif.getGiphyId()).isNull()
    }

    @Test
    fun `getGiphyId with blank search text returns null`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "   ")
        assertThat(gif.getGiphyId()).isNull()
    }

    @Test
    fun `getGiphyId with empty marker payload returns null`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "funny gid:")
        assertThat(gif.getGiphyId()).isNull()
    }

    // =========================================================================
    // getGiphyUrl — #149: URL only from a case-preserved marked ID
    // =========================================================================

    @Test
    fun `getGiphyUrl preserves the marked ID case`() {
        val gif = Gif(
            id = 1, width = 1, height = 1,
            searchText = "funny cat gid:xYz789AbC"
        )
        assertThat(gif.getGiphyUrl()).isEqualTo("https://media.giphy.com/media/xYz789AbC/giphy.gif")
    }

    @Test
    fun `getGiphyUrl is null for legacy case-smashed search text`() {
        // Pre-fix this produced https://media.giphy.com/media/cutecdmyfhpeane9ckv6ys/giphy.gif
        // — the reporter's exact dead link. No URL is strictly better than a 404.
        val gif = Gif(
            id = 1, width = 1, height = 1,
            searchText = "cute cat cutecdmyfhpeane9ckv6ys"
        )
        assertThat(gif.getGiphyUrl()).isNull()
    }

    @Test
    fun `getGiphyUrl returns null for empty search text`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "")
        assertThat(gif.getGiphyUrl()).isNull()
    }

    @Test
    fun `getGiphyUrl returns null for blank search text`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "   ")
        assertThat(gif.getGiphyUrl()).isNull()
    }

    // =========================================================================
    // getDisplayName
    // =========================================================================

    @Test
    fun `getDisplayName title-cases keywords`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "laughing happy abc123")
        assertThat(gif.getDisplayName()).isEqualTo("Laughing Happy")
    }

    @Test
    fun `getDisplayName with single keyword shows that keyword`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "funny")
        assertThat(gif.getDisplayName()).isEqualTo("Funny")
    }

    @Test
    fun `getDisplayName fallback for empty search text`() {
        val gif = Gif(id = 42, width = 1, height = 1, searchText = "")
        assertThat(gif.getDisplayName()).isEqualTo("GIF #42")
    }

    @Test
    fun `getDisplayName fallback for blank search text`() {
        val gif = Gif(id = 7, width = 1, height = 1, searchText = "   ")
        assertThat(gif.getDisplayName()).isEqualTo("GIF #7")
    }

    @Test
    fun `getDisplayName preserves already capitalized words`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "LOL omg giphyid")
        assertThat(gif.getDisplayName()).isEqualTo("LOL Omg")
    }

    // =========================================================================
    // matchesQuery
    // =========================================================================

    @Test
    fun `matchesQuery matches in search text`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "laughing happy funny abc123")
        assertThat(gif.matchesQuery("happy")).isTrue()
        assertThat(gif.matchesQuery("HAPPY")).isTrue()
        assertThat(gif.matchesQuery("laugh")).isTrue()
    }

    @Test
    fun `matchesQuery matches in category keywords`() {
        val gif = Gif(
            id = 1, width = 1, height = 1,
            searchText = "something giphyid",
            categories = listOf(GifCategory.AMUSEMENT)
        )
        // AMUSEMENT keywords include "laughing", "happy", "funny", "lol", "haha", "laugh"
        assertThat(gif.matchesQuery("lol")).isTrue()
        assertThat(gif.matchesQuery("haha")).isTrue()
    }

    @Test
    fun `matchesQuery is case insensitive`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "Laughing Happy abc123")
        assertThat(gif.matchesQuery("LAUGHING")).isTrue()
        assertThat(gif.matchesQuery("laughing")).isTrue()
    }

    @Test
    fun `matchesQuery trims whitespace`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "laughing abc123")
        assertThat(gif.matchesQuery("  laughing  ")).isTrue()
    }

    @Test
    fun `matchesQuery returns false for non-matching query`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "laughing abc123")
        assertThat(gif.matchesQuery("crying")).isFalse()
    }

    @Test
    fun `matchesQuery partial match works`() {
        val gif = Gif(id = 1, width = 1, height = 1, searchText = "laughing abc123")
        assertThat(gif.matchesQuery("laugh")).isTrue()
    }

    // =========================================================================
    // getAspectRatio
    // =========================================================================

    @Test
    fun `getAspectRatio returns width div height`() {
        val gif = Gif(id = 1, width = 320, height = 240)
        assertThat(gif.getAspectRatio()).isWithin(0.001f).of(320f / 240f)
    }

    @Test
    fun `getAspectRatio for square returns 1`() {
        val gif = Gif(id = 1, width = 100, height = 100)
        assertThat(gif.getAspectRatio()).isWithin(0.001f).of(1f)
    }

    @Test
    fun `getAspectRatio for tall gif returns less than 1`() {
        val gif = Gif(id = 1, width = 100, height = 200)
        assertThat(gif.getAspectRatio()).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `getAspectRatio for zero height returns 1 as fallback`() {
        val gif = Gif(id = 1, width = 200, height = 0)
        assertThat(gif.getAspectRatio()).isEqualTo(1f)
    }

    @Test
    fun `getAspectRatio for negative height returns 1 as fallback`() {
        // Edge case: invalid dimensions
        val gif = Gif(id = 1, width = 200, height = -10)
        assertThat(gif.getAspectRatio()).isEqualTo(1f)
    }

    // =========================================================================
    // Companion constants
    // =========================================================================

    @Test
    fun `THUMBS_DIR constant is correct`() {
        assertThat(Gif.THUMBS_DIR).isEqualTo("gifs/thumbs")
    }

    @Test
    fun `FULL_DIR constant is correct`() {
        assertThat(Gif.FULL_DIR).isEqualTo("gifs/full")
    }
}
