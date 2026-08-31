package fr.husi.fmt.mieru

private val MIERU_PORT_PATTERN = Regex("^(\\d+)(?:-(\\d+))?$")

data class MieruPort(
    val start: Int,
    val end: Int? = null,
) {
    val isRange: Boolean
        get() = end != null

    override fun toString(): String = end?.let { "$start-$it" } ?: start.toString()
}

fun parseMieruPort(value: String): MieruPort {
    val trimmed = value.trim()
    val match = MIERU_PORT_PATTERN.matchEntire(trimmed)
        ?: throw IllegalArgumentException("Enter a port or port range (for example, 4012-4021)")
    val start = match.groupValues[1].toIntOrNull()
        ?: throw IllegalArgumentException("Port must be between 1 and 65535")
    val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
        ?: if (match.groupValues[2].isNotEmpty()) {
            throw IllegalArgumentException("Port must be between 1 and 65535")
        } else {
            null
        }
    if (start !in 1..65535 || end != null && end !in 1..65535) {
        throw IllegalArgumentException("Port must be between 1 and 65535")
    }
    if (end != null && start > end) {
        throw IllegalArgumentException("Range start must not be greater than range end")
    }
    return MieruPort(start, end)
}

fun normalizeMieruPort(value: String): String? = runCatching {
    parseMieruPort(value).toString()
}.getOrNull()

fun validateMieruPort(value: String): String? = runCatching {
    parseMieruPort(value)
}.exceptionOrNull()?.message
