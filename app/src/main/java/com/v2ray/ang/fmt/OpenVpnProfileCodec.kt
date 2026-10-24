package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** Reads and edits OpenVPN directives and inline blocks without losing their order. */
object OpenVpnProfileCodec {
    private const val EMPTY_DIRECTIVE_MARKER = "# __PINGNG_OPENVPN_EMPTY_DIRECTIVE_7F33C1__"
    private const val EMPTY_BLOCK_MARKER = "# __PINGNG_OPENVPN_EMPTY_BLOCK_7F33C1__"

    enum class EditableKind { DIRECTIVE, INLINE_BLOCK, COMMENT, BLANK, RAW }

    data class EditableField(
        val kind: EditableKind,
        val name: String = "",
        val value: String = "",
    )

    data class Fields(
        val remote: String = "",
        val port: String = "1194",
        val protocol: String = "udp",
        val device: String = "",
        val cipher: String = "",
        val dataCiphers: String = "",
        val auth: String = "",
        val remoteCertTls: String = "",
        val verifyX509Name: String = "",
        val tlsVersionMin: String = "",
    )

    data class CredentialRequirements(
        val usernamePassword: Boolean,
        val privateKeyPassphrase: Boolean,
        val externalPki: Boolean,
    )

    data class ClientIdentityMaterial(
        val certificatePem: String = "",
        val privateKeyPem: String = "",
    )

    data class RemoteEndpoint(val address: String, val port: String)

    fun looksLikeOpenVpn(text: String): Boolean = directiveLines(text).any { (name, _) ->
        name in setOf("client", "remote", "proto", "dev", "auth-user-pass", "config")
    } || text.contains("<ca>", ignoreCase = true)

    fun credentialRequirements(
        text: String,
        privateKeyPem: String = "",
    ): CredentialRequirements {
        val directives = directiveLines(text)
        val fields = editableFields(text)
        val keyBlocks = fields
            .filter { it.kind == EditableKind.INLINE_BLOCK && it.name.substringBefore(' ').equals("key", true) }
            .joinToString("\n") { it.value }
        val hasInlineCert = fields.any {
            it.kind == EditableKind.INLINE_BLOCK && it.name.substringBefore(' ').equals("cert", true)
        }
        val hasInlineKey = fields.any {
            it.kind == EditableKind.INLINE_BLOCK && it.name.substringBefore(' ').equals("key", true)
        }
        val hasInlinePkcs12 = fields.any {
            it.kind == EditableKind.INLINE_BLOCK && it.name.substringBefore(' ').equals("pkcs12", true)
        }
        val externalPkiOption = directives.lastOrNull { it.first == "external-pki" }
            ?.second?.trim()?.let { value -> value != "0" && !value.equals("false", true) }
        val clientCertificateDisabled = directives.any { (name, value) ->
            name == "client-cert-not-required" ||
                name == "verify-client-cert" && value.equals("none", ignoreCase = true)
        } || externalPkiOption == false
        val encryptedKey = keyBlocks.contains("ENCRYPTED PRIVATE KEY", ignoreCase = true) ||
            keyBlocks.contains("Proc-Type: 4,ENCRYPTED", ignoreCase = true) ||
            privateKeyPem.contains("ENCRYPTED PRIVATE KEY", ignoreCase = true) ||
            privateKeyPem.contains("Proc-Type: 4,ENCRYPTED", ignoreCase = true)
        return CredentialRequirements(
            usernamePassword = directives.any { it.first == "auth-user-pass" },
            privateKeyPassphrase = directives.any { it.first == "askpass" } || encryptedKey,
            // File paths in cert/key directives are not bundled with an imported profile.
            // Ask for an identity unless an inline cert+key or PKCS#12 block is present.
            externalPki = !clientCertificateDisabled &&
                (externalPkiOption ?: !(hasInlinePkcs12 || (hasInlineCert && hasInlineKey))),
        )
    }

