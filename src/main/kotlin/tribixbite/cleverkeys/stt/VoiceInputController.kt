package tribixbite.cleverkeys.stt

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import tribixbite.cleverkeys.R
import java.io.File

/**
 * What the voice controller needs from the keyboard service. Implemented by
 * `VoiceInputBridge` (root package) over the InputConnection, the inline fields, the
 * suggestion bar and the keyboard view; a fake in tests.
 *
 * All calls happen on the main thread.
 */
interface VoiceHost {
    val context: Context
    val prefs: SharedPreferences

    /** How the focused field takes dictation right now. */
    fun fieldKind(): VoiceFieldKind

    /** The field's text before the caret (bounded), or null for BLIND fields (never read). */
    fun textBefore(kind: VoiceFieldKind): String?

    /** "Auto space after a suggestion" — the trailing-space convention. */
    fun trailingSpace(): Boolean

    /** Apply [ops] to the field of [kind]. Returns false when the field is gone. */
    fun apply(kind: VoiceFieldKind, ops: List<VoiceTextOp>): Boolean

    /** Show the listening indicator ([loading] while the model loads) with the current tail. */
    fun showListening(loading: Boolean, pendingText: String, onStop: () -> Unit, onCancel: () -> Unit)

    fun hideListening()

    /** A short message in the suggestion bar. */
    fun message(text: String)

    /** A TalkBack announcement (no-op when accessibility is off). */
    fun announce(text: String)

    /** Start the transparent permission activity. */
    fun requestMicPermission()

    /** Open Settings on the Voice input section. */
    fun openVoiceSettings()

    /** Open the app's system settings page (permission blocked). */
    fun openAppSystemSettings()

    /** The language the keyboard is typing in now (`en`, `de`, …), for picking a pack. */
    fun language(): String?

    /** Dictation changed the field: drop the keyboard's typed-word state. */
    fun onFieldEditedExternally()
}

/**
 * Voice input inside the keyboard: the key press → permission gate → model load → microphone →
 * streaming recognition → editor operations loop, driven by the pure [VoiceSessionMachine],
 * [MicPermissionGate] and [VoiceTextPlanner] (docs/specs/voice-input.md).
 *
 * Engine-neutral: the pack is resolved through [SpeechEngineRegistry]; nothing here names an
 * engine. Threads: the state machine, UI and editor writes run on the main thread; the model
 * loads on [worker]; each listening period has its own capture thread that reads the
 * microphone and runs recognition passes. Late results from an abandoned period are dropped by
 * a generation counter, and the machine ignores events that no longer fit its state.
 *
 * Privacy: audio lives only in the capture thread's buffers for the duration of a period; it is
 * never written, logged or sent (the app has no network permission).
 */
