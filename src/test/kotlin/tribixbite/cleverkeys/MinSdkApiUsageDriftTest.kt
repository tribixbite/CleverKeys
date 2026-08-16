package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Drift detection for **Java-8 / API-24 collection idioms in production code**,
 * where the app's `minSdk` is 21.
 *
 * These are not compile errors and not test failures — they are `NoSuchMethodError`
 * crashes at runtime on Android 5.0–6.0 (API 21–23), because the default methods
 * added to `java.util.Map` / `Set` in Java 8 exist in the Android runtime only from
 * API 24 (Nougat) and this project does NOT enable core-library desugaring.
 *
 * `lintDebug` (with `abortOnError = true`) is the primary gate, but lint runs late,
 * takes minutes, and its baseline is easy to widen by accident — this scan fails in
 * seconds inside `runPureTests` with the exact replacement idiom spelled out.
 * The class of bug has already been fixed once (commit `29f790fb`) and was
 * reintroduced by the 2026-08 context-LM wave, hence this guard.
 *
 * ### Safe replacements
 *
 * | API 24 idiom                    | API 21-safe replacement                                                  |
 * |---------------------------------|--------------------------------------------------------------------------|
 * | `ConcurrentHashMap.newKeySet()` | `Collections.newSetFromMap(ConcurrentHashMap<T, Boolean>())` (API 9)      |
 * | `map.computeIfAbsent(k) { … }`  | `map[k] ?: run { … ; map.putIfAbsent(k, fresh) ?: fresh }` — the receiver must be statically `ConcurrentMap` (that `putIfAbsent` is API 1) |
 * | `map.merge(k, 1, Int::plus)`    | a `putIfAbsent` / `replace(k, old, new)` CAS loop (both API 1 on `ConcurrentMap`) |
 * | `map.getOrDefault(k, d)`        | `map[k] ?: d`                                                            |
 * | `hashMap.putIfAbsent(k, v)`     | `if (k !in map) map[k] = v` — the *default* `Map#putIfAbsent` is API 24   |
 *
 * ### Escape hatch
 *
 * A line may be exempted with a trailing `// API21-OK: <reason>` comment (e.g. a
 * `putIfAbsent` whose receiver this scan mis-types). Do NOT exempt without proving
 * the receiver's static type resolves to a method that exists on API 21.
 *
 * Same source-scan convention as [LanguageSlotCoverageDriftTest] /
 * [CoreImeHygieneDriftTest]: project root as CWD.
 */
class MinSdkApiUsageDriftTest {

    /** Lines carrying this marker are deliberately exempt (must state a reason). */
    private val exemptionMarker = "API21-OK"

    /**
     * Unambiguous API-24 members: no Kotlin stdlib extension shares these names,
     * so any occurrence resolves to the Java default/static method.
     */
    private val unconditionalIdioms: List<Pair<Regex, String>> = listOf(
        Regex("""\bConcurrentHashMap\s*\.\s*newKeySet\s*\(""") to
            "ConcurrentHashMap.newKeySet() is API 24 — use Collections.newSetFromMap(ConcurrentHashMap()) (API 9)",
        Regex("""\.computeIfAbsent\s*\(""") to
            "Map#computeIfAbsent is API 24 — use `map[k] ?: (map.putIfAbsent(k, fresh) ?: fresh)` on a ConcurrentMap-typed receiver",
        Regex("""\.computeIfPresent\s*\(""") to
            "Map#computeIfPresent is API 24 — use a get + replace(k, old, new) CAS loop",
        Regex("""\.getOrDefault\s*\(""") to
            "Map#getOrDefault is API 24 — use `map[k] ?: default`",
        // `[({]` because Kotlin writes the single-lambda form without parentheses.
        Regex("""\.replaceAll\s*[({]""") to
            "Map/List#replaceAll is API 24 — rebuild the collection instead (java.lang.String#replaceAll IS API 1; exempt that line if the receiver is a String)",
        Regex("""\bObjects\s*\.\s*requireNonNullElse\s*\(""") to
            "Objects.requireNonNullElse is API 30 — use the elvis operator",
        Regex("""\bimport\s+java\.util\.stream\.""") to
            "java.util.stream is API 24 — use Kotlin sequences / collection operators",
        Regex("""\bimport\s+java\.util\.Optional\b""") to
            "java.util.Optional is API 24 — use Kotlin nullable types",
    )