    /**
     * Extract PEM certificate and private-key material from a selected PEM/.crt/.key file.
     * Combined PEM files are supported; DER and PKCS#12 files must be converted to PEM first.
     */
    fun readClientIdentityMaterial(text: String): ClientIdentityMaterial {
        val certificatePattern = Regex(
            "-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----",
            RegexOption.IGNORE_CASE,
        )
        val privateKeyPattern = Regex(
            "-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----[\\s\\S]*?-----END \\1-----",
            RegexOption.IGNORE_CASE,
        )
        return ClientIdentityMaterial(
            certificatePem = certificatePattern.findAll(text).joinToString("\n") { it.value.trim() },
            privateKeyPem = privateKeyPattern.findAll(text).joinToString("\n") { it.value.trim() },
        )
    }

    /** Remove imported password-file paths; secrets and PEM identity are supplied at runtime. */
    fun runtimeConfig(
        text: String,
        forcedVerbosity: Int? = null,
        clientCertificatePem: String = "",
        privateKeyPem: String = "",
    ): String {
        var result = text
        if (clientCertificatePem.isNotBlank()) {
            result = replaceInlineIdentityPart(result, "cert", clientCertificatePem)
        }
        if (privateKeyPem.isNotBlank()) {
            result = replaceInlineIdentityPart(result, "key", privateKeyPem)
        }
        if (clientCertificatePem.isNotBlank() && privateKeyPem.isNotBlank()) {
            result = removeInlineIdentityPart(result, "pkcs12")
            result = removeInlineIdentityPart(result, "external-pki")
        }
        if (directiveLines(result).any { it.first == "auth-user-pass" }) {
            result = removeDirectiveArgument(result, "auth-user-pass", keepBareDirective = true)
        }
        if (directiveLines(result).any { it.first == "askpass" }) {
            result = removeDirectiveArgument(result, "askpass", keepBareDirective = false)
        }
        result = adaptUnsupportedRuntimeOptions(result)
        return forcedVerbosity?.let { forceVerbosity(result, it) } ?: result
    }

    private fun replaceInlineIdentityPart(text: String, tag: String, pem: String): String {
        val contents = inlineBlockBody(pem, tag).trim()
        require(contents.isNotBlank()) { "The selected $tag data is empty" }
        val block = EditableField(EditableKind.INLINE_BLOCK, tag, contents)
        val fields = editableFields(text).toMutableList()
        val matches = fields.indices.filter { index ->
            val field = fields[index]
            (field.kind == EditableKind.DIRECTIVE && field.name.equals(tag, true)) ||
                (field.kind == EditableKind.INLINE_BLOCK && field.name.substringBefore(' ').equals(tag, true))
        }
        if (matches.isEmpty()) {
            fields += block
        } else {
            fields[matches.first()] = block
            matches.drop(1).asReversed().forEach { fields.removeAt(it) }
        }
        return serializeEditableFields(fields)
    }

    private fun removeInlineIdentityPart(text: String, tag: String): String =
        serializeEditableFields(editableFields(text).filterNot { field ->
            (field.kind == EditableKind.DIRECTIVE && field.name.equals(tag, true)) ||
                (field.kind == EditableKind.INLINE_BLOCK && field.name.substringBefore(' ').equals(tag, true))
        })

    private fun inlineBlockBody(value: String, tag: String): String {
        val pattern = Regex(
            "(?is)^\\s*<${Regex.escape(tag)}(?:\\s+[^>]*)?>\\s*(.*?)\\s*</${Regex.escape(tag)}\\s*>\\s*$",
        )
        return pattern.matchEntire(value)?.groupValues?.get(1) ?: value
    }

    /** Read the last verbosity directive from the config that will reach OpenVPN 3. */
    fun verbosity(text: String): Int? = directiveLines(text)
        .lastOrNull { it.first == "verb" }
        ?.second
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.firstOrNull()
        ?.toIntOrNull()

    private fun forceVerbosity(text: String, level: Int): String {
        require(level in 0..11) { "OpenVPN verbosity must be between 0 and 11" }
        return rewriteDirective(text, "verb", level.toString(), replaceAll = true)
    }

    /**
     * OpenVPN 3 ignores OpenVPN 2 cipher-list directives. Convert the first explicitly
     * configured data cipher to the equivalent single-cipher directive for this runtime copy.
     */
    fun explicitDataCipherRequested(text: String): Boolean {
        val cipherOptions = setOf("cipher", "data-ciphers", "data-ciphers-fallback", "ncp-ciphers")
        return directiveLines(text).any { (name, value) ->
            name in cipherOptions && value.isNotBlank()
        }
    }

