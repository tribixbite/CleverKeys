package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardDatabase
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardHistoryLimits
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardMediaManager
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.EditEntryResult
import tribixbite.cleverkeys.TypedFakePreferences

/**
 * Pre-tag follow-up to S8 (2026-11-25): every History limit the clipboard service enforces —
 * the limit type, the entry count, the total size, the per-item size and the Entry Duration —
 * comes from the STORED settings, never from the in-memory Config.
 *
 * Only the keyboard service's preference listener refreshes `Config.globalConfig()`. A full
 * restore in a process where the keyboard is not running (a new phone, the headless backup
 * activity, Settings alone) writes the settings and leaves Config holding the old values, so the
 * next capture used to be pruned by the OLD limit type and values. Here Config always holds the
 * old values and the settings store the new ones; every case fails if the service reads Config.
 *
 * Mock tier: the service is built without its constructor (objenesis) over a relaxed database.
 */
class ClipboardStoredLimitsTest {

    private val objenesis = ObjenesisStd()
    private lateinit var database: ClipboardDatabase
    private lateinit var service: ClipboardHistoryService
    private lateinit var settings: TypedFakePreferences
    private val minute = 60_000L

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        // The stale in-memory Config: what Settings showed before the restore.
        val stale = mockk<Config>(relaxed = true)
        stale.clipboard_history_enabled = true
        stale.clipboard_limit_type = "count"
        stale.clipboard_history_limit = 50
        stale.clipboard_size_limit_mb = 5
        stale.clipboard_max_item_size_kb = 1024
        stale.clipboard_history_duration = -1
        mockkObject(Config.Companion)
        every { Config.globalConfigOrNull() } returns stale
        every { Config.globalConfig() } returns stale

        // The settings store the restore just wrote (each test fills it).
        settings = TypedFakePreferences()
        // A @JvmStatic member of an object: the static entry point is what callers reach.
        mockkStatic(DirectBootAwarePreferences::class)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns settings

