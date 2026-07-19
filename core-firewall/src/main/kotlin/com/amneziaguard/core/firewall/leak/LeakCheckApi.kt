package com.amneziaguard.core.firewall.leak

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

data class LeakCheckResult(
    val exitIp: String?,
    val dnsLeak: Boolean?,
)

/** Queries an external endpoint to observe the current exit IP / DNS. */
interface LeakCheckApi {
    suspend fun check(): Result<LeakCheckResult>
}

/**
 * Default implementation hitting Mullvad's public JSON endpoint. Any tunneled
 * request already exits through the VPN, so the returned IP reflects the tunnel.
 * A mismatch with the expected server IP signals a leak.
 */
class MullvadLeakCheckApi @Inject constructor() : LeakCheckApi {

    override suspend fun check(): Result<LeakCheckResult> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            connection.inputStream.bufferedReader().use { reader ->
                val body = reader.readText()
                LeakCheckResult(
                    exitIp = extract(body, "ip"),
                    dnsLeak = extract(body, "mullvad_exit_ip")?.let { it == "false" },
                )
            }
        }
    }

    // Tiny extractor to avoid pulling in a JSON library for two fields.
    private fun extract(json: String, key: String): String? {
        val marker = "\"$key\""
        val start = json.indexOf(marker)
        if (start < 0) return null
        val colon = json.indexOf(':', start + marker.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        return if (json[i] == '"') {
            val end = json.indexOf('"', i + 1)
            if (end < 0) null else json.substring(i + 1, end)
        } else {
            val end = json.indexOfFirst(i) { it == ',' || it == '}' }
            if (end < 0) null else json.substring(i, end).trim()
        }
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (idx in from until length) if (predicate(this[idx])) return idx
        return -1
    }

    private companion object {
        const val ENDPOINT = "https://am.i.mullvad.net/json"
    }
}
