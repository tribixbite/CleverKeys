package tribixbite.cleverkeys.clipboard.sanitize

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Direct unit coverage of `SanitizationConfig.build()` — the production
 * code that resolves the active sanitizer from the three Config toggles
 * + bundled assets + custom file.
 *
 * Two contracts under test:
 *   - All toggles off → returns a no-op sanitizer that passes input
 *     through unchanged AND never opens any asset.
 *   - Custom toggle on but custom file missing → falls back to the
 *     bundled clearurls ruleset (no crash, no all-disable).
 *
 * The `ClipboardHistoryService.kt:248` hook itself is verified by
 * code inspection (one-line diff in a clearly-named file). Robolectric
 * coverage of that hook is deferred — would add a heavier test
 * dependency for marginal value over the diff review.
 */
class SanitizationConfigBuildTest {

    @Before
    fun setUp() {
        // Asset-load failure path in SanitizationConfig.loadAsset calls Log.e;
        // loadCustom failure path calls Log.w. Stub both for the entire suite.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkObject(tribixbite.cleverkeys.Config.Companion)
    }

    @Test
    fun toggleAllOff_returnsNoOpSanitizer() {
        // Spec §Testing > MockK requires this regression: when all three
        // toggles are off, the unified sanitizer must be a no-op (passes
        // input through unchanged) without ever opening any asset.
        //
        // Note: Config exposes its toggles as @JvmField var, so they are
        // plain Java fields (no method dispatch) and cannot be stubbed via
        // every { }. We create a relaxed mock and set the fields directly
        // — the defaults already match the "all off" state but we set
        // explicitly for clarity.
        mockkObject(tribixbite.cleverkeys.Config.Companion)
        val cfg = mockk<tribixbite.cleverkeys.Config>(relaxed = true)
        cfg.clipboard_sanitize_links_enabled = false
        cfg.clipboard_embed_enrich_enabled = false
        cfg.clipboard_custom_rules_enabled = false
        cfg.clipboard_custom_rules_uri = null
        every { tribixbite.cleverkeys.Config.globalConfig() } returns cfg

        val ctx = mockk<android.content.Context>(relaxed = true)
        val sanitizer = SanitizationConfig(ctx).sanitizer()

        val input = "https://example.com/page?utm_source=foo"
        assertThat(sanitizer.process(input)).isEqualTo(input)
        // Crucially: no asset access happened.
        verify(exactly = 0) { ctx.assets }
    }

    @Test
    fun customRulesEnabled_fileMissing_fallsBackToBundled() {
        // Spec §Testing > MockK regression: if user enables custom rules
        // but the persisted file is gone (manual cache wipe, app reinstall,
        // SAF grant revoked), the bundled rules must still apply — never
        // crash, never disable everything.
        mockkObject(tribixbite.cleverkeys.Config.Companion)
        val cfg = mockk<tribixbite.cleverkeys.Config>(relaxed = true)
        cfg.clipboard_sanitize_links_enabled = true
        cfg.clipboard_embed_enrich_enabled = false
        cfg.clipboard_custom_rules_enabled = true
        // URI persisted but file at app-private location does not exist
        cfg.clipboard_custom_rules_uri = "content://does.not.exist/rules.json"
        every { tribixbite.cleverkeys.Config.globalConfig() } returns cfg

        val bundledJson = """{
            "providers": {
                "globalRules": {
                    "urlPattern": ".*",
                    "rules": ["utm_source"]
                }
            }
        }""".trimIndent()

        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true) {
            every { open("url_rules/clearurls.json") } returns bundledJson.byteInputStream()
        }
        val nonexistentDir = java.io.File("/data/local/tmp/nonexistent-${System.currentTimeMillis()}")
        val ctx = mockk<android.content.Context>(relaxed = true) {
            every { assets } returns assetManager
            every { filesDir } returns nonexistentDir
            every { contentResolver } returns mockk(relaxed = true) {
                // SAF resolver returns null for the bogus URI
                every { openInputStream(any()) } returns null
            }
        }

        val sanitizer = SanitizationConfig(ctx).sanitizer()
        // Bundled `utm_source` strip MUST still apply despite custom file missing.
        assertThat(sanitizer.process("https://example.com/page?utm_source=foo&keep=bar"))
            .isEqualTo("https://example.com/page?keep=bar")
    }
}
