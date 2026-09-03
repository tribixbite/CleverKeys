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
                for (api in NETWORK_APIS) {
                    if (code.contains(api)) offenders += "${file.path}:${index + 1}: $api"
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
    }
}
