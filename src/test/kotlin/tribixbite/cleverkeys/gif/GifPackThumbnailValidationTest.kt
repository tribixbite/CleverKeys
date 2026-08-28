package tribixbite.cleverkeys.gif

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ARC-038 / #149: `GifPackManager.importPackFromUri` used to return `Success` for a pack that
 * shipped no thumbnails, because the thumbnail step was a bare `if (thumbsDir.exists())` whose
 * else-branch quietly produced `thumbCount = 0`. The pack installed, the DB rows landed, and the
 * grid rendered a wall of blank tiles — with nothing telling the user the PACK was the problem.
 *
 * The rejection now hinges on [GifPackManager.hasImportableThumbnails], and the risk that
 * matters is the predicate being an APPROXIMATION of what `GifAssetManager.importThumbnails`
 * actually copies. "The directory exists" and "the directory is non-empty" are both easy to
 * write and both wrong: each passes for a pack whose thumbnails are named in some other scheme,
 * which imports zero and lands right back at blank tiles. These cases pin the predicate to the
 * importer's real criterion — a `.webp` whose base name parses as a numeric id.
 *
 * Runs under `runMockTests` (real File IO, no MockK needed) rather than `runPureTests` because
 * the enclosing class is Android-typed.
 */
class GifPackThumbnailValidationTest {

    private val root = File("build/test-work/gif-pack-thumbs")

    @Before
    fun setUp() {
        root.deleteRecursively()
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun dir(name: String): File = File(root, name).apply { mkdirs() }

    private fun touch(parent: File, relativePath: String) {
        val f = File(parent, relativePath)
        f.parentFile?.mkdirs()
        f.writeBytes(byteArrayOf(0))
    }

    @Test
    fun `the real pack layout is accepted`() {
        // thumbs/{partition}/{id:06d}.webp — what tools/gif_pipeline/make_pack.py emits.
        val thumbs = dir("good")
        touch(thumbs, "000/000001.webp")
        touch(thumbs, "000/000002.webp")
        touch(thumbs, "001/001337.webp")

        assertThat(GifPackManager.hasImportableThumbnails(thumbs)).isTrue()
    }

    @Test
    fun `a flat unpartitioned layout is accepted`() {
        // importThumbnails walks recursively and keys off the file name only, so a flat
        // thumbs/000001.webp is importable too — the check must not require partition dirs.
        val thumbs = dir("flat")
        touch(thumbs, "000001.webp")

        assertThat(GifPackManager.hasImportableThumbnails(thumbs)).isTrue()
    }

    @Test
    fun `a missing thumbs directory is rejected`() {
        assertThat(GifPackManager.hasImportableThumbnails(File(root, "absent"))).isFalse()
    }

    @Test
    fun `an empty thumbs directory is rejected`() {
        assertThat(GifPackManager.hasImportableThumbnails(dir("empty"))).isFalse()
    }

    @Test
    fun `a directory of empty partition subdirs is rejected`() {
        val thumbs = dir("hollow")
        File(thumbs, "000").mkdirs()
        File(thumbs, "001").mkdirs()

        assertThat(GifPackManager.hasImportableThumbnails(thumbs)).isFalse()
    }

    /**
     * The case a naive "directory is non-empty" check would wave through: files are present,
     * but importThumbnails copies NONE of them, so the user still gets blank tiles.
     */
    @Test
    fun `non-numeric thumbnail names are rejected even though files exist`() {
        val thumbs = dir("named")
        touch(thumbs, "000/happy-cat.webp")
        touch(thumbs, "000/thumbs-up.webp")

        assertThat(GifPackManager.hasImportableThumbnails(thumbs)).isFalse()
    }

    @Test
    fun `non-webp files are rejected even with numeric names`() {
        val thumbs = dir("wrongext")
        touch(thumbs, "000/000001.png")
        touch(thumbs, "000/000002.gif")

        assertThat(GifPackManager.hasImportableThumbnails(thumbs)).isFalse()
    }

    @Test
    fun `one importable thumbnail among unimportable ones is enough`() {
        // The predicate gates the "can render ANYTHING" question, not completeness.
        val thumbs = dir("mixed")
        touch(thumbs, "000/readme.txt")
        touch(thumbs, "000/cover.webp")
        touch(thumbs, "000/000042.webp")

        assertThat(GifPackManager.hasImportableThumbnails(thumbs)).isTrue()
    }

    @Test
    fun `a file where the thumbs directory should be is rejected`() {
        // A pack ZIP could carry `thumbs` as a regular file; isDirectory must gate the walk.
        val notADir = File(root, "thumbs-as-file").apply { writeBytes(byteArrayOf(0)) }

        assertThat(GifPackManager.hasImportableThumbnails(notADir)).isFalse()
    }

    /** The rejection message must name the cause, so a user can act on it. */
    @Test
    fun `the rejection message identifies the legacy pack format`() {
        assertThat(GifPackManager.ERROR_MISSING_THUMBNAILS).contains("Legacy pack format")
        assertThat(GifPackManager.ERROR_MISSING_THUMBNAILS).contains("thumbnails")
    }
}