class VoiceInputController(
    private val host: VoiceHost,
    private val registry: SpeechEngineRegistry = SpeechEngineRegistry.DEFAULT,
    private val packs: () -> VoicePackManager = { VoicePacks.manager(host.context) },
    // The recorder is drained on its own thread so a slow recognition pass never overruns it.
    private val capture: (sampleRate: Int) -> PcmSource = { BufferedPcmSource(MicrophoneCapture(it), maxSamples = it * MAX_BUFFER_SECONDS) },
) {
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("voice-input").apply { start() }
    private val worker = Handler(workerThread.looper)

    private val machine = VoiceSessionMachine()

    /** Bumped for every listening period; a callback carrying an older value is stale. */
    private var generation = 0

    private var kind = VoiceFieldKind.TEXT
    private var planner: VoiceTextPlanner? = null

    // Worker-owned engine cache (loaded on first use, released when idle).
    private var engine: SpeechEngine? = null
    private var enginePack: File? = null

    @Volatile
    private var session: SpeechSession? = null

    @Volatile
    private var stopRequested = false

    private val releaseIdleEngine = Runnable { worker.post { releaseEngine() } }

    val state: VoiceState get() = machine.state

    /** The voice_input key / command. */
    fun onKeyPressed() {
        if (machine.isActive) {
            dispatch(VoiceEvent.Toggle)
            return
        }
        val fieldKind = host.fieldKind()
        val facts = VoicePacks.facts(host.context, host.prefs, fieldRefused = fieldKind == VoiceFieldKind.PASSWORD)
        when (MicPermissionGate.decide(MicPermissionGate.Trigger.KEY_PRESS, facts)) {
            MicPermissionGate.Decision.PROCEED -> {
                kind = fieldKind
                dispatch(VoiceEvent.Toggle)
            }
            MicPermissionGate.Decision.REQUEST_PERMISSION -> host.requestMicPermission()
            MicPermissionGate.Decision.EXPLAIN_BLOCKED ->
                explainThenOpen(MicPermissionGate.Decision.EXPLAIN_BLOCKED, R.string.voice_msg_blocked, host::openAppSystemSettings)
            MicPermissionGate.Decision.EXPLAIN_DISABLED ->
                explainThenOpen(MicPermissionGate.Decision.EXPLAIN_DISABLED, R.string.voice_msg_disabled, host::openVoiceSettings)
            MicPermissionGate.Decision.NEED_PACK ->
                explainThenOpen(MicPermissionGate.Decision.NEED_PACK, R.string.voice_msg_need_pack, host::openVoiceSettings)
            MicPermissionGate.Decision.FIELD_REFUSED -> host.message(text(R.string.voice_msg_password))
        }
    }

    private var lastExplained: MicPermissionGate.Decision? = null
    private var lastExplainedAt = 0L

    /**
     * First press: say what is missing in the bar (opening a screen would hide the keyboard
     * and the message with it). A second press while the message shows opens the screen that
     * fixes it.
     */
    private fun explainThenOpen(decision: MicPermissionGate.Decision, message: Int, open: () -> Unit) {
        val now = android.os.SystemClock.uptimeMillis()
        if (lastExplained == decision && now - lastExplainedAt < EXPLAIN_REPEAT_MILLIS) {
            lastExplained = null
            open()
            return
        }
        lastExplained = decision
        lastExplainedAt = now
        val msg = text(message) + " " + text(R.string.voice_msg_tap_again)
        host.message(msg)
        host.announce(msg)
    }

    /** The bar's ✕, keyboard hidden: abandon any listening period and drop the composing tail. */
    fun cancel() {
        if (machine.isActive) dispatch(VoiceEvent.Cancel)
    }

    /**
     * Another field took the input (`onStartInputView`): end the period without writing.
     * The current connection is the NEW field's; the old field already kept its underlined
     * words when it lost focus, and the keyboard can no longer reach it (spec FR-8).
     */
    fun onFieldChanged() {
        if (machine.isActive) dispatch(VoiceEvent.FieldChanged)
    }

    /** Service destroyed: cancel and free the model and the worker thread. */
    fun release() {
        cancel()
        main.removeCallbacks(releaseIdleEngine)
        worker.post {
            releaseEngine()
            workerThread.quitSafely()
        }
    }

    // ---- state machine plumbing ----------------------------------------------------------

    private fun dispatch(event: VoiceEvent) {
        for (action in machine.on(event)) perform(action)
    }

    private fun perform(action: VoiceAction) {
        when (action) {
            VoiceAction.Prepare -> prepare()
            VoiceAction.StartStreaming -> startStreaming()
            // The capture thread sees the flag, closes the recorder and decodes the rest; the bar
            // keeps showing the last partial until the final result replaces it.
            VoiceAction.Finish -> stopRequested = true
            is VoiceAction.Apply -> applyHypothesis(action.hypothesis)
            VoiceAction.Abort -> {
                abortCapture()
                planner?.let { host.apply(kind, it.onCancel()) }
                endPeriod()
                host.announce(text(R.string.voice_announce_cancelled))
            }
            VoiceAction.Release -> {
                abortCapture()
                endPeriod()
                host.announce(text(R.string.voice_announce_stopped))
            }
            VoiceAction.Done -> {
                endPeriod()
                host.announce(text(R.string.voice_announce_stopped))
            }
            is VoiceAction.Report -> {
                abortCapture()
                planner?.let { host.apply(kind, it.onCancel()) }
                endPeriod()
                val msg = text(failureMessage(action.reason))
                host.message(msg)
                host.announce(msg)
            }
        }
    }

    private fun prepare() {
        val gen = ++generation
        stopRequested = false
        planner = VoiceTextPlanner(kind, host.textBefore(kind), matchSentenceCase = true, trailingSpace = host.trailingSpace())
        host.showListening(loading = true, pendingText = "", onStop = ::onKeyPressed, onCancel = ::cancel)
        main.removeCallbacks(releaseIdleEngine)
        val language = host.language()
        worker.post {
            val failure = try {
                ensureEngine(language)
                null
            } catch (e: NoPackException) {
                VoiceFailure.NO_PACK
            } catch (e: Exception) {
                Log.e(TAG, "Voice model load failed", e)
                VoiceFailure.PACK_UNREADABLE
            }
            main.post {
                if (gen != generation) return@post
                dispatch(if (failure == null) VoiceEvent.Ready else VoiceEvent.Failed(failure))
            }
        }
    }

    /** Worker thread: load (or reuse) the engine for the active pack. */
    private fun ensureEngine(language: String?) {
        val pack = packs().activePack(language) ?: throw NoPackException()
        if (engine != null && enginePack == pack.dir) return
        releaseEngine()
        val factory = when (val r = registry.resolve(pack.manifest)) {
            is SpeechEngineRegistry.Resolution.Supported -> r.factory
            is SpeechEngineRegistry.Resolution.Unsupported -> throw SpeechEngineException(r.failure.logMessage)
        }
        engine = factory.load(pack.dir, SpeechEngineOptions())
        enginePack = pack.dir
        Log.i(TAG, "Voice engine loaded: ${pack.manifest.id} ${engine?.memoryReport()}")
    }

    private fun releaseEngine() {
        engine?.close()
        engine = null
        enginePack = null
    }

    private fun startStreaming() {
        val gen = generation
        val eng = engine ?: run {
            dispatch(VoiceEvent.Failed(VoiceFailure.PACK_UNREADABLE))
            return
        }
        host.showListening(loading = false, pendingText = "", onStop = ::onKeyPressed, onCancel = ::cancel)
        host.announce(text(R.string.voice_announce_listening))
        val s = eng.newSession().also { session = it }
        val source = capture(eng.sampleRate)
        val limitNanos = minOf(MAX_LISTEN_SECONDS, eng.maxUtteranceSeconds.coerceAtLeast(1).toLong()) * 1_000_000_000L
        Thread({ captureLoop(gen, s, source, eng.sampleRate, limitNanos) }, "voice-capture").start()
    }

    /** Capture thread: microphone → session; results posted to the main thread. */
    private fun captureLoop(gen: Int, s: SpeechSession, source: PcmSource, sampleRate: Int, limitNanos: Long) {
        // Large enough to take a whole backlog in one read: one accept = one decode, so a
        // recogniser that fell behind catches up (BufferedPcmSource returns what is queued).
        val buffer = FloatArray(sampleRate * READ_MAX_SECONDS)
        val started = System.nanoTime()
        try {
            try {
                source.start()
            } catch (e: MicUnavailableException) {
                Log.w(TAG, "Microphone unavailable: ${e.message}")
                post(gen) { dispatch(VoiceEvent.Failed(VoiceFailure.MIC_UNAVAILABLE)) }
                return
            } catch (e: SecurityException) {
                post(gen) { dispatch(VoiceEvent.Failed(VoiceFailure.PERMISSION_MISSING)) }
                return
            }
            var autoStopped = false
            while (!stopRequested && !s.isCancelled) {
                val n = source.read(buffer)
                if (n < 0) {
                    post(gen) { dispatch(VoiceEvent.Failed(VoiceFailure.MIC_UNAVAILABLE)) }
                    return
                }
                if (n == 0) break
                val h = s.accept(if (n == buffer.size) buffer.copyOf() else buffer.copyOf(n))
                if (h != null) post(gen) { dispatch(VoiceEvent.Partial(h)) }
                if (!autoStopped && System.nanoTime() - started > limitNanos) {
                    autoStopped = true
                    post(gen) { if (machine.state == VoiceState.LISTENING) dispatch(VoiceEvent.Toggle) }
                }
            }
            source.close()
            if (s.isCancelled) return
            // Stop pressed: the recorder is closed. Audio spoken before stop that the recogniser
            // had not reached yet is still queued — decode it, then the final pass.
            while (!s.isCancelled) {
                val n = source.read(buffer)
                if (n <= 0) break
                s.accept(buffer.copyOf(n))
            }
            if (s.isCancelled) return
            val final = s.finish()
            post(gen) { dispatch(VoiceEvent.Final(final)) }
        } catch (e: Exception) {
            Log.e(TAG, "Voice recognition failed", e)
            post(gen) { dispatch(VoiceEvent.Failed(VoiceFailure.ENGINE_ERROR)) }
        } finally {
            runCatching { source.close() }
            s.close()
        }
    }

    private fun post(gen: Int, block: () -> Unit) {
        main.post { if (gen == generation) block() }
    }

    private fun applyHypothesis(h: SpeechHypothesis) {
        val p = planner ?: return
        val ops = p.onHypothesis(h)
        if (ops.isNotEmpty()) {
            if (!host.apply(kind, ops)) {
                // The field went away under us: stop listening.
                dispatch(VoiceEvent.Cancel)
                return
            }
            host.onFieldEditedExternally()
        }
        if (!h.isFinal && machine.state == VoiceState.LISTENING) {
            host.showListening(loading = false, pendingText = h.pendingWords.joinToString(" "), onStop = ::onKeyPressed, onCancel = ::cancel)
        }
    }

    private fun abortCapture() {
        session?.cancel()
        stopRequested = true
    }

    private fun endPeriod() {
        generation++
        session = null
        planner = null
        host.hideListening()
        main.removeCallbacks(releaseIdleEngine)
        main.postDelayed(releaseIdleEngine, ENGINE_IDLE_MILLIS)
    }

    private fun failureMessage(reason: VoiceFailure): Int = when (reason) {
        VoiceFailure.NO_PACK -> R.string.voice_msg_need_pack
        VoiceFailure.PACK_UNREADABLE -> R.string.voice_msg_pack_unreadable
        VoiceFailure.ENGINE_ERROR -> R.string.voice_msg_engine_error
        VoiceFailure.MIC_UNAVAILABLE -> R.string.voice_msg_mic_unavailable
        VoiceFailure.PERMISSION_MISSING -> R.string.voice_msg_permission
        VoiceFailure.FIELD_REFUSED -> R.string.voice_msg_password
    }

    private fun text(id: Int): String = host.context.getString(id)

    private class NoPackException : Exception()

    companion object {
        private const val TAG = "VoiceInput"

        /** Largest single read from the buffered microphone (a backlog decoded in one pass). */
        private const val READ_MAX_SECONDS = 8

        /** Audio the buffered microphone may hold: the 60 s period plus slack, ~4.5 MB. */
        private const val MAX_BUFFER_SECONDS = 70

        /**
         * One listening period stops by itself after a minute: every pass re-decodes the whole
         * utterance, so cost grows with its length (research §5.2), and a forgotten open
         * microphone must not run on.
         */
        private const val MAX_LISTEN_SECONDS = 60L

        /** A second key press within this window opens the screen an explanation names. */
        private const val EXPLAIN_REPEAT_MILLIS = 4_000L

        /** Free the model this long after the last period (RAM matters in an IME process). */
        private const val ENGINE_IDLE_MILLIS = 60_000L
    }
}
