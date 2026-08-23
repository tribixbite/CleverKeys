package tribixbite.cleverkeys

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCoverageDriftTest {
    private val required = setOf(
        "collision_warning_title", "collision_warning_body", "collision_warning_examples",
        "dict_word_too_long_for_swipe_title", "dict_word_too_long_for_swipe_msg",
        "gesture_finger_occlusion_title", "gesture_finger_occlusion_desc",
        "gesture_touch_smoothing_title", "gesture_touch_smoothing_desc",
        "swipe_context_rescoring_title", "swipe_context_rescoring_desc",
        "swipe_engine_fallback_title", "swipe_engine_fallback_desc",
    )

    @Test fun newSwipeCopyExistsInEverySupportedLocale() {
        val localeFiles = File("res").listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && it.name != "values-night" && it.name != "values-v29" }
            .map { File(it, "strings.xml") }
        assertTrue("expected all 21 locale files", localeFiles.size == 21)
        for (file in localeFiles) {
            val text = file.readText()
            for (name in required) {
                assertTrue("${file.parentFile?.name} is missing $name", text.contains("name=\"$name\""))
            }
        }
    }
}
