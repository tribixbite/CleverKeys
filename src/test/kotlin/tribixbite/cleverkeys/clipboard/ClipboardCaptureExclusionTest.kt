package tribixbite.cleverkeys.clipboard

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults

/**
 * The two "don't capture this" clipboard promises, through the real
 * `ClipboardHistoryService.addCurrentClip()`.
 *
 * Release-record rows (both PRESENT-UNTESTED before this file):
 *
 * | version | note | anchor |
 * |---|---|---|
 * | v1.2.6 | "Clipboard skips password-manager copies (#62)" | `#clipboard_exclude_password_managers` |
 * | v1.2.8 | "Password manager exclusion (#62, #86)" | same |
 * | v1.2.8 | "Respect the IS_SENSITIVE clip flag (#86)" | `#isSensitive` |
 *
 * Both are privacy claims of the same shape — a copy the user made in a credential app must
 * never reach CleverKeys' clipboard history — so both are asserted the same way: the ONE
 * store call `addCurrentClip` can make (`addClip`) must not happen, and a positive control
 * proves the path is otherwise live so the negative is not vacuous.
 *
 * ## Harness
 *
 * `PrivateCopyServiceTest` documents why a MockK test cannot CONSTRUCT this service: an
 * anonymous `BroadcastReceiver` field initialiser reaches an android.jar stub super-constructor
 * MockK cannot intercept. Objenesis skips every constructor, and the instance is then wrapped
 * in a `spyk` so the internal `addClip` call is observable while `addCurrentClip`'s own body
 * runs for real. `Build.VERSION.SDK_INT` (0 under the stubs) is forced with the same Unsafe
 * helper that test uses — without it the API-33 arm of the IS_SENSITIVE check is unreachable.
 */
class ClipboardCaptureExclusionTest {

    /** The platform key password managers set on a sensitive clip (`ClipDescription.EXTRA_IS_SENSITIVE`). */
    private val isSensitiveKey = "android.content.extra.IS_SENSITIVE"

    private val objenesis = ObjenesisStd()

    private lateinit var context: Context
    private lateinit var clipboard: ClipboardManager
    private lateinit var usageStats: UsageStatsManager
    private lateinit var config: Config

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        setSdkInt(34)

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.clipboard_history_enabled = true
        config.clipboard_exclude_password_managers = true
        config.clipboard_respect_sensitive_flag = true
        every { Config.globalConfig() } returns config

