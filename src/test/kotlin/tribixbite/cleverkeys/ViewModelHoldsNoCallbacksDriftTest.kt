package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Review 2026-11-14 M9: a ViewModel outlives its Activity across a rotation, so a callback it
 * stores captures the DESTROYED Activity (and leaks it): the backup passphrase retry then ran
 * against a dead lifecycleScope and silently did nothing. ViewModels hold data; the live
 * Activity turns that data into calls.
 */
class ViewModelHoldsNoCallbacksDriftTest {

    @Test
    fun noViewModelStoresAFunctionTypedState() {
        val functionState = Regex("""mutableStateOf<\(\(.*\)\s*->""")
        val offenders = File("src/main/kotlin/tribixbite/cleverkeys").walkTopDown()
            .filter { it.extension == "kt" && ": ViewModel()" in it.readText() }
            .flatMap { f ->
                f.readLines().withIndex().filter { functionState.containsMatchIn(it.value) }
                    .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
            }.toList()
        assertWithMessage("function-typed state in a ViewModel (store data instead)").that(offenders).isEmpty()
    }
}
