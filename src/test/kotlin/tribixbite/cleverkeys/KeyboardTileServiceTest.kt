package tribixbite.cleverkeys

import android.service.quicksettings.Tile
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Pins the Quick Settings tile announced in v1.2.6 and v1.2.8 — "Switch keyboards from the
 * notification shade" (#1113).
 *
 * Three things have to hold for that promise to be true on a user's device:
 *
 *  1. the tile is **offered by the OS at all** — a `<service>` with
 *     `BIND_QUICK_SETTINGS_TILE` and the `QS_TILE` action, exported so SystemUI can bind it
 *     ([manifestDeclaresTheQuickSettingsTile]). Drop any one of those and the tile silently
 *     disappears from the shade's edit screen;
 *  2. the tile **reports the right state**, so the shade shows CleverKeys as active only
 *     when it really is typing ([tileStateFollowsTheSelectedIme]) and TalkBack announces
 *     the matching action ([tileContentDescriptionMatchesTheState]);
 *  3. tapping it **opens the input-method picker** ([onClickOpensTheInputMethodPicker]).
 *
 * (3) is pinned at source level, deliberately and with no better option available off-device:
 * every [android.service.quicksettings.TileService] callback starts with a `super` call into
 * an android.jar stub that throws `RuntimeException("Stub!")`, so `onClick()` cannot be
 * executed in a JVM test at all. The state decision was extracted to
 * [KeyboardTileService.tileStateFor] precisely so that (2) does not have the same problem.
 *
 * Mock tier: `TileService`/`Tile` must resolve from android.jar. Run with
 * `scripts/gradle-guard.sh runMockTests -PtestClass=KeyboardTileServiceTest`.
 */
class KeyboardTileServiceTest {

    private val packageName = "tribixbite.cleverkeys"

    // =========================================================================
    // Tile state
    // =========================================================================

    @Test
    fun tileStateFollowsTheSelectedIme() {
        assertWithMessage("CleverKeys is the selected IME -> the shade tile reads as ON")
            .that(KeyboardTileService.tileStateFor("$packageName/$packageName.CleverKeysService", packageName))
            .isEqualTo(Tile.STATE_ACTIVE)

        assertWithMessage("another keyboard is selected -> the tile must read as OFF")
            .that(KeyboardTileService.tileStateFor("com.example.other/com.example.other.Ime", packageName))
            .isEqualTo(Tile.STATE_INACTIVE)

        assertWithMessage("no IME recorded in settings -> OFF, never a crash or a stale ON")
            .that(KeyboardTileService.tileStateFor(null, packageName))
            .isEqualTo(Tile.STATE_INACTIVE)

        assertWithMessage("an empty settings value is not a match")
            .that(KeyboardTileService.tileStateFor("", packageName))
            .isEqualTo(Tile.STATE_INACTIVE)

        // The two states must be distinguishable — a tile that reports the same value both
        // ways is worse than no tile (it lies about the current keyboard).
        assertWithMessage("STATE_ACTIVE and STATE_INACTIVE must not collapse")
            .that(Tile.STATE_ACTIVE).isNotEqualTo(Tile.STATE_INACTIVE)
    }

    @Test
    fun tileContentDescriptionMatchesTheState() {
        val active = KeyboardTileService.tileContentDescriptionFor(Tile.STATE_ACTIVE)
        val inactive = KeyboardTileService.tileContentDescriptionFor(Tile.STATE_INACTIVE)

        assertWithMessage("the active description must say CleverKeys is the current keyboard")
            .that(active).isEqualTo("CleverKeys is active. Tap to switch keyboard.")
        assertWithMessage("the inactive description must offer the switch")
            .that(inactive).isEqualTo("Tap to switch to CleverKeys keyboard.")
        assertWithMessage("TalkBack must be able to tell the two states apart")
            .that(active).isNotEqualTo(inactive)
    }

    // =========================================================================
    // Availability in the shade
    // =========================================================================

    @Test
    fun manifestDeclaresTheQuickSettingsTile() {
        val manifest = File("AndroidManifest.xml")
        check(manifest.isFile) { "AndroidManifest.xml not found — run with the project root as CWD." }
        val declaration = Regex(
            """<service\b[^>]*android:name="tribixbite\.cleverkeys\.KeyboardTileService"[\s\S]*?</service>"""
        ).find(manifest.readText())?.value
            ?: throw AssertionError("AndroidManifest.xml no longer declares KeyboardTileService")

        assertWithMessage("SystemUI refuses to bind a tile service without this permission")
            .that(declaration).contains("android:permission=\"android.permission.BIND_QUICK_SETTINGS_TILE\"")
        assertWithMessage("the tile is discovered through the QS_TILE action")
            .that(declaration).contains("android.service.quicksettings.action.QS_TILE")
        assertWithMessage("SystemUI is another process — the service must be exported")
            .that(Regex("""android:exported="true"""").containsMatchIn(declaration)).isTrue()
        assertWithMessage("the tile needs a label and icon to be pickable in the shade editor")
            .that(Regex("""android:label="@string/app_name"""").containsMatchIn(declaration)).isTrue()
        assertWithMessage("the tile needs an icon to be pickable in the shade editor")
            .that(Regex("""android:icon="@mipmap/[A-Za-z0-9_]+"""").containsMatchIn(declaration)).isTrue()
    }

    // =========================================================================
    // Tap behaviour (source-level; see class KDoc for why)
    // =========================================================================

    @Test
    fun onClickOpensTheInputMethodPicker() {
        val source = File("src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt")
        check(source.isFile) { "${source.path} not found — run with the project root as CWD." }
        val onClick = Regex("""override fun onClick\(\)\s*\{([\s\S]*?)\n    }""").find(source.readText())
            ?.groupValues?.get(1)
            ?: throw AssertionError("KeyboardTileService no longer overrides onClick()")

        assertWithMessage("tapping the tile must raise the system keyboard picker — that IS the feature")
            .that(onClick).contains("showInputMethodPicker()")
        assertWithMessage("if the IMM is unavailable the tap must still land somewhere useful")
            .that(onClick).contains("Settings.ACTION_INPUT_METHOD_SETTINGS")
        // startActivityAndCollapse(Intent) throws UnsupportedOperationException on API 34+.
        // Without the version split the fallback path crashes the shade on modern devices.
        assertWithMessage("the deprecated Intent overload must stay behind an API guard")
            .that(onClick).contains("Build.VERSION_CODES.UPSIDE_DOWN_CAKE")
        assertWithMessage("API 34+ must use the PendingIntent overload")
            .that(onClick).contains("startActivityAndCollapse(pendingIntent)")
    }
}
