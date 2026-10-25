package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ClipboardMediaManager
import java.io.OutputStream

/**
 * Pixel checklist run 2026-10-25: copying an image of 80 px or less (a 64×64 image in Chrome)
 * logged "Thumbnail generation failed … Can't compress a recycled bitmap" and stored the media
 * row without a thumbnail. `scaleBitmap` returns its input when the image already fits the
 * 80×80 thumbnail box, and `generateThumbnail` recycled the decoded bitmap before compressing
 * the "scaled" one — the same object.
 *
 * The bitmap lifecycle is driven through [ClipboardMediaManager.thumbnailFromBitmap] (decode
 * needs BitmapFactory.Options, which the android.jar stubs cannot construct). The Bitmap mocks
 * behave like the platform: compress() on a recycled bitmap throws IllegalStateException.
 */
class ClipboardMediaThumbnailTest {

    private lateinit var manager: ClipboardMediaManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        manager = ClipboardMediaManager(mockk<Context>(relaxed = true))
    }

    @After
    fun teardown() = unmockkAll()

    /** A width×height bitmap whose compress() writes [bytes] bytes and fails once recycled. */
    private fun bitmap(width: Int, height: Int, bytes: Int = 64): Bitmap {
        var recycled = false
        val bmp = mockk<Bitmap>(relaxed = true)
        every { bmp.width } returns width
        every { bmp.height } returns height
        every { bmp.isRecycled } answers { recycled }
        every { bmp.recycle() } answers { recycled = true }
        every { bmp.compress(any(), any(), any()) } answers {
            check(!recycled) { "Can't compress a recycled bitmap" }
            thirdArg<OutputStream>().write(ByteArray(bytes))
            true
        }
        return bmp
    }

    @Test
    fun anImageThatAlreadyFitsTheThumbnailIsCompressedBeforeItIsRecycled() {
        val small = bitmap(64, 64)

        val blob = manager.thumbnailFromBitmap(small, maxDim = 80)

        assertWithMessage("a 64×64 image gets a thumbnail").that(blob).isNotNull()
        assertWithMessage("thumbnail bytes").that(blob!!.size).isEqualTo(64)
        verify(exactly = 1) { small.recycle() }
    }

    @Test
    fun aLargerImageRecyclesBothTheSourceAndTheScaledCopy() {
        val large = bitmap(400, 200)
        val scaled = bitmap(80, 40, bytes = 32)
        mockkStatic(Bitmap::class)
        every { Bitmap.createScaledBitmap(large, 80, 40, true) } returns scaled

        val blob = manager.thumbnailFromBitmap(large, maxDim = 80)

        assertWithMessage("thumbnail bytes come from the scaled copy").that(blob!!.size).isEqualTo(32)
        verify(exactly = 1) { large.recycle() }
        verify(exactly = 1) { scaled.recycle() }
        verify(exactly = 0) { large.compress(any(), any(), any()) }
    }
}
