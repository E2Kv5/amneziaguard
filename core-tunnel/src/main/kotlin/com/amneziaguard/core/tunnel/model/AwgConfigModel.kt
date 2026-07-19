package com.amneziaguard.core.tunnel.model

/**
 * Pure-Kotlin model of an AmneziaWG .conf file, including the AWG 2.0
 * obfuscation parameters (Jc/Jmin/Jmax, S1-S4, H1-H4, I1-I5). Kept free of
 * Android types so the parser is unit-testable on the JVM and usable by the
 * config editor. Serialization output is accepted by the tunnel library's own
 * org.amnezia.awg.config.Config.parse().
 *
 * H1-H4 are kept as strings to preserve AWG 2.0 range syntax ("123-456");
 * I1-I5 are opaque signature-packet definitions.
 */
data class AwgInterface(
    val privateKey: String? = null,
    val addresses: List<String> = emptyList(),
    val dns: List<String> = emptyList(),
    val listenPort: Int? = null,
    val mtu: Int? = null,
    val jc: Int? = null,
    val jmin: Int? = null,
    val jmax: Int? = null,
    val s1: Int? = null,
    val s2: Int? = null,
    val s3: Int? = null,
    val s4: Int? = null,
    val h1: String? = null,
    val h2: String? = null,
    val h3: String? = null,
    val h4: String? = null,
    val i1: String? = null,
    val i2: String? = null,
    val i3: String? = null,
    val i4: String? = null,
    val i5: String? = null,
    val includedApplications: List<String> = emptyList(),
    val excludedApplications: List<String> = emptyList(),
)

data class AwgPeer(
    val publicKey: String,
    val presharedKey: String? = null,
    val endpoint: String? = null,
    val allowedIps: List<String> = emptyList(),
    val persistentKeepalive: Int? = null,
)

class AwgParseException(message: String, val line: Int? = null) :
    Exception(if (line != null) "line $line: $message" else message)

