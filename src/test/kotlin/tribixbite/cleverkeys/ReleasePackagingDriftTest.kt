package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Pins the packaging-level promises CleverKeys has published in release notes — the ones
 * that live in build files rather than in Kotlin, and that therefore had no test at all
 * (they were `PRESENT-UNTESTED` rows in `docs/RELEASE_RECORD.md`):
 *
 *  - **v1.0.0 "Per-ABI APKs for smaller downloads"** — the `splits.abi` block, the
 *    `ext.abiCodes` mapping, the `versionCode * 10 + abiCode` packing, and the F-Droid
 *    recipe that has to reproduce all three bit-for-bit ([perAbiSplitsAreConfigured],
 *    [fdroidRecipeReproducesTheAbiSplitScheme]).
 *  - **v1.0.0 "Complete privacy (no network access)"** — no network permission in ANY
 *    merged manifest and no network API reachable from production Kotlin
 *    ([manifestRequestsNoNetworkPermission], [productionSourcesCallNoNetworkApi]).
 *  - **v1.0.3 / v1.0.5 ONNX keep rules** — the rules still exist AND the release build
 *    still consumes them ([onnxKeepRulesExistAndAreConsumedByTheReleaseBuild]).
 *  - **v1.0.7 / v1.1.70 F-Droid reproducibility** — the profileinstaller exclusion, proven
 *    against the resolved dependency graph rather than the declaration alone, plus the ART
 *    profile / dependency-info switches ([reproducibilityGuardsAreEffective]).
 *  - **v1.1.70 "Updated metadata with improved descriptions"** — the store copy exists,
 *    fits F-Droid's field limits, agrees with `AutoName`, and does not contradict the
 *    no-network manifest ([storeDescriptionsAreValidAndAgreeWithTheManifest]).
 *
 * Every assertion is on a value that the shipped artifact depends on, not on prose: an APK
 * built after any of these drifts would install with the wrong versionCode, request a
 * permission the notes say it never asks for, ship an R8-stripped ONNX runtime, or fail
 * F-Droid's reproducible-build verification.
 *
 * Project root as CWD, like the other repo-scanning pure tests ([SourceTextHygieneTest],
 * [ReleaseMetadataDriftTest], [CuratedInstrumentationListTest]).
 */
class ReleasePackagingDriftTest {

    private val buildGradle = readRequired("build.gradle")
    private val manifest = readRequired("AndroidManifest.xml")
    private val proguard = readRequired("proguard-rules.pro")
    private val fdroidRecipe = readRequired("metadata/fdroid/tribixbite.cleverkeys.yml")

    private fun readRequired(path: String): String {
        val file = File(path)
        check(file.isFile) {
            "${file.absolutePath} not found — this test must run with the project root as CWD."
        }
        return file.readText()
    }

