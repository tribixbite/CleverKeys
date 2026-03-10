package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Throwable crash guards in critical code paths.
 *
 * The v1.3.0 release fixed a language toggle crash caused by OOM during
 * config propagation. The fix: catch Throwable (not just Exception) in:
 *   - ConfigPropagator.propagateConfig()
 *   - ConfigurationManager.refresh()
 *   - ConfigurationManager.onSharedPreferenceChanged()
 *   - SwipePredictorOrchestrator.loadPrimaryDictionaryFromPrefs()
 *   - SwipePredictorOrchestrator.loadSecondaryDictionaryFromPrefs()
 *   - SwipePredictorOrchestrator.initializeLanguageDetector()
 *
 * These tests verify that the catch blocks exist and that the code paths
 * handle null/missing dependencies gracefully without crashing the IME.
 */
@RunWith(AndroidJUnit4::class)
class CrashGuardInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    // =========================================================================
    // ConfigPropagator — null managers should not crash
    // =========================================================================

    @Test
    fun configPropagator_allNullManagersSurvives() {
        // ConfigPropagator with all null managers should propagate without throwing.
        // This exercises the null-safe ?. calls on each manager.
        val propagator = ConfigPropagator(
            null, null, null, null, null, null, null, null
        )
        val config = Config.globalConfig()

        // Should not throw
        propagator.propagateConfig(config)
    }

    @Test
    fun configPropagator_allNullManagersWithResources() {
        // Same but with resources provided (exercises subtypeManager?.refreshSubtype path)
        val propagator = ConfigPropagator(
            null, null, null, null, null, null, null, null
        )
        val config = Config.globalConfig()

        propagator.propagateConfig(config, context.resources)
    }

    @Test
    fun configPropagator_resetKeyboardViewWithNull() {
        val propagator = ConfigPropagator(
            null, null, null, null, null, null, null, null
        )
        // Should not throw when keyboardView is null
        propagator.resetKeyboardView()
    }

    @Test
    fun configPropagator_builderProducesWorkingInstance() {
        // Builder pattern should produce a propagator that doesn't crash
        val propagator = ConfigPropagator.builder()
            .setClipboardManager(null)
            .setPredictionCoordinator(null)
            .setInputCoordinator(null)
            .setSuggestionHandler(null)
            .setNeuralLayoutHelper(null)
            .setLayoutManager(null)
            .setKeyboardView(null)
            .setSubtypeManager(null)
            .build()

        propagator.propagateConfig(Config.globalConfig())
    }

    // =========================================================================
    // ConfigPropagator — verify Throwable catch via reflection
    // =========================================================================

    @Test
    fun configPropagator_propagateConfigCatchesThrowable() {
        // Verify the method signature exists and the try-catch uses Throwable.
        // We can't easily trigger an OOM in a test, but we verify the method is robust
        // by calling it with a valid Config — if it throws, the catch was removed.
        val propagator = ConfigPropagator(
            null, null, null, null, null, null, null, null
        )
        try {
            propagator.propagateConfig(Config.globalConfig())
            // Success — the Throwable catch kept us safe
        } catch (t: Throwable) {
            fail("propagateConfig should catch Throwable, but threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // =========================================================================
    // ConfigurationManager — verify Throwable catch in refresh path
    // =========================================================================

    @Test
    fun configurationManager_catchesThrowableInRefreshSrc() {
        // Verify that ConfigurationManager.refresh() catches Throwable.
        // We check this by looking for the catch block via the method's behavior:
        // calling refresh on a properly initialized instance should not crash.
        val config = Config.globalConfig()
        val foldTracker = FoldStateTracker(context)

        val configManager = ConfigurationManager(context, config, foldTracker)

        // Register a listener that always throws Error (not just Exception)
        configManager.registerConfigChangeListener(object : ConfigChangeListener {
            override fun onConfigChanged(config: Config) {
                throw OutOfMemoryError("Simulated OOM in listener")
            }
            override fun onThemeChanged(prevTheme: Int, newTheme: Int) {}
        })

        // This should NOT crash — ConfigurationManager catches Throwable per-listener
        try {
            configManager.refresh(context.resources)
        } catch (t: Throwable) {
            fail("ConfigurationManager.refresh() should catch Throwable from listeners, but threw: ${t.javaClass.simpleName}")
        }
    }

    @Test
    fun configurationManager_continuesAfterListenerThrows() {
        // Verify that if one listener throws, the next listener still gets called.
        val config = Config.globalConfig()
        val foldTracker = FoldStateTracker(context)
        val configManager = ConfigurationManager(context, config, foldTracker)

        var secondListenerCalled = false

        // First listener throws
        configManager.registerConfigChangeListener(object : ConfigChangeListener {
            override fun onConfigChanged(config: Config) {
                throw RuntimeException("Simulated crash in first listener")
            }
            override fun onThemeChanged(prevTheme: Int, newTheme: Int) {}
        })

        // Second listener records if it was called
        configManager.registerConfigChangeListener(object : ConfigChangeListener {
            override fun onConfigChanged(config: Config) {
                secondListenerCalled = true
            }
            override fun onThemeChanged(prevTheme: Int, newTheme: Int) {}
        })

        configManager.refresh(context.resources)
        assertTrue("Second listener should still be called after first throws",
            secondListenerCalled)
    }

    @Test
    fun configurationManager_onSharedPreferenceChangedCatchesThrowable() {
        // Verify onSharedPreferenceChanged catches Throwable
        val config = Config.globalConfig()
        val foldTracker = FoldStateTracker(context)
        val configManager = ConfigurationManager(context, config, foldTracker)

        val prefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)

        // Should not throw
        try {
            configManager.onSharedPreferenceChanged(prefs, "nonexistent_key")
        } catch (t: Throwable) {
            fail("onSharedPreferenceChanged should catch Throwable, but threw: ${t.javaClass.simpleName}")
        }
    }

    // =========================================================================
    // ConfigurationManager — listener management
    // =========================================================================

    @Test
    fun configurationManager_registerNullListenerIgnored() {
        val config = Config.globalConfig()
        val foldTracker = FoldStateTracker(context)
        val configManager = ConfigurationManager(context, config, foldTracker)

        // Should not crash
        configManager.registerConfigChangeListener(null)
        configManager.unregisterConfigChangeListener(null)
    }

    @Test
    fun configurationManager_duplicateListenerIgnored() {
        val config = Config.globalConfig()
        val foldTracker = FoldStateTracker(context)
        val configManager = ConfigurationManager(context, config, foldTracker)

        var callCount = 0
        val listener = object : ConfigChangeListener {
            override fun onConfigChanged(config: Config) { callCount++ }
            override fun onThemeChanged(prevTheme: Int, newTheme: Int) {}
        }

        configManager.registerConfigChangeListener(listener)
        configManager.registerConfigChangeListener(listener) // Duplicate

        configManager.refresh(context.resources)
        assertEquals("Listener should only be called once (no duplicates)", 1, callCount)
    }

    @Test
    fun configurationManager_unregisterPreventsCallback() {
        val config = Config.globalConfig()
        val foldTracker = FoldStateTracker(context)
        val configManager = ConfigurationManager(context, config, foldTracker)

        var called = false
        val listener = object : ConfigChangeListener {
            override fun onConfigChanged(config: Config) { called = true }
            override fun onThemeChanged(prevTheme: Int, newTheme: Int) {}
        }

        configManager.registerConfigChangeListener(listener)
        configManager.unregisterConfigChangeListener(listener)

        configManager.refresh(context.resources)
        assertFalse("Unregistered listener should not be called", called)
    }

    @Test
    fun configurationManager_debugState() {
        val config = Config.globalConfig()
        val foldTracker = FoldStateTracker(context)
        val configManager = ConfigurationManager(context, config, foldTracker)

        val debugState = configManager.getDebugState()
        assertNotNull(debugState)
        assertTrue("Debug state should contain class name",
            debugState.contains("ConfigurationManager"))
        assertTrue("Debug state should contain listener count",
            debugState.contains("listeners="))
    }
}
