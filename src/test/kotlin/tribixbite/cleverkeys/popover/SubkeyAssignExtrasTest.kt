package tribixbite.cleverkeys.popover

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * The popover's assign/edit request survives the trip through `SubkeyAssignActivity`'s intent
 * extras ([SubkeyAssignExtras], the code `launch` and `requestFrom` share). Pure JVM: a map
 * stands in for the Intent.
 */
class SubkeyAssignExtrasTest {

    private fun roundTrip(request: SubkeyAssignRequest): SubkeyAssignRequest? {
        val strings = HashMap<String, String?>()
        val booleans = HashMap<String, Boolean>()
        SubkeyAssignExtras.write(request, { k, v -> strings[k] = v }, { k, v -> booleans[k] = v })
        return SubkeyAssignExtras.read({ strings[it] }, { k, default -> booleans[k] ?: default })
    }

    @Test
    fun everyFieldRoundTrips() {
        for (mode in SubkeyAssignRequest.Mode.entries) {
            for (direction in SwipeDirection.entries) {
                val request = SubkeyAssignRequest(
                    keyCode = "e", direction = direction, mode = mode,
                    hasDefault = true, isCustom = mode == SubkeyAssignRequest.Mode.EDIT,
                    currentLabel = "\\uE00D", labelUsesKeyFont = true,
                )
                assertThat(roundTrip(request)).isEqualTo(request)
            }
        }
        val plain = SubkeyAssignRequest("q", SwipeDirection.SW, SubkeyAssignRequest.Mode.ASSIGN, false, false, null)
        assertThat(roundTrip(plain)).isEqualTo(plain)
    }

    @Test
    fun missingOrUnknownRequiredExtrasReadAsNull() {
        val valid = mapOf(
            SubkeyAssignExtras.KEY to "e",
            SubkeyAssignExtras.DIRECTION to "NE",
            SubkeyAssignExtras.MODE to "ASSIGN",
        )
        assertThat(SubkeyAssignExtras.read({ valid[it] }, { _, d -> d })).isNotNull()
        for (broken in listOf(
            valid - SubkeyAssignExtras.KEY,
            valid + (SubkeyAssignExtras.KEY to ""),
            valid + (SubkeyAssignExtras.DIRECTION to "UP"),
            valid - SubkeyAssignExtras.MODE,
            valid + (SubkeyAssignExtras.MODE to "DELETE"),
        )) {
            assertThat(SubkeyAssignExtras.read({ broken[it] }, { _, d -> d })).isNull()
        }
    }
}