    /** `ext.abiCodes = ['armeabi-v7a': 1, 'arm64-v8a': 2, 'x86_64': 3]`, parsed. */
    private val abiCodes: Map<String, Int> by lazy {
        val block = Regex("""ext\.abiCodes\s*=\s*\[([^\]]+)]""").find(buildGradle)
            ?: throw AssertionError("build.gradle no longer declares ext.abiCodes")
        Regex("""'([^']+)'\s*:\s*(\d+)""").findAll(block.groupValues[1])
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    // =========================================================================
    // v1.0.0 — "Per-ABI APKs for smaller downloads"
    // =========================================================================

    @Test
    fun perAbiSplitsAreConfigured() {
        // The exact mapping is load-bearing: it is duplicated in the F-Droid recipe's
        // VercodeOperation and in the fastlane changelog filenames, and a reordering would
        // silently publish armv7 bytes under the arm64 versionCode.
        assertWithMessage("ext.abiCodes is the single source of the per-ABI versionCode suffix")
            .that(abiCodes)
            .containsExactly("armeabi-v7a", 1, "arm64-v8a", 2, "x86_64", 3)

        val splits = Regex("""splits\s*\{\s*abi\s*\{([\s\S]*?)}\s*}""").find(buildGradle)
            ?: throw AssertionError("build.gradle no longer declares a splits { abi { … } } block")
        val body = splits.groupValues[1]
        assertWithMessage("ABI splitting must be ON — otherwise one fat APK ships to everyone")
            .that(Regex("""\benable\s+true\b""").containsMatchIn(body)).isTrue()
        assertWithMessage("a universal APK would defeat the 'smaller downloads' promise")
            .that(Regex("""\buniversalApk\s+false\b""").containsMatchIn(body)).isTrue()

        val included = Regex("""include\s+((?:'[^']+'\s*,?\s*)+)""").find(body)
            ?.let { Regex("""'([^']+)'""").findAll(it.groupValues[1]).map { m -> m.groupValues[1] }.toSet() }
        assertWithMessage("the split ABI list must be exactly the ABIs abiCodes can number")
            .that(included).isEqualTo(abiCodes.keys)
    }

    /**
     * v2.0.0 release-record anchor: the notes announce "Requires Android 7+", and the floor
     * is load-bearing twice over — ONNX Runtime 1.21.1 declares AAR minSdk 24 (ARC-113),
     * and GifSqlCompatDriftTest's upsert ban is calibrated to API 24's SQLite. Raising it
     * without editing the announcement (and vice versa) is exactly the drift this catches.
     */
    @Test
    fun minSdkIsTheAnnouncedAndroidSevenFloor() {
        assertWithMessage("build.gradle minSdk must be the announced Android 7.0 (API 24) floor")
            .that(Regex("""\bminSdk\s+24\b""").containsMatchIn(buildGradle)).isTrue()
    }

    @Test
    fun perAbiVersionCodePackingIsUnchanged() {
        assertWithMessage(
            "the published versionCode packing is base*10+abiCode; F-Droid monotonicity and " +
                "the fastlane changelog filenames both assume it"
        ).that(
            Regex("""versionCodeOverride\s*=\s*variant\.versionCode\s*\*\s*10\s*\+\s*abiCode""")
                .containsMatchIn(buildGradle)
        ).isTrue()
        assertWithMessage("per-ABI outputs must carry the ABI in the filename (release assets)")
            .that(buildGradle).contains("CleverKeys-v\${variant.versionName}-\${abi}.apk")
    }

    @Test
    fun fdroidRecipeReproducesTheAbiSplitScheme() {
        // 1. VercodeOperation must enumerate exactly the abiCodes suffixes, in value order.
        val vercodeOps = Regex("""(?m)^VercodeOperation:\n((?:\s+- .*\n)+)""").find(fdroidRecipe)
            ?.let { Regex("""(?m)^\s+- (.+)$""").findAll(it.groupValues[1]).map { m -> m.groupValues[1].trim() }.toList() }
        assertWithMessage("F-Droid derives each per-ABI versionCode from VercodeOperation")
            .that(vercodeOps)
            .isEqualTo(abiCodes.values.sorted().map { "10 * %c + $it" })

        // 2. Every historical Build entry must agree with the same packing, and must point at
        //    the binary / prebuild for the ABI its suffix names. A mismatch here republishes
        //    one ABI's bytes under another ABI's versionCode.
        val builds = fdroidRecipe.substringAfter("\nBuilds:\n").substringBefore("\nAllowedAPKSigningKeys")
        val entries = builds.split(Regex("""(?m)^  - (?=versionName:)""")).filter { it.isNotBlank() }
        assertWithMessage("the F-Droid recipe must still carry per-ABI build entries")
            .that(entries.size).isAtLeast(3)
        val suffixToAbi = abiCodes.entries.associate { (abi, code) -> code to abi }

        for (entry in entries) {
            val versionName = Regex("""versionName:\s*([\d.]+)""").find(entry)!!.groupValues[1]
            val versionCode = Regex("""versionCode:\s*(\d+)""").find(entry)!!.groupValues[1].toInt()
            val (major, minor, patch) = versionName.split(".").map { it.toInt() }
            val base = major * 10000 + minor * 100 + patch
            val suffix = versionCode - base * 10
            assertWithMessage("$versionName/$versionCode does not fit base*10+abiCode (base=$base)")
                .that(suffix).isIn(abiCodes.values)
            val abi = suffixToAbi.getValue(suffix)
            assertWithMessage("$versionCode is the $abi slot, so it must publish the $abi APK")
                .that(entry).contains("CleverKeys-v%v-$abi.apk")
            assertWithMessage("$versionCode is the $abi slot, so its prebuild must build $abi")
                .that(entry).contains("include '$abi'/")
        }
    }

    /**
     * The `prefix_boosts` scanignore may appear ONLY in the build entries whose own commit
     * actually contained the `src/main/assets/prefix_boosts` binaries. (Do not write the
     * glob form here: Kotlin block comments NEST, so a literal slash-star inside a KDoc
     * opens a comment that never closes and the file fails to parse at EOF.)
     *
     * This started as an "delete the 9 stale neural-era lines" cleanup and the evidence
     * refuted it — the recipe tracks the tree exactly, per entry:
     *
     *   v1.1.99 (2026-01-08)  0 prefix_boosts files  → entry has no scanignore  ✓
     *   v1.2.1  (2026-01-09)  0 files                → entry has no scanignore  ✓
     *   v1.2.2  (2026-01-11) 10 files                → entry HAS it             ✓
     *   v1.2.5  (2026-01-14) 10 files                → entry HAS it             ✓
     *   v1.2.8  (2026-01-22) 11 files                → entry HAS it             ✓
     *
     * The directory was created 2026-01-10 (between v1.2.1 and v1.2.2) and deleted at 1.5.0
     * by the neural-engine removal (64f401d2). So the nine lines are accurate historical
     * build metadata, not drift: stripping them would break an F-Droid rebuild or a
     * reproducible-build re-verification of those three published versions, which is
     * precisely what a scanignore exists to prevent.
     *
     * What IS a live hazard is the copy-paste: 1.2.8 is the newest entry in the file, and
     * these recipes are written by duplicating the previous block. The next entry (2.0.0)
     * must not inherit a scanignore for a path that no longer exists. That is the direction
     * this test guards — history stays frozen, the future stays clean.
     */
    @Test
    fun prefixBoostScanignoresStayFrozenToTheVersionsThatShippedThoseAssets() {
        val builds = fdroidRecipe.substringAfter("\nBuilds:\n").substringBefore("\nAllowedAPKSigningKeys")
        val entries = builds.split(Regex("""(?m)^  - (?=versionName:)""")).filter { it.isNotBlank() }
        val carrying = entries.mapNotNull { entry ->
            if ("prefix_boosts" in entry) {
                Regex("""versionName:\s*([\d.]+)""").find(entry)!!.groupValues[1]
            } else null
        }.distinct().sorted()

        assertWithMessage(
            "src/main/assets/prefix_boosts/ existed only between 2026-01-10 and its deletion " +
                "at 1.5.0 (neural-engine removal). Entries for versions in that window must " +
                "KEEP the scanignore — removing it breaks a rebuild/reproducibility check of a " +
                "published version. Entries for any other version must not have it: a 1.5.0+ " +
                "recipe carrying this line is a copy-paste of the 1.2.8 block, ignoring a path " +
                "that no longer exists."
        ).that(carrying).isEqualTo(PREFIX_BOOST_ERA_VERSIONS)

        // The asset root really is gone at HEAD — the other half of the same claim.
        assertWithMessage("src/main/assets/prefix_boosts was deleted at 1.5.0; it must stay gone")
            .that(File("src/main/assets/prefix_boosts").exists()).isFalse()
    }

    // =========================================================================
    // v1.0.0 — "Complete privacy (no network access)"
    // =========================================================================

    /** Declared `<uses-permission>` names, with XML comments stripped first. */
    private fun declaredPermissions(xml: String): Set<String> {
        val withoutComments = xml.replace(Regex("""<!--[\s\S]*?-->"""), "")
        return Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(withoutComments)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun manifestRequestsNoNetworkPermission() {
        val main = declaredPermissions(manifest)
        // The exact set, so a new permission cannot slip in unnoticed under a claim of
        // "complete privacy". VIBRATE = haptics, READ_USER_DICTIONARY = the system
        // personal dictionary the suggestion pipeline reads.
        assertWithMessage("the release manifest's full permission set")
            .that(main)
            .containsExactly(
                "android.permission.VIBRATE",
                "android.permission.READ_USER_DICTIONARY",
                // Self-defined signature permission (I-8): gates the pre-26 debug
                // receivers against third-party broadcast injection. Grantable only
                // to same-signature apps; grants no capability to this app itself.
                "tribixbite.cleverkeys.permission.SET_DEBUG_MODE",
            )
        // The debug overlay merges into the debug APK; assert it adds none either, so an
        // instrumented-test convenience cannot become a shipped permission.
        val debugManifest = File("src/debug/AndroidManifest.xml")
        if (debugManifest.isFile) {
            assertWithMessage("the debug manifest overlay must not add permissions")
                .that(declaredPermissions(debugManifest.readText())).isEmpty()
        }
        for (forbidden in FORBIDDEN_PERMISSIONS) {
            assertWithMessage("'no network access' forbids $forbidden in any manifest")
                .that(main).doesNotContain(forbidden)
        }
    }

    @Test
    fun productionSourcesCallNoNetworkApi() {
        // A missing INTERNET permission already makes a socket throw, but that is a runtime
        // crash rather than a design guarantee. This pins the stronger, published claim:
        // production code contains no client of a network stack at all.
        val offenders = mutableListOf<String>()
        val sourceRoot = File("src/main/kotlin")
        check(sourceRoot.isDirectory) { "src/main/kotlin not found (wrong CWD?)" }
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//")
                for (api in NETWORK_API_PATTERNS) {
                    if (api.containsMatchIn(code)) offenders += "${file.path}:${index + 1}: ${api.pattern}"
                }
            }
        }
        assertWithMessage(
            "production Kotlin must not reference a network API — CleverKeys ships with no " +
                "INTERNET permission and the release notes promise no network access"
        ).that(offenders).isEmpty()
    }

