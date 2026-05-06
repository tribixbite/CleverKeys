package tribixbite.cleverkeys.clipboard.sanitize

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RulesetParserTest {

    @Test
    fun emptyProviders_parsesToEmptyMap() {
        val json = """{"providers":{}}"""
        val rs = RulesetParser.fromJson(json)
        assertThat(rs.providers).isEmpty()
    }
}
