package eve.hq.core

import kotlin.test.Test
import kotlin.test.assertEquals

// Proves the test toolchain runs; delete once core has real tests.
class SmokeTest {
    @Test
    fun toolchainRuns() = assertEquals(4, 2 + 2)
}
