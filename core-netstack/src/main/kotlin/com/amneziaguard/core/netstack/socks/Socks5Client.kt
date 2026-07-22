package com.amneziaguard.core.netstack.socks

import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal SOCKS5 client used to relay tun flows into amneziawg-go's local
 * SOCKS5 server (RFC 1928). Only the pieces we need: no-auth or username/
 * password auth, and the CONNECT command for TCP. The handshake logic is kept
 * stream-based so it can be unit-tested against an in-memory pipe.
 */
object Socks5Client {

    private const val VERSION = 0x05
    private const val CMD_CONNECT = 0x01
    private const val CMD_ASSOCIATE = 0x03
    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04
    private const val AUTH_NONE = 0x00
    private const val AUTH_USERPASS = 0x02

    class Socks5Exception(message: String) : Exception(message)

    data class Credentials(val username: String, val password: String)

    /**
     * Performs the greeting + optional auth + CONNECT to [destIp]:[destPort].
     * Throws [Socks5Exception] on any protocol/authorisation failure. On
     * success the streams are positioned right after the CONNECT reply, ready
     * to relay payload bytes.
     */
    fun connect(
        input: InputStream,
        output: OutputStream,
        destIp: ByteArray,
        destPort: Int,
        credentials: Credentials? = null,
    ) {
        greet(input, output, credentials)
        sendConnect(input, output, destIp, destPort)
    }

    /**
     * Opens a UDP association (RFC 1928 §4/§7) on an already-connected control
     * stream and returns the relay endpoint to send datagrams to. The request
     * declares 0.0.0.0:0 so the server accepts datagrams from any local port —
     * it validates the source against this address.
     *
     * The association lives as long as the control stream: closing it tears the
     * relay down.
     */
    fun associate(
        input: InputStream,
        output: OutputStream,
        credentials: Credentials? = null,
    ): Pair<ByteArray, Int> {
        greet(input, output, credentials)
        output.write(
            byteArrayOf(VERSION.toByte(), CMD_ASSOCIATE.toByte(), 0x00, ATYP_IPV4.toByte(),
                0, 0, 0, 0, 0, 0),
        )
        output.flush()

        val head = readExactly(input, 4)
        if (head[0].toInt() and 0xFF != VERSION) throw Socks5Exception("bad reply version")
        val rep = head[1].toInt() and 0xFF
        if (rep != 0x00) throw Socks5Exception("ASSOCIATE failed, reply=$rep")
        val addr = when (head[3].toInt() and 0xFF) {
            ATYP_IPV4 -> readExactly(input, 4)
            ATYP_IPV6 -> readExactly(input, 16)
            ATYP_DOMAIN -> readExactly(input, readExactly(input, 1)[0].toInt() and 0xFF)
            else -> throw Socks5Exception("bad reply atyp")
        }
        val portBytes = readExactly(input, 2)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return addr to port
    }

    private fun greet(input: InputStream, output: OutputStream, credentials: Credentials?) {
        val methods = if (credentials != null) {
            byteArrayOf(AUTH_NONE.toByte(), AUTH_USERPASS.toByte())
        } else {
            byteArrayOf(AUTH_NONE.toByte())
        }
        output.write(byteArrayOf(VERSION.toByte(), methods.size.toByte()) + methods)
        output.flush()

        val reply = readExactly(input, 2)
        if (reply[0].toInt() and 0xFF != VERSION) throw Socks5Exception("bad SOCKS version")
        when (reply[1].toInt() and 0xFF) {
            AUTH_NONE -> Unit
            AUTH_USERPASS -> {
                val creds = credentials ?: throw Socks5Exception("server requires auth")
                authenticate(input, output, creds)
            }
            0xFF -> throw Socks5Exception("no acceptable auth method")
            else -> throw Socks5Exception("unexpected auth method")
        }
    }

    private fun authenticate(input: InputStream, output: OutputStream, creds: Credentials) {
        val user = creds.username.toByteArray(Charsets.UTF_8)
        val pass = creds.password.toByteArray(Charsets.UTF_8)
        output.write(byteArrayOf(0x01, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        output.flush()
        val reply = readExactly(input, 2)
        if (reply[1].toInt() != 0) throw Socks5Exception("auth rejected")
    }

    private fun sendConnect(input: InputStream, output: OutputStream, destIp: ByteArray, destPort: Int) {
        val atyp = if (destIp.size == 16) ATYP_IPV6 else ATYP_IPV4
        output.write(
            byteArrayOf(VERSION.toByte(), CMD_CONNECT.toByte(), 0x00, atyp.toByte()) +
                destIp +
                byteArrayOf((destPort shr 8).toByte(), destPort.toByte()),
        )
        output.flush()

        val head = readExactly(input, 4)
        if (head[0].toInt() and 0xFF != VERSION) throw Socks5Exception("bad reply version")
        val rep = head[1].toInt() and 0xFF
        if (rep != 0x00) throw Socks5Exception("CONNECT failed, reply=$rep")
        // Drain the bound-address portion of the reply.
        val boundLen = when (head[3].toInt() and 0xFF) {
            ATYP_IPV4 -> 4
            ATYP_IPV6 -> 16
            ATYP_DOMAIN -> (readExactly(input, 1)[0].toInt() and 0xFF)
            else -> throw Socks5Exception("bad reply atyp")
        }
        readExactly(input, boundLen + 2)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw Socks5Exception("stream closed after $read/$n bytes")
            read += r
        }
        return buf
    }
}
