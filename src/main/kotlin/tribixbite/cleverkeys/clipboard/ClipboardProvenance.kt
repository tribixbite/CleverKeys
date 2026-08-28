package tribixbite.cleverkeys.clipboard

/**
 * #156 provenance display (pure JVM).
 *
 * The private-copy design (`156-private-copy-paste.md` §6.2/§6.3/§6.6) accepted the
 * content-injection risk of the exported `PROCESS_TEXT` activity **on the strength of this
 * display**: any app can plant a clipboard entry, but the entry is then attributable —
 * `getCallingPackage()` is kernel-attested, so the panel can name the app that produced it,
 * and a launch that skipped `startActivityForResult` (which the real selection toolbar never
 * does) is recorded as [DIRECT_LAUNCH] and is itself a strong injection tell. That converts an
 * unanswerable prevention question into a cheap detection answer — but only if it is rendered.
 *
 * This object owns the *decision* (which label, or none) so it is unit-testable without a
 * `PackageManager`; the caller owns the localized formatting and the actual PM lookup.
 */
object ClipboardProvenance {

    /**
     * Sentinel written to `source_package` by `PrivateCopyProcessTextActivity` when the caller
     * is not identifiable (no `startActivityForResult`). Must stay in sync with that activity's
     * private `DIRECT_LAUNCH` constant — `ClipboardProvenanceTest` pins the literal.
     */
    const val DIRECT_LAUNCH: String = "direct-launch"

    /**
     * Resolve the label to substitute into the "via …" provenance line.
     *
     * @param sourcePackage the raw `source_package` column value. `null`/blank for pre-V5 rows
     *   and for ordinary OS-clipboard captures — those have no provenance to show.
     * @param directLaunchLabel localized label used for the [DIRECT_LAUNCH] sentinel.
     * @param resolveAppLabel PackageManager lookup; returns `null` when the package is not
     *   installed (uninstalled source app), in which case the raw package name is shown so the
     *   attribution survives the app's removal.
     * @return the label to render, or `null` when no provenance line should be shown at all
     *   (never an empty "via" line).
     */
    fun label(
        sourcePackage: String?,
        directLaunchLabel: String,
        resolveAppLabel: (String) -> String?,
    ): String? {
        val pkg = sourcePackage?.trim()
        if (pkg.isNullOrEmpty()) return null
        if (pkg == DIRECT_LAUNCH) return directLaunchLabel
        val resolved = resolveAppLabel(pkg)?.trim()
        return if (resolved.isNullOrEmpty()) pkg else resolved
    }
}
