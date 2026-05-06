package tribixbite.cleverkeys.clipboard.sanitize

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmbedEnrichRulesTest {

    private val ruleset: Ruleset by lazy {
        val stream = javaClass.getResourceAsStream("/url_rules/embed_enrich.json")
            ?: error("embed_enrich.json missing from test classpath")
        RulesetParser.fromJson(stream.bufferedReader().use { it.readText() })
    }

    private val sanitizer by lazy { RulesetUrlSanitizer(ruleset) }

    @Test
    fun twitterUrl_rewritesToFxtwitter() {
        assertThat(sanitizer.process("https://x.com/foo/status/123"))
            .isEqualTo("https://fxtwitter.com/foo/status/123")
    }

    @Test
    fun twitterUrlWithWww_rewritesToFxtwitter() {
        assertThat(sanitizer.process("https://www.twitter.com/foo/status/123"))
            .isEqualTo("https://fxtwitter.com/foo/status/123")
    }

    @Test
    fun redditUrl_rewritesToRxddit() {
        assertThat(sanitizer.process("https://www.reddit.com/r/AskHistorians/comments/abc"))
            .isEqualTo("https://rxddit.com/r/AskHistorians/comments/abc")
    }

    @Test
    fun oldReddit_alsoRewrites() {
        assertThat(sanitizer.process("https://old.reddit.com/r/foo"))
            .isEqualTo("https://rxddit.com/r/foo")
    }

    @Test
    fun instagramUrl_rewritesToDdinstagram() {
        assertThat(sanitizer.process("https://www.instagram.com/p/abc"))
            .isEqualTo("https://ddinstagram.com/p/abc")
    }

    @Test
    fun tiktokUrl_rewritesToVxtiktok() {
        assertThat(sanitizer.process("https://www.tiktok.com/@user/video/123"))
            .isEqualTo("https://vxtiktok.com/@user/video/123")
    }

    @Test
    fun bskyUrl_rewritesToFxbsky() {
        assertThat(sanitizer.process("https://bsky.app/profile/foo.bsky.social/post/abc"))
            .isEqualTo("https://fxbsky.app/profile/foo.bsky.social/post/abc")
    }

    @Test
    fun pixivUrl_rewritesToPhixiv() {
        assertThat(sanitizer.process("https://www.pixiv.net/en/artworks/123"))
            .isEqualTo("https://phixiv.net/en/artworks/123")
    }

    @Test
    fun furaffinityUrl_rewritesToFxfuraffinity() {
        assertThat(sanitizer.process("https://www.furaffinity.net/view/12345/"))
            .isEqualTo("https://fxfuraffinity.net/view/12345/")
    }

    @Test
    fun deviantartUrl_rewritesToFxdeviantart() {
        assertThat(sanitizer.process("https://www.deviantart.com/artist/art/title-123"))
            .isEqualTo("https://fxdeviantart.com/artist/art/title-123")
    }

    @Test
    fun youtubeUrl_NOT_rewritten() {
        // YouTube intentionally omitted from the embed-fix list — already embeds well.
        val input = "https://www.youtube.com/watch?v=abc"
        assertThat(sanitizer.process(input)).isEqualTo(input)
    }

    @Test
    fun nonEmbedDomainNotInList_leftAlone() {
        val input = "https://example.com/path"
        assertThat(sanitizer.process(input)).isEqualTo(input)
    }

    @Test
    fun idempotent_alreadyRewrittenStaysSame() {
        val once = sanitizer.process("https://x.com/foo")
        val twice = sanitizer.process(once)
        assertThat(twice).isEqualTo(once)
    }
}