    /**
     * `Map#merge` — matched only on a LOWERCASE receiver so it cannot fire on the
     * project's own `Type.merge(...)` helpers (`CtcLexiconMerge.merge`,
     * `RulesetParser.merge`, `ExtraKeys.merge`), which are calls on an
     * object/companion receiver and are therefore capitalised.
     */
    private val mapMergeIdiom = Regex("""\b[a-z][A-Za-z0-9_]*\.merge\s*\(""")

    // Receiver names declared in a file as a NON-concurrent map. `Map#putIfAbsent`
    // is a Java 8 default method (API 24) on those, whereas `ConcurrentMap#putIfAbsent`
    // is an abstract method present since API 1 — the receiver's static type decides.
    private val plainMapTypes = "(?:Mutable)?(?:Map|HashMap|LinkedHashMap|TreeMap|SortedMap)"
    private val plainMapCtors = "(?:java\\.util\\.)?(?:HashMap|LinkedHashMap|TreeMap|mutableMapOf|linkedMapOf|sortedMapOf|hashMapOf)"
    private val plainMapAssignedDecl = Regex("""\b(?:val|var)\s+(\w+)\s*(?::[^=\n]*)?=\s*$plainMapCtors\b""")
    private val plainMapTypedDecl = Regex("""\b(\w+)\s*:\s*$plainMapTypes\s*<""")

    private fun mainSources(): List<File> {
        val mainDir = File(System.getProperty("user.dir") ?: ".", "src/main/kotlin")
        check(mainDir.exists()) {
            "src/main/kotlin not found at ${mainDir.absolutePath} — drift test must run with project root as CWD."
        }
        val files = mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(files.size > 100) {
            "Scanned only ${files.size} production sources — the walk is broken, not a real pass."
        }
        return files
    }

    /** Executable (non-comment, non-exempt) content of a source line, "" when there is none. */
    private fun codeOf(rawLine: String): String {
        val trimmed = rawLine.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return ""
        if (trimmed.contains(exemptionMarker)) return ""
        return rawLine.substringBefore("//")
    }

