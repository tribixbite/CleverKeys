package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-contract RED tests for Bucket B feature requests.
 *
 * Why source-scanning: the project does not currently include
 * `androidx.compose.ui:ui-test-junit4`, so declarative Compose UI tests
 * aren't available. Until that's added (separate refactor), these tests
 * pin the impl contract by scanning source for required patterns.
 *
 * Each test fails until the corresponding feature ships. When a fix
 * is implemented, the source-pattern assertion turns green automatically.
 *
 * NOTE: source-pattern tests are brittle. Once Compose UI test infra is
 * added, prefer rewriting these as proper `composeTestRule` assertions
 * against the rendered UI.
 *
 * Issues covered:
 *   #94 — long-press on Settings VersionInfoCard copies version to clipboard
 *   #93 — ThemeSettingsActivity hex color input field
 *   #134 — show-keyboard button on ShortSwipeCustomizationActivity
 *   #130 — clipboard panel uses theme-derived colors not hardcoded purple
 *   #71 — clipboard load uses Dispatchers.IO (off-UI-thread)
 */
class BucketBSourceContractTest {

    private val srcDir = File("src/main/kotlin/tribixbite/cleverkeys")

    private fun readSource(filename: String): String {
        val file = File(srcDir, filename)
        require(file.exists()) { "Source file not found: ${file.absolutePath}" }
        return file.readText()
    }

    // =========================================================================
    // #94 — Long-press on Settings version row copies version info to clipboard
    // Reporter request: bug-reporting workflow needs easy version copy.
    // Expected impl: combinedClickable { onLongClick = { ... copy ... } } on
    // the VersionInfoCard composable.
    // =========================================================================

    @Test
    fun `issue 94 — VersionInfoCard supports long-press copy`() {
        val source = readSource("SettingsActivity.kt")
        // Locate VersionInfoCard definition and check for long-click support.
        val cardStart = source.indexOf("private fun VersionInfoCard")
        assertThat(cardStart).isAtLeast(0)
        // Find next ~80 lines of the function body
        val cardBody = source.substring(cardStart, minOf(cardStart + 4000, source.length))

        // Expected pattern: combinedClickable with onLongClick handler
        val hasLongClickHandler = cardBody.contains("combinedClickable") ||
            cardBody.contains("onLongClick") ||
            cardBody.contains("detectTapGestures")
        assertThat(hasLongClickHandler).isTrue()  // RED until #94 fix lands
    }

    @Test
    fun `issue 94 — copy-to-clipboard side effect referenced near VersionInfoCard`() {
        val source = readSource("SettingsActivity.kt")
        val cardStart = source.indexOf("private fun VersionInfoCard")
        assertThat(cardStart).isAtLeast(0)
        val cardBody = source.substring(cardStart, minOf(cardStart + 4000, source.length))
        // Expected: ClipboardManager / setPrimaryClip / setText invocation
        val hasClipboardWrite = cardBody.contains("setPrimaryClip") ||
            cardBody.contains("ClipData.newPlainText") ||
            cardBody.contains("clipboardManager.setText") ||
            cardBody.contains("CLIPBOARD_SERVICE")
        assertThat(hasClipboardWrite).isTrue()  // RED until #94 fix lands
    }

    // =========================================================================
    // #93 — Custom Themes hex color input field
    // Reporter request: HSV/RGB sliders are imprecise; want a #RRGGBB input.
    // Expected: ThemeSettingsActivity has an OutlinedTextField/TextField for
    // hex input with parse-on-change behavior.
    // =========================================================================

    @Test
    fun `issue 93 — ThemeSettingsActivity has hex color input field`() {
        val source = readSource("ThemeSettingsActivity.kt")
        // Expected: an OutlinedTextField/TextField with "hex" semantics
        val hasHexInput = source.contains("OutlinedTextField", ignoreCase = false) ||
            (source.contains("TextField", ignoreCase = false) && source.lowercase().contains("hex"))
        assertThat(hasHexInput).isTrue()  // RED until #93 fix lands
    }

