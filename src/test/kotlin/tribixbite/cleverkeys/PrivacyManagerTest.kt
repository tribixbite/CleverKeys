package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MockK-based JVM tests for PrivacyManager.
 *
 * Uses MockSharedPreferences (from MockClasses.kt) for realistic HashMap-backed
 * SharedPreferences behavior. Mocks Android framework statics (Log,
 * DirectBootAwarePreferences) to run on pure JVM without Robolectric.
 *
 * Note: Build.VERSION.SDK_INT is 0 from android.jar stubs, so the constructor
 * takes the else branch and calls context.getSharedPreferences() directly
 * (not createDeviceProtectedStorageContext). We mock that path accordingly.
 */
class PrivacyManagerTest {

    // Two separate prefs stores: one for privacy_settings, one for main app prefs
    private lateinit var privacyPrefs: MockSharedPreferences
    private lateinit var mainPrefs: MockSharedPreferences
    private lateinit var context: Context
    private lateinit var manager: PrivacyManager

    @Before
    fun setup() {
        // Mock android.util.Log to prevent "Stub!" crashes
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0

        // Create prefs stores
        privacyPrefs = MockSharedPreferences()
        mainPrefs = MockSharedPreferences()

        // Build.VERSION.SDK_INT = 0 from android.jar stubs, so constructor takes
        // the else branch: context.getSharedPreferences("privacy_settings", MODE_PRIVATE)
        context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences("privacy_settings", Context.MODE_PRIVATE) } returns privacyPrefs

        // Mock DirectBootAwarePreferences @JvmStatic method — must use mockkStatic with
        // function reference (mockkObject doesn't intercept @JvmStatic dispatch on objects)
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns mainPrefs

        // Clear singleton so each test gets a fresh instance
        clearPrivacyManagerSingleton()

