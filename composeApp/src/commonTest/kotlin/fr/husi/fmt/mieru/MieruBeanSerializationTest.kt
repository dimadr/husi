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
    fun `version 3 round trip and clone preserve port range`() {
        val source = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 4012
            portRange = "4012-4021"
            protocol = MieruBean.PROTOCOL_UDP
            username = "user"
            password = "pass"
            mtu = 1400
        }

        val restored = BeanConverters.deserialize(
            MieruBean(),
            BeanConverters.serialize(source),
        )
        val cloned = source.clone()

        assertEquals(4012, restored.serverPort)
        assertEquals("4012-4021", restored.portRange)
        assertEquals(MieruBean.PROTOCOL_UDP, restored.protocol)
        assertEquals("4012-4021", cloned.portRange)
        assertEquals(4012, cloned.serverPort)
    }
}
