package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Audit L9 (2026-10-20): `!!` on state another thread or a recomposition can change between the
 * null check and the dereference. Each site now reads the value ONCE into a local and branches on
 * that local, so the check and the use see the same value:
 *
 *  - `ClipboardHistoryService` companion: `_service` is a `@Volatile` static, and `_pasteCallback`
 *    is reassigned whenever the IME re-initialises the service.
 *  - `DictionaryDataSource.cachedWords`: a plain `var` written from the IO dispatcher.
 *  - `CommandPaletteDialog`'s `pendingX` Compose state (`by mutableStateOf`, which cannot smart-cast).
 *
 * A source scan (no Android runtime) keeps the pattern from coming back at these sites.
 */
class NullAssertionDriftTest {

    private val srcRoot = File("src/main/kotlin/tribixbite/cleverkeys")
    private fun read(rel: String) = File(srcRoot, rel).readText()

    private fun assertNoAssertion(rel: String, pattern: Regex) {
        val hits = pattern.findAll(read(rel)).map { it.value }.toList()
        assertWithMessage("$rel must read mutable state once into a local instead of `!!`: $hits")
            .that(hits).isEmpty()
    }

    @Test
    fun clipboardServiceStaticsAreReadOnce() =
        assertNoAssertion("clipboard/ClipboardHistoryService.kt", Regex("""_(?:service|pasteCallback)!!"""))

    @Test
    fun dictionaryCacheIsReadOnce() =
        assertNoAssertion("DictionaryDataSource.kt", Regex("""cachedWords!!"""))

    @Test
    fun commandPalettePendingStateIsReadOnce() =
        assertNoAssertion("customization/CommandPaletteDialog.kt", Regex("""pending\w+!!"""))
}