        usageStats = mockk()
        clipboard = mockk()
        context = mockk()
        every { context.getSystemService(Context.USAGE_STATS_SERVICE) } returns usageStats
        foregroundApp("com.example.notes")
    }

    @After
    fun teardown() {
        setSdkInt(0)
        unmockkAll()
    }

    // ------------------------------------------------------------------ fixtures

    /** Make [getForegroundAppPackage] resolve to [pkg] through the UsageStats path. */
    private fun foregroundApp(pkg: String?) {
        if (pkg == null) {
            every { usageStats.queryUsageStats(any(), any(), any()) } returns emptyList()
            return
        }
        val row = mockk<UsageStats>()
        every { row.packageName } returns pkg
        every { row.lastTimeUsed } returns System.currentTimeMillis()
        every { usageStats.queryUsageStats(any(), any(), any()) } returns listOf(row)
    }

    /** A plain-text clip, optionally carrying the platform IS_SENSITIVE extra. */
    private fun primaryClip(text: String, sensitive: Boolean? = null) {
        val description = mockk<ClipDescription>()
        every { description.extras } returns when (sensitive) {
            null -> null
            else -> mockk<PersistableBundle>().also {
                every { it.getBoolean(isSensitiveKey, false) } returns sensitive
            }
        }
        val item = mockk<ClipData.Item>()
        every { item.text } returns text
        every { item.uri } returns null

        val clip = mockk<ClipData>()
        every { clip.description } returns description
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        every { clipboard.primaryClip } returns clip
    }

    /** A spied, field-seeded service whose `addClip` is observable and inert. */
    private fun service(): ClipboardHistoryService {
        val real = objenesis.newInstance(ClipboardHistoryService::class.java)
        val spy = spyk(real)
        spy.setField("_context", context)
        spy.setField("_cm", clipboard)
        every { spy.addClip(any()) } just Runs
        return spy
    }

    private fun ClipboardHistoryService.captureCurrentClip() =
        ClipboardHistoryService::class.java.getDeclaredMethod("addCurrentClip")
            .apply { isAccessible = true }
            .invoke(this)

    // ------------------------------------------- #62: password-manager package exclusion

    @Test
    fun aCopyMadeInAPasswordManagerIsNotStored() {
        foregroundApp("com.x8bit.bitwarden")
        primaryClip("correct-horse-battery-staple")

        val svc = service()
        svc.captureCurrentClip()

        // A Bitwarden copy must never enter clipboard history. The exclusion returns BEFORE
        // `_cm.primaryClip` is read, so the secret never crosses into the IME process at all —
        // hence the second assertion, which is stronger than "not stored".
        verify(exactly = 0) { svc.addClip(any()) }
        verify(exactly = 0) { clipboard.primaryClip }
    }

    @Test
    fun aCopyMadeInAnOrdinaryAppIsStored() {
        foregroundApp("com.example.notes")
        primaryClip("shopping list")

        val svc = service()
        svc.captureCurrentClip()

        // Positive control: without it, "not stored" above could be true for any reason.
        verify(exactly = 1) { svc.addClip("shopping list") }
    }

    @Test
    fun turningTheExclusionOffLetsAPasswordManagerCopyThrough() {
        config.clipboard_exclude_password_managers = false
        foregroundApp("com.x8bit.bitwarden")
        primaryClip("secret")

        val svc = service()
        svc.captureCurrentClip()

        // The setting is the control: off means no package filtering at all.
        verify(exactly = 1) { svc.addClip("secret") }
    }

    @Test
    fun anUndetectableForegroundAppDoesNotBlockOrdinaryCopies() {
        // No usage-stats permission is the common case; detection returns null and the
        // exclusion must fail OPEN (a keyboard that silently stopped recording clipboard
        // history on every device without the permission would be the worse bug).
        foregroundApp(null)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns null
        primaryClip("note")

        val svc = service()
        svc.captureCurrentClip()

        verify(exactly = 1) { svc.addClip("note") }
    }

    @Test
    fun theBlockedPackageListNamesTheManagersTheNotesClaim() {
        // #62 named the password managers; #86 added the browser-based ones. Spot-checking the
        // exact package ids is what makes the list a contract rather than a comment — a typo
        // ("com.x8bit.bitwarden2") silently disables the exclusion for that app.
        assertThat(Defaults.PASSWORD_MANAGER_PACKAGES).containsAtLeast(
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "com.agilebits.onepassword",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "keepass2android.keepass2android",
            "com.kunzisoft.keepass.free",
            "com.kunzisoft.keepass.libre",
            "io.enpass.app",
            "proton.android.pass",
        )
        // #86 explicitly extended the list to browsers, whose built-in managers copy credentials.
        assertThat(Defaults.PASSWORD_MANAGER_PACKAGES).containsAtLeast(
            "com.android.chrome",
            "com.microsoft.emmx",
            "com.samsung.android.samsungpass",
            "org.mozilla.firefox",
        )
        assertWithMessage("an ordinary app must not be in the blocklist")
            .that(Defaults.PASSWORD_MANAGER_PACKAGES).doesNotContain("com.example.notes")
    }

    // --------------------------------------------------- #86: the Android 13+ sensitive flag

    @Test
    fun aClipFlaggedSensitiveIsNotStoredEvenFromAnOrdinaryApp() {
        foregroundApp("com.example.notes")
        primaryClip("one-time-code-123456", sensitive = true)

        val svc = service()
        svc.captureCurrentClip()

        // IS_SENSITIVE is the robust signal #86 added: any app — not just a blocklisted one —
        // can mark a clip sensitive, and CleverKeys must honour it.
        verify(exactly = 0) { svc.addClip(any()) }
    }

    @Test
    fun aClipExplicitlyFlaggedNotSensitiveIsStored() {
        foregroundApp("com.example.notes")
        primaryClip("public text", sensitive = false)

        val svc = service()
        svc.captureCurrentClip()

        verify(exactly = 1) { svc.addClip("public text") }
    }

    @Test
    fun aClipWithNoExtrasAtAllIsStored() {
        foregroundApp("com.example.notes")
        primaryClip("plain", sensitive = null)

        val svc = service()
        svc.captureCurrentClip()

        // Absent extras must read as NOT sensitive, never as a crash.
        verify(exactly = 1) { svc.addClip("plain") }
    }

    @Test
    fun turningTheSensitiveFlagRespectOffStoresTheClipAnyway() {
        config.clipboard_respect_sensitive_flag = false
        foregroundApp("com.example.notes")
        primaryClip("one-time-code-123456", sensitive = true)

        val svc = service()
        svc.captureCurrentClip()

        // The setting is the control for the flag check too.
        verify(exactly = 1) { svc.addClip("one-time-code-123456") }
    }

    @Test
    fun onPreTiramisuTheSensitiveFlagArmIsNotConsulted() {
        // The extra only exists from API 33. Below it the SDK guard short-circuits, so a
        // stray extra on an old device must not silently drop the user's clipboard.
        setSdkInt(32)
        foregroundApp("com.example.notes")
        primaryClip("legacy device text", sensitive = true)

        val svc = service()
        svc.captureCurrentClip()

        verify(exactly = 1) { svc.addClip("legacy device text") }
    }

    @Test
    fun theExtraIsReadUnderThePlatformKey() {
        // Reading the wrong key would make the whole check a silent no-op: `getBoolean` would
        // return the `false` default for every clip. Pin the exact lookup.
        foregroundApp("com.example.notes")
        val description = mockk<ClipDescription>()
        val extras = mockk<PersistableBundle>()
        every { extras.getBoolean(isSensitiveKey, false) } returns true
        every { description.extras } returns extras
        val item = mockk<ClipData.Item>()
        every { item.text } returns "secret"
        every { item.uri } returns null
        val clip = mockk<ClipData>()
        every { clip.description } returns description
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        every { clipboard.primaryClip } returns clip

        service().captureCurrentClip()

        verify(exactly = 1) { extras.getBoolean(isSensitiveKey, false) }
    }

    // ------------------------------------------------------------------ reflection

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError(
            "field '$name' not found on ${javaClass.name} or its supertypes — it was renamed " +
                "or removed; declared here: ${javaClass.declaredFields.map { it.name }}"
        )
    }

    /**
     * Set `Build.VERSION.SDK_INT` via sun.misc.Unsafe (Java 17+ removed Field.modifiers access).
     *
     * The `initialize = true` forName below is load-bearing: `Unsafe.staticFieldBase/Offset` do
     * NOT trigger class initialization, so writing before `<clinit>` has run lets the class
     * initializer overwrite the value with the android.jar stub default (0) on the very first
     * read. Symptom when omitted: the FIRST test in the class sees 0 and every later one sees
     * the forced value.
     */
    private fun setSdkInt(sdkInt: Int) {
        Class.forName("android.os.Build\$VERSION", true, javaClass.classLoader)
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass
        val field = android.os.Build.VERSION::class.java.getField("SDK_INT")
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod(
            "putInt", Object::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).invoke(unsafe, base, offset, sdkInt)
        assertWithMessage("SDK_INT must actually be forced, or the API-gated arms are unreachable")
            .that(android.os.Build.VERSION.SDK_INT).isEqualTo(sdkInt)
    }
}
