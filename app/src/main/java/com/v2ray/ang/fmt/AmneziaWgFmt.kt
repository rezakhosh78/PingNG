package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.InetAddress
import java.util.Base64
import java.util.Locale

/** Parser and UAPI serializer for the official AmneziaWG .conf format. */
object AmneziaWgFmt {
    /** Valid section skeleton used to expose every editable field before a config is imported. */
    const val EMPTY_EDIT_CONFIG = """[Interface]
PrivateKey =
Address =

[Peer]
PublicKey =
Endpoint =
AllowedIPs = 0.0.0.0/0, ::/0
"""
    private const val maxJunkPacketCount = 128
    private const val maxJunkPacketSize = 65_535
    private const val maxCustomPacketTagSize = 1_000
    private val sizedCustomPacketTag = Regex("<\\s*(?:r|rc|rd|dz)\\s+(-?\\d+)\\s*>", RegexOption.IGNORE_CASE)
    private val unsupportedCustomPacketCounterTag = Regex("<\\s*c\\s*>", RegexOption.IGNORE_CASE)
    private val anyStaticBytesTag = Regex("<\\s*b\\b", RegexOption.IGNORE_CASE)
    private val staticBytesTag = Regex("<\\s*b\\s+0x([^>\\s]+)\\s*>", RegexOption.IGNORE_CASE)
    private val legacyQuicStaticTag = Regex("0xc7000000010(?=\\s*>)", RegexOption.IGNORE_CASE)

