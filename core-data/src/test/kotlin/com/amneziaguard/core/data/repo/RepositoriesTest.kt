package com.amneziaguard.core.data.repo

import com.amneziaguard.core.data.model.AppMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepositoriesTest {

    @Test
    fun `server save splits secrets out of Room and keeps them in the SecretStore`() = runTest {
        val dao = FakeServerDao()
        val secrets = FakeSecretStore()
        val repo = ServerRepository(dao, secrets)

        val id = repo.save(
            name = "Home",
            confBody = "[Interface]\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = abc",
            endpoint = "vpn.example.com:51820",
            privateKey = "SECRET_PRIVATE",
            presharedKeys = mapOf(0 to "SECRET_PSK"),
        )

        val stored = dao.byId(id)!!
        // The private key must never be persisted in Room.
        assert(!stored.confBody.contains("SECRET_PRIVATE"))
        assertEquals("SECRET_PRIVATE", secrets.getPrivateKey(id))
        assertEquals("SECRET_PSK", secrets.getPresharedKey(id, 0))
    }

    @Test
    fun `deleting a server clears its secrets`() = runTest {
        val dao = FakeServerDao()
        val secrets = FakeSecretStore()
        val repo = ServerRepository(dao, secrets)
        val id = repo.save("Home", "[Interface]\n\n[Peer]\nPublicKey = abc", "e:1", "PK", emptyMap())

        repo.delete(id)

        assertNull(dao.byId(id))
        assertNull(secrets.getPrivateKey(id))
    }

    @Test
    fun `applyGroup writes the group mode to every member`() = runTest {
        val ruleDao = FakeAppRuleDao()
        val groupDao = FakeAppGroupDao()
        val repo = RuleRepository(ruleDao, groupDao)

        val groupId = repo.createGroup(
            name = "Trackers",
            mode = AppMode.BLOCK,
            members = listOf("com.ads", "com.tracker"),
        )
        repo.applyGroup(groupId)

        val rules = repo.observeRules().first()
        assertEquals(AppMode.BLOCK, rules["com.ads"])
        assertEquals(AppMode.BLOCK, rules["com.tracker"])
    }

    @Test
    fun `setMode with null clears the rule`() = runTest {
        val repo = RuleRepository(FakeAppRuleDao(), FakeAppGroupDao())
        repo.setMode("com.foo", AppMode.BYPASS)
        assertEquals(AppMode.BYPASS, repo.rules()["com.foo"])

        repo.setMode("com.foo", null)
        assertNull(repo.rules()["com.foo"])
    }
}