    // =========================================================================
    // v1.0.3 / v1.0.5 — ONNX runtime + inner-class keep rules
    // =========================================================================

    @Test
    fun onnxKeepRulesExistAndAreConsumedByTheReleaseBuild() {
        // `**` (not `*`) is what covers nested types such as ai.onnxruntime.OrtSession$Result
        // — that widening IS the v1.0.5 "inner classes" fix, and `{ *; }` keeps their members
        // for the JNI FindClass/GetFieldID lookups libonnxruntime4j_jni.so performs.
        assertWithMessage("R8 would strip the ONNX runtime's JNI-reached types without this")
            .that(Regex("""(?m)^-keep\s+class\s+ai\.onnxruntime\.\*\*\s*\{\s*\*;\s*}""")
                .containsMatchIn(proguard)).isTrue()
        assertWithMessage("ONNX's optional back-ends produce reference warnings without this")
            .that(Regex("""(?m)^-dontwarn\s+ai\.onnxruntime\.\*\*""").containsMatchIn(proguard)).isTrue()
        assertWithMessage("the app's own ONNX session loader must survive shrinking too")
            .that(Regex("""(?m)^-keep\s+class\s+tribixbite\.cleverkeys\.onnx\.\*\*\s*\{\s*\*;\s*}""")
                .containsMatchIn(proguard)).isTrue()

        // A keep rule nobody consumes is not a guard: pin that the release variant both
        // minifies and points R8 at this file.
        val release = releaseBuildTypeBlock()
        assertWithMessage("release must minify — otherwise these rules never run")
            .that(Regex("""\bminifyEnabled\s+true\b""").containsMatchIn(release)).isTrue()
        assertWithMessage("release must feed proguard-rules.pro to R8")
            .that(release).contains("'proguard-rules.pro'")
    }

