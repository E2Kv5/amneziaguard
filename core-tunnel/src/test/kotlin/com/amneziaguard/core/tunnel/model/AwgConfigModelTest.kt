package com.amneziaguard.core.tunnel.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgConfigModelTest {

    private val fullConf = """
        [Interface]
        PrivateKey = cGl2YXRla2V5cGl2YXRla2V5cGl2YXRla2V5cGl2YQ==
        Address = 10.8.0.2/32, fd00::2/128
        DNS = 1.1.1.1, 8.8.8.8
        MTU = 1280
        ListenPort = 51820
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 116
        S2 = 61
        S3 = 12
        S4 = 32
        H1 = 1234567890
        H2 = 987654321
        H3 = 32-64535
        H4 = 76600000-77000000
        I1 = <b 0xc6000000010864e3d6db>
        I2 = <b 0x0400000000>
        I3 = <t 15>
        I4 = <r 32>
        I5 = <wt 10>
        IncludedApplications = org.example.one, org.example.two

        [Peer]
        PublicKey = cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHVibGk=
        PresharedKey = cHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHM=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = vpn.example.com:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun `parses all awg 2_0 interface parameters`() {
        val config = AwgConfigModel.parseOrThrow(fullConf)
        val iface = config.iface
        assertEquals(4, iface.jc)
        assertEquals(40, iface.jmin)
        assertEquals(70, iface.jmax)
        assertEquals(116, iface.s1)
        assertEquals(61, iface.s2)
        assertEquals(12, iface.s3)
        assertEquals(32, iface.s4)
        assertEquals("1234567890", iface.h1)
        assertEquals("987654321", iface.h2)
        assertEquals("32-64535", iface.h3)
        assertEquals("76600000-77000000", iface.h4)
        assertEquals("<b 0xc6000000010864e3d6db>", iface.i1)
        assertEquals("<b 0x0400000000>", iface.i2)
        assertEquals("<t 15>", iface.i3)
        assertEquals("<r 32>", iface.i4)
        assertEquals("<wt 10>", iface.i5)
        assertEquals(listOf("org.example.one", "org.example.two"), iface.includedApplications)
        assertEquals(listOf("10.8.0.2/32", "fd00::2/128"), iface.addresses)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), iface.dns)
        assertEquals(1280, iface.mtu)
        assertEquals(51820, iface.listenPort)
    }

    @Test
    fun `parses peer section`() {
        val peer = AwgConfigModel.parseOrThrow(fullConf).peers.single()
        assertEquals("cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHVibGk=", peer.publicKey)
        assertEquals("vpn.example.com:51820", peer.endpoint)
        assertEquals(listOf("0.0.0.0/0", "::/0"), peer.allowedIps)
        assertEquals(25, peer.persistentKeepalive)
    }

    @Test
    fun `serialize then parse is identity`() {
        val first = AwgConfigModel.parseOrThrow(fullConf)
        val second = AwgConfigModel.parseOrThrow(first.serialize())
        assertEquals(first, second)
    }

    @Test
    fun `serialize is idempotent`() {
        val model = AwgConfigModel.parseOrThrow(fullConf)
        assertEquals(model.serialize(), AwgConfigModel.parseOrThrow(model.serialize()).serialize())
    }

    @Test
    fun `keys are case-insensitive and comments are stripped`() {
        val config = AwgConfigModel.parseOrThrow(
            """
            [interface]
            address = 10.0.0.2/32 # inline comment
            jC = 7
            # full-line comment

            [PEER]
            publickey = abc
            """.trimIndent()
        )
        assertEquals(listOf("10.0.0.2/32"), config.iface.addresses)
        assertEquals(7, config.iface.jc)
        assertEquals("abc", config.peers.single().publicKey)
    }

    @Test
    fun `repeated list keys accumulate`() {
        val config = AwgConfigModel.parseOrThrow(
            """
            [Interface]
            Address = 10.0.0.2/32
            Address = fd00::2/128

            [Peer]
            PublicKey = abc
            AllowedIPs = 0.0.0.0/0
            AllowedIPs = ::/0
            """.trimIndent()
        )
        assertEquals(2, config.iface.addresses.size)
        assertEquals(2, config.peers.single().allowedIps.size)
    }

    @Test
    fun `missing interface section fails`() {
        val result = AwgConfigModel.parse("[Peer]\nPublicKey = abc")
        assertTrue(result.isFailure)
    }

    @Test
    fun `peer without public key fails`() {
        val result = AwgConfigModel.parse("[Interface]\nAddress = 10.0.0.2/32\n\n[Peer]\nEndpoint = a:1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `non-integer jc fails`() {
        val result = AwgConfigModel.parse("[Interface]\nJc = many")
        assertTrue(result.isFailure)
    }

    @Test
    fun `jmin greater than jmax fails`() {
        val result = AwgConfigModel.parse("[Interface]\nJmin = 80\nJmax = 40")
        assertTrue(result.isFailure)
    }

    @Test
    fun `attribute outside section fails`() {
        val result = AwgConfigModel.parse("Address = 10.0.0.2/32")
        assertTrue(result.isFailure)
    }

    @Test
    fun `unknown attribute fails`() {
        val result = AwgConfigModel.parse("[Interface]\nBogus = 1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `splitSecrets removes keys and withSecrets restores them`() {
        val model = AwgConfigModel.parseOrThrow(fullConf)
        val split = model.splitSecrets()

        assertNull(split.config.iface.privateKey)
        assertTrue(split.config.peers.all { it.presharedKey == null })
        assertEquals("cGl2YXRla2V5cGl2YXRla2V5cGl2YXRla2V5cGl2YQ==", split.privateKey)
        assertEquals(1, split.presharedKeys.size)

        val restored = split.config.withSecrets(split.privateKey, split.presharedKeys)
        assertEquals(model, restored)
    }

    @Test
    fun `extra interface lines are appended inside interface section`() {
        val model = AwgConfigModel.parseOrThrow(
            "[Interface]\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = abc"
        )
        val text = model.serialize(extraInterfaceLines = listOf("ExcludedApplications = com.foo"))
        val reparsed = AwgConfigModel.parseOrThrow(text)
        assertEquals(listOf("com.foo"), reparsed.iface.excludedApplications)
    }

    @Test
    fun `endpoint summary comes from first peer with endpoint`() {
        val model = AwgConfigModel.parseOrThrow(fullConf)
        assertEquals("vpn.example.com:51820", model.endpointSummary)
    }
}
