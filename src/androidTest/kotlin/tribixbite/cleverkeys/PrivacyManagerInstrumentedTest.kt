package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for PrivacyManager — covers org.json-dependent paths
 * that cannot be tested on pure JVM (android.jar stubs throw Stub!).
 *
 * Specifically covers:
 * - Audit trail recording and retrieval (org.json.JSONArray/JSONObject)
 * - exportSettings() JSON serialization
 * - Full consent → audit → export lifecycle
 */
@RunWith(AndroidJUnit4::class)
class PrivacyManagerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var manager: PrivacyManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Clear privacy prefs before each test for isolation
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("privacy_settings", Context.MODE_PRIVATE)
            .edit().clear().apply()
        manager = PrivacyManager(context)
    }

    // =========================================================================
    // Audit trail — org.json dependent paths
    // =========================================================================

    @Test
    fun auditTrailEmptyInitially() {
        val trail = manager.getAuditTrail()
        assertTrue("Audit trail should be empty initially", trail.isEmpty())
    }

    @Test
    fun grantConsentRecordsAuditEntry() {
        manager.grantConsent()
        val trail = manager.getAuditTrail()
        assertEquals("Should have 1 audit entry after grantConsent", 1, trail.size)
        assertEquals("consent_granted", trail[0].action)
        assertTrue(trail[0].description.contains("granted"))
        assertTrue("Timestamp should be recent", trail[0].timestamp > 0)
    }

    @Test
    fun revokeConsentRecordsAuditEntry() {
        manager.grantConsent()
        manager.revokeConsent()
        val trail = manager.getAuditTrail()
        assertEquals("Should have 2 audit entries", 2, trail.size)
        // Most recent first (reversed)
        assertEquals("consent_revoked", trail[0].action)
        assertEquals("consent_granted", trail[1].action)
    }

    @Test
    fun auditTrailMostRecentFirst() {
        manager.grantConsent()
        manager.revokeConsent()
        manager.grantConsent()
        val trail = manager.getAuditTrail()
        assertEquals(3, trail.size)
        // Most recent should be first
        assertEquals("consent_granted", trail[0].action)
        assertEquals("consent_revoked", trail[1].action)
        assertEquals("consent_granted", trail[2].action)
        // Timestamps should be in descending order
        assertTrue(trail[0].timestamp >= trail[1].timestamp)
    }

    @Test
    fun auditTrailEntryHasAllFields() {
        manager.grantConsent()
        val entry = manager.getAuditTrail()[0]
        assertTrue("Timestamp should be positive", entry.timestamp > 0)
        assertNotNull("Action should not be null", entry.action)
        assertNotNull("Description should not be null", entry.description)
        assertTrue("Action should not be empty", entry.action.isNotEmpty())
        assertTrue("Description should not be empty", entry.description.isNotEmpty())
    }

    @Test
    fun auditTrailPersistsAcrossInstances() {
        manager.grantConsent()
        // Create new PrivacyManager instance
        val manager2 = PrivacyManager(context)
        val trail = manager2.getAuditTrail()
        assertEquals("Audit trail should persist across instances", 1, trail.size)
        assertEquals("consent_granted", trail[0].action)
    }

    @Test
    fun resetAllRecordsAuditEntry() {
        manager.grantConsent()
        manager.resetAll()
        // resetAll clears prefs then records audit, so only the reset entry remains
        val trail = manager.getAuditTrail()
        // After clear + record, only the reset entry survives
        assertTrue("Should have at least 1 entry from reset", trail.isNotEmpty())
        assertEquals("reset_all", trail[0].action)
    }

    // =========================================================================
    // exportSettings — org.json dependent
    // =========================================================================

    @Test
    fun exportSettingsReturnsValidJson() {
        manager.grantConsent()
        val json = manager.exportSettings()
        // Should be parseable JSON
        val obj = JSONObject(json)
        assertTrue("Should have consent section", obj.has("consent"))
        assertTrue("Should have settings section", obj.has("settings"))
        assertTrue("Should have audit_trail section", obj.has("audit_trail"))
    }

    @Test
    fun exportSettingsConsentSection() {
        manager.grantConsent()
        val obj = JSONObject(manager.exportSettings())
        val consent = obj.getJSONObject("consent")
        assertTrue("Consent should be granted", consent.getBoolean("granted"))
        assertTrue("Version should be positive", consent.getInt("version") >= 1)
        assertTrue("Timestamp should be positive", consent.getLong("timestamp") > 0)
    }

    @Test
    fun exportSettingsSettingsSection() {
        manager.grantConsent()
        val obj = JSONObject(manager.exportSettings())
        val settings = obj.getJSONObject("settings")
        // Verify all expected fields exist
        assertTrue(settings.has("collect_swipe_data"))
        assertTrue(settings.has("collect_performance_data"))
        assertTrue(settings.has("collect_error_logs"))
        assertTrue(settings.has("anonymize_data"))
        assertTrue(settings.has("local_only_training"))
        assertTrue(settings.has("allow_data_export"))
        assertTrue(settings.has("allow_model_sharing"))
        assertTrue(settings.has("data_retention_days"))
        assertTrue(settings.has("auto_delete_enabled"))
    }

    @Test
    fun exportSettingsAuditTrailSection() {
        manager.grantConsent()
        manager.revokeConsent()
        val obj = JSONObject(manager.exportSettings())
        val auditTrail = obj.getJSONArray("audit_trail")
        // Should have 2 entries (grant + revoke)
        assertEquals(2, auditTrail.length())
        // Each entry should have timestamp, action, description
        val entry = auditTrail.getJSONObject(0)
        assertTrue(entry.has("timestamp"))
        assertTrue(entry.has("action"))
        assertTrue(entry.has("description"))
    }

    @Test
    fun exportSettingsWithoutConsentHasDefaults() {
        // No consent granted — defaults should be present
        val obj = JSONObject(manager.exportSettings())
        val consent = obj.getJSONObject("consent")
        assertFalse("Consent should not be granted", consent.getBoolean("granted"))
    }

    // =========================================================================
    // Full lifecycle — consent → settings → audit → export
    // =========================================================================

    @Test
    fun fullLifecycleRoundTrip() {
        // Grant consent
        manager.grantConsent()
        assertTrue(manager.hasConsent())

        // Change some settings
        manager.setCollectSwipeData(false)
        manager.setAnonymizeData(true)

        // Check audit trail has entries
        val trail = manager.getAuditTrail()
        assertTrue("Should have audit entries", trail.isNotEmpty())

        // Export and verify
        val json = manager.exportSettings()
        val obj = JSONObject(json)
        assertTrue(obj.getJSONObject("consent").getBoolean("granted"))
        assertFalse(obj.getJSONObject("settings").getBoolean("collect_swipe_data"))
        assertTrue(obj.getJSONObject("settings").getBoolean("anonymize_data"))
    }
}
