package com.amneziaguard.core.netstack.socks

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Socks5ClientTest {

    @Test
    fun `no-auth connect sends correct greeting and request`() {
        // Server: choose no-auth (05 00), then CONNECT success with IPv4 bound addr.
        val serverBytes = byteArrayOf(
            0x05, 0x00,
            0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0,
        )
        val input = ByteArrayInputStream(serverBytes)
        val output = ByteArrayOutputStream()

        Socks5Client.connect(input, output, byteArrayOf(1, 1, 1, 1), 443)

        val sent = output.toByteArray()
        // Greeting: VER=05, NMETHODS=01, METHOD=00
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), sent.copyOfRange(0, 3))
        // Request: VER=05 CMD=01 RSV=00 ATYP=01 addr(4) port(2)
        assertArrayEquals(
            byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 1, 1, 1, (443 shr 8).toByte(), (443 and 0xFF).toByte()),
            sent.copyOfRange(3, sent.size),
        )
    }

    @Test
    fun `username-password auth flow succeeds`() {
        val serverBytes = byteArrayOf(
            0x05, 0x02,          // choose user/pass
            0x01, 0x00,          // auth success
            0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, // connect ok
        )
        val output = ByteArrayOutputStream()
        Socks5Client.connect(
            ByteArrayInputStream(serverBytes),
            output,
            byteArrayOf(10, 0, 0, 1),
            8080,
            Socks5Client.Credentials("u", "p"),
        )
        val sent = output.toByteArray()
        // Greeting offers both no-auth and user/pass.
        assertEquals(0x05, sent[0].toInt())
        assertEquals(0x02, sent[1].toInt())
    }

    @Test
    fun `connect failure reply throws`() {
        val serverBytes = byteArrayOf(
            0x05, 0x00,
            0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0, // REP=05 connection refused
        )
        assertThrows(Socks5Client.Socks5Exception::class.java) {
            Socks5Client.connect(
                ByteArrayInputStream(serverBytes),
                ByteArrayOutputStream(),
                byteArrayOf(1, 1, 1, 1),
                443,
            )
        }
    }

    @Test
    fun `ipv6 destination uses atyp 4`() {
        val serverBytes = byteArrayOf(
            0x05, 0x00,
            0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0,
        )
        val output = ByteArrayOutputStream()
        val v6 = ByteArray(16).also { it[15] = 1 } // ::1
        Socks5Client.connect(ByteArrayInputStream(serverBytes), output, v6, 443)
        val sent = output.toByteArray()
        // Byte after greeting: request ATYP at index 3+3 = 6.
        assertEquals(0x04, sent[6].toInt())
    }
}
