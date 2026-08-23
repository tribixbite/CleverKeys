package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/** Guards review tooling by rejecting binary NUL bytes in source-controlled text files. */
class SourceTextHygieneTest {

    @Test
    fun trackedTextSourcesContainNoNulBytes() {
        val roots = listOf("src", "res", "docs", ".github", "scripts")
        val extensions = setOf(
            "kt", "kts", "java", "xml", "md", "yml", "yaml", "json", "gradle",
            "properties", "sh", "py", "js", "mjs", "ts", "tsx", "css", "html",
        )
        val offenders = ArrayList<String>()

        for (rootName in roots) {
            val root = File(rootName)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .onEnter { dir ->
                    dir.name !in setOf("build", "dist", "node_modules", ".git")
                }
                .filter { it.isFile && it.extension.lowercase() in extensions }
                .forEach { file ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var found = false
                        while (!found) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            found = buffer.copyOf(read).any { it == 0.toByte() }
                        }
                        if (found) offenders.add(file.invariantSeparatorsPath)
                    }
                }
        }

        assertWithMessage(
            "Text source files containing NUL bytes become binary to Git and evade review"
        ).that(offenders).isEmpty()
    }
}
