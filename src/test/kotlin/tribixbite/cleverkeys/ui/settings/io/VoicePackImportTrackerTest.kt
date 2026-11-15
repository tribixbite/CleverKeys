package tribixbite.cleverkeys.ui.settings.io

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Test

/**
 * Review 2026-11-14 (low): a voice-pack import ran in the Settings activity's lifecycleScope with
 * its status in activity fields, so a rotation during an import lost "Importing…", the result
 * and the microphone request that follows a successful import. [VoicePackImportTracker] holds
 * the import for the process; an activity created after the rotation reads the same state.
 */
class VoicePackImportTrackerTest {

    private fun tracker() = VoicePackImportTracker(Dispatchers.Default)

    @Test
    fun anImportStartedByOneActivityIsSeenByTheNextOne() = runBlocking {
        val tracker = tracker()
        val gate = CompletableDeferred<Unit>()
        tracker.start("Importing…") {
            gate.await()
            VoiceImportStatus.Ok("Imported Moonshine") to true
        }
        // The first activity is destroyed here (rotation); the new one reads the tracker.
        assertThat(tracker.state.value.inProgress).isTrue()
        assertThat(tracker.state.value.status).isEqualTo(VoiceImportStatus.Ok("Importing…"))

        gate.complete(Unit)
        val done = withTimeout(5_000) { tracker.state.first { !it.inProgress } }
        assertThat(done.status).isEqualTo(VoiceImportStatus.Ok("Imported Moonshine"))
        assertThat(done.micRequestPending).isTrue()
    }

    @Test
    fun theMicrophoneRequestIsHandedOutOnce() = runBlocking {
        val tracker = tracker()
        tracker.start("Importing…") { VoiceImportStatus.Ok("ok") to true }
        withTimeout(5_000) { tracker.state.first { !it.inProgress } }

        assertThat(tracker.consumeMicRequest()).isTrue()
        assertThat(tracker.consumeMicRequest()).isFalse()
    }

    @Test
    fun aFailedImportAsksForNothing() = runBlocking {
        val tracker = tracker()
        tracker.start("Importing…") { VoiceImportStatus.Failed("Import failed: bad pack") to false }
        val done = withTimeout(5_000) { tracker.state.first { !it.inProgress } }

        assertThat(done.status).isEqualTo(VoiceImportStatus.Failed("Import failed: bad pack"))
        assertThat(tracker.consumeMicRequest()).isFalse()
    }

    @Test
    fun aSecondStartWhileImportingIsIgnored() = runBlocking {
        val tracker = tracker()
        val gate = CompletableDeferred<Unit>()
        assertThat(tracker.start("Importing…") { gate.await(); VoiceImportStatus.Ok("first") to true }).isTrue()
        assertThat(tracker.start("Importing…") { VoiceImportStatus.Ok("second") to true }).isFalse()

        gate.complete(Unit)
        val done = withTimeout(5_000) { tracker.state.first { !it.inProgress } }
        assertThat(done.status).isEqualTo(VoiceImportStatus.Ok("first"))
    }

    @Test
    fun anErrorEscapingTheImportStillReleasesTheButton() = runBlocking {
        val tracker = tracker()
        tracker.start("Importing…") { throw StackOverflowError("simulated") }
        val done = withTimeout(5_000) { tracker.state.first { !it.inProgress } }
        assertThat(done.status).isNull()
    }

    @Test
    fun clearingTheStatusKeepsARunningImport() = runBlocking {
        val tracker = tracker()
        val gate = CompletableDeferred<Unit>()
        tracker.start("Importing…") { gate.await(); VoiceImportStatus.Ok("ok") to true }
        tracker.clearStatus()
        assertThat(tracker.state.value.status).isEqualTo(VoiceImportStatus.Ok("Importing…"))
        gate.complete(Unit)
        withTimeout(5_000) { tracker.state.first { !it.inProgress } }
        tracker.clearStatus()
        assertThat(tracker.state.value.status).isNull()
    }

    /**
     * Follow-up review 2026-11-15: leaving Settings for good kept an unmade microphone request
     * (and a running import's result) for the next visit, maybe days later.
     */
    @Test
    fun leavingSettingsDropsAFinishedImportsMessageAndRequest() = runBlocking {
        val tracker = tracker()
        tracker.start("Importing…") { VoiceImportStatus.Ok("ok") to true }
        withTimeout(5_000) { tracker.state.first { !it.inProgress } }

        tracker.leave()

        assertThat(tracker.state.value.status).isNull()
        assertThat(tracker.consumeMicRequest()).isFalse()
    }

    @Test
    fun anImportRunningWhenSettingsIsLeftEndsSilently() = runBlocking {
        val tracker = tracker()
        val gate = CompletableDeferred<Unit>()
        tracker.start("Importing…") { gate.await(); VoiceImportStatus.Ok("ok") to true }
        tracker.leave()
        gate.complete(Unit)
        val done = withTimeout(5_000) { tracker.state.first { !it.inProgress } }

        assertThat(done.status).isNull()
        assertThat(tracker.consumeMicRequest()).isFalse()
    }

    @Test
    fun comingBackDuringTheImportWantsItsResultAgain() = runBlocking {
        val tracker = tracker()
        val gate = CompletableDeferred<Unit>()
        tracker.start("Importing…") { gate.await(); VoiceImportStatus.Ok("ok") to true }
        tracker.leave()
        tracker.attach()  // Settings opened again before the import ended
        gate.complete(Unit)
        val done = withTimeout(5_000) { tracker.state.first { !it.inProgress } }

        assertThat(done.status).isEqualTo(VoiceImportStatus.Ok("ok"))
        assertThat(tracker.consumeMicRequest()).isTrue()
    }
}
