package tribixbite.cleverkeys.gif

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.gif.GifInsertPolicy.Action

/**
 * gh #149 — the pure decision matrix behind a GIF-panel tap.
 *
 * The primary delivery is the locally-shipped media via commitContent (works for every
 * pack, offline, no INTERNET permission); the Giphy URL is only a text fallback and only
 * exists when the pack carries a case-preserved marked ID. The old behavior — always
 * committing a URL rebuilt from lowercased keywords — inserted dead 404 links.
 */
class GifInsertPolicyTest {

    private val api = GifInsertPolicy.MIN_COMMIT_CONTENT_API

    // ── decide: primary media commit ─────────────────────────────────────────────

    @Test
    fun `media commit wins when editor accepts webp on api 25 plus`() {
        assertThat(
            GifInsertPolicy.decide(api, icAvailable = true, hasLocalMedia = true, editorAcceptsWebp = true, hasUrl = true)
        ).isEqualTo(Action.COMMIT_MEDIA)
        // Also without any URL (legacy pack) — media still inserts.
        assertThat(
            GifInsertPolicy.decide(api, icAvailable = true, hasLocalMedia = true, editorAcceptsWebp = true, hasUrl = false)
        ).isEqualTo(Action.COMMIT_MEDIA)
    }

    @Test
    fun `below api 25 never commits content`() {
        assertThat(
            GifInsertPolicy.decide(api - 1, icAvailable = true, hasLocalMedia = true, editorAcceptsWebp = true, hasUrl = true)
        ).isEqualTo(Action.COMMIT_URL_TEXT)
    }

    // ── decide: URL text fallback ────────────────────────────────────────────────

    @Test
    fun `editor without content support falls back to url text`() {
        assertThat(
            GifInsertPolicy.decide(api, icAvailable = true, hasLocalMedia = true, editorAcceptsWebp = false, hasUrl = true)
        ).isEqualTo(Action.COMMIT_URL_TEXT)
    }

    @Test
    fun `no local media with a valid url commits the url`() {
        assertThat(
            GifInsertPolicy.decide(api, icAvailable = true, hasLocalMedia = false, editorAcceptsWebp = true, hasUrl = true)
        ).isEqualTo(Action.COMMIT_URL_TEXT)
    }

    // ── decide: legacy pack (no url) in a content-less editor ────────────────────

    @Test
    fun `legacy pack in a contentless editor copies the media instead of a dead link`() {
        assertThat(
            GifInsertPolicy.decide(api, icAvailable = true, hasLocalMedia = true, editorAcceptsWebp = false, hasUrl = false)
        ).isEqualTo(Action.COPY_MEDIA_TO_CLIPBOARD)
    }

    // ── decide: no InputConnection ───────────────────────────────────────────────

    @Test
    fun `without an input connection media is copied to the clipboard`() {
        assertThat(
            GifInsertPolicy.decide(api, icAvailable = false, hasLocalMedia = true, editorAcceptsWebp = true, hasUrl = true)
        ).isEqualTo(Action.COPY_MEDIA_TO_CLIPBOARD)
    }

    @Test
    fun `without an input connection or media the url is copied`() {
        assertThat(
            GifInsertPolicy.decide(api, icAvailable = false, hasLocalMedia = false, editorAcceptsWebp = false, hasUrl = true)
        ).isEqualTo(Action.COPY_URL_TO_CLIPBOARD)
    }

    @Test
    fun `nothing usable yields none`() {
        assertThat(
            GifInsertPolicy.decide(api, icAvailable = true, hasLocalMedia = false, editorAcceptsWebp = true, hasUrl = false)
        ).isEqualTo(Action.NONE)
        assertThat(
            GifInsertPolicy.decide(api, icAvailable = false, hasLocalMedia = false, editorAcceptsWebp = false, hasUrl = false)
        ).isEqualTo(Action.NONE)
    }

    // ── afterFailedMediaCommit ───────────────────────────────────────────────────

    @Test
    fun `failed media commit falls to url text when available`() {
        assertThat(GifInsertPolicy.afterFailedMediaCommit(icAvailable = true, hasUrl = true))
            .isEqualTo(Action.COMMIT_URL_TEXT)
    }

    @Test
    fun `failed media commit without url copies the media`() {
        assertThat(GifInsertPolicy.afterFailedMediaCommit(icAvailable = true, hasUrl = false))
            .isEqualTo(Action.COPY_MEDIA_TO_CLIPBOARD)
        assertThat(GifInsertPolicy.afterFailedMediaCommit(icAvailable = false, hasUrl = true))
            .isEqualTo(Action.COPY_MEDIA_TO_CLIPBOARD)
    }

    // ── editorAcceptsMime ────────────────────────────────────────────────────────

    @Test
    fun `mime matching covers exact wildcard and star`() {
        val mime = GifInsertPolicy.MIME_WEBP
        assertThat(GifInsertPolicy.editorAcceptsMime(arrayOf("image/webp"), mime)).isTrue()
        assertThat(GifInsertPolicy.editorAcceptsMime(arrayOf("image/*"), mime)).isTrue()
        assertThat(GifInsertPolicy.editorAcceptsMime(arrayOf("*/*"), mime)).isTrue()
        assertThat(GifInsertPolicy.editorAcceptsMime(arrayOf("*"), mime)).isTrue()
        assertThat(GifInsertPolicy.editorAcceptsMime(arrayOf("image/png", "image/gif"), mime)).isFalse()
        assertThat(GifInsertPolicy.editorAcceptsMime(arrayOf("video/*"), mime)).isFalse()
        assertThat(GifInsertPolicy.editorAcceptsMime(emptyArray(), mime)).isFalse()
        assertThat(GifInsertPolicy.editorAcceptsMime(null, mime)).isFalse()
    }

    @Test
    fun `mime matching is case-insensitive and trims whitespace`() {
        assertThat(GifInsertPolicy.editorAcceptsMime(arrayOf("Image/WebP"), "image/webp")).isTrue()
        assertThat(GifInsertPolicy.editorAcceptsMime(arrayOf(" image/webp "), "image/webp")).isTrue()
    }
}
