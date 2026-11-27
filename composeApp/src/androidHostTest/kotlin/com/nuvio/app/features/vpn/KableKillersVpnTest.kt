package com.nuvio.app.features.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KableKillersVpnTest {
    private val config = """
        [Interface]
        PrivateKey = aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI=
        Address = 10.0.0.2/32
        DNS = 198.18.0.1

        [Peer]
        PublicKey = d29ybGQtd29ybGQtd29ybGQtd29ybGQtd29ybGQtMTI=
        AllowedIPs = 0.0.0.0/0
        Endpoint = atl-a01.wg.ipvanish.com:51820
    """.trimIndent()

    @Test
    fun appOnlyRoutingAddsKableKillersPackage() {
        val routed = KableKillersVpn.withRouting(config, "com.kablekillers.mobile", wholeDevice = false)
        val lines = routed.lines()
        assertEquals("[Interface]", lines[0])
        assertEquals("IncludedApplications = com.kablekillers.mobile", lines[1])
    }

    @Test
    fun wholeDeviceLeavesConfigUntouched() {
        assertEquals(config, KableKillersVpn.withRouting(config, "com.kablekillers.mobile", wholeDevice = true))
    }

    @Test
    fun existingAppListIsKept() {
        val custom = config.replace("[Interface]", "[Interface]\nExcludedApplications = com.example")
        val routed = KableKillersVpn.withRouting(custom, "com.kablekillers.mobile", wholeDevice = false)
        assertEquals(custom, routed)
        assertTrue("IncludedApplications" !in routed)
    }

    @Test
    fun serverLabelComesFromEndpoint() {
        assertEquals("atl-a01.wg.ipvanish.com", KableKillersVpn.serverLabelOf(config))
    }
}
