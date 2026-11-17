package com.nuvio.app.features.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrCodeEncoderTest {
    @Test
    fun shortUrlUsesSmallVersionWithFinderPatterns() {
        val modules = assertNotNull(QrCodeEncoder.encode("https://nuvio.tv/link?code=ABCD-1234"))
        assertEquals(29, modules.size) // version 3
        val last = modules.size - 1
        // Finder pattern corners are dark, separators are light.
        assertTrue(modules[0][0] && modules[0][last] && modules[last][0])
        assertTrue(!modules[7][0] && !modules[0][7])
        // Timing pattern alternates.
        for (i in 8 until last - 7) assertEquals(i % 2 == 0, modules[6][i])
    }

    @Test
    fun longestSupportedPayloadFits() {
        assertEquals(57, assertNotNull(QrCodeEncoder.encode("a".repeat(213))).size) // version 10
    }

    @Test
    fun tooLongPayloadReturnsNull() {
        assertNull(QrCodeEncoder.encode("a".repeat(214)))
    }
}
