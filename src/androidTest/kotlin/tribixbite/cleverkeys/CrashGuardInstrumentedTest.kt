package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 *
 * ## What these tests assert (ARC-044, 2026-08-29)
 *
 * They used to call the propagator and assert nothing at all — a green run only
 * proved "no exception escaped", which for an all-null propagator is nearly
 * vacuous. Each test now pins the two contracts that ARE observable with null
 * managers, both read off state the test already had:
 *
 *  1. **`ConfigPropagator` is a pusher, never a mutator.** `propagateConfig`
 *     forwards the caller's [Config] to each manager and calls
 *     `keyboardView?.reset()`; it writes nothing back. So every public field of
 *     the live global [Config] must be byte-identical before and after — across a
 *     single call, ten repeats, and the with/without-`resources` alternation.
 *     Snapshotting by REFLECTION rather than by a hand-picked field list means a
 *     newly added `Config` field is covered the day it lands.
 *  2. **The builder's fluent contract.** Every setter returns the same builder
 *     instance, `builder()` hands out a fresh one per call, and `build()` mints a
 *     new propagator each time — the properties callers rely on when they chain.
 *
 * The snapshot is a before/after DELTA, never an absolute value: instrumented
 * classes that run earlier under the orchestrator legitimately mutate the global
 * `Config` (e.g. `GeometricSwipeOracleTest.harness()` sets `swipe_engine_mode`),
 * and an absolute expectation would make this class order-dependent.
 *
 * ARC-074 adds a constructor-scoped [ConfigPropagationProbe]. The guard test injects an
 * [Error] through that seam, proving the `catch (Throwable)` branch rather than a quiet path.
 */
@RunWith(AndroidJUnit4::class)
class CrashGuardInstrumentedTest {