    /** "file:line — reason" for every offending line. */
    private fun scan(): List<String> {
        val violations = mutableListOf<String>()

        for (file in mainSources()) {
            val text = file.readText()
            val relative = file.path.substringAfter("src/main/kotlin/")

            val plainMapNames: Set<String> =
                (plainMapAssignedDecl.findAll(text).map { it.groupValues[1] } +
                    plainMapTypedDecl.findAll(text).map { it.groupValues[1] }).toSet()
            val plainMapPutIfAbsent = plainMapNames.takeIf { it.isNotEmpty() }?.let { names ->
                Regex("""\b(?:${names.joinToString("|") { Regex.escape(it) }})\.putIfAbsent\s*\(""")
            }

            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = codeOf(rawLine)
                if (line.isBlank()) return@forEachIndexed
                val where = "$relative:${index + 1}"

                for ((pattern, reason) in unconditionalIdioms) {
                    if (pattern.containsMatchIn(line)) violations += "$where — $reason\n      ${rawLine.trim()}"
                }
                if (mapMergeIdiom.containsMatchIn(line)) {
                    violations += "$where — Map#merge is API 24 — use a putIfAbsent/replace CAS loop\n      ${rawLine.trim()}"
                }
                if (plainMapPutIfAbsent?.containsMatchIn(line) == true) {
                    violations += "$where — Map#putIfAbsent on a non-concurrent receiver is API 24 — use `if (k !in map) map[k] = v`\n      ${rawLine.trim()}"
                }
            }
        }
        return violations
    }

    @Test
    fun `production code uses no API 24 collection idioms under minSdk 21`() {
        val violations = scan()
        assertWithMessage(
            "minSdk is 21, so these calls throw NoSuchMethodError on Android 5.0–6.0:\n" +
                violations.joinToString("\n")
        ).that(violations).isEmpty()
    }

    @Test
    fun `minSdk is still below 24 - this guard is only needed there`() {
        val gradle = File(System.getProperty("user.dir") ?: ".", "build.gradle").readText()
        val minSdk = Regex("""minSdk\s+(\d+)""").find(gradle)?.groupValues?.get(1)?.toInt()
        assertWithMessage("minSdk declaration not found in build.gradle").that(minSdk).isNotNull()
        // If minSdk is ever raised to 24+, this whole test class can be deleted.
        assertThat(minSdk!!).isLessThan(24)
    }

    @Test
    fun `the scanner detects planted violations and ignores sanctioned idioms`() {
        // Guards against regex rot silently turning the scan into a no-op.
        fun fires(sample: String): Boolean =
            unconditionalIdioms.any { (pattern, _) -> pattern.containsMatchIn(codeOf(sample)) } ||
                mapMergeIdiom.containsMatchIn(codeOf(sample))

        val offending = listOf(
            "    private val s: MutableSet<String> = ConcurrentHashMap.newKeySet()",
            "        return map.computeIfAbsent(k) { build(it) }",
            "        map.computeIfPresent(k) { _, v -> v + 1 }",
            "        val n = counts.getOrDefault(word, 0)",
            "        counts.merge(word, 1, Int::plus)",
            "        entries.replaceAll { it.trim() }",
            "import java.util.stream.Collectors",
        )
        for (sample in offending) {
            assertWithMessage("scanner missed: $sample").that(fires(sample)).isTrue()
        }

        val benign = listOf(
            "        val merged = CtcLexiconMerge.merge(basePairs, customPairs, disabled)",
            "        return ExtraKeys.merge(extraKeys)",
            "        merged = RulesetParser.merge(merged, loadAsset(\"url_rules/clearurls.json\"))",
            "        bigrams[w] ?: ConcurrentHashMap<String, Int>().let { bigrams.putIfAbsent(w, it) ?: it }",
            "     * ConcurrentHashMap#computeIfAbsent is API 24; putIfAbsent (ConcurrentMap, API 1)",
            "        // getOrDefault is API 24; values are non-null",
            "        val x = counts.merge(k, 1, Int::plus) // API21-OK: not real, exempted",
        )
        for (sample in benign) {
            assertWithMessage("false positive on: $sample").that(fires(sample)).isFalse()
        }
    }

    @Test
    fun `the non-concurrent putIfAbsent detector types receivers from their declaration`() {
        val plainDeclarations = listOf(
            "        val ordinals = HashMap<String, Int>(entries.size * 2)",
            "        val byLang = LinkedHashMap<String, MutableList<String>>()",
            "        fun compute(dst: MutableMap<KeyValue, KeyboardData.PreferredPos>, q: Query) {",
        )
        for (decl in plainDeclarations) {
            val names = (plainMapAssignedDecl.findAll(decl).map { it.groupValues[1] } +
                plainMapTypedDecl.findAll(decl).map { it.groupValues[1] }).toSet()
            assertWithMessage("no plain-map receiver detected in: $decl").that(names).isNotEmpty()
        }

        // A ConcurrentHashMap-typed receiver must NOT be classified as a plain map:
        // `ConcurrentMap#putIfAbsent` is the sanctioned API 1 replacement idiom.
        val concurrent = "    private val languages: ConcurrentHashMap<String, LanguageBigrams> = ConcurrentHashMap()"
        val names = (plainMapAssignedDecl.findAll(concurrent).map { it.groupValues[1] } +
            plainMapTypedDecl.findAll(concurrent).map { it.groupValues[1] }).toSet()
        assertWithMessage("ConcurrentHashMap receiver misclassified as a plain map").that(names).isEmpty()
    }
}
