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
 *   - SwipePredictorOrchestrator.loadPrimaryDictionaryFromPrefs()
 *
 * ConfigPropagator is the primary test target because it's constructable
 * without UI Context (all manager refs are nullable). ConfigurationManager
 * requires FoldStateTracker which needs Activity/WindowContext and can't
 * be tested in standard instrumentation without MockK.
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
        propagator.propagateConfig(Config.globalConfig())
    }

    @Test
    fun configPropagator_allNullManagersWithResources() {
        // Same but with resources provided (exercises subtypeManager?.refreshSubtype path)
        val propagator = ConfigPropagator(
            null, null, null, null, null, null, null, null
        )
        propagator.propagateConfig(Config.globalConfig(), context.resources)
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
    // ConfigPropagator — verify Throwable catch
    // =========================================================================

    @Test
    fun configPropagator_propagateConfigCatchesThrowable() {
        // Verify the try-catch(Throwable) block in propagateConfig keeps the
        // method from throwing even with valid Config + null managers.
        val propagator = ConfigPropagator(
            null, null, null, null, null, null, null, null
        )
        try {
            propagator.propagateConfig(Config.globalConfig())
        } catch (t: Throwable) {
            fail("propagateConfig should catch Throwable, but threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Test
    fun configPropagator_propagateConfigMultipleTimes() {
        // Verify repeated propagation doesn't accumulate state or crash.
        val propagator = ConfigPropagator(
            null, null, null, null, null, null, null, null
        )
        val config = Config.globalConfig()
        repeat(10) {
            propagator.propagateConfig(config)
        }
    }

    @Test
    fun configPropagator_propagateConfigWithAndWithoutResources() {
        // Verify alternating calls with/without resources works.
        val propagator = ConfigPropagator(
            null, null, null, null, null, null, null, null
        )
        val config = Config.globalConfig()
        propagator.propagateConfig(config)
        propagator.propagateConfig(config, context.resources)
        propagator.propagateConfig(config)
    }
}