    private class DeliberatePropagationError : Error("ARC-074 injected Error")

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "Config must initialize — every assertion below reads the global Config",
            TestConfigHelper.ensureConfigInitialized(context)
        )
    }

    // =========================================================================
    // Snapshot helpers — "propagation must not mutate the config it propagates"
    // =========================================================================

    /**
     * Every public field of [config] (the 141 `@JvmField var` settings plus the public
     * statics), keyed by field name. Values are compared by `equals`; reference-typed
     * fields therefore compare by identity, which is exactly the "untouched" property
     * this pins.
     */
    private fun snapshot(config: Config): Map<String, Any?> =
        config.javaClass.fields.associate { it.name to it.get(config) }

    private fun assertUnchanged(before: Map<String, Any?>, config: Config, what: String) {
        val after = snapshot(config)
        assertEquals("$what: the set of Config fields must not change", before.keys, after.keys)
        for ((name, value) in before) {
            assertEquals(
                "$what: ConfigPropagator mutated Config.$name — it may only PUSH the " +
                    "config to managers, never write back to it",
                value, after[name]
            )
        }
    }

    /** Guards the snapshot itself: a reflection change that returned nothing would pass silently. */
    private fun assertSnapshotIsSubstantial(snapshot: Map<String, Any?>) {
        assertTrue(
            "the reflective Config snapshot covers only ${snapshot.size} fields — Config " +
                "declares 141 `@JvmField var` settings, so a near-empty snapshot means the " +
                "reflection stopped working and every assertion below went vacuous",
            snapshot.size >= 100
        )
    }

    private fun nullPropagator() = ConfigPropagator(null, null, null, null, null, null, null, null)

    // =========================================================================
    // ConfigPropagator — null managers should not crash
    // =========================================================================

    @Test
    fun configPropagator_allNullManagersSurvives() {
        // ConfigPropagator with all null managers should propagate without throwing.
        // This exercises the null-safe ?. calls on each manager.
        val propagator = nullPropagator()
        val config = Config.globalConfig()
        val before = snapshot(config)
        assertSnapshotIsSubstantial(before)

        propagator.propagateConfig(config)

        assertUnchanged(before, config, "propagateConfig(config)")
        assertSame(
            "propagation must not re-initialize the global Config singleton",
            config, Config.globalConfig()
        )
    }

    @Test
    fun configPropagator_allNullManagersWithResources() {
        // Same but with resources provided (exercises subtypeManager?.refreshSubtype path)
        val propagator = nullPropagator()
        val config = Config.globalConfig()
        val before = snapshot(config)
        assertSnapshotIsSubstantial(before)

        propagator.propagateConfig(config, context.resources)

        // The resources overload takes the subtype-refresh branch first; with a null
        // SubtypeManager it must still leave the config exactly as handed in.
        assertUnchanged(before, config, "propagateConfig(config, resources)")
        assertSame(
            "propagation must not re-initialize the global Config singleton",
            config, Config.globalConfig()
        )
    }

    @Test
    fun configPropagator_resetKeyboardViewWithNull() {
        val propagator = nullPropagator()
        val config = Config.globalConfig()
        val before = snapshot(config)

        // `keyboardView?.reset()` with a null view is a no-op, and repeating it must stay one.
        repeat(3) { propagator.resetKeyboardView() }

        assertUnchanged(before, config, "resetKeyboardView() ×3")
        // Still usable afterwards — resetKeyboardView must not leave the propagator broken.
        propagator.propagateConfig(config)
        assertUnchanged(before, config, "propagateConfig after resetKeyboardView()")
    }

    @Test
    fun configPropagator_builderProducesWorkingInstance() {
        // The fluent contract: every setter returns THE SAME builder, so a chain
        // accumulates rather than discarding earlier calls.
        val builder = ConfigPropagator.builder()
        assertSame("setClipboardManager must return this", builder, builder.setClipboardManager(null))
        assertSame("setPredictionCoordinator must return this", builder, builder.setPredictionCoordinator(null))
        assertSame("setInputCoordinator must return this", builder, builder.setInputCoordinator(null))
        assertSame("setSuggestionHandler must return this", builder, builder.setSuggestionHandler(null))
        assertSame("setKeyboardDimensionsHelper must return this", builder, builder.setKeyboardDimensionsHelper(null))
        assertSame("setLayoutManager must return this", builder, builder.setLayoutManager(null))
        assertSame("setKeyboardView must return this", builder, builder.setKeyboardView(null))
        assertSame("setSubtypeManager must return this", builder, builder.setSubtypeManager(null))

        // `builder()` is a factory, not a shared singleton — two callers configuring
        // propagators concurrently must not see each other's managers.
        assertNotSame(
            "ConfigPropagator.builder() must hand out a fresh Builder per call",
            ConfigPropagator.builder(), ConfigPropagator.builder()
        )

        val propagator = builder.build()
        assertNotSame(
            "build() must mint a new ConfigPropagator per call, not memoize one",
            propagator, builder.build()
        )

        val config = Config.globalConfig()
        val before = snapshot(config)
        assertSnapshotIsSubstantial(before)
        propagator.propagateConfig(config)
        assertUnchanged(before, config, "builder-built propagateConfig")
    }

    // =========================================================================
    // ConfigPropagator — verify Throwable catch
    // =========================================================================

    @Test
    fun configPropagator_propagateConfigCatchesThrowable() {
        val config = Config.globalConfig()
        val before = snapshot(config)
        var probeInvoked = false
        val propagator = ConfigPropagator(
            null, null, null, null, null, null, null, null,
            ConfigPropagationProbe {
                probeInvoked = true
                throw DeliberatePropagationError()
            }
        )

        try {
            propagator.propagateConfig(config)
        } catch (t: Throwable) {
            fail("propagateConfig should catch Throwable, but leaked " + t.javaClass.simpleName)
        }

        assertTrue("failure probe must run inside the guarded region", probeInvoked)
        assertUnchanged(before, config, "propagateConfig after caught Error")
    }

    @Test
    fun configPropagator_propagateConfigMultipleTimes() {
        // Verify repeated propagation doesn't accumulate state or crash.
        val propagator = nullPropagator()
        val config = Config.globalConfig()
        val before = snapshot(config)
        assertSnapshotIsSubstantial(before)
        repeat(10) { i ->
            propagator.propagateConfig(config)
            // Checked EVERY iteration, not just at the end: a mutation that a later
            // pass undoes would be invisible to a single trailing assertion.
            assertUnchanged(before, config, "propagateConfig repeat #${i + 1}")
        }
    }

    @Test
    fun configPropagator_propagateConfigWithAndWithoutResources() {
        // Verify alternating calls with/without resources works.
        val propagator = nullPropagator()
        val config = Config.globalConfig()
        val before = snapshot(config)
        assertSnapshotIsSubstantial(before)

        propagator.propagateConfig(config)
        assertUnchanged(before, config, "alternation step 1 (no resources)")
        propagator.propagateConfig(config, context.resources)
        assertUnchanged(before, config, "alternation step 2 (with resources)")
        propagator.propagateConfig(config)
        assertUnchanged(before, config, "alternation step 3 (no resources)")

        assertSame(
            "the alternation must not re-initialize the global Config singleton",
            config, Config.globalConfig()
        )
    }
}