    /** OpenVPN 3 requires an explicit compatibility mode for profiles using compression. */
    fun requestsCompression(text: String): Boolean = directiveLines(text).any { (name, value) ->
        name == "comp-lzo" && !value.equals("no", ignoreCase = true) ||
            name == "compress" && value.isNotBlank() && !value.equals("no", ignoreCase = true)
    }

    /** Enable OpenSSL's legacy provider only when the imported profile explicitly needs it. */
    fun requestsLegacyAlgorithms(text: String): Boolean {
        val directives = directiveLines(text)
        val cipherOptions = setOf("cipher", "data-ciphers", "data-ciphers-fallback", "ncp-ciphers")
        val legacyCiphers = setOf(
            "bf-cbc", "blowfish-cbc", "des-cbc", "3des-cbc", "des-ede-cbc",
            "des-ede3-cbc", "idea-cbc", "rc2-cbc", "rc4", "rc4-40", "rc4-128",
        )
        val hasLegacyDataCipher = directives
            .filter { it.first in cipherOptions }
            .flatMap { it.second.split(Regex("[:,\\s]+")) }
            .any { it.lowercase() in legacyCiphers }
        val hasLegacyAuth = directives.any {
            it.first == "auth" && it.second.lowercase() in setOf("md4", "md5")
        }
        val hasLegacyTlsCipher = directives
            .filter { it.first == "tls-cipher" || it.first == "tls-ciphersuites" }
            .any { (_, value) ->
                listOf("_3DES_", "_DES_", "_RC2_", "_RC4_", "_IDEA_")
                    .any { value.uppercase().contains(it) }
            }
        return hasLegacyDataCipher || hasLegacyAuth || hasLegacyTlsCipher
    }

    /**
     * OpenVPN 3 does not implement several OpenVPN 2 compatibility directives. Translate
     * OpenVPN 2 cipher-list directives to their first configured cipher when needed, then
     * remove options that have no Android/OpenVPN 3 equivalent. The stored profile is unchanged.
     */
    private fun adaptUnsupportedRuntimeOptions(text: String): String {
        val unsupported = setOf(
            "data-ciphers",
            "data-ciphers-fallback",
            "ncp-ciphers",
            // Windows-only policy; Android's VpnService does not implement this option.
            "block-outside-dns",
            "explicit-exit-notify",
            "resolv-retry",
            "persist-key",
            "persist-tun",
        )
        val directives = directiveLines(text)
        val hasCipher = directives.any { it.first == "cipher" && it.second.isNotBlank() }
        val fallbackCipher = listOf("data-ciphers", "ncp-ciphers", "data-ciphers-fallback")
            .firstNotNullOfOrNull { option ->
                directives.firstOrNull { it.first == option && it.second.isNotBlank() }
                    ?.second
                    ?.split(Regex("[:,\\s]+"))
                    ?.firstOrNull(String::isNotBlank)
            }
            .orEmpty()

        val lines = text.split('\n')
        val adapted = mutableListOf<String>()
        var insideOpaqueInlineBlock = false
        var insertedFallbackCipher = false

        for (line in lines) {
            val trimmed = line.trim()
            if (insideOpaqueInlineBlock) {
                adapted += line
                if (trimmed.startsWith("</") && trimmed.endsWith(">")) {
                    insideOpaqueInlineBlock = false
                }
                continue
            }
            if (trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.startsWith("</")) {
                val blockName = trimmed.removePrefix("<").removeSuffix(">")
                    .trim().substringBefore(' ').lowercase()
                adapted += line
                insideOpaqueInlineBlock = blockName != "connection"
                continue
            }
            if (trimmed.startsWith("</") && trimmed.endsWith(">")) {
                adapted += line
                continue
            }
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                adapted += line
                continue
            }

            val name = trimmed.takeWhile { !it.isWhitespace() }.lowercase()
            val value = trimmed.dropWhile { !it.isWhitespace() }.trim()
            // OpenVPN Connect profiles often wrap Windows-only options this way. OpenVPN 3
            // ignores the wrapper itself, but then reports the nested option as unused.
            if (name == "setenv") {
                val arguments = value.split(Regex("\\s+"))
                if (arguments.size >= 2 && arguments[0].equals("opt", ignoreCase = true) &&
                    arguments[1].lowercase() in unsupported
                ) {
                    continue
                }
            }
            if (name == "ignore-unknown-option") {
                val remaining = value.split(Regex("[,\\s]+")).filter { option ->
                    option.isNotBlank() && option.lowercase() !in unsupported
                }
                if (remaining.isNotEmpty()) {
                    val indent = line.takeWhile(Char::isWhitespace)
                    adapted += indent + name + " " + remaining.joinToString(" ")
                }
                continue
            }
            if (name in setOf("data-ciphers", "ncp-ciphers", "data-ciphers-fallback")) {
                if (!hasCipher && !insertedFallbackCipher && fallbackCipher.isNotBlank()) {
                    val indent = line.takeWhile(Char::isWhitespace)
                    adapted += indent + "cipher " + fallbackCipher
                    insertedFallbackCipher = true
                }
                continue
            }
            if (name in unsupported) continue
            adapted += line
        }