    /** The `release { … }` buildType body (up to the sibling `debug {`). */
    private fun releaseBuildTypeBlock(): String {
        val start = Regex("""(?m)^\s{4}release\s*\{""").find(buildGradle)
            ?: throw AssertionError("build.gradle no longer declares a release buildType")
        val rest = buildGradle.substring(start.range.last)
        val end = Regex("""(?m)^\s{4}debug\s*\{""").find(rest)
            ?: throw AssertionError("build.gradle no longer declares a debug buildType after release")
        return rest.substring(0, end.range.first)
    }

    // =========================================================================
    // v1.0.7 / v1.1.70 — F-Droid build & verification reproducibility
    // =========================================================================

    /**
     * 2026-09-09 APK-diet audit: the repo-root `assets/` directory is a PACKAGED asset
     * root (build.gradle assets.srcDirs), and it silently shipped 6.4 MB of README
     * marketing art (raccoon logos, banners) in every APK. Only the theme font belongs
     * there; artwork lives in `art/`, outside the packaged roots.
     */
    @Test
    fun packagedRepoRootAssetsCarryOnlyTheThemeFont() {
        val packaged = File("assets").walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertWithMessage(
            "repo-root assets/ is a packaged asset root — every file here ships in the APK. " +
                "Marketing art belongs in art/ (not packaged)."
        ).that(packaged).containsExactly("special_font.ttf")
    }

