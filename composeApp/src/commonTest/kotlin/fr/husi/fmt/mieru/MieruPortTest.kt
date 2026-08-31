package fr.husi.fmt.mieru

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MieruPortTest {

    @Test
    fun `valid ports and ranges are normalized`() {
        val cases = mapOf(
            "4012" to "4012",
            " 4012-4021 " to "4012-4021",
            "1" to "1",
            "65535" to "65535",
            "1-65535" to "1-65535",
        )

        for ((input, expected) in cases) {
            assertEquals(expected, parseMieruPort(input).toString(), input)
            assertNull(validateMieruPort(input), input)
        }
    }

    @Test
    fun `invalid ports and ranges are rejected`() {
        val cases = listOf(
            "0",
            "65536",
            "4021-4012",
            "4012-",
            "-4021",
            "4012-4021-4030",
            "abc",
            "4012 - 4021",
            "",
        )

        for (input in cases) {
            assertFailsWith<IllegalArgumentException>(input) { parseMieruPort(input) }
        }
    }
}
