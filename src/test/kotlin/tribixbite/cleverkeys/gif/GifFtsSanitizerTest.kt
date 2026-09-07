package tribixbite.cleverkeys.gif

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Audit E-7 (2026-09-06) — `sanitizeFtsQuery` must not let FTS4 syntax through.
 *
 * The old sanitizer stripped only `" ' * -`. A colon survived and compiled to FTS4
 * column-filter syntax: query `re: hello` → MATCH `re:* hello*` → SQLite "no such
 * column: re" → the executeFtsSearch catch swallowed it → zero results even though
 * "hello" was indexed. Separately, the #149 `gid:<ID>` marker means FTS4 indexes the
 * constant token `gid` on every new-pack row — prefix-starring a bare `gid` query token
 * matched the entire pack.
 *
 * RED (2026-09-06, pre-fix): sanitizeFtsQuery("re: hello") returned "re:* hello*"
 * (colon alive); sanitizeFtsQuery("gid") returned "gid*" (whole-pack match).
 */
class GifFtsSanitizerTest {

    @Test
    fun colonIsNeutralizedBeforeStarring() {
        assertWithMessage("a colon compiles to an FTS4 column filter and errors the MATCH")
            .that(GifDatabase.sanitizeFtsQuery("re: hello"))
            .isEqualTo("re* hello*")
    }

    @Test
    fun ftsSyntaxCharactersAreAllNeutralized() {
        // NEAR/parens/caret/quote/star/minus/colon — everything outside [a-z0-9 ] goes.
        assertThat(GifDatabase.sanitizeFtsQuery("""cat AND (dog:^"fish" NEAR/2 -bird*)"""))
            .isEqualTo("cat* and* dog* fish* near* 2* bird*")
    }

    @Test
    fun queriesAreLowercasedToMatchTheIndexNormalization() {
        assertThat(GifDatabase.sanitizeFtsQuery("Eye Roll")).isEqualTo("eye* roll*")
    }

    @Test
    fun bareGidTokenIsDropped() {
        assertWithMessage("the #149 marker indexes a constant `gid` token on every new-pack row")
            .that(GifDatabase.sanitizeFtsQuery("gid")).isEmpty()
        assertThat(GifDatabase.sanitizeFtsQuery("cat gid")).isEqualTo("cat*")
    }

    @Test
    fun plainQueriesStillStarPerToken() {
        assertThat(GifDatabase.sanitizeFtsQuery("cute cat")).isEqualTo("cute* cat*")
        assertThat(GifDatabase.sanitizeFtsQuery("")).isEmpty()
    }
}
