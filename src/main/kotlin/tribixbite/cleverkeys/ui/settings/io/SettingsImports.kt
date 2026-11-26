package tribixbite.cleverkeys.ui.settings.io

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.SettingsActivity

/**
 * What a Settings file operation does once it has finished: show its result, reload the shown
 * settings, refresh a list. It runs on the main thread against the Settings activity in FRONT
 * when the operation ends, which after a rotation is not the one that started it, so it must
 * use its receiver and never a captured activity.
 */
internal typealias SettingsFollowUp = SettingsActivity.() -> Unit

/** Spell a [SettingsFollowUp] literal where Kotlin cannot infer the receiver type. */
internal fun followUp(block: SettingsFollowUp): SettingsFollowUp = block

/**
 * The Settings file operations that outlive the activity (pre-tag review 2026-11-25 S2), one
 * [ImportTracker] per screen area that shows its own progress. Each runs one operation at a time.
 */
internal object SettingsImports {
    /**
     * Backup & Restore's exports and imports (settings, full backup, clipboard, dictionary).
     * They share the one full-screen progress overlay, `BackupRestoreViewModel.isProcessing`,
     * which mirrors [ImportTracker.State.inProgress].
     */
    val files = ImportTracker<Unit, SettingsFollowUp>()

    /** The GIF section's pack import (`gifImportInProgress` mirrors it). */
    val gifPack = ImportTracker<Unit, SettingsFollowUp>()

    /** The Multi-Language section's language-pack import. */
    val languagePack = ImportTracker<Unit, SettingsFollowUp>()

    /** The Clipboard section's custom sanitization-rules import (a small JSON file). */
    val clipboardRules = ImportTracker<Unit, SettingsFollowUp>()

    /** Every tracker a Settings activity registers with, the voice-pack import's included. */
    private val all: List<ImportTracker<*, *>>
        get() = listOf(files, gifPack, languagePack, clipboardRules, VoicePackImportTracker.shared)

    fun attach(owner: SettingsActivity) = all.forEach { it.attach(owner) }

    fun detach(owner: SettingsActivity, finishing: Boolean) = all.forEach { it.detach(owner, finishing) }
}

/**
 * Run a Backup & Restore file operation in [SettingsImports.files]. [work] runs off the main
 * thread and returns the follow-up; a failure it throws becomes [onFailure]'s follow-up, a
 * cancellation never does ([catchingFailures]). Work whose effect must not depend on Settings
 * still being open (protected-storage copy, the dictionary-reload broadcast) belongs in [work]:
 * a follow-up is dropped when the user has left Settings for good.
 *
 * [work] may hold the starting activity's `backupRestoreManager` and `prefs` until it ends (they
 * are what it operates on); everything shown goes through the follow-up's receiver.
 */
internal fun runSettingsFileOperation(
    work: suspend () -> SettingsFollowUp,
    onFailure: (Exception) -> SettingsFollowUp,
): Boolean = SettingsImports.files.start(null) {
    ImportTracker.Outcome(null, catchingFailures({ work() }, onFailure))
}

/**
 * Register this activity with every Settings tracker and, while it is started, mirror their
 * progress into the screen and run each finished operation's follow-up once. Called from
 * onCreate; balanced by [SettingsImports.detach] in onDestroy.
 */
internal fun SettingsActivity.observeSettingsImports() {
    SettingsImports.attach(this)
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                SettingsImports.files.state.collect { state ->
                    backupRestoreViewModel.isProcessing = state.inProgress
                    if (state.event != null) SettingsImports.files.consumeEvent()?.invoke(this@observeSettingsImports)
                }
            }
            launch {
                SettingsImports.gifPack.state.collect { state ->
                    gifImportInProgress = state.inProgress
                    if (state.event != null) SettingsImports.gifPack.consumeEvent()?.invoke(this@observeSettingsImports)
                }
            }
            launch {
                SettingsImports.languagePack.state.collect { state ->
                    if (state.event != null) SettingsImports.languagePack.consumeEvent()?.invoke(this@observeSettingsImports)
                }
            }
            launch {
                SettingsImports.clipboardRules.state.collect { state ->
                    if (state.event != null) SettingsImports.clipboardRules.consumeEvent()?.invoke(this@observeSettingsImports)
                }
            }
        }
    }
    observeVoicePackImport(this)
}
