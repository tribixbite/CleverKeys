package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * ARC-011 pure-JVM tests for [ClipboardProvenance] — the label decision behind the expanded
 * clipboard entry's "Private copy · via …" line.
 *
 * The private-copy threat review (design §6.2/§6.3/§6.6) accepted the content-injection risk
 * of the exported `PROCESS_TEXT` activity *because* injected entries are attributable in the
 * UI. These tests pin the four cases that acceptance depends on: a resolvable app, an
 * uninstalled app, the `direct-launch` sentinel, and "no provenance → no line".
 */
class ClipboardProvenanceTest {

    /** Resolver that knows about exactly one installed app. */
    private val installed: (String) -> String? = { pkg ->
        if (pkg == "com.example.notes") "Notes" else null
    }

    private fun label(sourcePackage: String?): String? =
        ClipboardProvenance.label(sourcePackage, DIRECT_LAUNCH_LABEL, installed)

    // ── No provenance → no line (never an empty "via") ─────────────────────

    @Test
    fun nullSourcePackage_rendersNothing() {
        // Pre-V5 rows and ordinary OS-clipboard captures store NULL source_package.
        assertThat(label(null)).isNull()
    }

    @Test
    fun blankSourcePackage_rendersNothing() {
        // Defensive: a whitespace-only column value must not produce "via  ".
        assertThat(label("   ")).isNull()
    }

    // ── Installed app → its user-visible label ─────────────────────────────

    @Test
    fun installedPackage_resolvesToAppLabel() {
        assertThat(label("com.example.notes")).isEqualTo("Notes")
    }

    // ── Uninstalled app → the raw package (attribution survives uninstall) ─

    @Test
    fun unknownPackage_fallsBackToRawPackageName() {
        // NameNotFoundException is modeled as a null resolver result. Showing the raw package
        // is strictly better than showing nothing: the entry stays attributable after the
        // source app is removed, which is exactly the forensic case §6.3 banks on.
        assertThat(label("com.gone.app")).isEqualTo("com.gone.app")
    }

    @Test
    fun resolverReturningBlank_fallsBackToRawPackageName() {
        // A pathological app label (empty/whitespace) must not render as "via ".
        val blankResolver: (String) -> String? = { "  " }
        assertThat(ClipboardProvenance.label("com.blank.app", DIRECT_LAUNCH_LABEL, blankResolver))
            .isEqualTo("com.blank.app")
    }

    // ── The injection tell ─────────────────────────────────────────────────

    @Test
    fun directLaunchSentinel_usesTheLocalizedSentinelLabel() {
        // A launch without startActivityForResult (which the real selection toolbar never does)
        // is the strong programmatic-injection tell — it must NOT be resolved as a package name.
        assertThat(label("direct-launch")).isEqualTo(DIRECT_LAUNCH_LABEL)
    }

    @Test
    fun directLaunchSentinel_isNotPassedToThePackageManager() {
        var queried: String? = null
        ClipboardProvenance.label("direct-launch", DIRECT_LAUNCH_LABEL) { pkg ->
            queried = pkg
            "should never be used"
        }
        assertThat(queried).isNull()
    }

    // ── Cross-file invariant ───────────────────────────────────────────────

    @Test
    fun sentinelMatchesTheWriterInPrivateCopyProcessTextActivity() {
        // The sentinel is written in PrivateCopyProcessTextActivity (a private const, so it
        // cannot be referenced) and read here. A silent divergence would make every injected
        // entry render as a bogus "app label", quietly defeating the §6.3 detection answer.
        val writer = File(
            "src/main/kotlin/tribixbite/cleverkeys/PrivateCopyProcessTextActivity.kt"
        )
        check(writer.exists()) {
            "PrivateCopyProcessTextActivity.kt not found at ${writer.absolutePath} — " +
                "this test must run with the project root as CWD."
        }
        val declared = Regex("""DIRECT_LAUNCH\s*=\s*"([^"]+)"""").find(writer.readText())
        check(declared != null) { "DIRECT_LAUNCH constant not found in PrivateCopyProcessTextActivity.kt" }
        assertThat(declared.groupValues[1]).isEqualTo(ClipboardProvenance.DIRECT_LAUNCH)
    }

    private companion object {
        /** Stands in for R.string.clipboard_provenance_direct_launch (no Android on this runner). */
        const val DIRECT_LAUNCH_LABEL = "direct launch (not a selection menu)"
    }
}