    @Test
    fun `issue 93 — hex parse logic exists for color input`() {
        val source = readSource("ThemeSettingsActivity.kt")
        // Expected: a regex/match that accepts "#RRGGBB" or similar
        val hasHexParse = source.contains("Regex(\"#") ||
            source.contains("\"\"\"#") ||
            source.contains("toLong(16)") ||
            source.contains("Color(\"#")
        assertThat(hasHexParse).isTrue()  // RED until #93 fix lands
    }

    // =========================================================================
    // #134 — Show-keyboard button on ShortSwipeCustomizationActivity
    // Reporter request: when keyboard loses focus during customization, no way
    // to reopen without leaving the screen. Wants a static button.
    // Expected: button with content description / text matching "show keyboard"
    // (or icon button with that semantic).
    // =========================================================================

    @Test
    fun `issue 134 — ShortSwipeCustomizationActivity has a show-keyboard button`() {
        val source = readSource("ShortSwipeCustomizationActivity.kt")
        val lower = source.lowercase()
        // Expected: text or content description hint indicating "show keyboard"
        val hasShowKeyboard = lower.contains("show keyboard") ||
            lower.contains("showsoftinput") ||
            lower.contains("showsoftinput(")
        assertThat(hasShowKeyboard).isTrue()  // RED until #134 fix lands
    }

    @Test
    fun `issue 134 — InputMethodManager showSoftInput referenced for re-open`() {
        val source = readSource("ShortSwipeCustomizationActivity.kt")
        // Expected: imm.showSoftInput(targetView, ...) call to bring the IME back
        val hasShowSoftInput = source.contains("showSoftInput")
        assertThat(hasShowSoftInput).isTrue()  // RED until #134 fix lands
    }

    // =========================================================================
    // #130 — Clipboard panel uses theme-derived colors, not hardcoded purple
    // Reporter screenshot: clipboard renders in default Material purple
    // regardless of active custom theme.
    // Expected: clipboard rendering reads theme attrs (colorPrimary/etc.)
    // instead of hardcoded values.
    //
    // We pin this by checking that ClipboardHistoryView uses theme attribute
    // resolution (e.g., obtainStyledAttributes / theme.resolveAttribute) for
    // its colors and does NOT contain hardcoded purple hex values.
    // =========================================================================

    @Test
    fun `issue 130 — ClipboardHistoryView resolves colors from theme attrs`() {
        val source = readSource("ClipboardHistoryView.kt")
        val resolvesTheme = source.contains("resolveAttribute") ||
            source.contains("obtainStyledAttributes") ||
            source.contains("ContextThemeWrapper") ||
            source.contains("R.attr.color")
        assertThat(resolvesTheme).isTrue()
    }

    @Test
    fun `issue 130 — ClipboardHistoryView contains no hardcoded Material purple`() {
        val source = readSource("ClipboardHistoryView.kt")
        // Material default purple variants: 6200EE, BB86FC, 3700B3
        val hasHardcodedPurple = listOf("6200EE", "BB86FC", "3700B3", "#6200ee", "#bb86fc")
            .any { source.contains(it, ignoreCase = true) }
        assertThat(hasHardcodedPurple).isFalse()
    }

    // =========================================================================
    // #71 — Clipboard load runs off the UI thread (Dispatchers.IO)
    // Already shipped via commit e159dca56. Pin the contract: ClipboardHistoryView
    // must use Dispatchers.IO (or equivalent off-thread mechanism) for DB reads.
    // =========================================================================

    @Test
    fun `issue 71 — ClipboardHistoryView dispatches DB load to Dispatchers IO`() {
        val source = readSource("ClipboardHistoryView.kt")
        val isAsync = source.contains("Dispatchers.IO") ||
            source.contains("withContext(Dispatchers.IO)") ||
            source.contains("launch(Dispatchers.IO)")
        assertThat(isAsync).isTrue()
    }

    @Test
    fun `issue 71 — loadDataAsync function exists`() {
        val source = readSource("ClipboardHistoryView.kt")
        assertThat(source).contains("fun loadDataAsync")
    }

    @Test
    fun `issue 71 — async load cancels previous job to avoid stale data`() {
        val source = readSource("ClipboardHistoryView.kt")
        // Pattern: loadJob?.cancel() before starting a new load
        val hasCancellation = source.contains("loadJob?.cancel()") ||
            source.contains("loadJob = ") && source.contains(".cancel()")
        assertThat(hasCancellation).isTrue()
    }
}