    private val awgInterfaceKeys = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
        "headerprotectionkey", "contentpaddingaddition", "rekeyaftertime",
        "rekeytimeout", "rejectaftertime", "keepalivetimeout",
        "maxhandshakeattempts", "randomtrailers", "disablecookies",
    )

    val editableInterfaceKeys = listOf(
        "Name", "PrivateKey", "Address", "DNS", "MTU", "ListenPort",
        "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4",
        "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5",
        "HeaderProtectionKey", "ContentPaddingAddition", "RekeyAfterTime",
        "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout",
        "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies",
    )
    val editablePeerKeys = listOf("PublicKey", "PresharedKey", "Endpoint", "AllowedIPs", "PersistentKeepalive")
    val booleanKeys = setOf("randomtrailers", "disablecookies")
    private val numericKeys = setOf(
        "mtu", "listenport", "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "rekeyaftertime", "rekeytimeout", "rejectaftertime",
        "keepalivetimeout", "maxhandshakeattempts", "contentpaddingaddition", "persistentkeepalive",
    )

    data class Config(
        val interfaceValues: Map<String, String>,
        val peers: List<Map<String, String>>,
        val originalText: String,
    ) {
        val addresses: List<String>
            get() = interfaceValues["address"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        val dnsServers: List<String>
            get() = interfaceValues["dns"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        val routes: List<String>
            get() = peers.flatMap { it["allowedips"].orEmpty().split(',') }
                .map(String::trim).filter(String::isNotEmpty)
        val mtu: Int? get() = interfaceValues["mtu"]?.toIntOrNull()?.takeIf { it in 576..9000 }
    }

    fun cleanRemark(value: String?, fallback: String? = null): String =
        value.orEmpty()
            .replace(Regex("^AmneziaWG\\s*[·:-]?\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf(String::isNotBlank)
            ?: fallback?.trim()?.takeIf(String::isNotBlank)
            ?: "New tunnel"

    /** AWG and WireGuard share the same file structure; AWG-only keys identify AWG automatically. */
    fun isAmneziaWgConfig(text: String): Boolean {
        val sections = parseSections(text)
        if (sections != null && (
                sections.first.keys.any { it in awgInterfaceKeys } ||
                    sections.second.any { peer -> peer.keys.any { it in awgInterfaceKeys } }
            )
        ) return true

        // Keep malformed AWG files from falling through to the regular WireGuard
        // parser, which would silently discard the obfuscation parameters.
        return text.lineSequence().any { original ->
            val line = original.trim().removePrefix("\uFEFF").trimStart()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                false
            } else {
                line.substringBefore('#').substringBefore(';')
                    .substringBefore('=')
                    .trim()
                    .lowercase(Locale.ROOT) in awgInterfaceKeys
            }
        }
    }

    fun parse(text: String, forceAmneziaWg: Boolean = false): ProfileItem? {
        if (!forceAmneziaWg && !isAmneziaWgConfig(text)) return null
        val (interfaceValues, peers) = parseSections(text) ?: return null
        if (interfaceValues["privatekey"].isNullOrBlank() || peers.isEmpty()) return null
        if (peers.any { it["publickey"].isNullOrBlank() || it["endpoint"].isNullOrBlank() }) return null
        if (peers.any { it["allowedips"].isNullOrBlank() }) return null

        val firstPeer = peers.first()
        val endpoint = splitEndpoint(firstPeer["endpoint"].orEmpty())
        return ProfileItem.create(EConfigType.AMNEZIAWG).apply {
            remarks = cleanRemark(interfaceValues["name"], endpoint.first)
            description = "AmneziaWG"
            server = endpoint.first
            serverPort = endpoint.second
            secretKey = interfaceValues["privatekey"]
            localAddress = interfaceValues["address"]
            publicKey = firstPeer["publickey"]
            preSharedKey = firstPeer["presharedkey"]?.takeIf(String::isNotBlank)
            mtu = interfaceValues["mtu"]?.toIntOrNull()?.takeIf { it in 576..9000 } ?: 1280
            amneziawgConfig = text.trim()
        }
    }

    fun readConfig(text: String): Config {
        val (interfaceValues, peers) = parseSections(text)
            ?: throw IllegalArgumentException("فایل کانفیگ AmneziaWG معتبر نیست")
        require(interfaceValues["privatekey"].isNullOrBlank().not()) { "PrivateKey در بخش Interface پیدا نشد" }
        require(peers.isNotEmpty()) { "بخش Peer در فایل پیدا نشد" }
        require(peers.all { !it["publickey"].isNullOrBlank() && !it["endpoint"].isNullOrBlank() }) {
            "PublicKey یا Endpoint یکی از Peerها ناقص است"
        }
        require(peers.all { !it["allowedips"].isNullOrBlank() }) { "AllowedIPs در یکی از Peerها خالی است" }
        return Config(interfaceValues, peers, text)
    }

    /** Lenient reader used by the editor so clearing a required value does not hide the form. */
    fun readEditableConfig(text: String): Config? {
        val (interfaceValues, peers) = parseSections(text) ?: return null
        return Config(interfaceValues, peers, text)
    }

    fun value(text: String, sectionName: String, key: String, peerIndex: Int = 0): String {
        val (interfaceValues, peers) = parseSections(text) ?: return ""
        val values = if (sectionName.equals("Interface", ignoreCase = true)) {
            interfaceValues
        } else {
            peers.getOrNull(peerIndex).orEmpty()
        }
        return values[key.lowercase(Locale.ROOT)].orEmpty()
    }

    /** Update one .conf value while leaving unrelated lines and comments intact. */
    fun setField(text: String, sectionName: String, key: String, value: String, peerIndex: Int = 0): String {
        val lines = text.split('\n').toMutableList()
        val wantedSection = sectionName.lowercase(Locale.ROOT)
        var currentSection = ""
        var currentPeer = -1
        var sectionStart = -1
        var sectionEnd = lines.size
        val matches = mutableListOf<Int>()

        lines.forEachIndexed { index, original ->
            val headerLine = original.removePrefix("\uFEFF").substringBefore('#').trim()
            val header = headerLine.removeSuffix("]").removePrefix("[").trim().lowercase(Locale.ROOT)
            if (headerLine.startsWith("[") && headerLine.endsWith("]")) {
                if (sectionStart >= 0 && sectionEnd == lines.size) sectionEnd = index
                currentSection = header
                if (header == "peer") currentPeer++
                if (header == wantedSection && (header != "peer" || currentPeer == peerIndex)) {
                    sectionStart = index
                    sectionEnd = lines.size
                }
                return@forEachIndexed
            }
            if (sectionStart >= 0 && sectionEnd == lines.size && currentSection == wantedSection &&
                (wantedSection != "peer" || currentPeer == peerIndex)
            ) {
                val separator = original.indexOf('=')
                if (separator > 0 && original.substring(0, separator).trim().equals(key, ignoreCase = true)) {
                    matches += index
                }
            }
        }

        require(sectionStart >= 0) { "[$sectionName] section is missing" }
        if (matches.isNotEmpty()) {
            val first = matches.first()
            if (value.isBlank()) {
                matches.asReversed().forEach { lines.removeAt(it) }
            } else {
                val original = lines[first]
                val separator = original.indexOf('=')
                val right = original.substring(separator + 1)
                val suffix = Regex("[ \\t]+[;#].*$").find(right)?.value.orEmpty()
                lines[first] = original.substring(0, separator + 1) + " " + value.trim() + suffix
                matches.drop(1).asReversed().forEach { lines.removeAt(it) }
            }
        } else if (value.isNotBlank()) {
            var insertion = sectionEnd
            while (insertion > sectionStart + 1 && lines[insertion - 1].isBlank()) insertion--
            lines.add(insertion, "$key = ${value.trim()}")
        }
        return lines.joinToString("\n")
    }

    fun isNumericField(key: String): Boolean = key.lowercase(Locale.ROOT) in numericKeys
    fun isBooleanField(key: String): Boolean = key.lowercase(Locale.ROOT) in booleanKeys
    fun splitEndpoint(value: String): Pair<String?, String?> {
        val endpoint = value.trim()
        if (endpoint.startsWith("[")) {
            val close = endpoint.indexOf(']')
            if (close > 0 && endpoint.getOrNull(close + 1) == ':') {
                return endpoint.substring(1, close) to endpoint.substring(close + 2).takeIf(String::isNotBlank)
            }
            return endpoint.removeSurrounding("[", "]") to null
        }
        val separator = endpoint.lastIndexOf(':')
        if (separator <= 0 || separator == endpoint.lastIndex) return endpoint to null
        return endpoint.substring(0, separator) to endpoint.substring(separator + 1)
    }

    /** Converts the official configuration to the AmneziaWG Go userspace UAPI format. */
    fun toGoUapi(
        text: String,
        endpointResolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    ): String {
        val config = readConfig(text)
        val device = config.interfaceValues
        validateNativeSafeValues(config)
        val junkCount = device["jc"]?.toIntOrNull() ?: 0
        val junkMin = device["jmin"]?.toIntOrNull()
        val junkMax = device["jmax"]?.toIntOrNull()
        return buildString {
            appendLine("private_key=${keyToHex(device.getValue("privatekey"))}")
            device["listenport"]?.takeIf(String::isNotBlank)?.let {
                appendLine("listen_port=$it")
            }

            val deviceKeys = listOf(
                "jc" to "jc", "jmin" to "jmin", "jmax" to "jmax",
                "s1" to "s1", "s2" to "s2", "s3" to "s3", "s4" to "s4",
                "h1" to "h1", "h2" to "h2", "h3" to "h3", "h4" to "h4",
                "i1" to "i1", "i2" to "i2", "i3" to "i3", "i4" to "i4", "i5" to "i5",
                "headerprotectionkey" to "header_protection_key",
                "contentpaddingaddition" to "content_padding_addition",
                "rekeyaftertime" to "rekey_after_time",
                "rekeytimeout" to "rekey_timeout",
                "rejectaftertime" to "reject_after_time",
                "keepalivetimeout" to "keepalive_timeout",
                "maxhandshakeattempts" to "max_handshake_attempts",
                "randomtrailers" to "random_trailers",
                "disablecookies" to "disable_cookies",
            )
            deviceKeys.forEach { (sourceKey, uapiKey) ->
                device[sourceKey]?.takeIf(String::isNotBlank)?.let { value ->
                    val encoded = when (sourceKey) {
                        "headerprotectionkey" -> keyToHex(value)
                        "randomtrailers", "disablecookies" -> booleanToUapi(value)
                        "i1", "i2", "i3", "i4", "i5" -> normalizeLegacyStaticBytes(value)
                        // The current upstream AWG 3.1 engine treats Jmax as an exclusive
                        // bound. Restore the old equal-bound behavior without letting its
                        // zero-width random range crash the Go runtime.
                        "jmax" -> if (junkCount > 0 && junkMin != null && junkMax == junkMin) {
                            (junkMax + 1).toString()
                        } else value
                        else -> value
                    }
                    appendLine("$uapiKey=$encoded")
                }
            }

            // Keep the same ordering as the official AmneziaWG Android
            // serializer: all device fields first, then replace_peers, then
            // peer blocks. The Go UAPI accepts either order, but matching the
            // reference client avoids surprising reconfigure behavior.
            appendLine("replace_peers=true")
            config.peers.forEach { peer ->
                appendLine("public_key=${keyToHex(peer.getValue("publickey"))}")
                peer.getValue("allowedips").split(',').map(String::trim).filter(String::isNotEmpty)
                    .forEach { appendLine("allowed_ip=$it") }
                appendLine("endpoint=${resolveEndpoint(peer.getValue("endpoint"), endpointResolver)}")
                peer["persistentkeepalive"]?.takeIf(String::isNotBlank)?.let {
                    appendLine("persistent_keepalive_interval=$it")
                }
                peer["presharedkey"]?.takeIf(String::isNotBlank)?.let {
                    appendLine("preshared_key=${keyToHex(it)}")
                }
            }
            appendLine()
        }
    }

    /** Rejects malformed values that the bundled AWG Go engine can turn into a process crash. */
    private fun validateNativeSafeValues(config: Config) {
        val device = config.interfaceValues
        val junkCount = device["jc"]?.let { value ->
            value.toIntOrNull()?.also { count ->
                require(count in 0..maxJunkPacketCount) {
                    "Jc باید بین ۰ و $maxJunkPacketCount باشد تا هسته AmneziaWG از کار نیفتد"
                }
            } ?: throw IllegalArgumentException("مقدار Jc در کانفیگ AmneziaWG عدد صحیح نیست")
        } ?: 0

        val junkMin = parseBoundedNonNegative(device, "jmin", maxJunkPacketSize)
        val junkMax = parseBoundedNonNegative(device, "jmax", maxJunkPacketSize)
        if (junkCount > 0) {
            require(junkMin != null && junkMax != null && junkMin > 0 && junkMax > 0) {
                "وقتی Jc بزرگ‌تر از صفر است، Jmin و Jmax باید هر دو مقدار مثبت داشته باشند"
            }
            require(junkMax >= junkMin) {
                "Jmax باید بزرگ‌تر یا مساوی Jmin باشد؛ در غیر این صورت هسته AmneziaWG ممکن است متوقف شود"
            }
            require(junkMax < maxJunkPacketSize) { "مقدار Jmax برای هسته AmneziaWG بیش از حد بزرگ است" }
        }

        for (key in listOf("i1", "i2", "i3", "i4", "i5")) {
            val value = normalizeLegacyStaticBytes(device[key].orEmpty())
            require(!unsupportedCustomPacketCounterTag.containsMatchIn(value)) {
                "$key contains <c>, which is not supported by the bundled AmneziaWG 3.x userspace engine"
            }
            val staticTagCount = anyStaticBytesTag.findAll(value).count()
            val parsedStaticTags = staticBytesTag.findAll(value).toList()
            require(parsedStaticTags.size == staticTagCount) {
                "$key contains a malformed <b> static-bytes tag"
            }
            parsedStaticTags.forEach { match ->
                val hex = match.groupValues[1]
                require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                    "$key contains a <b> tag with an invalid or odd-length hex sequence"
                }
            }
            sizedCustomPacketTag.findAll(value).forEach { match ->
                val size = match.groupValues[1].toIntOrNull()
                    ?: throw IllegalArgumentException("اندازه در $key کانفیگ AmneziaWG معتبر نیست")
                require(size in 0..maxCustomPacketTagSize) {
                    "اندازه تگ‌های r/rc/rd/dz در $key باید بین ۰ و $maxCustomPacketTagSize باشد"
                }
            }
        }

        // S1-S4 are uint16 values in the official userspace API. S4 must not
        // be rejected globally: the official Android client supports positive
        // S4 values and the supplied ReZa profile relies on S4=22. When
        // header protection is enabled, the native engine additionally
        // requires every padding prefix to provide its 12-byte nonce.
        val paddings = (1..4).mapNotNull { index ->
            parseBoundedNonNegative(device, "s$index", 65_535)
        }
        if (device["headerprotectionkey"]?.isNotBlank() == true) {
            require(paddings.size == 4 && paddings.all { it >= 12 }) {
                "وقتی HeaderProtectionKey فعال است، S1 تا S4 باید حداقل ۱۲ باشند"
            }
        }
    }

    /**
     * Older Amnezia examples used 0xc7000000010 for the QUIC-like I1 prefix.
     * It is an odd-length hex string; with the accompanying <rc 8> tag the
     * intended byte is the QUIC DCID length 0x08. Repair this one known legacy
     * typo in the UAPI payload while still rejecting all other malformed tags.
     */
    private fun normalizeLegacyStaticBytes(value: String): String =
        legacyQuicStaticTag.replace(value, "0xc70000000108")

    private fun parseBoundedNonNegative(values: Map<String, String>, key: String, maximum: Int): Int? {
        val raw = values[key]?.takeIf(String::isNotBlank) ?: return null
        val parsed = raw.toIntOrNull()
            ?: throw IllegalArgumentException("مقدار ${key.uppercase(Locale.ROOT)} در کانفیگ AmneziaWG عدد صحیح نیست")
        require(parsed in 0..maximum) {
            "مقدار ${key.uppercase(Locale.ROOT)} باید بین ۰ و $maximum باشد"
        }
        return parsed
    }

    private fun parseSections(text: String): Pair<Map<String, String>, List<Map<String, String>>>? {
        val interfaceValues = linkedMapOf<String, String>()
        val peers = mutableListOf<MutableMap<String, String>>()
        var section: MutableMap<String, String>? = null
        var sawInterface = false

        text.lineSequence().forEach { original ->
            val line = original.trim().removePrefix("\uFEFF").trimStart()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
            val uncommentedLine = line.substringBefore('#').trim()
            if (uncommentedLine.isEmpty()) return@forEach
            when {
                uncommentedLine.equals("[Interface]", ignoreCase = true) -> {
                    sawInterface = true
                    section = interfaceValues
                }
                uncommentedLine.equals("[Peer]", ignoreCase = true) -> {
                    if (!sawInterface) return null
                    peers += linkedMapOf<String, String>()
                    section = peers.last()
                }
                uncommentedLine.startsWith("[") -> return null
                else -> {
                    val target = section ?: return null
                    val separator = uncommentedLine.indexOf('=')
                    if (separator <= 0) return null
                    val key = uncommentedLine.substring(0, separator).trim().lowercase(Locale.ROOT)
                    val value = uncommentedLine.substring(separator + 1).trim().substringBeforeInlineComment().trim()
                    if (key.isEmpty()) return null
                    if (key in setOf("address", "dns", "allowedips") && target[key].isNullOrBlank().not()) {
                        target[key] = target.getValue(key) + ", " + value
                    } else {
                        target[key] = value
                    }
                }
            }
        }
        if (!sawInterface || peers.isEmpty()) return null
        return interfaceValues to peers
    }

    private fun String.substringBeforeInlineComment(): String {
        val hash = indexOf(" #")
        val semicolon = indexOf(" ;")
        val end = listOf(hash, semicolon).filter { it >= 0 }.minOrNull() ?: length
        return substring(0, end)
    }

    private fun keyToHex(value: String): String {
        val key = value.trim()
        if (key.matches(Regex("[0-9a-fA-F]{64}"))) return key.lowercase(Locale.ROOT)
        val bytes = try {
            Base64.getMimeDecoder().decode(key)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("کلید AmneziaWG معتبر نیست", e)
        }
        require(bytes.size == 32) { "طول کلید AmneziaWG باید ۳۲ بایت باشد" }
        return bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun booleanToUapi(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
        "true", "1", "on" -> "1"
        "false", "0", "off" -> "0"
        else -> throw IllegalArgumentException("مقدار بولی AmneziaWG باید true یا false باشد")
    }

    private fun resolveEndpoint(value: String, endpointResolver: (String) -> List<InetAddress>): String {
        val (host, port) = splitEndpoint(value)
        require(!host.isNullOrBlank() && !port.isNullOrBlank()) { "Endpoint باید به‌شکل host:port باشد" }
        require(port.toIntOrNull()?.let { it in 1..65535 } == true) {
            "پورت Endpoint در کانفیگ AmneziaWG معتبر نیست"
        }
        val resolved = try {
            endpointResolver(host).firstOrNull()?.hostAddress?.substringBefore('%') ?: host
        } catch (_: Exception) {
            host
        }
        return if (resolved.contains(':')) "[$resolved]:$port" else "$resolved:$port"
    }

}
