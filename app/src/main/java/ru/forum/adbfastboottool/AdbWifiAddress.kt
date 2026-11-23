package ru.forum.adbfastboottool

internal data class AdbWifiAddress(val host: String, val port: Int? = null) {
    companion object {
        fun parse(text: String): AdbWifiAddress? {
            val address = text.trim()
            val firstColon = address.indexOf(':')
            val (host, portSuffix) = when {
                address.startsWith('[') -> {
                    val closingBracket = address.indexOf(']')
                    if (closingBracket < 0) return null
                    address.substring(1, closingBracket) to address.substring(closingBracket + 1)
                }
                firstColon >= 0 && firstColon == address.lastIndexOf(':') ->
                    address.substring(0, firstColon) to address.substring(firstColon)
                else -> address to ""
            }
            if (host.isEmpty() || host.any { it.isWhitespace() || it == '[' || it == ']' }) return null
            if (portSuffix.isEmpty()) return AdbWifiAddress(host)
            if (!portSuffix.startsWith(':')) return null
            val port = parsePort(portSuffix.drop(1)) ?: return null
            return AdbWifiAddress(host, port)
        }

        fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
