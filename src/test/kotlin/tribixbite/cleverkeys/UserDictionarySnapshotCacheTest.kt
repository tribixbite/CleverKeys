package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.LexiconContentVersion
import tribixbite.cleverkeys.swipe.UserDictionarySnapshot
import java.io.File

/**
 * ARC-102 — the epoch-gated cache in front of the platform user-dictionary read.
 *
 * `UserDictionaryWords.snapshot` is called once per lexicon build, i.e. once per decode and twice
 * under `enable_multilang`, and every call was a binder round trip to the
 * `android.provider.UserDictionary` provider. ARC-081 chose correctness over caching precisely
 * because a stale cache reintroduces the bug it fixed — a word added OUTSIDE the app never
 * becoming swipeable — so the only acceptable cache is one that cannot go stale silently.
 *
 * The design recorded in that `TODO(perf)`, and implemented here:
 *
 *  - a process-wide EPOCH, bumped by every `UserDictionaryObserver` callback (the observer is
 *    registered on the whole `UserDictionary.Words` URI *with descendants*, so it sees every
 *    provider change regardless of locale);
 *  - an entry per language, served only while its epoch is still the current one;
 *  - caching **disabled entirely** while no observer is running — otherwise a device where the
 *    tap predictor never initialised would serve a snapshot frozen at process start, which is
 *    exactly ARC-081 again.
 *
 * The three behaviours below are the whole contract; the source scans pin that the real
 * production seam is wired to it rather than to a second, hand-rolled cache.
 */
class UserDictionarySnapshotCacheTest {

    /** Counts provider reads and lets a test change what the "provider" holds. */
    private class FakeProvider {
        var reads = 0
            private set
        var rows: MutableMap<String, List<Pair<String, Int>>> = mutableMapOf(
            "en" to listOf("kubernetes" to 40),
            "fr" to listOf("bricolage" to 40),
        )

        fun read(language: String): UserDictionarySnapshot {
            reads++
            return UserDictionarySnapshot.of(rows[language] ?: emptyList())
        }
    }

    private fun source(relative: String): String {
        val f = File("src/main/kotlin", relative)
        assertWithMessage("expected source file to exist: ${f.path} (run from project root)")
            .that(f.isFile).isTrue()
        return f.readText()
    }

    // ── 1. a live observer means one read per epoch, per language ───────────────────

