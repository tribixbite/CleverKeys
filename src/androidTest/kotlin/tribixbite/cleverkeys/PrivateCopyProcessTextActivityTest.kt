package tribixbite.cleverkeys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #156 instrumented tests for [PrivateCopyProcessTextActivity] (entry point B).
 *
 * Verifies (design §4, §6):
 *   - Launch with EXTRA_PROCESS_TEXT (feature enabled) → a private DB row exists with the marker,
 *     and the OS primary clip is UNCHANGED across the whole flow (the security invariant).
 *   - Result is RESULT_CANCELED with null data for both READONLY values (never setResult(RESULT_OK)).
 *   - The component is manifest-disabled by default; the settings flip enables it.
 *   - Hostile/empty intents cause no crash and no row.
 *
 * COMPONENT-ENABLE RELIABILITY: the activity is `android:enabled="false"` by default. Launching it
 * races the enable propagation to ActivityManagerService — the symptom is "Unable to resolve
 * activity" / a scenario stuck at PRE_ON_CREATE. `@Before` therefore (1) flips the in-process
 * setComponentEnabledSetting, (2) POLLS getComponentEnabledSetting until it reports ENABLED, and
 * (3) additionally runs a BLOCKING `pm enable` shell command that only returns once PackageManager
 * has fully propagated the change — draining ActivityManager's resolver cache before any launch.
 *
 * The OS-clipboard invariant is verified RELIABLY via [clipboardHelper] (adopt shell identity → set a
 * baseline, READ the clip back to compare), not via a non-focused OnPrimaryClipChangedListener that
 * never fires on API 29+.
 *
 * FLAGGED: androidTest — run via ew-cli (Pixel7 API 34, debug APK, --use-orchestrator).
 */
@RunWith(AndroidJUnit4::class)
class PrivateCopyProcessTextActivityTest {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase
    private lateinit var component: ComponentName
    private val clipboardHelper = PrivateCopyClipboardTestHelper()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        db = ClipboardDatabase.getInstance(context)
        // component uses ctx.packageName (which carries the `.debug` applicationIdSuffix); the class
        // name is absolute, so flattenToShortString() → "tribixbite.cleverkeys.debug/...ProcessText…".
        component = ComponentName(context, PrivateCopyProcessTextActivity::class.java)
        clearAll()
        // Enable the feature pref + component so the activity is launchable.
        DirectBootAwarePreferences.get_shared_preferences(context).edit()
            .putBoolean(PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED, true).commit()

