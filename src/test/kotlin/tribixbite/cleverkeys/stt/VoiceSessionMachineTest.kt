package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [VoiceSessionMachine]: the listening / partial / final / cancel lifecycle. */
class VoiceSessionMachineTest {

    private val partial = SpeechHypothesis("hello wor", 1, isFinal = false)
    private val final = SpeechHypothesis("hello world", 2, isFinal = true)

    @Test
    fun aFullSessionGoesIdlePreparingListeningFinishingIdle() {
        val m = VoiceSessionMachine()
        assertThat(m.on(VoiceEvent.Toggle)).containsExactly(VoiceAction.Prepare)
        assertThat(m.state).isEqualTo(VoiceState.PREPARING)
        assertThat(m.on(VoiceEvent.Ready)).containsExactly(VoiceAction.StartStreaming)
        assertThat(m.state).isEqualTo(VoiceState.LISTENING)
        assertThat(m.on(VoiceEvent.Partial(partial))).containsExactly(VoiceAction.Apply(partial))
        assertThat(m.on(VoiceEvent.Toggle)).containsExactly(VoiceAction.Finish)
        assertThat(m.state).isEqualTo(VoiceState.FINISHING)
        assertThat(m.on(VoiceEvent.Final(final))).containsExactly(VoiceAction.Apply(final), VoiceAction.Done).inOrder()
        assertThat(m.state).isEqualTo(VoiceState.IDLE)
        assertThat(m.isActive).isFalse()
    }

    @Test
    fun cancelFromAnyActiveStateAborts() {
        for (setup in listOf<(VoiceSessionMachine) -> Unit>(
            { it.on(VoiceEvent.Toggle) },
            { it.on(VoiceEvent.Toggle); it.on(VoiceEvent.Ready) },
            { it.on(VoiceEvent.Toggle); it.on(VoiceEvent.Ready); it.on(VoiceEvent.Toggle) },
        )) {
            val m = VoiceSessionMachine().also(setup)
            assertThat(m.on(VoiceEvent.Cancel)).containsExactly(VoiceAction.Abort)
            assertThat(m.state).isEqualTo(VoiceState.IDLE)
        }
        assertThat(VoiceSessionMachine().on(VoiceEvent.Cancel)).isEmpty()
    }

    /**
     * Saga 2026-10-29: a field change used to cancel, and cancel clears the composing tail
     * through the CURRENT connection — by then the NEW field's, so `setComposingText("")`
     * replaced whatever the new field had selected. The old field already finished its own
     * composition on blur, which the keyboard cannot undo. A field change releases the session
     * without any text operation (spec FR-8: field change stops; only cancel discards).
     */
    @Test
    fun aFieldChangeReleasesWithoutTouchingAnyField() {
        for (setup in listOf<(VoiceSessionMachine) -> Unit>(
            { it.on(VoiceEvent.Toggle) },
            { it.on(VoiceEvent.Toggle); it.on(VoiceEvent.Ready) },
            { it.on(VoiceEvent.Toggle); it.on(VoiceEvent.Ready); it.on(VoiceEvent.Toggle) },
        )) {
            val m = VoiceSessionMachine().also(setup)
            assertThat(m.on(VoiceEvent.FieldChanged)).containsExactly(VoiceAction.Release)
            assertThat(m.state).isEqualTo(VoiceState.IDLE)
            // A final result that arrives later belongs to the old field: ignored.
            assertThat(m.on(VoiceEvent.Final(final))).isEmpty()
        }
        assertThat(VoiceSessionMachine().on(VoiceEvent.FieldChanged)).isEmpty()
    }

    /** The service routes a new field to the release path, never to cancel (which writes). */
    @Test
    fun theServiceReleasesOnANewFieldInsteadOfCancelling() {
        val src = java.io.File("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt").readText()
        val body = src.substringAfter("override fun onStartInputView(").substringBefore("override fun ")
        assertThat(body).contains("_voiceInput?.onFieldChanged()")
        assertThat(body).doesNotContain("_voiceInput?.cancel()")
    }

    @Test
    fun aSecondTapWhileLoadingIsNeverMind() {
        val m = VoiceSessionMachine()
        m.on(VoiceEvent.Toggle)
        assertThat(m.on(VoiceEvent.Toggle)).containsExactly(VoiceAction.Abort)
        // The load finishing afterwards must not start streaming.
        assertThat(m.on(VoiceEvent.Ready)).isEmpty()
        assertThat(m.state).isEqualTo(VoiceState.IDLE)
    }

    @Test
    fun lateEventsAfterTheUserMovedOnAreIgnored() {
        val m = VoiceSessionMachine()
        m.on(VoiceEvent.Toggle); m.on(VoiceEvent.Ready); m.on(VoiceEvent.Cancel)
        assertThat(m.on(VoiceEvent.Partial(partial))).isEmpty()
        assertThat(m.on(VoiceEvent.Final(final))).isEmpty()
        assertThat(m.on(VoiceEvent.Failed(VoiceFailure.ENGINE_ERROR))).isEmpty()
        // A partial during FINISHING is stale too: only the final counts then.
        val f = VoiceSessionMachine()
        f.on(VoiceEvent.Toggle); f.on(VoiceEvent.Ready); f.on(VoiceEvent.Toggle)
        assertThat(f.on(VoiceEvent.Partial(partial))).isEmpty()
        assertThat(f.on(VoiceEvent.Toggle)).isEmpty()
    }

    @Test
    fun aFailureReportsAndReturnsToIdle() {
        val m = VoiceSessionMachine()
        m.on(VoiceEvent.Toggle)
        assertThat(m.on(VoiceEvent.Failed(VoiceFailure.MIC_UNAVAILABLE)))
            .containsExactly(VoiceAction.Report(VoiceFailure.MIC_UNAVAILABLE))
        assertThat(m.state).isEqualTo(VoiceState.IDLE)
        // And a new session can start.
        assertThat(m.on(VoiceEvent.Toggle)).containsExactly(VoiceAction.Prepare)
    }
}
