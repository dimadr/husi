/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package fr.husi.fmt.mieru

import fr.husi.ktx.blankAsNull
import fr.husi.ktx.isIpAddress
import fr.husi.ktx.queryParameterNotBlank
import fr.husi.ktx.toJsonMapKxs
import fr.husi.ktx.toJsonStringKxs
import fr.husi.ktx.unUrlSafe
import fr.husi.libcore.Libcore
import fr.husi.logLevelString

fun MieruBean.buildMieruConfig(port: Int, logLevel: Int): String {
    val remotePort = parseMieruPort(portRange.ifBlank { finalPort.toString() })
    val profile = mutableMapOf(
        "profileName" to "default",
        "user" to mapOf(
            "name" to username,
            "password" to password.also {
                if (it.isEmpty()) error("mieru password is empty")
            },
        ),
        "servers" to listOf(
            mutableMapOf<String, Any>(
                "portBindings" to buildMieruPortBindings(remotePort),
            ).also {
                // mieru refuses to parse a domain name in the ipAddress field.
                if (finalAddress.isIpAddress()) {
                    it["ipAddress"] = finalAddress
                } else {
                    it["domainName"] = finalAddress
                }
            },
        ),
        "mtu" to mtu,
        "multiplexing" to mieruMuxToString(serverMuxNumber)?.let { mapOf("level" to it) },
        // "handshakeMode" to "HANDSHAKE_NO_WAIT",
        // https://github.com/enfein/mieru/issues/254
        // Mieru TCP mux long-time mutex holding + no wait = bug.
        "handshakeMode" to "HANDSHAKE_STANDARD",
    )
    trafficPattern.blankAsNull()?.let { trafficPattern ->
        profile["trafficPattern"] = runCatching {
            trafficPattern.toJsonMapKxs().let {
                it["trafficPattern"] ?: it
            }
        }.getOrElse { _ ->
            Libcore.decodeMieruTrafficPattern(trafficPattern).toJsonMapKxs().let {
                it["trafficPattern"] ?: it
            }
        }
    }
    val basic = mutableMapOf(
        "activeProfile" to "default",
        "socks5Port" to port,
        "loggingLevel" to logLevel.takeIf { it > 0 }?.let { logLevelString(it).uppercase() },
        "advancedSettings" to mapOf("noCheckUpdate" to true),
        "profiles" to listOf(profile),
    )
    return basic.toJsonStringKxs()
}

// https://github.com/enfein/mieru/blob/b1cd50fabb2f893c7878388767d97370dbb7a660/pkg/appctl/url.go#L51
fun parseMieru(link: String): MieruBean = MieruBean().apply {
    val url = Libcore.parseURL(link)
    username = url.username
    password = url.password
    serverAddress = url.host
    val repeatedPorts = link.queryParameterValues("port")
    val normalizedRepeatedPorts = repeatedPorts.mapNotNull(::normalizeMieruPort)
    val repeatedProtocols = link.queryParameterValues("protocol").map(String::uppercase)
    val remotePort = parseMieruPort(
        repeatedPorts.firstOrNull() ?: url.ports.ifBlank { defaultPort.toString() },
    )
    serverPort = remotePort.start
    portRange = remotePort.toString().takeIf { remotePort.isRange }.orEmpty()
    protocol = if (
        repeatedPorts.size == 2 &&
        normalizedRepeatedPorts.size == 2 &&
        normalizedRepeatedPorts.distinct().size == 1 &&
        repeatedProtocols.size == 2 &&
        repeatedProtocols.toSet() == setOf(MieruBean.PROTOCOL_TCP, MieruBean.PROTOCOL_UDP)
    ) {
        MieruBean.PROTOCOL_TCP_UDP
    } else {
        repeatedProtocols.firstOrNull()?.takeIf {
            it == MieruBean.PROTOCOL_TCP || it == MieruBean.PROTOCOL_UDP
        } ?: MieruBean.PROTOCOL_TCP
    }

    name = url.queryParameter("profile")
    mtu = url.queryParameterNotBlank("mtu")?.toIntOrNull() ?: 0
    serverMuxNumber = url.queryParameter("multiplexing")?.let {
        parseMieruMux(it)
    } ?: 0
    trafficPattern = url.queryParameter("traffic-pattern")
}

fun MieruBean.toUri(): String = Libcore.newURL("mierus").apply {
    username = this@toUri.username
    password = this@toUri.password
    host = serverAddress
    val remotePort = portRange.ifBlank { serverPort.toString() }
    if (protocol == MieruBean.PROTOCOL_TCP_UDP) {
        addQueryParameter("port", remotePort)
        addQueryParameter("port", remotePort)
        addQueryParameter("protocol", MieruBean.PROTOCOL_TCP)
        addQueryParameter("protocol", MieruBean.PROTOCOL_UDP)
    } else {
        addQueryParameter("port", remotePort)
        addQueryParameter("protocol", protocol.uppercase())
    }

    addQueryParameter("profile", name.ifBlank { "default" })
    mtu.takeIf { it > 0 }?.let {
        addQueryParameter("mtu", it.toString())
    }
    serverMuxNumber.takeIf { it > 0 }?.let {
        addQueryParameter("multiplexing", mieruMuxToString(it))
    }
    trafficPattern.blankAsNull()?.let { trafficPattern ->
        val base64TrafficPattern = runCatching {
            Libcore.encodeMieruTrafficPattern(trafficPattern)
        }.getOrElse {
            trafficPattern
        }
        addQueryParameter("traffic-pattern", base64TrafficPattern)
    }
}.string

private fun MieruBean.buildMieruPortBindings(remotePort: MieruPort): List<Map<String, Any>> {
    val protocols = if (protocol == MieruBean.PROTOCOL_TCP_UDP) {
        listOf(MieruBean.PROTOCOL_TCP, MieruBean.PROTOCOL_UDP)
    } else {
        listOf(protocol.uppercase())
    }
    return protocols.map { bindingProtocol ->
        mutableMapOf<String, Any>("protocol" to bindingProtocol).apply {
            if (remotePort.isRange) {
                put("portRange", remotePort.toString())
            } else {
                put("port", remotePort.start)
            }
        }
    }
}

private fun String.queryParameterValues(key: String): List<String> {
    val rawQuery = substringAfter('?', "").substringBefore('#')
    if (rawQuery.isEmpty()) return emptyList()
    return rawQuery.split('&').mapNotNull { parameter ->
        val encodedName = parameter.substringBefore('=')
        if (encodedName.unUrlSafe() != key) return@mapNotNull null
        parameter.substringAfter('=', "").unUrlSafe().takeIf(String::isNotBlank)
    }
}

private fun parseMieruMux(link: String): Int? = when (link) {
    "MULTIPLEXING_OFF" -> 0
    "MULTIPLEXING_LOW" -> 1
    "MULTIPLEXING_MEDIUM" -> 2
    "MULTIPLEXING_HIGH" -> 3
    else -> null
}

private fun mieruMuxToString(level: Int): String? = when (level) {
    // 0 -> "MULTIPLEXING_OFF"
    1 -> "MULTIPLEXING_LOW"
    2 -> "MULTIPLEXING_MEDIUM"
    3 -> "MULTIPLEXING_HIGH"
    else -> null
}
