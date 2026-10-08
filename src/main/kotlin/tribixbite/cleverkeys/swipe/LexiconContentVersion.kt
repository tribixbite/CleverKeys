package tribixbite.cleverkeys.swipe

import java.security.MessageDigest

/**
 * The generation token both swipe adapters key their per-language lexicon memo on.
 *
 * Was two byte-identical private `contentVersion` functions, one in [CtcEngineAdapter] and one
 * in [GeometricEngineAdapter]. Unified when ARC-081 added a FOURTH input (the platform
 * user-dictionary fingerprint): a duplicated hash is exactly the shape that lets one engine
 * silently keep serving a stale trie while the other picks a change up, and the audit finding
 * was precisely that the two engines had drifted apart on user words.
 *
 * The value is opaque — only equality matters. Any change to an input must change it, which is
 * what forces a rebuild of the merged lexicon (CTC: trie + ordinals + rescue index; geometric:
 * merged word array + template index).
 */
object LexiconContentVersion {

    /**
     * Stable 64-bit content version over every input the merged lexicon is derived from.
     *
     * @param sourceId identity of the BASE word list — an immutable asset path, or a langpack
     *   path plus its length/mtime fingerprint (a pack file is mutable on disk).
     * @param customJson the raw `custom_words_<lang>` preference value.
     * @param disabled the `disabled_words_<lang>` preference set (hashed in sorted order, so a
     *   `Set`'s unspecified iteration order cannot churn the version).
     * @param userDictionaryFingerprint [UserDictionarySnapshot.fingerprint] of the platform
     *   user-dictionary rows for this language (ARC-081).
     * @param swipePriorityJson the raw `swipe_priority_<lang>` preference value (user swipe
     *   priority, 2026-10-08). Hashed ONLY when non-empty, so a user who never raised a word
     *   keeps the exact version (and memo) they had before the input existed.
     */
    fun of(
        sourceId: String,
        customJson: String,
        disabled: Set<String>,
        userDictionaryFingerprint: String,
        swipePriorityJson: String = "",
    ): Long {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sourceId.toByteArray(Charsets.UTF_8)); md.update(0)
        md.update(customJson.toByteArray(Charsets.UTF_8)); md.update(0)
        for (w in disabled.sorted()) {
            md.update(w.toByteArray(Charsets.UTF_8)); md.update(1)
        }
        md.update(2)
        md.update(userDictionaryFingerprint.toByteArray(Charsets.UTF_8))
        if (swipePriorityJson.isNotEmpty()) {
            md.update(3)
            md.update(swipePriorityJson.toByteArray(Charsets.UTF_8))
        }
        val d = md.digest()
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return v
    }
}