        return adapted.joinToString("\n").trimEnd().let { if (it.isBlank()) "" else "$it\n" }
    }

    /**
     * Convert every meaningful line into an editable field. Inline certificate/key blocks are
     * represented as one multiline field; comments and blank lines remain in sequence so a
     * save does not silently reorder or discard the imported profile.
     */
    fun editableFields(text: String): List<EditableField> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        val fields = mutableListOf<EditableField>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].removeSuffix("\r")
            val trimmed = line.trim()
            when {
                trimmed == EMPTY_DIRECTIVE_MARKER -> fields += EditableField(EditableKind.DIRECTIVE)
                trimmed == EMPTY_BLOCK_MARKER -> fields += EditableField(EditableKind.INLINE_BLOCK)
                trimmed.isEmpty() -> fields += EditableField(EditableKind.BLANK)
                trimmed.startsWith("#") || trimmed.startsWith(";") ->
                    fields += EditableField(EditableKind.COMMENT, value = line)
                isInlineBlockOpen(trimmed) -> {
                    val name = trimmed.removePrefix("<").removeSuffix(">")
                    val closeTag = "</${name.substringBefore(' ').lowercase()}>"
                    val body = mutableListOf<String>()
                    index++
                    while (index < lines.size && lines[index].trim().lowercase() != closeTag) {
                        body += lines[index].removeSuffix("\r")
                        index++
                    }
                    fields += EditableField(EditableKind.INLINE_BLOCK, name, body.joinToString("\n"))
                }
                trimmed.startsWith("</") && trimmed.endsWith(">") ->
                    fields += EditableField(EditableKind.RAW, value = line)
                else -> {
                    val name = trimmed.takeWhile { !it.isWhitespace() }
                    if (name.isBlank()) {
                        fields += EditableField(EditableKind.RAW, value = line)
                    } else {
                        val value = trimmed.drop(name.length).trimStart()
                        fields += EditableField(EditableKind.DIRECTIVE, name, value)
                    }
                }
            }
            index++
        }
        return fields
    }

    fun serializeEditableFields(fields: List<EditableField>): String {
        val lines = fields.map { field ->
            when (field.kind) {
                EditableKind.DIRECTIVE -> listOf(field.name.trim(), field.value.trim())
                    .filter(String::isNotEmpty).joinToString(" ")
                    .ifEmpty { EMPTY_DIRECTIVE_MARKER }
                EditableKind.INLINE_BLOCK -> {
                    val name = field.name.trim()
                    if (name.isEmpty()) EMPTY_BLOCK_MARKER else {
                        val closeName = name.substringBefore(' ').lowercase()
                        "<$name>\n${field.value.trimEnd('\n')}\n</$closeName>"
                    }
                }
                EditableKind.COMMENT -> field.value
                EditableKind.BLANK -> ""
                EditableKind.RAW -> field.value
            }
        }
        return lines.joinToString("\n").trimEnd().let { if (it.isBlank()) "" else "$it\n" }
    }

    fun updateEditableField(text: String, index: Int, field: EditableField): String {
        val fields = editableFields(text).toMutableList()
        if (index !in fields.indices) return text
        fields[index] = field
        return serializeEditableFields(fields)
    }

    fun addEditableField(text: String, kind: EditableKind): String {
        val fields = editableFields(text).toMutableList()
        fields += when (kind) {
            EditableKind.INLINE_BLOCK -> EditableField(EditableKind.INLINE_BLOCK)
            EditableKind.COMMENT -> EditableField(EditableKind.COMMENT, value = "# ")
            EditableKind.BLANK -> EditableField(EditableKind.BLANK)
            EditableKind.DIRECTIVE, EditableKind.RAW -> EditableField(EditableKind.DIRECTIVE)
        }
        return serializeEditableFields(fields)
    }

    fun removeEditableField(text: String, index: Int): String {
        val fields = editableFields(text).toMutableList()
        if (index !in fields.indices) return text
        fields.removeAt(index)
        return serializeEditableFields(fields)
    }

    /** Drop untouched blank directive rows before storing the actual OpenVPN profile. */
    fun removeEmptyDirectivePlaceholders(text: String): String = serializeEditableFields(
        editableFields(text).filterNot {
            (it.kind == EditableKind.DIRECTIVE || it.kind == EditableKind.INLINE_BLOCK) &&
                it.name.isBlank() && it.value.isBlank()
        },
    )

    /** These imported switches carry no value field unless one was explicitly supplied. */
    fun isValuelessDirective(name: String): Boolean = name.lowercase() in setOf(
        "client", "nobind", "persist-key", "persist-tun", "remote-random",
        "remote-random-hostname", "float", "auth-nocache", "pull", "tls-client",
        "mute-replay-warnings", "auth-user-pass",
    )

    private fun isInlineBlockOpen(line: String): Boolean =
        line.startsWith("<") && line.endsWith(">") && !line.startsWith("</")

    fun detect(text: String): Fields {
        val directives = directiveLines(text)
        val remoteArgs = directives.firstOrNull { it.first == "remote" }?.second.orEmpty()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        val remote = remoteArgs.firstOrNull().orEmpty()
        val remotePort = remoteArgs.getOrNull(1)?.toIntOrNull()?.toString() ?: "1194"
        val protocol = value(directives, "proto")
            .ifBlank { remoteArgs.getOrNull(2).orEmpty() }
            .ifBlank { "udp" }

        return Fields(
            remote = remote,
            port = remotePort,
            protocol = protocol,
            device = value(directives, "dev"),
            cipher = value(directives, "cipher"),
            dataCiphers = value(directives, "data-ciphers"),
            auth = value(directives, "auth"),
            remoteCertTls = value(directives, "remote-cert-tls"),
            verifyX509Name = value(directives, "verify-x509-name"),
            tlsVersionMin = value(directives, "tls-version-min"),
        )
    }

    fun createProfile(text: String, fileName: String, subscriptionId: String): ProfileItem {
        val fields = detect(text)
        val name = fileName.substringAfterLast('/').substringBeforeLast('.')
            .ifBlank { fields.remote.ifBlank { "OpenVPN" } }
        return ProfileItem.create(EConfigType.OPENVPN).copy(
            remarks = name,
            subscriptionId = subscriptionId,
            server = fields.remote,
            serverPort = fields.port,
            network = fields.protocol,
            openVpnConfig = text,
        )
    }

    fun updateRemote(text: String, remote: String, port: String): String {
        val host = remote.trim()
        val normalizedPort = port.trim().ifBlank { "1194" }
        val replacement = listOf(host, normalizedPort).filter(String::isNotBlank).joinToString(" ")
        return rewriteDirective(text, "remote", replacement, replaceAll = false)
    }

    /** Splits the address and numeric port fields from one editable remote directive. */
    fun remoteEndpoint(value: String): RemoteEndpoint {
        val args = value.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val hasPort = args.getOrNull(1)?.toIntOrNull() != null
        return RemoteEndpoint(
            address = args.firstOrNull().orEmpty(),
            port = if (hasPort) args[1] else "1194",
        )
    }

    /** Updates one remote row while retaining its protocol and any trailing arguments. */
    fun updateEditableRemote(
        text: String,
        index: Int,
        address: String,
        port: String,
    ): String {
        val fields = editableFields(text).toMutableList()
        val field = fields.getOrNull(index) ?: return text
        if (field.kind != EditableKind.DIRECTIVE || !field.name.equals("remote", ignoreCase = true)) {
            return text
        }
        val args = field.value.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val hasPort = args.getOrNull(1)?.toIntOrNull() != null
        val trailingArgs = args.drop(if (hasPort) 2 else 1)
        val updatedArgs = buildList {
            address.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            port.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            addAll(trailingArgs)
        }
        fields[index] = field.copy(value = updatedArgs.joinToString(" "))
        return serializeEditableFields(fields)
    }

    fun updateDirective(text: String, name: String, value: String): String =
        rewriteDirective(text, name, value.trim(), replaceAll = true)

    private fun value(directives: List<Pair<String, String>>, name: String): String =
        directives.firstOrNull { it.first == name }?.second.orEmpty()

    private fun directiveLines(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var insideInlineBlock = false
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (insideInlineBlock) {
                if (trimmed.startsWith("</") && trimmed.endsWith(">")) insideInlineBlock = false
                return@forEach
            }
            if (trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.startsWith("</")) {
                insideInlineBlock = trimmed.removePrefix("<").removeSuffix(">").trim()
                    .substringBefore(' ').lowercase() != "connection"
                return@forEach
            }
            if (trimmed.startsWith("</") && trimmed.endsWith(">")) return@forEach
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith(";")) return@forEach
            val name = trimmed.takeWhile { !it.isWhitespace() }.lowercase()
            val args = trimmed.dropWhile { !it.isWhitespace() }.trim()
            result += name to args
        }
        return result
    }

    private fun rewriteDirective(
        text: String,
        name: String,
        value: String,
        replaceAll: Boolean,
    ): String {
        val normalizedName = name.lowercase()
        val lines = text.split('\n').toMutableList()
        var insideInlineBlock = false
        var found = false
        for (index in lines.indices) {
            val trimmed = lines[index].trim()
            if (insideInlineBlock) {
                if (trimmed.startsWith("</") && trimmed.endsWith(">")) insideInlineBlock = false
                continue
            }
            if (trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.startsWith("</")) {
                insideInlineBlock = trimmed.removePrefix("<").removeSuffix(">").trim()
                    .substringBefore(' ').lowercase() != "connection"
                continue
            }
            if (trimmed.startsWith("</") && trimmed.endsWith(">")) continue
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val currentName = trimmed.takeWhile { !it.isWhitespace() }.lowercase()
            if (currentName != normalizedName) continue

            if (!found) {
                found = true
                lines[index] = if (value.isBlank()) "" else {
                    val indent = lines[index].takeWhile(Char::isWhitespace)
                    "$indent$normalizedName $value"
                }
            } else if (replaceAll) {
                lines[index] = ""
            }
        }

        if (!found && value.isNotBlank()) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "$normalizedName $value"
        }
        return lines.joinToString("\n").trimEnd().let { if (it.isBlank()) "" else "$it\n" }
    }

    private fun removeDirectiveArgument(
        text: String,
        name: String,
        keepBareDirective: Boolean,
    ): String {
        val lines = text.split('\n').toMutableList()
        var insideInlineBlock = false
        var found = false
        for (index in lines.indices) {
            val trimmed = lines[index].trim()
            if (insideInlineBlock) {
                if (trimmed.startsWith("</") && trimmed.endsWith(">")) insideInlineBlock = false
                continue
            }
            if (trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.startsWith("</")) {
                insideInlineBlock = trimmed.removePrefix("<").removeSuffix(">")
                    .trim().substringBefore(' ').lowercase() != "connection"
                continue
            }
            if (trimmed.startsWith("</") && trimmed.endsWith(">")) continue
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val currentName = trimmed.takeWhile { !it.isWhitespace() }.lowercase()
            if (currentName != name) continue
            if (found) {
                lines[index] = ""
            } else {
                found = true
                val indent = lines[index].takeWhile(Char::isWhitespace)
                lines[index] = if (keepBareDirective) "$indent$name" else ""
            }
        }
        return lines.joinToString("\n").trimEnd().let { if (it.isBlank()) "" else "$it\n" }
    }
}
