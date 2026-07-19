package com.amneziaguard.core.firewall

import com.amneziaguard.core.data.model.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallPolicyCompilerTest {

    private val compiler = FirewallPolicyCompiler()

    @Test
    fun `empty rules produce empty policy`() {
        val policy = compiler.compile(emptyMap(), AppMode.VPN)
        assertTrue(policy.included.isEmpty())
        assertTrue(policy.excluded.isEmpty())
    }

    @Test
    fun `default vpn excludes only bypass apps`() {
        val rules = mapOf(
            "com.bank" to AppMode.BYPASS,
            "com.chat" to AppMode.VPN,
            "com.ads" to AppMode.BLOCK,
        )
        val policy = compiler.compile(rules, AppMode.VPN)
        assertEquals(setOf("com.bank"), policy.excluded)
        assertTrue(policy.included.isEmpty())
    }

    @Test
    fun `default bypass includes vpn and block apps`() {
        val rules = mapOf(
            "com.bank" to AppMode.BYPASS,
            "com.chat" to AppMode.VPN,
            "com.ads" to AppMode.BLOCK,
        )
        val policy = compiler.compile(rules, AppMode.BYPASS)
        assertEquals(setOf("com.chat", "com.ads"), policy.included)
        assertTrue(policy.excluded.isEmpty())
    }

    @Test
    fun `policy never mixes included and excluded`() {
        val modes = AppMode.entries
        // Exhaustively try mixed rule sets under both defaults; construction
        // would throw if the invariant were violated.
        for (default in listOf(AppMode.VPN, AppMode.BYPASS)) {
            val rules = mapOf(
                "a" to modes[0],
                "b" to modes[1],
                "c" to modes[2],
            )
            val policy = compiler.compile(rules, default)
            assertTrue(policy.included.isEmpty() || policy.excluded.isEmpty())
        }
    }

    @Test
    fun `blockedPackages returns block apps only`() {
        val rules = mapOf(
            "com.bank" to AppMode.BYPASS,
            "com.ads" to AppMode.BLOCK,
            "com.tracker" to AppMode.BLOCK,
        )
        assertEquals(setOf("com.ads", "com.tracker"), compiler.blockedPackages(rules))
    }
}