    /**
     * Same audit: the ONNX models are read into the heap (ModelLoader.readModelBytes →
     * stream.readBytes()) and never mmap'd, so STORED packaging buys nothing and costs
     * ~625 KB of download per APK. Nothing may re-add a noCompress for them.
     */
    @Test
    fun onnxModelsAreDeflatedInTheApk() {
        assertWithMessage(
            "build.gradle must not exempt onnx assets from compression — they are heap-read, " +
                "never mmap'd, and deflate is deterministic (no reproducibility cost)"
        ).that(Regex("""noCompress[^\n]*onnx""").containsMatchIn(buildGradle)).isFalse()
    }

    /**
     * 2026-09-10 APK-diet round 2: native libraries are DEFLATED in the APK
     * (`useLegacyPackaging = true`) rather than STORED page-aligned.
     *
     * AGP has defaulted to `useLegacyPackaging = false` since 4.2, which stores the `.so`
     * entries uncompressed so the loader can mmap them straight out of the APK. That trades
     * ~11.5 MB of *download* for ~6.4 MB of saved *installed* footprint — a bad trade for an
     * app whose native payload is one ONNX runtime loaded once at IME start. The maintainer
     * accepted the inverse trade on 2026-09-10: smaller download, larger install.
     *
     * The floor matters: extraction-at-install is only legal below API 23 without this flag,
     * and `useLegacyPackaging = true` makes AGP emit `android:extractNativeLibs="true"` into
     * the merged manifest. minSdk 24 is safely above the API 23 boundary either way, and
     * nothing in the app mmaps a `.so` itself.
     */
    @Test
    fun nativeLibrariesAreDeflatedForASmallerDownload() {
        val packaging = packagingBlock()
        assertWithMessage(
            "packaging { jniLibs { useLegacyPackaging = true } } is what deflates the native " +
                "libraries; without it AGP STOREs them and the download grows ~11.5 MB"
        ).that(
            Regex("""jniLibs\s*\{[^}]*useLegacyPackaging\s*=\s*true""")
                .containsMatchIn(packaging)
        ).isTrue()

        // AGP derives android:extractNativeLibs from the flag. A hand-written value in the
        // source manifest would win the merge and silently undo it.
        for (path in listOf("AndroidManifest.xml", "src/debug/AndroidManifest.xml")) {
            val file = File(path)
            if (!file.isFile) continue
            assertWithMessage(
                "$path must not hard-code android:extractNativeLibs — AGP writes it from " +
                    "packaging.jniLibs.useLegacyPackaging and a manual value overrides it"
            ).that(file.readText()).doesNotContain("extractNativeLibs")
        }
    }