data class AwgConfigModel(
    val iface: AwgInterface,
    val peers: List<AwgPeer>,
) {

    val endpointSummary: String
        get() = peers.firstNotNullOfOrNull { it.endpoint } ?: ""

    /** Serializes back to .conf text with canonical key names. */
    fun serialize(extraInterfaceLines: List<String> = emptyList()): String = buildString {
        appendLine("[Interface]")
        iface.privateKey?.let { appendLine("PrivateKey = $it") }
        if (iface.addresses.isNotEmpty()) appendLine("Address = ${iface.addresses.joinToString(", ")}")
        if (iface.dns.isNotEmpty()) appendLine("DNS = ${iface.dns.joinToString(", ")}")
        iface.listenPort?.let { appendLine("ListenPort = $it") }
        iface.mtu?.let { appendLine("MTU = $it") }
        iface.jc?.let { appendLine("Jc = $it") }
        iface.jmin?.let { appendLine("Jmin = $it") }
        iface.jmax?.let { appendLine("Jmax = $it") }
        iface.s1?.let { appendLine("S1 = $it") }
        iface.s2?.let { appendLine("S2 = $it") }
        iface.s3?.let { appendLine("S3 = $it") }
        iface.s4?.let { appendLine("S4 = $it") }
        iface.h1?.let { appendLine("H1 = $it") }
        iface.h2?.let { appendLine("H2 = $it") }
        iface.h3?.let { appendLine("H3 = $it") }
        iface.h4?.let { appendLine("H4 = $it") }
        iface.i1?.let { appendLine("I1 = $it") }
        iface.i2?.let { appendLine("I2 = $it") }
        iface.i3?.let { appendLine("I3 = $it") }
        iface.i4?.let { appendLine("I4 = $it") }
        iface.i5?.let { appendLine("I5 = $it") }
        if (iface.includedApplications.isNotEmpty()) {
            appendLine("IncludedApplications = ${iface.includedApplications.joinToString(", ")}")
        }
        if (iface.excludedApplications.isNotEmpty()) {
            appendLine("ExcludedApplications = ${iface.excludedApplications.joinToString(", ")}")
        }
        extraInterfaceLines.forEach { appendLine(it) }
        peers.forEach { peer ->
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${peer.publicKey}")
            peer.presharedKey?.let { appendLine("PresharedKey = $it") }
            if (peer.allowedIps.isNotEmpty()) appendLine("AllowedIPs = ${peer.allowedIps.joinToString(", ")}")
            peer.endpoint?.let { appendLine("Endpoint = $it") }
            peer.persistentKeepalive?.let { appendLine("PersistentKeepalive = $it") }
        }
    }

    /** Copy without secrets, plus the secrets themselves, for split storage. */
    fun splitSecrets(): SplitConfig {
        val psks = buildMap {
            peers.forEachIndexed { index, peer ->
                peer.presharedKey?.let { put(index, it) }
            }
        }
        val stripped = copy(
            iface = iface.copy(privateKey = null),
            peers = peers.map { it.copy(presharedKey = null) },
        )
        return SplitConfig(stripped, iface.privateKey, psks)
    }

    /** Restores secrets into the model (inverse of [splitSecrets]). */
    fun withSecrets(privateKey: String?, presharedKeys: Map<Int, String>): AwgConfigModel =
        copy(
            iface = iface.copy(privateKey = privateKey),
            peers = peers.mapIndexed { index, peer ->
                peer.copy(presharedKey = presharedKeys[index] ?: peer.presharedKey)
            },
        )

    data class SplitConfig(
        val config: AwgConfigModel,
        val privateKey: String?,
        val presharedKeys: Map<Int, String>,
    )

    companion object {

        fun parse(text: String): Result<AwgConfigModel> = runCatching { parseOrThrow(text) }

        fun parseOrThrow(text: String): AwgConfigModel {
            var iface: MutableInterface? = null
            var currentPeer: MutablePeer? = null
            val peers = mutableListOf<MutablePeer>()
            var section = Section.NONE

            text.lineSequence().forEachIndexed { index, raw ->
                val lineNo = index + 1
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed

                if (line.startsWith("[")) {
                    when (line.lowercase()) {
                        "[interface]" -> {
                            if (iface != null) throw AwgParseException("duplicate [Interface] section", lineNo)
                            iface = MutableInterface()
                            section = Section.INTERFACE
                        }
                        "[peer]" -> {
                            currentPeer = MutablePeer().also { peers.add(it) }
                            section = Section.PEER
                        }
                        else -> throw AwgParseException("unknown section $line", lineNo)
                    }
                    return@forEachIndexed
                }

                val eq = line.indexOf('=')
                if (eq <= 0) throw AwgParseException("expected 'Key = value'", lineNo)
                val key = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()
                if (value.isEmpty()) throw AwgParseException("empty value for $key", lineNo)

                when (section) {
                    Section.NONE -> throw AwgParseException("attribute outside of a section", lineNo)
                    Section.INTERFACE -> iface!!.set(key, value, lineNo)
                    Section.PEER -> currentPeer!!.set(key, value, lineNo)
                }
            }

            val parsedIface = iface ?: throw AwgParseException("missing [Interface] section")
            validate(parsedIface, lineNoHint = null)
            return AwgConfigModel(
                iface = parsedIface.build(),
                peers = peers.map { it.build() },
            )
        }

        private fun validate(iface: MutableInterface, lineNoHint: Int?) {
            val jmin = iface.jmin
            val jmax = iface.jmax
            if (jmin != null && jmax != null && jmin > jmax) {
                throw AwgParseException("Jmin ($jmin) must not exceed Jmax ($jmax)", lineNoHint)
            }
            val jc = iface.jc
            if (jc != null && jc < 0) {
                throw AwgParseException("Jc must be non-negative", lineNoHint)
            }
        }

        private fun parseInt(key: String, value: String, line: Int): Int =
            value.toIntOrNull() ?: throw AwgParseException("$key expects an integer, got '$value'", line)

        private fun parseList(value: String): List<String> =
            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private enum class Section { NONE, INTERFACE, PEER }

        private class MutableInterface {
            var privateKey: String? = null
            val addresses = mutableListOf<String>()
            val dns = mutableListOf<String>()
            var listenPort: Int? = null
            var mtu: Int? = null
            var jc: Int? = null
            var jmin: Int? = null
            var jmax: Int? = null
            var s1: Int? = null
            var s2: Int? = null
            var s3: Int? = null
            var s4: Int? = null
            var h1: String? = null
            var h2: String? = null
            var h3: String? = null
            var h4: String? = null
            var i1: String? = null
            var i2: String? = null
            var i3: String? = null
            var i4: String? = null
            var i5: String? = null
            val includedApplications = mutableListOf<String>()
            val excludedApplications = mutableListOf<String>()

            fun set(key: String, value: String, line: Int) {
                when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses += parseList(value)
                    "dns" -> dns += parseList(value)
                    "listenport" -> listenPort = parseInt(key, value, line)
                    "mtu" -> mtu = parseInt(key, value, line)
                    "jc" -> jc = parseInt(key, value, line)
                    "jmin" -> jmin = parseInt(key, value, line)
                    "jmax" -> jmax = parseInt(key, value, line)
                    "s1" -> s1 = parseInt(key, value, line)
                    "s2" -> s2 = parseInt(key, value, line)
                    "s3" -> s3 = parseInt(key, value, line)
                    "s4" -> s4 = parseInt(key, value, line)
                    "h1" -> h1 = value
                    "h2" -> h2 = value
                    "h3" -> h3 = value
                    "h4" -> h4 = value
                    "i1" -> i1 = value
                    "i2" -> i2 = value
                    "i3" -> i3 = value
                    "i4" -> i4 = value
                    "i5" -> i5 = value
                    "includedapplications" -> includedApplications += parseList(value)
                    "excludedapplications" -> excludedApplications += parseList(value)
                    else -> throw AwgParseException("unknown [Interface] attribute '$key'", line)
                }
            }

            fun build() = AwgInterface(
                privateKey = privateKey,
                addresses = addresses.toList(),
                dns = dns.toList(),
                listenPort = listenPort,
                mtu = mtu,
                jc = jc, jmin = jmin, jmax = jmax,
                s1 = s1, s2 = s2, s3 = s3, s4 = s4,
                h1 = h1, h2 = h2, h3 = h3, h4 = h4,
                i1 = i1, i2 = i2, i3 = i3, i4 = i4, i5 = i5,
                includedApplications = includedApplications.toList(),
                excludedApplications = excludedApplications.toList(),
            )
        }

        private class MutablePeer {
            var publicKey: String? = null
            var presharedKey: String? = null
            var endpoint: String? = null
            val allowedIps = mutableListOf<String>()
            var persistentKeepalive: Int? = null

            fun set(key: String, value: String, line: Int) {
                when (key) {
                    "publickey" -> publicKey = value
                    "presharedkey" -> presharedKey = value
                    "endpoint" -> endpoint = value
                    "allowedips" -> allowedIps += parseList(value)
                    "persistentkeepalive" -> persistentKeepalive = parseInt(key, value, line)
                    else -> throw AwgParseException("unknown [Peer] attribute '$key'", line)
                }
            }

            fun build(): AwgPeer {
                val pk = publicKey ?: throw AwgParseException("[Peer] section is missing PublicKey")
                return AwgPeer(
                    publicKey = pk,
                    presharedKey = presharedKey,
                    endpoint = endpoint,
                    allowedIps = allowedIps.toList(),
                    persistentKeepalive = persistentKeepalive,
                )
            }
        }
    }
}
