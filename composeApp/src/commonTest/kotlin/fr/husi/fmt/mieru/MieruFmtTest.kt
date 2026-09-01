package fr.husi.fmt.mieru

import fr.husi.fmt.FmtTestConstant
import fr.husi.ktx.toJsonMapKxs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MieruFmtTest {

    private fun Any?.asJsonMap(): Map<String, Any?> = assertIs(this)

    private fun Any?.asJsonList(): List<Any?> = assertIs(this)

    private fun Map<String, Any?>.firstProfile(): Map<String, Any?> =
        this["profiles"].asJsonList().first().asJsonMap()

    private fun MieruBean.portBindings(): List<Map<String, Any?>> {
        val config = buildMieruConfig(port = 2080, logLevel = 0).toJsonMapKxs()
        return config.firstProfile()["servers"].asJsonList().first().asJsonMap()["portBindings"]
            .asJsonList().map { it.asJsonMap() }
    }

    @Test
    fun `displayAddress should preserve single port and port range`() {
        val bean = MieruBean().apply {
            serverAddress = "2.27.202.65"
            serverPort = 4012
        }

        assertEquals("2.27.202.65:4012", bean.displayAddress())

        bean.portRange = "4012-4021"

        assertEquals("2.27.202.65:4012-4021", bean.displayAddress())
    }

    @Test
    fun `parseMieru should parse url with all fields`() {
        val bean = parseMieru(FmtTestConstant.MIERU_URL)

        assertEquals("example.com", bean.serverAddress)
        assertEquals(8080, bean.serverPort)
        assertEquals("user", bean.username)
        assertEquals("pass", bean.password)
        assertEquals("myprofile", bean.name)
        assertEquals(1400, bean.mtu)
        assertEquals(3, bean.serverMuxNumber)
        assertEquals(MieruBean.PROTOCOL_TCP, bean.protocol)
    }

    @Test
    fun `parseMieru should use defaults for missing or unknown optional fields`() {
        val bean = parseMieru("mierus://user:pass@example.com?multiplexing=UNKNOWN")

        assertEquals(1080, bean.serverPort)
        assertEquals(0, bean.mtu)
        assertEquals(0, bean.serverMuxNumber)
    }

    @Test
    fun `parseMieru should preserve base64 traffic pattern from share link`() {
        val bean = parseMieru(FmtTestConstant.MIERU_TRAFFIC_PATTERN_URL)

        assertEquals(FmtTestConstant.MIERU_TRAFFIC_PATTERN_BASE64, bean.trafficPattern)
    }

    @Test
    fun `toUri should preserve serializable fields through parseMieru`() {
        val source = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 8080
            username = "user"
            password = "pass"
            name = "myprofile"
            mtu = 1200
            serverMuxNumber = 2
        }

        val parsed = parseMieru(source.toUri())

        assertEquals(source.serverAddress, parsed.serverAddress)
        assertEquals(source.serverPort, parsed.serverPort)
        assertEquals(source.username, parsed.username)
        assertEquals(source.password, parsed.password)
        assertEquals(source.name, parsed.name)
        assertEquals(source.mtu, parsed.mtu)
        assertEquals(source.serverMuxNumber, parsed.serverMuxNumber)
        assertEquals(source.protocol, parsed.protocol)
    }

    @Test
    fun `toUri should omit empty optional query fields and provide upstream profile`() {
        val source = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 8080
            username = "user"
            password = "pass"
            mtu = 0
            serverMuxNumber = 0
        }

        val uri = source.toUri()
        val parsed = parseMieru(uri)

        assertTrue(uri.contains("profile=default"))
        assertFalse(uri.contains("mtu="))
        assertFalse(uri.contains("multiplexing="))
        assertEquals(0, parsed.mtu)
        assertEquals(0, parsed.serverMuxNumber)
    }

    @Test
    fun `buildMieruConfig should map key fields to structured json`() {
        val bean = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 8080
            username = "user"
            password = "secret"
            protocol = MieruBean.PROTOCOL_TCP
            mtu = 1400
            serverMuxNumber = 3
        }
        bean.initializeDefaultValues()

        val config = bean.buildMieruConfig(port = 2080, logLevel = 0).toJsonMapKxs()

        assertEquals("default", config["activeProfile"])
        assertEquals(2080, assertIs<Number>(config["socks5Port"]).toInt())
        assertNull(config["loggingLevel"])

        val profiles = config["profiles"].asJsonList()
        assertEquals(1, profiles.size)
        val profile = profiles.first().asJsonMap()
        assertEquals("default", profile["profileName"])
        assertEquals(1400, assertIs<Number>(profile["mtu"]).toInt())
        assertEquals("HANDSHAKE_STANDARD", profile["handshakeMode"])

        val user = profile["user"].asJsonMap()
        assertEquals("user", user["name"])
        assertEquals("secret", user["password"])

        val servers = profile["servers"].asJsonList()
        val firstServer = servers.first().asJsonMap()
        assertEquals("example.com", firstServer["domainName"])
        assertNull(firstServer["ipAddress"])

        val bindings = firstServer["portBindings"].asJsonList()
        val firstBinding = bindings.first().asJsonMap()
        assertEquals(8080, assertIs<Number>(firstBinding["port"]).toInt())
        assertEquals("TCP", firstBinding["protocol"])
        assertFalse(firstBinding.containsKey("portRange"))

        val multiplexing = profile["multiplexing"].asJsonMap()
        assertEquals("MULTIPLEXING_HIGH", multiplexing["level"])
    }

    @Test
    fun `buildMieruConfig should emit UDP portRange without port`() {
        val bean = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 4012
            portRange = "4012-4021"
            username = "user"
            password = "secret"
            protocol = MieruBean.PROTOCOL_UDP
            mtu = 1400
            serverMuxNumber = 3
        }
        bean.initializeDefaultValues()

        val config = bean.buildMieruConfig(port = 2080, logLevel = 0).toJsonMapKxs()
        val binding = config.firstProfile()["servers"]
            .asJsonList().first().asJsonMap()["portBindings"]
            .asJsonList().first().asJsonMap()

        assertEquals("4012-4021", binding["portRange"])
        assertEquals("UDP", binding["protocol"])
        assertFalse(binding.containsKey("port"))
        assertEquals(1400, assertIs<Number>(config.firstProfile()["mtu"]).toInt())
        assertEquals(
            "MULTIPLEXING_HIGH",
            config.firstProfile()["multiplexing"].asJsonMap()["level"],
        )
    }

    @Test
    fun `buildMieruConfig should emit TCP portRange without port`() {
        val bean = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 4012
            portRange = "4012-4021"
            username = "user"
            password = "secret"
            protocol = MieruBean.PROTOCOL_TCP
        }
        bean.initializeDefaultValues()

        val bindings = bean.portBindings()

        assertEquals(1, bindings.size)
        assertEquals("TCP", bindings[0]["protocol"])
        assertEquals("4012-4021", bindings[0]["portRange"])
        assertFalse(bindings[0].containsKey("port"))
    }

    @Test
    fun `buildMieruConfig should emit TCP and UDP bindings for range`() {
        val bean = MieruBean().apply {
            serverAddress = "2.27.202.65"
            serverPort = 4012
            portRange = "4012-4021"
            username = "user"
            password = "secret"
            protocol = MieruBean.PROTOCOL_TCP_UDP
        }
        bean.initializeDefaultValues()

        val bindings = bean.portBindings()

        assertEquals(2, bindings.size)
        assertEquals("TCP", bindings[0]["protocol"])
        assertEquals("4012-4021", bindings[0]["portRange"])
        assertFalse(bindings[0].containsKey("port"))
        assertEquals("UDP", bindings[1]["protocol"])
        assertEquals("4012-4021", bindings[1]["portRange"])
        assertFalse(bindings[1].containsKey("port"))
    }

    @Test
    fun `buildMieruConfig should emit TCP and UDP bindings for single port`() {
        val bean = MieruBean().apply {
            serverAddress = "2.27.202.65"
            serverPort = 4012
            username = "user"
            password = "secret"
            protocol = MieruBean.PROTOCOL_TCP_UDP
        }
        bean.initializeDefaultValues()

        val bindings = bean.portBindings()

        assertEquals(2, bindings.size)
        assertEquals("TCP", bindings[0]["protocol"])
        assertEquals(4012, assertIs<Number>(bindings[0]["port"]).toInt())
        assertFalse(bindings[0].containsKey("portRange"))
        assertEquals("UDP", bindings[1]["protocol"])
        assertEquals(4012, assertIs<Number>(bindings[1]["port"]).toInt())
        assertFalse(bindings[1].containsKey("portRange"))
    }

    @Test
    fun `upstream mierus URI preserves single TCP port`() {
        val source = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 4012
            username = "user"
            password = "pass"
            protocol = MieruBean.PROTOCOL_TCP
        }

        val uri = source.toUri()
        val parsed = parseMieru(uri)

        assertTrue(uri.contains("port=4012"))
        assertTrue(uri.contains("protocol=TCP"))
        assertEquals(4012, parsed.serverPort)
        assertEquals("", parsed.portRange)
        assertEquals(MieruBean.PROTOCOL_TCP, parsed.protocol)
    }

    @Test
    fun `upstream mierus URI preserves UDP port range`() {
        val source = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 4012
            portRange = "4012-4021"
            username = "user"
            password = "pass"
            protocol = MieruBean.PROTOCOL_UDP
        }

        val uri = source.toUri()
        val parsed = parseMieru(uri)

        assertTrue(uri.contains("port=4012-4021"))
        assertTrue(uri.contains("protocol=UDP"))
        assertEquals(4012, parsed.serverPort)
        assertEquals("4012-4021", parsed.portRange)
        assertEquals(MieruBean.PROTOCOL_UDP, parsed.protocol)
    }

    @Test
    fun `upstream mierus URI preserves TCP and UDP port range`() {
        val source = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 4012
            portRange = "4012-4021"
            username = "user"
            password = "pass"
            protocol = MieruBean.PROTOCOL_TCP_UDP
        }

        val uri = source.toUri()
        val parsed = parseMieru(uri)

        assertEquals(2, Regex("port=4012-4021").findAll(uri).count())
        assertTrue(uri.contains("protocol=TCP"))
        assertTrue(uri.contains("protocol=UDP"))
        assertEquals(4012, parsed.serverPort)
        assertEquals("4012-4021", parsed.portRange)
        assertEquals(MieruBean.PROTOCOL_TCP_UDP, parsed.protocol)
    }

    @Test
    fun `buildMieruConfig should put IP server address into ipAddress`() {
        val bean = MieruBean().apply {
            serverAddress = "12.34.56.78"
            serverPort = 8080
            username = "user"
            password = "secret"
            protocol = MieruBean.PROTOCOL_TCP
        }
        bean.initializeDefaultValues()

        val config = bean.buildMieruConfig(port = 2080, logLevel = 0).toJsonMapKxs()
        val firstServer = config.firstProfile()["servers"].asJsonList().first().asJsonMap()

        assertEquals("12.34.56.78", firstServer["ipAddress"])
        assertNull(firstServer["domainName"])
    }

    @Test
    fun `buildMieruConfig should set trafficPattern field directly`() {
        val bean = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 8080
            username = "user"
            password = "secret"
            protocol = MieruBean.PROTOCOL_TCP
            trafficPattern = """
                {
                  "activeProfile": "should-not-override",
                  "trafficPattern": {
                    "unlockAll": false,
                    "tcpFragment": {
                      "enable": true,
                      "maxSleepMs": 10
                    },
                    "nonce": {
                      "type": "NONCE_TYPE_PRINTABLE",
                      "applyToAllUDPPacket": true,
                      "minLen": 6,
                      "maxLen": 8
                    }
                  }
                }
            """.trimIndent()
        }
        bean.initializeDefaultValues()

        val config = bean.buildMieruConfig(port = 2080, logLevel = 0).toJsonMapKxs()
        val trafficPattern = config.firstProfile()["trafficPattern"].asJsonMap()

        assertEquals("default", config["activeProfile"])
        assertEquals(false, trafficPattern["unlockAll"])

        val tcpFragment = trafficPattern["tcpFragment"].asJsonMap()
        assertEquals(true, tcpFragment["enable"])
        assertEquals(10, assertIs<Number>(tcpFragment["maxSleepMs"]).toInt())

        val nonce = trafficPattern["nonce"].asJsonMap()
        assertEquals("NONCE_TYPE_PRINTABLE", nonce["type"])
        assertEquals(true, nonce["applyToAllUDPPacket"])
        assertEquals(6, assertIs<Number>(nonce["minLen"]).toInt())
        assertEquals(8, assertIs<Number>(nonce["maxLen"]).toInt())
    }

    @Test
    fun `buildMieruConfig should accept base64 trafficPattern input`() {
        val bean = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 8080
            username = "user"
            password = "secret"
            protocol = MieruBean.PROTOCOL_TCP
            trafficPattern = FmtTestConstant.MIERU_TRAFFIC_PATTERN_BASE64
        }
        bean.initializeDefaultValues()

        val config = bean.buildMieruConfig(port = 2080, logLevel = 0).toJsonMapKxs()
        val trafficPattern = config.firstProfile()["trafficPattern"].asJsonMap()

        assertEquals(42, assertIs<Number>(trafficPattern["seed"]).toInt())
        assertEquals(true, trafficPattern["unlockAll"])
        val nonce = trafficPattern["nonce"].asJsonMap()
        assertEquals("NONCE_TYPE_FIXED", nonce["type"])
    }

    @Test
    fun `toUri should export trafficPattern as base64 protobuf`() {
        val source = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 8080
            username = "user"
            password = "pass"
            trafficPattern = """
                {
                  "trafficPattern": {
                    "seed": 42,
                    "unlockAll": true,
                    "tcpFragment": {
                      "enable": true,
                      "maxSleepMs": 10
                    },
                    "nonce": {
                      "type": "NONCE_TYPE_FIXED",
                      "applyToAllUDPPacket": true,
                      "customHexStrings": [
                        "00010203",
                        "04050607"
                      ]
                    }
                  }
                }
            """.trimIndent()
        }

        val uri = source.toUri()
        val parsed = parseMieru(uri)

        assertEquals(
            true,
            uri.contains("traffic-pattern=${FmtTestConstant.MIERU_TRAFFIC_PATTERN_BASE64}"),
        )
        assertEquals(FmtTestConstant.MIERU_TRAFFIC_PATTERN_BASE64, parsed.trafficPattern)
    }

    @Test
    fun `buildMieruConfig should decode base64 trafficPattern from share link`() {
        val bean = parseMieru(FmtTestConstant.MIERU_TRAFFIC_PATTERN_CONFIG_URL)
        bean.protocol = MieruBean.PROTOCOL_TCP
        bean.initializeDefaultValues()

        val config = bean.buildMieruConfig(port = 2080, logLevel = 0).toJsonMapKxs()
        val trafficPattern = config.firstProfile()["trafficPattern"].asJsonMap()

        assertEquals(42, assertIs<Number>(trafficPattern["seed"]).toInt())
        assertEquals(true, trafficPattern["unlockAll"])
    }
}
