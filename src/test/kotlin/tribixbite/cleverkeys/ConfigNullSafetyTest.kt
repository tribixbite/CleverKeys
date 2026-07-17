package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure JVM tests for [Config]'s null-safe global accessors.
 *
 * [Config.initGlobalConfig] constructs a real [Config] (Android `Resources`,
 * `SharedPreferences`), so it can never run in a pure JVM test — the backing
 * `_globalConfig` field is therefore guaranteed `null` here. That makes this the
 * ideal place to assert the "not initialized" contract:
 *
 *  - [Config.globalConfigOrNull] returns `null` (branchable, no crash).
 *  - [Config.globalConfig] throws [IllegalStateException] with a diagnostic message.
 *  - [Config.globalPrefs] routes through [Config.globalConfig] and throws the same.
 */
class ConfigNullSafetyTest {

    @Test
    fun globalConfigOrNull_returnsNull_beforeInit() {
        assertThat(Config.globalConfigOrNull()).isNull()
    }

    @Test
    fun globalConfig_throwsIllegalState_beforeInit() {
        val ex = assertThrows(IllegalStateException::class.java) {
            Config.globalConfig()
        }
        assertThat(ex).hasMessageThat().contains("Config not initialized")
        assertThat(ex).hasMessageThat().contains("initGlobalConfig()")
    }

    @Test
    fun globalPrefs_throwsIllegalState_beforeInit() {
        val ex = assertThrows(IllegalStateException::class.java) {
            Config.globalPrefs()
        }
        assertThat(ex).hasMessageThat().contains("Config not initialized")
    }
}
