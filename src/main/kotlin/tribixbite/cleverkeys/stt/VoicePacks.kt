package tribixbite.cleverkeys.stt

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import tribixbite.cleverkeys.BuildConfig
import java.io.File

/**
 * Android glue for voice input: the process-wide [VoicePackManager] over
 * `files/voicepacks/`, the preference keys, and the microphone-permission facts
 * [MicPermissionGate] reads. Shared by the settings screen, the permission activity and the
 * keyboard service so they can never disagree about a key name or a directory.
 */
object VoicePacks {
    private const val TAG = "VoicePacks"

    /** Settings switch (user-visible, backed up). Default OFF (maintainer decision 2026-10-29). */
    const val PREF_ENABLED = "voice_input_enabled"
    const val DEFAULT_ENABLED = false

    /**
     * INTERNAL, never exported: the last microphone request was denied with "don't ask again",
     * so only the system settings page can grant it. Cleared when the permission is granted.
     */
    const val PREF_PERMISSION_BLOCKED = "voice_mic_permission_blocked"

    @SuppressLint("StaticFieldLeak") // application context only
    @Volatile
    private var manager: VoicePackManager? = null

    /** The process-wide manager (application context; recovery runs once). */
    fun manager(context: Context): VoicePackManager = manager ?: synchronized(this) {
        manager ?: VoicePackManager(
            root = File(context.applicationContext.filesDir, VoicePackManager.DIR_NAME),
            scratchDir = context.applicationContext.cacheDir,
            appVersionCode = BuildConfig.VERSION_CODE,
            log = { Log.i(TAG, it) },
        ).also { manager = it }
    }

    fun isEnabled(prefs: SharedPreferences): Boolean = try {
        prefs.getBoolean(PREF_ENABLED, DEFAULT_ENABLED)
    } catch (e: ClassCastException) {
        DEFAULT_ENABLED
    }

    fun hasMicPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isPermissionBlocked(prefs: SharedPreferences): Boolean = try {
        prefs.getBoolean(PREF_PERMISSION_BLOCKED, false)
    } catch (e: ClassCastException) {
        false
    }

    /** The gate's facts for [context] right now. */
    fun facts(context: Context, prefs: SharedPreferences, fieldRefused: Boolean = false): MicPermissionGate.Facts =
        MicPermissionGate.Facts(
            voiceEnabled = isEnabled(prefs),
            packInstalled = manager(context).installedPacks().isNotEmpty(),
            permissionGranted = hasMicPermission(context),
            permissionBlocked = isPermissionBlocked(prefs),
            fieldRefused = fieldRefused,
        )
}
