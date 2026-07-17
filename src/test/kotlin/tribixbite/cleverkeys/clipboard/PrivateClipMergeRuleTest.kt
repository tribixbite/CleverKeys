package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.PrivateClipMergeRule

/**
 * #156 pure-JVM tests for [PrivateClipMergeRule] — the sticky-privacy dedup merge rule (design
 * §5.4), extracted from [tribixbite.cleverkeys.ClipboardDatabase.addClipboardEntry] so its
 * semantics are verified without any Android/SQLite wiring.
 */
class PrivateClipMergeRuleTest {

    // ── is_private is STICKY (old OR new) ──────────────────────────────────

    @Test
    fun isPrivate_normalOntoNormal_staysNormal() {
        assertThat(PrivateClipMergeRule.mergeIsPrivate(existing = false, incoming = false)).isFalse()
    }

    @Test
    fun isPrivate_privateOntoNormal_upgradesToPrivate() {
        // Privately copying text that already exists as a normal entry upgrades the row to private.
        assertThat(PrivateClipMergeRule.mergeIsPrivate(existing = false, incoming = true)).isTrue()
    }

    @Test
    fun isPrivate_normalOntoPrivate_staysPrivate_stickiness() {
        // A later NORMAL copy of existing private content keeps is_private = 1 (sticky). It cannot
        // un-ring the bell (that normal copy already hit the OS clipboard), but it preserves policy.
        assertThat(PrivateClipMergeRule.mergeIsPrivate(existing = true, incoming = false)).isTrue()
    }

    @Test
    fun isPrivate_privateOntoPrivate_staysPrivate() {
        assertThat(PrivateClipMergeRule.mergeIsPrivate(existing = true, incoming = true)).isTrue()
    }

    // ── source_package: most-recent-non-null wins ──────────────────────────

    @Test
    fun source_incomingNonNull_wins() {
        assertThat(PrivateClipMergeRule.mergeSourcePackage("old.app", "new.app")).isEqualTo("new.app")
    }

    @Test
    fun source_incomingNull_keepsExisting() {
        assertThat(PrivateClipMergeRule.mergeSourcePackage("old.app", null)).isEqualTo("old.app")
    }

    @Test
    fun source_existingNull_takesIncoming() {
        assertThat(PrivateClipMergeRule.mergeSourcePackage(null, "new.app")).isEqualTo("new.app")
    }

    @Test
    fun source_bothNull_isNull() {
        assertThat(PrivateClipMergeRule.mergeSourcePackage(null, null)).isNull()
    }

    @Test
    fun source_directLaunchProvenanceOverwritesNull() {
        // Entry point B records "direct-launch" as provenance; it must overwrite a prior null.
        assertThat(PrivateClipMergeRule.mergeSourcePackage(null, "direct-launch")).isEqualTo("direct-launch")
    }
}