        // The component is android:enabled="false" in the manifest (opt-in, default off). Enable it
        // reliably: (1) in-process flip, (2) poll PackageManager until it reports ENABLED, and (3) a
        // blocking `pm enable` that returns only after PMS has propagated the change — draining
        // ActivityManagerService's resolver cache so the launch below doesn't race it.
        enableComponentReliably()
        clipboardHelper.adopt()
    }

    @After
    fun tearDown() {
        clearAll()
        clipboardHelper.drop()
        // Restore the manifest default (enabled="false") so no other test sees the component enabled.
        context.packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
        setComponentEnabledViaShell(enabled = false)
        DirectBootAwarePreferences.get_shared_preferences(context).edit()
            .putBoolean(PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED, false).commit()
    }

    private fun clearAll() {
        db.writableDatabase.delete("clipboard_entries", null, null)
    }

    private fun setComponentEnabled(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    /**
     * Enable [component] and BLOCK until the enable has fully propagated. Combines the in-process
     * setter (fast, but async wrt ActivityManager), a poll on getComponentEnabledSetting, and a
     * blocking `pm enable` shell command (returns only once PackageManagerService is done). Any of
     * these alone can still race the launch; together they close the "Unable to resolve activity" gap.
     */
    private fun enableComponentReliably() {
        // (1) In-process flip.
        setComponentEnabled(true)
        // (2) Poll until PackageManager reports ENABLED (up to ~5s).
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (context.packageManager.getComponentEnabledSetting(component)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) break
            Thread.sleep(100)
        }
        // (3) Blocking `pm enable` — drains the resolver cache in ActivityManagerService.
        setComponentEnabledViaShell(enabled = true)
        // Final confirmation the state is ENABLED before any launch.
        assertEquals(
            "component must be ENABLED before launching the activity",
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            context.packageManager.getComponentEnabledSetting(component)
        )
    }

    /**
     * Enable/disable [component] via a blocking `pm` shell command so the change is fully propagated
     * to ActivityManagerService before we launch — avoids the async-IPC race that leaves the launch
     * stuck at PRE_ON_CREATE. `pm default-state` restores the manifest's android:enabled="false".
     */
    private fun setComponentEnabledViaShell(enabled: Boolean) {
        val flat = component.flattenToShortString()
        val verb = if (enabled) "enable" else "default-state"
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = uiAutomation.executeShellCommand("pm $verb $flat")
        // Drain the output to block until the command completes.
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
    }

    private fun processTextIntent(text: CharSequence, readonly: Boolean): Intent =
        Intent(context, PrivateCopyProcessTextActivity::class.java).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readonly)
        }

    private fun hasEntry(content: String): Boolean =
        db.getActiveClipboardEntries().any { it.content == content && it.isPrivate }

    // ── Store + OS-clipboard invariant ────────────────────────────────────────

    @Test
    fun processText_storesPrivately_andLeavesOsClipboardUntouched() {
        // Seed a known baseline; the private-copy activity must never write the OS clipboard, so the
        // baseline must survive the whole flow. Asserted by READING the clip back (non-vacuous).
        val baseline = "os-clipboard-baseline-${System.nanoTime()}"
        clipboardHelper.setPrimaryClip("baseline", baseline)
        assertEquals("baseline must be set before the flow", baseline, clipboardHelper.readPrimaryClipText())

        // launchActivityForResult (not plain launch): the activity NEVER calls setResult by design
        // (the host's text stays untouched), so scenario.result is only readable when the scenario
        // was started for-result — otherwise getResult() throws "You must start Activity first".
        ActivityScenario.launchActivityForResult<PrivateCopyProcessTextActivity>(processTextIntent("secret note", readonly = false)).use { scenario ->
            assertEquals(android.app.Activity.RESULT_CANCELED, scenario.result.resultCode)
            assertNull(scenario.result.resultData)  // never setResult(RESULT_OK)
        }
        assertTrue("private entry stored", hasEntry("secret note"))

        // SECURITY INVARIANT (non-vacuous): the OS primary clip is unchanged — still the baseline.
        clipboardHelper.waitForIdle()
        assertEquals(
            "process-text private copy must not write the OS clipboard — it must still hold the baseline",
            baseline,
            clipboardHelper.readPrimaryClipText()
        )
    }

    @Test
    fun processTextReadonly_behavesIdentically_noResult() {
        ActivityScenario.launchActivityForResult<PrivateCopyProcessTextActivity>(processTextIntent("readonly sel", readonly = true)).use { scenario ->
            assertEquals(android.app.Activity.RESULT_CANCELED, scenario.result.resultCode)
            assertNull(scenario.result.resultData)
        }
        assertTrue(hasEntry("readonly sel"))
    }

    /**
     * ARC-037: the confirmation toast became suppressible. This locks the part that MATTERS —
     * suppressing the acknowledgement must not suppress the work. A gate placed one line too
     * early would turn "don't tell me" into "don't copy", and the user would have no way to
     * notice, because the whole feature's premise is that nothing reaches the OS clipboard
     * where they could otherwise check.
     *
     * The toast's absence itself is not asserted: toasts are not readable from instrumentation
     * on API 30+ (same limitation already recorded on the OVER_CAP test below).
     */
    @Test
    fun toastSuppressed_stillStoresTheEntry() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        prefs.edit().putBoolean(PrivateCopyProcessTextActivity.PREF_TOAST_ENABLED, false).commit()
        try {
            ActivityScenario.launch<PrivateCopyProcessTextActivity>(
                processTextIntent("quiet note", readonly = false)
            ).use { }
            assertTrue("suppressing the toast must not suppress the copy", hasEntry("quiet note"))
        } finally {
            // Restore the opt-out default for every other test in the class.
            prefs.edit().remove(PrivateCopyProcessTextActivity.PREF_TOAST_ENABLED).commit()
        }
    }

    /** And the default (pref unset) path still stores — the gate defaults to SHOWING the toast. */
    @Test
    fun toastPrefUnset_defaultsToEnabled_andStillStores() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        prefs.edit().remove(PrivateCopyProcessTextActivity.PREF_TOAST_ENABLED).commit()
        assertTrue(
            "unset must read as true (opt-out), matching SettingsDefaults",
            prefs.getBoolean(PrivateCopyProcessTextActivity.PREF_TOAST_ENABLED, true)
        )
        ActivityScenario.launch<PrivateCopyProcessTextActivity>(
            processTextIntent("loud note", readonly = false)
        ).use { }
        assertTrue(hasEntry("loud note"))
    }

    // ── Hostile / empty intents ───────────────────────────────────────────────

    @Test
    fun emptyExtra_noRow_noCrash() {
        ActivityScenario.launchActivityForResult<PrivateCopyProcessTextActivity>(
            Intent(context, PrivateCopyProcessTextActivity::class.java).apply {
                action = Intent.ACTION_PROCESS_TEXT; type = "text/plain"
            }
        ).use { scenario ->
            assertEquals(android.app.Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
        assertEquals(0, db.getActiveClipboardEntries().size)
    }

    @Test
    fun blankText_noRow() {
        ActivityScenario.launch<PrivateCopyProcessTextActivity>(processTextIntent("   ", readonly = false)).use { }
        assertEquals(0, db.getActiveClipboardEntries().size)
    }

    /**
     * Finding 10: an oversized selection (> clipboard_max_item_size_kb) is rejected at the parser's
     * OVER_CAP gate and stores NO row. The activity now surfaces a Toast for this case (mirroring the
     * service's "too large" message) instead of a silent Log.w — the visible-feedback behavior can't
     * be asserted from instrumentation without a UI hook, but this locks the no-row / no-crash
     * contract so a payload just over the 512 KB-class cap is dropped cleanly rather than stored.
     */
    @Test
    fun overCapText_noRow_noCrash() {
        // A payload just over the 512 KB-class default cap can't cross startActivity's Binder
        // transaction (UTF-16 doubles it past the ~1 MB limit → "Failure from system"). Lower the
        // cap so a small string is still over-cap, exercising the same OVER_CAP reject path via a
        // legal Intent. clipboard_max_item_size_kb is the exact field the activity reads.
        val cfg = Config.globalConfig()
        val originalCap = cfg.clipboard_max_item_size_kb
        cfg.clipboard_max_item_size_kb = 1
        try {
            val oversized = "a".repeat(2 * 1024)   // 2 KB > 1 KB cap, tiny for Binder
            ActivityScenario.launch<PrivateCopyProcessTextActivity>(
                processTextIntent(oversized, readonly = false)
            ).use { }
            assertEquals("oversized selection must not be stored", 0, db.getActiveClipboardEntries().size)
        } finally {
            cfg.clipboard_max_item_size_kb = originalCap
        }
    }

    // ── Component gating ──────────────────────────────────────────────────────

    @Test
    fun component_flipsBetweenEnabledAndDisabled() {
        setComponentEnabled(true)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            context.packageManager.getComponentEnabledSetting(component)
        )
        setComponentEnabled(false)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(component)
        )
    }

    /**
     * Bug-report #3 (2026-07-20, "no 'Copy to private' in the text-selection menu"): pins the FULL
     * settings-toggle chain. [reconcilePrivateCopyToolbarComponent] is the exact function the
     * Settings switch ("Private copy in other apps", ClipboardSection) and the settings-load /
     * backup-import reconcilers call. Toggle ON must (a) set the OS component state to ENABLED and
     * (b) make the activity RESOLVE for ACTION_PROCESS_TEXT + text/plain — the query the system
     * text-selection toolbar performs. Toggle OFF must remove it from resolution (design §6.6:
     * default-off, zero exported surface until opt-in — so a user who has NOT enabled the toggle
     * seeing no menu entry is BY DESIGN, not this bug).
     */
    @Test
    fun reconcileToggle_enablesComponent_andProcessTextResolves() {
        val pm = context.packageManager
        // The toolbar's query: PROCESS_TEXT + text/plain, scoped to our package.
        val processTextQuery = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .setPackage(context.packageName)

        fun resolvesProcessText(): Boolean =
            pm.queryIntentActivities(processTextQuery, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == PrivateCopyProcessTextActivity::class.java.name }

        // OFF path — the production off-state is explicit DISABLED (not DEFAULT, design §6.6).
        reconcilePrivateCopyToolbarComponent(context, false)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(component)
        )
        assertFalse(
            "disabled component must be invisible to PROCESS_TEXT resolution",
            resolvesProcessText()
        )

        // ON path — what flipping the settings switch runs.
        reconcilePrivateCopyToolbarComponent(context, true)
        assertEquals(
            "toggle ON must enable the component",
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            pm.getComponentEnabledSetting(component)
        )
        assertTrue(
            "enabled component must resolve for ACTION_PROCESS_TEXT text/plain — otherwise " +
                "'Private copy' never appears in other apps' selection toolbars",
            resolvesProcessText()
        )
    }
}
