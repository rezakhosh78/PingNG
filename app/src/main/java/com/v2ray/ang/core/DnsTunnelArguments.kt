package com.v2ray.ang.core

/** Pure CLI validation, shared by the editor, link importer, and runtime. */
object DnsTunnelArguments {
    fun dnstt(binary: String, transport: String, resolvers: String, key: String, domain: String, port: Int): List<String> {
        require(transport in setOf("udp", "doh", "dot")) { "DNSTT transport must be udp, doh, or dot" }
        require(key.matches(Regex("[0-9a-fA-F]{64}"))) { "DNSTT public key must contain 64 hex characters" }
        require(domain.isNotBlank() && domain.none { it.isWhitespace() || it == '/' || it == '\\' }) { "Invalid DNSTT domain" }
        val addresses = resolvers.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        require(addresses.size == 1) { "DNSTT requires exactly one resolver" }
        val resolver = addresses.single()
        require(resolver.none { it.isWhitespace() }) { "Invalid DNSTT resolver" }
        if (transport == "doh") require(resolver.startsWith("https://")) { "DNSTT DoH requires an HTTPS URL" }
        require(!resolver.startsWith("-")) { "Invalid DNSTT resolver" }
        if (transport == "doh") {
            val uri = runCatching { java.net.URI(resolver) }.getOrNull()
            require(uri?.host != null && uri.userInfo == null) { "Invalid DNSTT DoH URL" }
        }
        val address = when {
            transport == "doh" -> resolver
            resolver.startsWith("[") && resolver.endsWith("]") -> "$resolver:${if (transport == "udp") 53 else 853}"
            resolver.count { it == ':' } > 1 && !resolver.startsWith("[") -> "[$resolver]:${if (transport == "udp") 53 else 853}"
            !resolver.contains(':') -> "$resolver:${if (transport == "udp") 53 else 853}"
            else -> resolver
        }
        return listOf(binary, "-$transport", address, "-pubkey", key, domain, "127.0.0.1:$port")
    }
}
