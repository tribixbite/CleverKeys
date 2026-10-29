package tribixbite.cleverkeys.stt

import tribixbite.cleverkeys.stt.moonshine.MoonshineV2StreamingEngine

/**
 * Maps a voice pack's `engine.id` + `engine.version` to the [SpeechEngineFactory] that runs it.
 *
 * This file is the ONLY place outside `stt/moonshine/` allowed to name a concrete engine
 * (`SttArchitectureDriftTest`): replacing the engine means writing the new factory, adding it to
 * [DEFAULT_FACTORIES] and publishing a pack that names its id. A pack for an engine this build
 * does not register — a pack built for a newer CleverKeys — is refused at import with
 * [VoicePackImportFailure.NeedsNewerApp], never half-loaded.
 *
 * Pure: the factories are only instantiated, never loaded, until a pack is opened.
 */
class SpeechEngineRegistry(factories: List<SpeechEngineFactory>) {

    private val byId: Map<String, SpeechEngineFactory>

    init {
        val ids = factories.map { it.engineId }
        require(ids.toSet().size == ids.size) { "duplicate engine ids: $ids" }
        require(factories.none { it.versions.isEmpty() }) { "an engine registered an empty version range" }
        byId = factories.associateBy { it.engineId }
    }

    /** Ids this build can run, for the log and the settings screen. */
    val engineIds: Set<String> get() = byId.keys

    /** What [resolve] decided. */
    sealed interface Resolution {
        data class Supported(val factory: SpeechEngineFactory) : Resolution
        data class Unsupported(val failure: VoicePackImportFailure) : Resolution
    }

    /** The factory for [engineId] at [engineVersion], or the refusal to show. */
    fun resolve(engineId: String, engineVersion: Int): Resolution {
        val factory = byId[engineId]
            ?: return Resolution.Unsupported(VoicePackImportFailure.NeedsNewerApp("unknown engine \"$engineId\""))
        if (engineVersion !in factory.versions) {
            return Resolution.Unsupported(
                VoicePackImportFailure.NeedsNewerApp("$engineId version $engineVersion not in ${factory.versions}")
            )
        }
        return Resolution.Supported(factory)
    }

    /** [resolve] for a parsed manifest. */
    fun resolve(manifest: VoicePackManifest): Resolution = resolve(manifest.engineId, manifest.engineVersion)

    companion object {
        /** Every engine this build ships. Add a successor engine here (and only here). */
        val DEFAULT_FACTORIES: List<SpeechEngineFactory> = listOf(
            MoonshineV2StreamingEngine.Factory,
        )

        /** The process-wide registry of [DEFAULT_FACTORIES]. */
        val DEFAULT: SpeechEngineRegistry by lazy { SpeechEngineRegistry(DEFAULT_FACTORIES) }
    }
}