        database = mockk(relaxed = true)
        every { database.addClipboardEntry(any(), any(), any(), any()) } returns true
        every { database.applySizeLimit(any()) } returns Pair(0, emptyList())
        every { database.applySizeLimitBytes(any(), any()) } returns Pair(0, emptyList())
        service = objenesis.newInstance(ClipboardHistoryService::class.java)
        service.setField("_context", mockk<Context>(relaxed = true))
        service.setField("_database", database)
        service.setField("_mediaManager\$delegate", lazyOf(mockk<ClipboardMediaManager>(relaxed = true)))
    }

    @After
    fun teardown() = unmockkAll()

    private fun merge() = service.addMergedClip(ClipboardBulkPlans.MergePlan("a\nb", 2, 0, false)).getOrThrow()

    @Test
    fun aRestoredByteLimitPrunesByTotalSize() {
        // Import types: the import table stores these as String; Settings stores the MB as Int.
        settings.values["clipboard_limit_type"] = "size"
        settings.values["clipboard_size_limit_mb"] = 1

        merge()

        verify(exactly = 1) { database.applySizeLimitBytes(1, any()) }
        verify(exactly = 0) { database.applySizeLimit(any()) }
    }

    @Test
    fun aRestoredSmallerCountPrunesToIt() {
        settings.values["clipboard_limit_type"] = "count"
        settings.values["clipboard_history_limit"] = "10"

        merge()

        verify(exactly = 1) { database.applySizeLimit(10) }
        verify(exactly = 0) { database.applySizeLimitBytes(any(), any()) }
    }

    @Test
    fun theListShowsAtMostTheStoredCount() {
        settings.values["clipboard_history_limit"] = 2
        every { database.getActiveClipboardEntries() } returns
            (1..5).map { ClipboardEntry("e$it", it.toLong(), rowId = it.toLong()) }

        assertThat(service.clearExpiredAndGetHistory().map { it.content }).containsExactly("e1", "e2").inOrder()
    }

    @Test
    fun anEditLargerThanTheStoredItemLimitIsRefused() {
        settings.values["clipboard_max_item_size_kb"] = 64
        val big = "x".repeat(100 * 1024)

        val outcome = service.editEntryFollowingClipboard("old", big, ClipboardTab.HISTORY, fillEmptyClipboard = false)

        assertThat(outcome.result).isEqualTo(EditEntryResult.InvalidContent)
        verify(exactly = 0) { database.updateHistoryEntryContent(any(), any()) }
    }

    @Test
    fun aNewClippingExpiresByTheStoredDuration() {
        settings.values[ClipboardHistoryService.PREF_HISTORY_DURATION] = "60"
        val expiry = slot<Long>()
        every { database.addClipboardEntry(any(), capture(expiry), any(), any()) } returns true
        val before = System.currentTimeMillis()

        merge()

        assertWithMessage("an hour from the merge, not never (the stale Config's -1)")
            .that(expiry.captured).isIn(com.google.common.collect.Range.closed(
                before + 60 * minute, System.currentTimeMillis() + 60 * minute))
    }

    @Test
    fun theReaderTakesEitherStoredTypeAndClampsLikeConfig() {
        assertThat(ClipboardHistoryLimits.fromSettings(TypedFakePreferences())).isEqualTo(
            ClipboardHistoryLimits(ClipboardHistoryLimits.LIMIT_TYPE_COUNT, 0, 5, 512))
        val imported = TypedFakePreferences(mapOf(
            "clipboard_limit_type" to "size",
            "clipboard_history_limit" to "900",       // above SettingsRanges' 500
            "clipboard_size_limit_mb" to 3,           // Int, as Settings writes it
            "clipboard_max_item_size_kb" to " 8 ",    // below the 64 floor, padded
        ))
        val limits = ClipboardHistoryLimits.fromSettings(imported)
        assertThat(limits).isEqualTo(ClipboardHistoryLimits("size", 500, 3, 64))
        assertThat(limits.bySize).isTrue()
    }

    /**
     * Note10+ r17 (2026-11-26): the screen's sliders, Reset All and an import all STORE these
     * three as Int, but the reader read them as String, so every read logged "String preference
     * … was stored as int". Int is their stored type; the reader reads it without a warning and
     * still accepts the String an older import left (see the case above).
     */
    @Test
    fun theReaderReadsTheIntTheScreenStoresWithoutAWarning() {
        val screen = TypedFakePreferences(mapOf(
            "clipboard_limit_type" to "size",
            "clipboard_history_limit" to 120,
            "clipboard_size_limit_mb" to 5,
            "clipboard_max_item_size_kb" to 512,
        ))
        assertThat(ClipboardHistoryLimits.fromSettings(screen)).isEqualTo(ClipboardHistoryLimits("size", 120, 5, 512))
        verify(exactly = 0) { Log.w("Config", match<String> { it.contains("was stored as") }) }
    }

    @Test
    fun configRefreshAndTheServiceShareTheReader() {
        // One parser: Config.refresh assigns its four clipboard fields from it.
        val config = java.io.File("src/main/kotlin/tribixbite/cleverkeys/Config.kt").readText()
        assertThat(config).contains("ClipboardHistoryLimits.fromSettings(_prefs)")
        val service = java.io.File("src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt").readText()
        assertWithMessage("no clipboard limit is read from the in-memory Config")
            .that(service).doesNotContainMatch(
                """globalConfig(OrNull)?\(\)\??\.clipboard_(history_limit|limit_type|size_limit_mb|max_item_size_kb|history_duration)""")
    }

    // ---------------------------------------------------------------- capture switches
    // Fable audit 2026-11-26 (0498aa112 incomplete): the History on/off switch, the two
    // exclusions and the media size cap were still read from the in-memory Config. History
    // turned OFF by a restore in a process without the keyboard kept capturing.

    /** The URL sanitizer passes text through, so `addClip` reaches the store in the mock tier. */
    private fun passThroughSanitizer() {
        val sanitization = mockk<tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig>()
        val off = tribixbite.cleverkeys.ClipboardSanitizeSettings(false, false, false, null, false)
        every { sanitization.storedSwitches() } returns off
        every { sanitization.sanitizer(off).process(any()) } answers { firstArg() }
        service.setField("_sanitizationConfig\$delegate", lazyOf(sanitization))
    }

    @Test
    fun historyTurnedOffByARestoreStopsCapture() {
        passThroughSanitizer()
        settings.values["clipboard_history_enabled"] = false

        service.addClip("a secret the user no longer wants kept")

        verify(exactly = 0) { database.addClipboardEntry(any(), any(), any(), any()) }
        assertThat(service.getClipboardStatus()).isEqualTo("Clipboard history disabled in settings")
    }

    @Test
    fun historyTurnedOnByARestoreCaptures() {
        val stale = Config.globalConfig()
        stale.clipboard_history_enabled = false
        settings.values["clipboard_history_enabled"] = true
        passThroughSanitizer()

        service.addClip("kept")

        verify(exactly = 1) { database.addClipboardEntry("kept", any(), any(), any()) }
    }

    @Test
    fun aRestoredPasswordManagerExclusionSkipsTheClipUnread() {
        // The stale Config (relaxed) has the exclusion off; the restore turned it on.
        settings.values["clipboard_exclude_password_managers"] = true
        val manager = android.app.ActivityManager.RunningTaskInfo::class.java
            .let { objenesis.newInstance(it) }
        manager.topActivity = mockk<android.content.ComponentName> {
            every { packageName } returns tribixbite.cleverkeys.Defaults.PASSWORD_MANAGER_PACKAGES.first()
        }
        val activities = mockk<android.app.ActivityManager>()
        @Suppress("DEPRECATION")
        every { activities.getRunningTasks(1) } returns listOf(manager)
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.USAGE_STATS_SERVICE) } returns null
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activities
        service.setField("_context", context)
        val cm = mockk<android.content.ClipboardManager>(relaxed = true)
        service.setField("_cm", cm)

        ClipboardHistoryService::class.java.getDeclaredMethod("captureClip", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(service, false)

        verify(exactly = 0) { cm.primaryClip }
        verify(exactly = 0) { database.addClipboardEntry(any(), any(), any(), any()) }
    }

    @Test
    fun theCaptureReaderTakesTheStoredSwitchesAndClampsTheMediaCap() {
        assertThat(tribixbite.cleverkeys.ClipboardCaptureSettings.fromSettings(TypedFakePreferences())).isEqualTo(
            tribixbite.cleverkeys.ClipboardCaptureSettings(
                historyEnabled = tribixbite.cleverkeys.Defaults.CLIPBOARD_HISTORY_ENABLED,
                excludePasswordManagers = tribixbite.cleverkeys.Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS,
                respectSensitiveFlag = tribixbite.cleverkeys.Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG,
                textOnly = tribixbite.cleverkeys.Defaults.CLIPBOARD_TEXT_ONLY,
                mediaEnabled = tribixbite.cleverkeys.Defaults.CLIPBOARD_MEDIA_ENABLED,
                maxMediaSizeMb = tribixbite.cleverkeys.Defaults.CLIPBOARD_MAX_MEDIA_SIZE_MB,
            ))
        val restored = tribixbite.cleverkeys.ClipboardCaptureSettings.fromSettings(TypedFakePreferences(mapOf(
            "clipboard_history_enabled" to false,
            "clipboard_exclude_password_managers" to true,
            "clipboard_respect_sensitive_flag" to true,
            "clipboard_text_only" to true,
            "clipboard_media_enabled" to false,
            "clipboard_max_media_size_mb" to 400,   // above SettingsRanges' 50
        )))
        assertThat(restored).isEqualTo(tribixbite.cleverkeys.ClipboardCaptureSettings(false, true, true, true, false, 50))
    }

    @Test
    fun noCaptureSwitchIsReadFromTheInMemoryConfig() {
        val config = java.io.File("src/main/kotlin/tribixbite/cleverkeys/Config.kt").readText()
        assertThat(config).contains("ClipboardCaptureSettings.fromSettings(_prefs)")
        val service = java.io.File("src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt").readText()
        assertWithMessage("no capture switch is read from the in-memory Config")
            .that(service).doesNotContainMatch(
                """globalConfig(OrNull)?\(\)\??\.clipboard_(history_enabled|exclude_password_managers|respect_sensitive_flag|text_only|media_enabled|max_media_size_mb)\b""")
        assertWithMessage("no Config snapshot held in a local either")
            .that(service).doesNotContainMatch("""val\s+cfg\s*=\s*Config\.globalConfig\(\)""")
    }

    // ---------------------------------------------------------------- link sanitizing
    // Fable review 2026-11-26: `storeClip` read `clipboard_sanitize_system_clipboard` from
    // `Config.globalConfig()` BEFORE the insert, and `SanitizationConfig` built its ruleset from
    // Config too. After a restore in a process without the keyboard both were stale, and where
    // Config was never initialised the read threw and the clip was lost.

    private val bundledRules = """{"providers":{"globalRules":{"urlPattern":".*","rules":["utm_source"]}}}"""

    /** A real [tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig] over [bundledRules]. */
    private fun realSanitizer() {
        val assets = mockk<android.content.res.AssetManager>()
        every { assets.open("url_rules/clearurls.json") } answers { bundledRules.byteInputStream() }
        val context = mockk<Context>(relaxed = true)
        every { context.assets } returns assets
        service.setField("_context", context)
        service.setField("_sanitizationConfig\$delegate",
            lazyOf(tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig(context)))
    }

    @Test
    fun aClipIsStoredWhenConfigWasNeverInitialised() {
        every { Config.globalConfig() } throws IllegalStateException("Config not initialised")
        every { Config.globalConfigOrNull() } returns null
        realSanitizer()

        service.addClip("kept although the keyboard never ran")

        verify(exactly = 1) { database.addClipboardEntry("kept although the keyboard never ran", any(), any(), any()) }
    }

    @Test
    fun aRestoredSanitizeSwitchCleansTheStoredLink() {
        // The stale Config (relaxed) has sanitizing off; the restore turned it on.
        settings.values["clipboard_sanitize_links_enabled"] = true
        settings.values["clipboard_sanitize_system_clipboard"] = false
        realSanitizer()

        service.addClip("https://example.com/p?utm_source=feed&keep=1")

        verify(exactly = 1) { database.addClipboardEntry("https://example.com/p?keep=1", any(), any(), any()) }
    }

    @Test
    fun theCachedRulesetFollowsTheStoredSwitches() {
        settings.values["clipboard_sanitize_system_clipboard"] = false
        realSanitizer()
        service.addClip("https://example.com/a?utm_source=x")
        verify(exactly = 1) { database.addClipboardEntry("https://example.com/a?utm_source=x", any(), any(), any()) }

        // Switched on with no rules-changed broadcast (an import in a process without Settings).
        settings.values["clipboard_sanitize_links_enabled"] = true
        service.addClip("https://example.com/b?utm_source=x")

        verify(exactly = 1) { database.addClipboardEntry("https://example.com/b", any(), any(), any()) }
    }

    @Test
    fun theSanitizeReaderTakesTheStoredSwitches() {
        val reader = tribixbite.cleverkeys.ClipboardSanitizeSettings
        assertThat(reader.fromSettings(TypedFakePreferences())).isEqualTo(
            tribixbite.cleverkeys.ClipboardSanitizeSettings(
                sanitizeLinks = tribixbite.cleverkeys.Defaults.CLIPBOARD_SANITIZE_LINKS_ENABLED,
                embedEnrich = tribixbite.cleverkeys.Defaults.CLIPBOARD_EMBED_ENRICH_ENABLED,
                customRules = tribixbite.cleverkeys.Defaults.CLIPBOARD_CUSTOM_RULES_ENABLED,
                customRulesUri = null,
                rewriteSystemClipboard = tribixbite.cleverkeys.Defaults.CLIPBOARD_SANITIZE_SYSTEM_CLIPBOARD,
            ))
        assertThat(reader.fromSettings(TypedFakePreferences(mapOf(
            "clipboard_sanitize_links_enabled" to true,
            "clipboard_embed_enrich_enabled" to true,
            "clipboard_custom_rules_enabled" to true,
            "clipboard_custom_rules_uri" to "content://rules",
            "clipboard_sanitize_system_clipboard" to false,
        )))).isEqualTo(tribixbite.cleverkeys.ClipboardSanitizeSettings(true, true, true, "content://rules", false))
        // An empty stored URI means none, as Config read it.
        assertThat(reader.fromSettings(TypedFakePreferences(mapOf("clipboard_custom_rules_uri" to ""))).customRulesUri).isNull()
    }

    @Test
    fun noSanitizeSwitchIsReadFromTheInMemoryConfig() {
        val config = java.io.File("src/main/kotlin/tribixbite/cleverkeys/Config.kt").readText()
        assertThat(config).contains("ClipboardSanitizeSettings.fromSettings(_prefs)")
        val pattern = """globalConfig(OrNull)?\(\)\??\.clipboard_(sanitize_links_enabled|embed_enrich_enabled|custom_rules_enabled|custom_rules_uri|sanitize_system_clipboard)\b"""
        for (path in listOf(
            "src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt",
            "src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/SanitizationConfig.kt",
        )) {
            val text = java.io.File(path).readText()
            assertWithMessage("$path reads no sanitize switch from Config").that(text).doesNotContainMatch(pattern)
            assertWithMessage("$path holds no Config snapshot").that(text)
                .doesNotContainMatch("""=\s*(try \{\s*)?Config\.globalConfig(OrNull)?\(\)""")
        }
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                it.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError("field '$name' not found on ${javaClass.name}")
    }
}