    @Test
    fun `two builds with no change hit the provider once`() {
        val provider = FakeProvider()
        val cache = UserDictionarySnapshotCache()
        cache.observerStarted()

        val first = cache.snapshot("en", provider::read)
        val second = cache.snapshot("en", provider::read)

        assertWithMessage(
            "ARC-102: before the cache every lexicon build queried the provider, so this read " +
                "twice — once per decode, twice per decode under enable_multilang."
        ).that(provider.reads).isEqualTo(1)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `each language is cached separately`() {
        val provider = FakeProvider()
        val cache = UserDictionarySnapshotCache()
        cache.observerStarted()

        cache.snapshot("en", provider::read)
        cache.snapshot("fr", provider::read)
        assertWithMessage("the two locales are different provider queries")
            .that(provider.reads).isEqualTo(2)

        assertThat(cache.snapshot("en", provider::read).entries)
            .containsExactly("kubernetes" to 40)
        assertThat(cache.snapshot("fr", provider::read).entries)
            .containsExactly("bricolage" to 40)
        assertWithMessage("and neither evicts the other")
            .that(provider.reads).isEqualTo(2)
    }

    // ── 2. an observer signal invalidates ───────────────────────────────────────────

    @Test
    fun `an observed provider change makes the next build re-query`() {
        val provider = FakeProvider()
        val cache = UserDictionarySnapshotCache()
        cache.observerStarted()

        assertThat(cache.snapshot("en", provider::read).entries)
            .containsExactly("kubernetes" to 40)

        // A word added through Settings -> Languages -> Personal dictionary: the provider
        // changes, and the ContentObserver is the only thing in the process that knows.
        provider.rows["en"] = listOf("kubernetes" to 40, "istio" to 40)
        cache.providerChanged()

        assertWithMessage(
            "ARC-081 is the reason this cache is epoch-gated: a word added outside the app must " +
                "become swipeable, so an observer callback has to drop what is held."
        ).that(cache.snapshot("en", provider::read).entries)
            .containsExactly("kubernetes" to 40, "istio" to 40)
        assertThat(provider.reads).isEqualTo(2)

        assertWithMessage("and the fresh value is then cached in its turn")
            .that(cache.snapshot("en", provider::read).entries).hasSize(2)
        assertThat(provider.reads).isEqualTo(2)
    }

    @Test
    fun `one change invalidates every language, not just the one that changed`() {
        val provider = FakeProvider()
        val cache = UserDictionarySnapshotCache()
        cache.observerStarted()
        cache.snapshot("en", provider::read)
        cache.snapshot("fr", provider::read)

        // The observer callback carries no locale: it is registered on the whole Words URI with
        // descendants, so which language changed is not knowable from the signal.
        cache.providerChanged()

        cache.snapshot("en", provider::read)
        cache.snapshot("fr", provider::read)
        assertThat(provider.reads).isEqualTo(4)
    }

    // ── 3. no observer, no cache ────────────────────────────────────────────────────

    @Test
    fun `with no observer running every build queries the provider`() {
        val provider = FakeProvider()
        val cache = UserDictionarySnapshotCache()

        assertWithMessage("caching must be OFF until an observer is registered")
            .that(cache.isCaching).isFalse()

        repeat(3) { cache.snapshot("en", provider::read) }

        assertWithMessage(
            "without the observer nothing can invalidate, so a cached snapshot would be frozen " +
                "for the life of the process — the ARC-081 bug with extra steps."
        ).that(provider.reads).isEqualTo(3)
    }

    @Test
    fun `stopping the observer both disables and drops the cache`() {
        val provider = FakeProvider()
        val cache = UserDictionarySnapshotCache()
        cache.observerStarted()
        cache.snapshot("en", provider::read)
        assertThat(provider.reads).isEqualTo(1)

        cache.observerStopped()
        assertThat(cache.isCaching).isFalse()
        cache.snapshot("en", provider::read)
        assertThat(provider.reads).isEqualTo(2)

        // Whatever happened while the observer was down was not observed, so a restart may not
        // resurrect anything measured before it.
        provider.rows["en"] = listOf("istio" to 40)
        cache.observerStarted()
        assertThat(cache.snapshot("en", provider::read).entries)
            .containsExactly("istio" to 40)
        assertThat(provider.reads).isEqualTo(3)
    }

    @Test
    fun `caching stays off until the last observer stops`() {
        val cache = UserDictionarySnapshotCache()
        cache.observerStarted()
        cache.observerStarted()
        cache.observerStopped()
        assertWithMessage(
            "two predictors can observe at once (a second IME session, the settings process); " +
                "one stopping must not silently disable invalidation for the other."
        ).that(cache.isCaching).isTrue()
        cache.observerStopped()
        assertThat(cache.isCaching).isFalse()

        // Unbalanced stops must not drive the count negative and latch caching back on.
        cache.observerStopped()
        assertThat(cache.isCaching).isFalse()
    }

    // ── ARC-081's fingerprint semantics survive the cache ───────────────────────────

    @Test
    fun `a changed snapshot still changes the lexicon memo version`() {
        val provider = FakeProvider()
        val cache = UserDictionarySnapshotCache()
        cache.observerStarted()

        fun version() = LexiconContentVersion.of(
            "asset:dictionaries/en_enhanced.bin", "{}", emptySet(),
            cache.snapshot("en", provider::read).fingerprint,
        )

        val before = version()
        assertWithMessage("a cached read must not churn the memo key")
            .that(version()).isEqualTo(before)

        provider.rows["en"] = listOf("kubernetes" to 40, "istio" to 40)
        cache.providerChanged()

        assertWithMessage(
            "ARC-081: the snapshot is an INPUT to the lexicon memo key, so a provider change " +
                "must still move that key — otherwise the memoized trie survives the edit and " +
                "the new word stays unswipeable even though the cache was invalidated."
        ).that(version()).isNotEqualTo(before)
    }

    // ── wiring: the production seam uses this cache, and feeds its epoch ────────────

    @Test
    fun `the production snapshot goes through the cache and the TODO is gone`() {
        val words = source("tribixbite/cleverkeys/UserDictionaryWords.kt")

        assertWithMessage("ARC-102: snapshot() must serve from the epoch-gated cache")
            .that(words).contains("UserDictionarySnapshotCache")
        assertWithMessage(
            "the TODO(perf) that recorded this design is now implemented; leaving it would " +
                "invite a second, unsafe cache next to this one."
        ).that(words).doesNotContain("TODO(perf)")
    }

    @Test
    fun `the observer drives the epoch on change, start and stop`() {
        val observer = source("tribixbite/cleverkeys/UserDictionaryObserver.kt")

        val onChange = observer.substringAfter("override fun onChange(")
            .substringBefore("private fun loadUserDictionaryCache")
        assertWithMessage(
            "ARC-102: the ContentObserver callback is the ONLY signal that the provider moved, " +
                "so it must bump the epoch — without it the cache is frozen and ARC-081 is back."
        ).that(onChange).contains("UserDictionaryWords.onProviderChanged()")

        assertWithMessage(
            "start()/stop() must gate caching: a snapshot may only be reused while something " +
                "is listening for the change that would invalidate it."
        ).that(observer).contains("UserDictionaryWords.onObserverStarted()")
        assertThat(observer).contains("UserDictionaryWords.onObserverStopped()")
    }
}
