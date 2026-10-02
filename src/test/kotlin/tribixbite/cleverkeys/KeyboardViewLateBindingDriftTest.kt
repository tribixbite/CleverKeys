package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Audit A-2 (2026-09-06, P1) — theme change orphaned every constructor-captured
 * `Keyboard2View` reference.
 *
 * `CleverKeysService.onThemeChanged` (and the stale-theme branch of `onStartInputView`)
 * replace `_keyboardView` with a freshly inflated view, but KeyboardReceiver, LayoutBridge,
 * InputCoordinator, ConfigPropagator, SuggestionBridge and the component graph all captured
 * the ORIGINAL view as an immutable constructor val. After any theme change the layout-switch
 * keys (123/ABC/cycle) and the autocap shift indicator dispatched into the detached old view
 * until process restart.
 *
 * Fix: the view seam is LATE-BOUND — every holder takes a `() -> Keyboard2View` provider and
 * resolves it per access, so calls always land on the service's live view.
 *
 * Two pins:
 *  1. Structural ratchet: none of the wiring/coordinator classes may declare a
 *     `Keyboard2View`-typed field (constructor-captured identity). Only providers are legal.
 *  2. Behavioral: the receiver consults the provider AT DISPATCH TIME (SWITCH_NUMERIC /
 *     set_shift_state), not at construction — proven with a marker-throwing provider, because
 *     a real `Keyboard2View` cannot exist at this tier (its companion `<clinit>` allocates
 *     `android.graphics.RectF`, which the android.jar stub throws on — the direct
 *     "setKeyboard lands on view B" assertion is therefore instrumented-only).
 *
 * RED (2026-09-06, pre-fix): pin 1 failed for all six classes (each declared a
 * `Keyboard2View` field); pin 2 failed with NoSuchFieldException("keyboardViewProvider") —
 * the receiver had no provider seam at all.
 */
class KeyboardViewLateBindingDriftTest {

    private val objenesis = org.objenesis.ObjenesisStd()

    /** Classes on the A-2 blast radius: everything wired once at onCreate that needs the view. */
    private val viewHolders = listOf(
        KeyboardReceiver::class.java,
        LayoutBridge::class.java,
        InputCoordinator::class.java,
        ConfigPropagator::class.java,
        SuggestionBridge::class.java,
        KeyboardComponentGraph::class.java,
    )

    @Test
    fun noClassOnTheViewSeamCapturesAKeyboard2ViewField() {
        for (cls in viewHolders) {
            val captured = cls.declaredFields
                .filter { it.type.name == "tribixbite.cleverkeys.Keyboard2View" }
                .map { it.name }
            assertWithMessage(
                "${cls.simpleName} declares Keyboard2View field(s) $captured — a captured view " +
                    "goes stale the moment onThemeChanged replaces the service's _keyboardView " +
                    "(audit A-2). Hold a `() -> Keyboard2View` provider and resolve per access."
            ).that(captured).isEmpty()
        }
    }

    @Test
    fun receiverConsultsTheProviderAtDispatchTime() {
        val receiver = objenesis.newInstance(KeyboardReceiver::class.java)

        val layoutManager = mockk<LayoutManager>()
        every { layoutManager.loadNumpad(any()) } returns mockk(relaxed = true)
        seedField(receiver, "layoutManager", layoutManager)

        class ProviderConsulted : RuntimeException("provider consulted at dispatch time")
        val provider: () -> Keyboard2View = { throw ProviderConsulted() }
        try {
            seedField(receiver, "keyboardViewProvider", provider)
        } catch (e: NoSuchFieldException) {
            throw AssertionError(
                "KeyboardReceiver has no keyboardViewProvider seam — the view is constructor-" +
                    "captured, so every post-theme-change SWITCH_* / shift-state call lands on " +
                    "the detached old view (audit A-2).",
                e
            )
        }

        // SWITCH_NUMERIC resolves the numpad first, then must reach for the LIVE view.
        assertThrows(ProviderConsulted::class.java) {
            receiver.handle_event_key(KeyValue.Event.SWITCH_NUMERIC)
        }
        // Autocap's shift-state updates take the same seam.
        assertThrows(ProviderConsulted::class.java) {
            receiver.set_shift_state(true, false)
        }
    }

    /**
     * GH #145/#138: a cold view must get its service handle even with both prediction
     * switches off and no predictor built yet. Execute the real graph wiring and the
     * real view assignment; only the coordinator getter avoids constructing model services.
     */
    @Test
    fun coldViewGetsServiceHandleWithPredictionAndSwipeTypingDisabled() {
        val config = objenesis.newInstance(Config::class.java).apply {
            word_prediction_enabled = false
            swipe_typing_enabled = false
        }
        val service = mockk<CleverKeysService>(relaxed = true)
        val coordinator = mockk<PredictionCoordinator>()
        every { coordinator.getWordPredictor() } returns null
        val graph = spyk(objenesis.newInstance(KeyboardComponentGraph::class.java))
        every { graph.predictionCoordinator } returns coordinator
        seedField(graph, "config", config)
        seedField(graph, "service", service)

        // Each newly inflated view starts without a handle, including a replacement
        // after a theme change. The provider must resolve the current instance each time.
        repeat(2) {
            val view = objenesis.newInstance(Keyboard2View::class.java)
            val handle = Keyboard2View::class.java.getDeclaredField("_keyboard2").apply {
                isAccessible = true
            }
            assertThat(handle.get(view)).isNull()
            seedField(graph, "keyboardViewProvider", { view })

            graph.wireSwipeTypingComponents()

            assertThat(handle.get(view)).isSameInstanceAs(service)
        }
        verify(exactly = 2) { coordinator.getWordPredictor() }
        verify(exactly = 0) { coordinator.initialize() }
    }

    private fun seedField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
