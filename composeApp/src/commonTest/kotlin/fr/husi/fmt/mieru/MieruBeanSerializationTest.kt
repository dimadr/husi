package fr.husi.fmt.mieru

import fr.husi.fmt.BeanConverters
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MieruBeanSerializationTest {

    @Test
    fun `version 2 payload remains readable`() {
        val version2 = Base64.getDecoder().decode(
            "AgAAAHZfc2VydmVyQWRkcmVz8z3fAABUQ9B2X3VzZXJuYW3ldl9wYXNzd29y5HZfdHJhZ" +
                "mZpY1BhdHRlcu4EAAAAdl9uYW3ldl9jdXN0b21PdXRib3VuZEpzb+52X2N1c3RvbUNvbmZp" +
                "Z0pzb+4BAXqCAAAXygAAAaEkAAA=",
        )

        val bean = BeanConverters.deserialize(MieruBean(), version2)

        assertEquals(57149, bean.serverPort)
        assertEquals(MieruBean.PROTOCOL_TCP, bean.protocol)
        assertEquals("v_trafficPattern", bean.trafficPattern)
        assertEquals("", bean.portRange)
        assertContentEquals(version2, BeanConverters.serialize(bean))
    }

    @Test
    fun `version 3 round trip preserves all protocols`() {
        val protocols = listOf(
            MieruBean.PROTOCOL_TCP,
            MieruBean.PROTOCOL_UDP,
            MieruBean.PROTOCOL_TCP_UDP,
        )

        for (protocol in protocols) {
            val source = MieruBean().apply {
                serverAddress = "example.com"
                serverPort = 4012
                portRange = "4012-4021"
                this.protocol = protocol
                username = "user"
                password = "pass"
                mtu = 1380
            }

            val restored = BeanConverters.deserialize(
                MieruBean(),
                BeanConverters.serialize(source),
            )
            val cloned = source.clone()

            assertEquals(4012, restored.serverPort, protocol)
            assertEquals("4012-4021", restored.portRange, protocol)
            assertEquals(protocol, restored.protocol, protocol)
            assertEquals("4012-4021", cloned.portRange, protocol)
            assertEquals(protocol, cloned.protocol, protocol)
            if (MieruBean.usesUdp(protocol)) {
                assertEquals(1380, restored.mtu, protocol)
                assertEquals(1380, cloned.mtu, protocol)
            }
        }
    }

    @Test
    fun `TCP ping capability follows transport mode`() {
        val bean = MieruBean()

        bean.protocol = MieruBean.PROTOCOL_TCP
        assertEquals(true, bean.canTCPing)

        bean.protocol = MieruBean.PROTOCOL_UDP
        assertEquals(false, bean.canTCPing)

        bean.protocol = MieruBean.PROTOCOL_TCP_UDP
        assertEquals(true, bean.canTCPing)
    }

    @Test
    fun `single port profiles preserve transport and UDP MTU on reload`() {
        for (mode in listOf(MieruBean.PROTOCOL_TCP, MieruBean.PROTOCOL_UDP, MieruBean.PROTOCOL_TCP_UDP)) {
            val source = MieruBean().also {
                it.serverAddress = "example.com"
                it.serverPort = 4012
                it.protocol = mode
                it.mtu = 1380
            }
            val bytes = BeanConverters.serialize(source)
            val restored = BeanConverters.deserialize(MieruBean(), bytes)
            assertContentEquals(bytes, BeanConverters.serialize(restored))
            assertEquals(mode, restored.protocol)
            assertEquals(mode, restored.clone().protocol)
            assertEquals("", restored.portRange)
            assertEquals("example.com:4012", restored.displayAddress())
            assertEquals(true, restored.canSelfProtect)
            if (MieruBean.usesUdp(mode)) assertEquals(1380, restored.clone().mtu)
        }
    }
}
