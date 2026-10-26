package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Upgrade test 2026-10-25 (`tools/upgrade-test/`): an image of 80 px or less copied under
 * v1.5.0 was stored with `thumbnail_blob` NULL (its generateThumbnail recycled the bitmap it
 * was about to compress; fixed for new copies by c1bf9530). 2.0 never revisited those rows, so
 * they kept the MIME fallback icon forever. [ClipboardThumbnailRepair] is the startup pass that
 * regenerates the missing thumbnails from the saved media file and writes them back.
 */
class ClipboardThumbnailRepairTest {

    /** Records every generate/store call so a test can assert what the pass touched. */
    private class Fake(
        private val existing: Set<String>,
        private val blobs: Map<String, ByteArray?> = emptyMap(),
    ) {
        val generated = mutableListOf<Pair<String, String>>()
        val stored = mutableMapOf<String, ByteArray>()

        fun run(missing: Map<String, String>, limit: Int = ClipboardThumbnailRepair.MAX_PER_PASS) =
            ClipboardThumbnailRepair.repair(
                missing = missing,
                fileExists = { it in existing },
                generate = { path, mime ->
                    generated += path to mime
                    if (path in blobs) blobs[path] else byteArrayOf(1, 2, 3)
                },
                store = { path, blob -> stored[path] = blob; 1 },
                limit = limit,
            )
    }

    private val small = "clipboard_media/0/small.png"

    @Test
    fun aV150SmallImageWithoutThumbnailGetsOneStoredForItsMediaPath() {
        val fake = Fake(existing = setOf(small))
        val outcome = fake.run(mapOf(small to "image/png"))

        assertThat(fake.generated).containsExactly(small to "image/png")
        assertThat(fake.stored.keys).containsExactly(small)
        assertThat(outcome).isEqualTo(ClipboardThumbnailRepair.Outcome(repaired = 1, failed = 0, skipped = 0))
    }

    @Test
    fun videoAndPdfRowsAreRepairedTooBecauseGenerateThumbnailHandlesThem() {
        val video = "clipboard_media/0/v.mp4"
        val pdf = "clipboard_media/0/d.pdf"
        val fake = Fake(existing = setOf(video, pdf))
        val outcome = fake.run(mapOf(video to "video/mp4", pdf to "application/pdf"))

        assertThat(fake.stored.keys).containsExactly(video, pdf)
        assertThat(outcome.repaired).isEqualTo(2)
    }

    @Test
    fun aMimeTypeWithoutAThumbnailDecoderIsNeverDecoded() {
        // A zip or plain file can never get a thumbnail; decoding it on every start is waste.
        val zip = "clipboard_media/0/a.zip"
        val fake = Fake(existing = setOf(zip))
        val outcome = fake.run(mapOf(zip to "application/zip"))

        assertThat(fake.generated).isEmpty()
        assertThat(fake.stored).isEmpty()
        assertThat(outcome).isEqualTo(ClipboardThumbnailRepair.Outcome(repaired = 0, failed = 0, skipped = 1))
    }

    @Test
    fun aMissingMediaFileIsSkippedWithoutDecoding() {
        val fake = Fake(existing = emptySet())
        val outcome = fake.run(mapOf(small to "image/png"))

        assertThat(fake.generated).isEmpty()
        assertThat(outcome.skipped).isEqualTo(1)
    }

    @Test
    fun aFailedDecodeStoresNothingAndCountsAsFailed() {
        val fake = Fake(existing = setOf(small), blobs = mapOf(small to null))
        val outcome = fake.run(mapOf(small to "image/png"))

        assertThat(fake.stored).isEmpty()
        assertThat(outcome).isEqualTo(ClipboardThumbnailRepair.Outcome(repaired = 0, failed = 1, skipped = 0))
    }

    @Test
    fun anExceptionFromOnePathDoesNotStopTheRest() {
        val other = "clipboard_media/0/other.png"
        val outcome = ClipboardThumbnailRepair.repair(
            missing = linkedMapOf(small to "image/png", other to "image/png"),
            fileExists = { true },
            generate = { path, _ -> if (path == small) throw IllegalStateException("boom") else byteArrayOf(9) },
            store = { _, _ -> 1 },
        )
        assertThat(outcome).isEqualTo(ClipboardThumbnailRepair.Outcome(repaired = 1, failed = 1, skipped = 0))
    }

    @Test
    fun onePassDecodesAtMostTheLimit() {
        val paths = (0 until 5).associate { "clipboard_media/0/$it.png" to "image/png" }
        val fake = Fake(existing = paths.keys)
        val outcome = fake.run(paths, limit = 3)

        assertThat(fake.generated).hasSize(3)
        assertThat(outcome.repaired).isEqualTo(3)
    }

    @Test
    fun mimeTypeSupportMatchesGenerateThumbnail() {
        assertThat(ClipboardThumbnailRepair.canHaveThumbnail("image/png")).isTrue()
        assertThat(ClipboardThumbnailRepair.canHaveThumbnail("image/gif")).isTrue()
        assertThat(ClipboardThumbnailRepair.canHaveThumbnail("video/mp4")).isTrue()
        assertThat(ClipboardThumbnailRepair.canHaveThumbnail("application/pdf")).isTrue()
        assertThat(ClipboardThumbnailRepair.canHaveThumbnail("text/plain")).isFalse()
        assertThat(ClipboardThumbnailRepair.canHaveThumbnail("application/zip")).isFalse()
    }
}
