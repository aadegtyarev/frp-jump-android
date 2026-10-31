package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.SecretBox

class MqttLoginsTest {
    private val storage = MemoryStorage()

    /** Seals by reversing: the stored text must never hold the password as typed. */
    private val box = object : SecretBox {
        override fun seal(plain: String) = "sealed:" + plain.reversed()

        override fun open(sealed: String) = sealed.removePrefix("sealed:").reversed()

        override fun destroy() = Unit
    }

    @Test
    fun `a remembered login is stored sealed, per connection`() {
        MqttLogins(storage, box).remember("work", "c1", MqttLogin("user", "secret-pw"))
        assertFalse(storage.text!!.contains("secret-pw"))
        val restarted = MqttLogins(storage, box)
        val login = restarted.get("work", "c1")!!
        assertEquals("user", login.user)
        assertEquals("secret-pw", login.password)
        assertNull(restarted.get("work", "c2"))
        assertNull(restarted.get("home", "c1"))
    }

    @Test
    fun `deleting a connection, forgetting a profile and wiping clear the logins`() {
        val logins = MqttLogins(storage, box)
        logins.remember("work", "c1", MqttLogin("a", "1"))
        logins.remember("work", "c2", MqttLogin("b", "2"))
        logins.remember("home", "c3", MqttLogin("c", "3"))
        logins.forget("work", "c1")
        assertNull(MqttLogins(storage, box).get("work", "c1"))
        logins.forgetProfile("work")
        assertNull(MqttLogins(storage, box).get("work", "c2"))
        assertEquals("c", MqttLogins(storage, box).get("home", "c3")!!.user)
        logins.wipe()
        assertNull(storage.text)
        assertNull(logins.get("home", "c3"))
    }

    @Test
    fun `a login whose key is gone reads as none`() {
        val keystore = FakeKeystore()
        val sealing = keystore.box("frpjump-ssh")
        MqttLogins(storage, sealing).remember("work", "c1", MqttLogin("u", "p"))
        sealing.destroy()
        assertNull(MqttLogins(storage, sealing).get("work", "c1"))
    }

    @Test
    fun `pins are kept per connection and go with it`() {
        val pins = PinStore(storage)
        pins.set("work", "c1", listOf(pinFilter(MqttPath.parse("/devices/x"), PinMask.ONE_LEVEL_BELOW), "\$SYS/#"))
        assertEquals(listOf("/devices/x/+", "\$SYS/#"), PinStore(storage).get("work", "c1"))
        pins.forget("work", "c1")
        assertTrue(PinStore(storage).get("work", "c1").isEmpty())
    }
}