        manager = PrivacyManager(context)
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
        unmockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        clearPrivacyManagerSingleton()
    }

    /**
     * Clear the PrivacyManager singleton instance via reflection so
     * each test gets a fresh PrivacyManager with clean prefs.
     */
    private fun clearPrivacyManagerSingleton() {
        try {
            val companionField = PrivacyManager::class.java.getDeclaredField("instance")
            companionField.isAccessible = true
            companionField.set(null, null)
        } catch (_: Exception) {
            try {
                val companion = PrivacyManager.Companion
                val instanceField = companion.javaClass.getDeclaredField("instance")
                instanceField.isAccessible = true
                instanceField.set(companion, null)
            } catch (_: Exception) {
                // Singleton may not have been set
            }
        }
    }

    // =========================================================================
    // Consent: hasConsent / grantConsent / revokeConsent
    // =========================================================================

    @Test
    fun `hasConsent returns false by default`() {
        assertThat(manager.hasConsent()).isFalse()
    }

    @Test
    fun `hasConsent returns true after grantConsent`() {
        manager.grantConsent()
        assertThat(manager.hasConsent()).isTrue()
    }

    @Test
    fun `grantConsent stores consent flag timestamp and version in prefs`() {
        manager.grantConsent()
        assertThat(privacyPrefs.getBoolean("consent_given", false)).isTrue()
        assertThat(privacyPrefs.getInt("consent_version", 0)).isEqualTo(1)
        assertThat(privacyPrefs.getLong("consent_timestamp", 0)).isGreaterThan(0)
    }

    @Test
    fun `revokeConsent sets consent to false`() {
        manager.grantConsent()
        assertThat(manager.hasConsent()).isTrue()

        manager.revokeConsent()
        assertThat(manager.hasConsent()).isFalse()
    }

    @Test
    fun `revokeConsent with deleteData false still revokes consent`() {
        manager.grantConsent()
        manager.revokeConsent(deleteData = false)
        assertThat(manager.hasConsent()).isFalse()
    }

    // =========================================================================
    // getConsentStatus
    // =========================================================================

    @Test
    fun `getConsentStatus returns correct defaults when no consent given`() {
        val status = manager.getConsentStatus()
        assertThat(status.hasConsent).isFalse()
        assertThat(status.consentTimestamp).isEqualTo(0)
        assertThat(status.consentVersion).isEqualTo(0)
        // Version 0 < CURRENT_CONSENT_VERSION (1), so needsUpdate = true
        assertThat(status.needsUpdate).isTrue()
    }

    @Test
    fun `getConsentStatus after grantConsent has correct version and no update needed`() {
        manager.grantConsent()
        val status = manager.getConsentStatus()
        assertThat(status.hasConsent).isTrue()
        assertThat(status.consentVersion).isEqualTo(1)
        assertThat(status.consentTimestamp).isGreaterThan(0)
        // Current version matches, no update needed
        assertThat(status.needsUpdate).isFalse()
    }

    // =========================================================================
    // Data collection flags (read from mainPrefs)
    // =========================================================================

    @Test
    fun `canCollectSwipeData returns default false`() {
        assertThat(manager.canCollectSwipeData()).isFalse()
    }

    @Test
    fun `canCollectSwipeData returns true when enabled in mainPrefs`() {
        mainPrefs.putBoolean("privacy_collect_swipe", true)
        assertThat(manager.canCollectSwipeData()).isTrue()
    }

    @Test
    fun `canCollectPerformanceData returns default false`() {
        assertThat(manager.canCollectPerformanceData()).isFalse()
    }

    @Test
    fun `canCollectPerformanceData returns true when enabled in mainPrefs`() {
        mainPrefs.putBoolean("privacy_collect_performance", true)
        assertThat(manager.canCollectPerformanceData()).isTrue()
    }

    @Test
    fun `canCollectErrorLogs returns default false`() {
        assertThat(manager.canCollectErrorLogs()).isFalse()
    }

    @Test
    fun `canCollectErrorLogs returns true when enabled in mainPrefs`() {
        mainPrefs.putBoolean("privacy_collect_errors", true)
        assertThat(manager.canCollectErrorLogs()).isTrue()
    }

    // =========================================================================
    // Anonymization defaults
    // =========================================================================

    @Test
    fun `shouldAnonymizeData defaults to true`() {
        assertThat(manager.shouldAnonymizeData()).isTrue()
    }

    @Test
    fun `shouldRemoveTimestamps defaults to false`() {
        assertThat(manager.shouldRemoveTimestamps()).isFalse()
    }

    @Test
    fun `shouldHashDeviceId defaults to true`() {
        assertThat(manager.shouldHashDeviceId()).isTrue()
    }

    // =========================================================================
    // Training and export defaults
    // =========================================================================

    @Test
    fun `isLocalOnlyTraining defaults to true`() {
        assertThat(manager.isLocalOnlyTraining()).isTrue()
    }

    @Test
    fun `isDataExportAllowed defaults to false`() {
        assertThat(manager.isDataExportAllowed()).isFalse()
    }

    @Test
    fun `isModelSharingAllowed defaults to false`() {
        assertThat(manager.isModelSharingAllowed()).isFalse()
    }

    // =========================================================================
    // Data retention
    // =========================================================================

    @Test
    fun `getDataRetentionDays defaults to 90`() {
        assertThat(manager.getDataRetentionDays()).isEqualTo(90)
    }

    @Test
    fun `setDataRetentionDays stores custom value`() {
        manager.setDataRetentionDays(30)
        assertThat(manager.getDataRetentionDays()).isEqualTo(30)
    }

    @Test
    fun `isAutoDeleteEnabled defaults to true`() {
        assertThat(manager.isAutoDeleteEnabled()).isTrue()
    }

    // =========================================================================
    // shouldPerformCleanup (time-based logic)
    // =========================================================================

    @Test
    fun `shouldPerformCleanup returns true when auto-delete enabled and never cleaned`() {
        // Auto-delete defaults to true, last cleanup defaults to 0
        // System.currentTimeMillis() - 0 > 1 day, so cleanup needed
        assertThat(manager.shouldPerformCleanup()).isTrue()
    }

    @Test
    fun `shouldPerformCleanup returns false when auto-delete disabled`() {
        manager.setAutoDeleteEnabled(false)
        assertThat(manager.shouldPerformCleanup()).isFalse()
    }

    @Test
    fun `shouldPerformCleanup returns false right after cleanup performed`() {
        manager.recordCleanupPerformed()
        assertThat(manager.shouldPerformCleanup()).isFalse()
    }

    // =========================================================================
    // recordCleanupPerformed
    // =========================================================================

    @Test
    fun `recordCleanupPerformed stores timestamp in prefs`() {
        manager.recordCleanupPerformed()
        val timestamp = privacyPrefs.getLong("last_cleanup_timestamp", 0)
        assertThat(timestamp).isGreaterThan(0)
    }

    // =========================================================================
    // getDataRetentionCutoff
    // =========================================================================

    @Test
    fun `getDataRetentionCutoff is current time minus retention period`() {
        val before = System.currentTimeMillis()
        val cutoff = manager.getDataRetentionCutoff()
        val after = System.currentTimeMillis()

        // Default 90 days in ms
        val retentionMs = 90L * 24 * 60 * 60 * 1000
        assertThat(cutoff).isAtLeast(before - retentionMs)
        assertThat(cutoff).isAtMost(after - retentionMs)
    }

    @Test
    fun `getDataRetentionCutoff respects custom retention days`() {
        manager.setDataRetentionDays(7)
        val before = System.currentTimeMillis()
        val cutoff = manager.getDataRetentionCutoff()

        val retentionMs = 7L * 24 * 60 * 60 * 1000
        // Small tolerance for test execution time
        assertThat(cutoff).isAtLeast(before - retentionMs - 100)
    }

    // =========================================================================
    // updateSettings / getSettings
    // =========================================================================

    @Test
    fun `getSettings returns all defaults when nothing configured`() {
        val settings = manager.getSettings()
        assertThat(settings.collectSwipeData).isFalse()
        assertThat(settings.collectPerformanceData).isFalse()
        assertThat(settings.collectErrorLogs).isFalse()
        assertThat(settings.anonymizeData).isTrue()
        assertThat(settings.localOnlyTraining).isTrue()
        assertThat(settings.allowDataExport).isFalse()
        assertThat(settings.allowModelSharing).isFalse()
        assertThat(settings.dataRetentionDays).isEqualTo(90)
        assertThat(settings.autoDeleteEnabled).isTrue()
    }

    @Test
    fun `updateSettings stores all fields and getSettings reads them back`() {
        val settings = PrivacyManager.PrivacySettings(
            collectSwipeData = true,
            collectPerformanceData = true,
            collectErrorLogs = true,
            anonymizeData = false,
            localOnlyTraining = false,
            allowDataExport = true,
            allowModelSharing = true,
            dataRetentionDays = 30,
            autoDeleteEnabled = false
        )
        manager.updateSettings(settings)

        val result = manager.getSettings()
        assertThat(result.collectSwipeData).isTrue()
        assertThat(result.collectPerformanceData).isTrue()
        assertThat(result.collectErrorLogs).isTrue()
        assertThat(result.anonymizeData).isFalse()
        assertThat(result.localOnlyTraining).isFalse()
        assertThat(result.allowDataExport).isTrue()
        assertThat(result.allowModelSharing).isTrue()
        assertThat(result.dataRetentionDays).isEqualTo(30)
        assertThat(result.autoDeleteEnabled).isFalse()
    }

    // =========================================================================
    // Individual setters
    // =========================================================================

    @Test
    fun `setCollectSwipeData enables collection in privacy prefs`() {
        manager.setCollectSwipeData(true)
        assertThat(privacyPrefs.getBoolean("collect_swipe_data", false)).isTrue()
    }

    @Test
    fun `setCollectPerformanceData enables collection in privacy prefs`() {
        manager.setCollectPerformanceData(true)
        assertThat(privacyPrefs.getBoolean("collect_performance_data", false)).isTrue()
    }

    @Test
    fun `setCollectErrorLogs enables collection in privacy prefs`() {
        manager.setCollectErrorLogs(true)
        assertThat(privacyPrefs.getBoolean("collect_error_logs", false)).isTrue()
    }

    @Test
    fun `setAnonymizeData disables anonymization`() {
        manager.setAnonymizeData(false)
        assertThat(manager.shouldAnonymizeData()).isFalse()
    }

    @Test
    fun `setLocalOnlyTraining disables local-only mode`() {
        manager.setLocalOnlyTraining(false)
        assertThat(manager.isLocalOnlyTraining()).isFalse()
    }

    @Test
    fun `setAllowDataExport enables export`() {
        manager.setAllowDataExport(true)
        assertThat(manager.isDataExportAllowed()).isTrue()
    }

    @Test
    fun `setAllowModelSharing enables sharing`() {
        manager.setAllowModelSharing(true)
        assertThat(manager.isModelSharingAllowed()).isTrue()
    }

    @Test
    fun `setAutoDeleteEnabled disables auto-delete`() {
        manager.setAutoDeleteEnabled(false)
        assertThat(manager.isAutoDeleteEnabled()).isFalse()
    }

    // =========================================================================
    // getAuditTrail
    // =========================================================================

    @Test
    fun `getAuditTrail returns empty list when no actions recorded`() {
        val trail = manager.getAuditTrail()
        assertThat(trail).isEmpty()
    }

    // NOTE: getAuditTrail (with entries) and exportSettings tests are excluded because
    // org.json.JSONObject/JSONArray from android.jar are stubs that throw RuntimeException.
    // PrivacyManager.recordAuditEntry() and exportSettings() depend on org.json internally.
    // These tests require Robolectric or a real org.json dependency.

    // =========================================================================
    // resetAll
    // =========================================================================

    @Test
    fun `resetAll clears all privacy prefs and restores defaults`() {
        manager.grantConsent()
        manager.setDataRetentionDays(7)
        manager.setCollectSwipeData(true)

        manager.resetAll()

        assertThat(manager.hasConsent()).isFalse()
        assertThat(manager.getDataRetentionDays()).isEqualTo(90) // back to default
        assertThat(manager.getSettings().collectSwipeData).isFalse()
    }

    // =========================================================================
    // Data class property tests
    // =========================================================================

    @Test
    fun `ConsentStatus data class holds correct values`() {
        val status = PrivacyManager.ConsentStatus(
            hasConsent = true,
            consentTimestamp = 1234567890L,
            consentVersion = 1,
            needsUpdate = false
        )
        assertThat(status.hasConsent).isTrue()
        assertThat(status.consentTimestamp).isEqualTo(1234567890L)
        assertThat(status.consentVersion).isEqualTo(1)
        assertThat(status.needsUpdate).isFalse()
    }

    @Test
    fun `PrivacySettings data class holds correct values`() {
        val settings = PrivacyManager.PrivacySettings(
            collectSwipeData = true,
            collectPerformanceData = false,
            collectErrorLogs = true,
            anonymizeData = true,
            localOnlyTraining = false,
            allowDataExport = true,
            allowModelSharing = false,
            dataRetentionDays = 45,
            autoDeleteEnabled = true
        )
        assertThat(settings.collectSwipeData).isTrue()
        assertThat(settings.collectPerformanceData).isFalse()
        assertThat(settings.collectErrorLogs).isTrue()
        assertThat(settings.anonymizeData).isTrue()
        assertThat(settings.localOnlyTraining).isFalse()
        assertThat(settings.allowDataExport).isTrue()
        assertThat(settings.allowModelSharing).isFalse()
        assertThat(settings.dataRetentionDays).isEqualTo(45)
        assertThat(settings.autoDeleteEnabled).isTrue()
    }

    @Test
    fun `AuditEntry data class holds correct values`() {
        val entry = PrivacyManager.AuditEntry(
            timestamp = 999L,
            action = "test_action",
            description = "test description"
        )
        assertThat(entry.timestamp).isEqualTo(999L)
        assertThat(entry.action).isEqualTo("test_action")
        assertThat(entry.description).isEqualTo("test description")
    }
}