    /** The `android { packaging { … } }` body. */
    private fun packagingBlock(): String {
        val start = Regex("""(?m)^\s{2}packaging\s*\{""").find(buildGradle)
            ?: throw AssertionError("build.gradle no longer declares a packaging { … } block")
        val rest = buildGradle.substring(start.range.first)
        // Balanced-brace scan: the block contains nested resources { } / jniLibs { } bodies.
        var depth = 0
        for ((offset, ch) in rest.withIndex()) {
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) return rest.substring(0, offset + 1)
            }
        }
        throw AssertionError("build.gradle's packaging { … } block is unbalanced")
    }

    // =========================================================================
    // 2026-09-10 APK diet — shipped-locale filtering
    // =========================================================================

    /**
     * `ext.shippedLocales = ['cs', 'de', …]`, parsed — the same shape as [abiCodes], so the
     * single declaration in build.gradle is the only place the list lives.
     */
    private val shippedLocales: List<String> by lazy {
        val block = Regex("""ext\.shippedLocales\s*=\s*\[([^\]]+)]""").find(buildGradle)
            ?: throw AssertionError("build.gradle no longer declares ext.shippedLocales")
        Regex("""'([^']+)'""").findAll(block.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    /**
     * The locale qualifiers actually present as `res/values-<qualifier>` directories, and the
     * two shapes that are NOT locales.
     *
     * A qualifier is locale-shaped iff it is a 2–3 letter ISO-639 code with an optional
     * `-rXX` region, or a BCP-47 `b+…` form. Sweeping the full Android configuration-qualifier
     * vocabulary, `car` (UI mode) is the *only* 2–3-lowercase-letter token that is not a
     * language — every other non-locale qualifier is 4+ characters (`land`, `port`, `hdpi`,
     * `night`, `ldrtl`, `small`, `nokeys`, …) or carries a digit (`v29`, `sw600dp`, `12key`).
     * So the classifier below is exact rather than heuristic.
     */
    private fun localeQualifiersOnDisk(): Pair<List<String>, List<String>> {
        val res = File("res")
        check(res.isDirectory) { "res/ not found — this test must run with the project root as CWD." }
        val qualifiers = res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { it.name.removePrefix("values-") }
        val locales = qualifiers.filter { LOCALE_QUALIFIER.matches(it) && it !in LOCALE_SHAPED_NON_LOCALES }
        return locales.sorted() to (qualifiers - locales.toSet()).sorted()
    }

    /**
     * 2026-09-10 APK-diet round 2: `resConfigs` strips the ~64 locale resource tables that
     * AndroidX / Material / Compose drag into `resources.arsc` for languages CleverKeys does
     * not translate (the pre-filter APK carried 85 locale configs against 21 shipped ones).
     *
     * The failure mode this pins is **silent**: a locale listed in neither the filter nor a
     * typo-free form simply loses its whole string table and the UI falls back to English,
     * with no build error. Two legacy ISO-639 codes make that easy to trigger, and both were
     * verified against the exact `aapt2` this build uses (2026-09-10):
     *
     *   `aapt2 link -c in`  → keeps `res/values-in`   (Indonesian survives)
     *   `aapt2 link -c id`  → DROPS `res/values-in`   (Indonesian silently vanishes)
     *
     * Same trap for Hebrew (`iw`, not `he`) and Yiddish (`ji`, not `yi`). The rule that falls
     * out is simple and is what this test enforces: **the filter token must be byte-identical
     * to the resource directory's qualifier**, because that is the string aapt2 parsed when it
     * assigned the config. A bogus token is a hard `error: invalid config '…' for -c option`,
     * so typos fail the build — only a *plausible* wrong alias is dangerous.
     *
     * Also verified on the same aapt2: a locale-only filter constrains only the locale axis —
     * `values-night`, `values-v29`, densities, `-land`, `-ldrtl` and `sw600dp` all survive,
     * and the unqualified `res/values` table is always kept.
     */
    @Test
    fun shippedLocaleFilterMatchesTheResourceDirectoriesExactly() {
        val (locales, nonLocales) = localeQualifiersOnDisk()

        assertWithMessage(
            "unrecognised res/values-* qualifiers: these are neither locale-shaped nor in the " +
                "audited non-locale set. Classify each one, then either add it to " +
                "ext.shippedLocales (if it is a language) or to LOCALE_SHAPED_NON_LOCALES / " +
                "the audited set below — do NOT loosen the classifier."
        ).that(nonLocales).isEqualTo(AUDITED_NON_LOCALE_QUALIFIERS)

        assertWithMessage(
            "ext.shippedLocales must be EXACTLY the res/values-* locale directories. A locale " +
                "present on disk but missing here loses its entire string table in the APK " +
                "(silent English fallback); an entry here with no directory is dead weight. " +
                "Use the directory qualifier verbatim — Indonesian is 'in' (NOT 'id'), Hebrew " +
                "would be 'iw' (NOT 'he'): aapt2 matches the token it parsed, and the modern " +
                "alias drops the table without a warning."
        ).that(shippedLocales.sorted()).isEqualTo(locales)

        assertWithMessage("the locale list must not carry duplicates")
            .that(shippedLocales).hasSize(shippedLocales.toSet().size)

        // A declaration nobody consumes filters nothing. AGP 8.8.2 exposes
        // BaseFlavor.resourceConfigurations as a read-only Set (getter only, no setter), so
        // resConfigs(...) — which is `resourceConfigurations.addAll(...)` inside AGP — is the
        // Groovy entry point. `androidResources.localeFilters` does not exist until AGP 8.10.
        assertWithMessage("defaultConfig must feed ext.shippedLocales to the resource filter")
            .that(
                Regex("""resConfigs\s+rootProject\.ext\.shippedLocales""").containsMatchIn(buildGradle)
            ).isTrue()
    }

    @Test
    fun reproducibilityGuardsAreEffective() {
        assertWithMessage("the baseline-profile installer varies per build environment")
            .that(
                Regex("""exclude\s+group:\s*'androidx\.profileinstaller',\s*module:\s*'profileinstaller'""")
                    .containsMatchIn(buildGradle)
            ).isTrue()

        // The declaration alone proves nothing — assert the RESOLVED graph is clean. The
        // lockfile is generated from real task classpaths, so profileinstaller appearing
        // here would mean the exclusion stopped taking effect.
        val lockfile = File("gradle.lockfile")
        check(lockfile.isFile) { "gradle.lockfile not found (wrong CWD?)" }
        assertWithMessage("profileinstaller resolved back into the app classpath")
            .that(lockfile.readText()).doesNotContain("androidx.profileinstaller")

        assertWithMessage("ART profile tasks embed environment-dependent bytes in the APK")
            .that(
                Regex("""task\.name\.contains\("ArtProfile"\)\s*\|\|\s*task\.name\.contains\("BaselineProfile"\)""")
                    .containsMatchIn(buildGradle)
            ).isTrue()
        val dependenciesInfo = Regex("""dependenciesInfo\s*\{([\s\S]*?)}""").find(buildGradle)
            ?.groupValues?.get(1)
            ?: throw AssertionError("build.gradle no longer declares a dependenciesInfo block")
        assertWithMessage("the signed dependency blob is not reproducible by a rebuilder")
            .that(Regex("""includeInApk\s*=\s*false""").containsMatchIn(dependenciesInfo)).isTrue()
        assertWithMessage("same for the bundle blob")
            .that(Regex("""includeInBundle\s*=\s*false""").containsMatchIn(dependenciesInfo)).isTrue()
    }

    // =========================================================================
    // v1.1.70 — "Updated metadata with improved descriptions"
    // =========================================================================

    @Test
    fun storeDescriptionsAreValidAndAgreeWithTheManifest() {
        val autoName = Regex("""(?m)^AutoName:\s*(.+)$""").find(fdroidRecipe)?.groupValues?.get(1)?.trim()
        assertWithMessage("F-Droid names the app from AutoName").that(autoName).isEqualTo("CleverKeys")
        val title = readRequired("fastlane/metadata/android/en-US/title.txt").trim()
        assertWithMessage("the fastlane title and the F-Droid AutoName must not diverge")
            .that(title).isEqualTo(autoName)

        val short = readRequired("fastlane/metadata/android/en-US/short_description.txt").trim()
        assertThat(short).isNotEmpty()
        // F-Droid truncates a Summary longer than 80 characters.
        assertWithMessage("short_description is F-Droid's Summary field, hard-limited to 80 chars")
            .that(short.length).isAtMost(80)
        assertWithMessage("the Summary is a single line")
            .that(short.lines().size).isEqualTo(1)

        val full = readRequired("fastlane/metadata/android/en-US/full_description.txt").trim()
        assertThat(full).isNotEmpty()
        assertWithMessage("full_description is F-Droid's Description field, limited to 4000 chars")
            .that(full.length).isAtMost(4000)
        // The store copy and the manifest are two halves of the same promise: the listing
        // claims offline operation while the manifest requests no network permission. Either
        // one drifting without the other is a published lie.
        assertWithMessage("the store listing must keep claiming offline/local operation")
            .that(full.lowercase()).contains("offline")
        assertWithMessage("the store listing must not promise a network feature")
            .that(declaredPermissions(manifest)).doesNotContain("android.permission.INTERNET")
    }

    private companion object {
        /** ISO-639 (2–3 letters) + optional `-rXX` region, or a BCP-47 `b+lang+Script` form. */
        val LOCALE_QUALIFIER = Regex("""^([a-z]{2,3}(-r[A-Z]{2})?|b\+[A-Za-z+]+)$""")

        /** The only locale-shaped Android qualifier that is not a language (UI mode: car dock). */
        val LOCALE_SHAPED_NON_LOCALES = setOf("car")

        /** Audited 2026-09-10: the non-locale `res/values-*` qualifiers this app actually has. */
        val AUDITED_NON_LOCALE_QUALIFIERS = listOf("night", "v29")

        /**
         * The published versions whose source tree contained `src/main/assets/prefix_boosts/`
         * (verified against the v-tags on 2026-09-10: 10, 10 and 11 `.bin` files respectively).
         */
        val PREFIX_BOOST_ERA_VERSIONS = listOf("1.2.2", "1.2.5", "1.2.8")

        val FORBIDDEN_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
        )

        /**
         * Network client APIs. Deliberately narrow — `Uri.parse` + `ACTION_VIEW` (the GitHub
         * link in the launcher) hands the URL to the browser and is NOT network access by
         * this app, so it is not listed.
         */
        val NETWORK_APIS = listOf(
            "java.net.URL",
            "java.net.Socket",
            "java.net.HttpURLConnection",
            "HttpURLConnection",
            "openConnection(",
            "okhttp3",
            "retrofit2",
            "java.net.DatagramSocket",
            "javax.net.ssl",
        )

        /**
         * Word-boundary-anchored when the pattern ends in a word character, so
         * `java.net.URL` cannot substring-match `java.net.URLDecoder` — a pure
         * string transform with no network capability (bit the D-2 percent-decode).
         */
        val NETWORK_API_PATTERNS = NETWORK_APIS.map { api ->
            Regex(Regex.escape(api) + if (api.last().isLetterOrDigit()) """\b""" else "")
        }
    }
}
