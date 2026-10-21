package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenVpnProfileCodecTest {

    @Test
    fun `editable representation round trips directives comments and inline blocks`() {
        val config = """# client profile
client
remote vpn.example 1194 udp
auth-user-pass
<ca>
-----BEGIN CERTIFICATE-----
test-certificate
-----END CERTIFICATE-----
</ca>
<connection>
remote backup.example 443 tcp
connect-timeout 10
</connection>
"""

        assertEquals(config, OpenVpnProfileCodec.serializeEditableFields(
            OpenVpnProfileCodec.editableFields(config),
        ))
    }

    @Test
    fun `editing one repeated directive leaves the other entries in order`() {
        val config = "remote first.example 1194 udp\nremote second.example 443 tcp\n"
        val fields = OpenVpnProfileCodec.editableFields(config)

        val edited = OpenVpnProfileCodec.updateEditableField(
            config,
            index = 1,
            field = fields[1].copy(value = "backup.example 8443 tcp"),
        )

        assertEquals(
            "remote first.example 1194 udp\nremote backup.example 8443 tcp\n",
            edited,
        )
    }

    @Test
    fun `remote editor separates address and port and preserves protocol for every server`() {
        val config = "remote primary.example 1194 udp\nremote backup.example 443 tcp-client\n"
        val first = OpenVpnProfileCodec.editableFields(config)[0]
        val second = OpenVpnProfileCodec.editableFields(config)[1]

        assertEquals("primary.example", OpenVpnProfileCodec.remoteEndpoint(first.value).address)
        assertEquals("1194", OpenVpnProfileCodec.remoteEndpoint(first.value).port)
        assertEquals("backup.example", OpenVpnProfileCodec.remoteEndpoint(second.value).address)
        assertEquals("443", OpenVpnProfileCodec.remoteEndpoint(second.value).port)

        val edited = OpenVpnProfileCodec.updateEditableRemote(
            config,
            index = 1,
            address = "new-backup.example",
            port = "8443",
        )

        assertEquals(
            "remote primary.example 1194 udp\nremote new-backup.example 8443 tcp-client\n",
            edited,
        )
    }

    @Test
    fun `remote with protocol but no port defaults to 1194 and retains protocol`() {
        val config = "remote vpn.example tcp-client\n"

        assertEquals(
            "remote vpn.example 443 tcp-client\n",
            OpenVpnProfileCodec.updateEditableRemote(config, 0, "vpn.example", "443"),
        )
    }

    @Test
    fun `detect keeps TCP and UDP transport from the profile for native real-delay probes`() {
        assertEquals("tcp-client", OpenVpnProfileCodec.detect("client\nproto tcp-client\nremote vpn.example 443\n").protocol)
        assertEquals("udp6", OpenVpnProfileCodec.detect("client\nproto udp6\nremote vpn.example 1194\n").protocol)
    }

    @Test
    fun `fields can be added and removed without losing inline certificates`() {
        val config = "client\n<ca>\ncertificate\n</ca>\n"
        val withOption = OpenVpnProfileCodec.addEditableField(
            config,
            OpenVpnProfileCodec.EditableKind.DIRECTIVE,
        )
        val removed = OpenVpnProfileCodec.removeEmptyDirectivePlaceholders(
            OpenVpnProfileCodec.removeEditableField(withOption, 0),
        )

        assertEquals("<ca>\ncertificate\n</ca>\n", removed)
    }

    @Test
    fun `runtime profile keeps auth prompt but removes local secret file paths`() {
        val config = "auth-user-pass /private/credentials.txt\naskpass /private/key.pass\nremote vpn.example 1194\n"

        assertEquals(
            "auth-user-pass\n\nremote vpn.example 1194\n",
            OpenVpnProfileCodec.runtimeConfig(config),
        )
    }

    @Test
    fun `runtime profile adapts OpenVPN 2 cipher directives for OpenVPN 3`() {
        val config = """client
data-ciphers AES-128-GCM:AES-256-GCM
tls-ciphersuites TLS_AES_256_GCM_SHA384
ncp-ciphers AES-128-GCM
ignore-unknown-option data-ciphers,tls-ciphersuites,explicit-exit-notify,resolv-retry,persist-key,persist-tun,ncp-ciphers
remote vpn.example 1194
"""

        assertEquals(
            "client\ncipher AES-128-GCM\ntls-ciphersuites TLS_AES_256_GCM_SHA384\n" +
                "ignore-unknown-option tls-ciphersuites\nremote vpn.example 1194\n",
            OpenVpnProfileCodec.runtimeConfig(config),
        )
        assertEquals("data-ciphers AES-128-GCM:AES-256-GCM", config.lines()[1])
    }

    @Test
    fun `runtime profile translates legacy cipher list fallback and flags legacy algorithms`() {
        val config = "client\nncp-ciphers BF-CBC:AES-256-CBC\nremote vpn.example 1194\n"

        assertEquals(
            "client\ncipher BF-CBC\nremote vpn.example 1194\n",
            OpenVpnProfileCodec.runtimeConfig(config),
        )
        assertEquals(true, OpenVpnProfileCodec.explicitDataCipherRequested(config))
        assertEquals(true, OpenVpnProfileCodec.requestsLegacyAlgorithms(config))
    }

    @Test
    fun `runtime profile retains tls ciphersuites and flags legacy tls settings`() {
        val config = "client\ntls-ciphersuites TLS_AES_256_GCM_SHA384\n"
        val legacy = "client\ntls-cipher TLS-RSA-WITH-3DES-EDE-CBC-SHA\n"

        assertEquals(config, OpenVpnProfileCodec.runtimeConfig(config))
        assertEquals(false, OpenVpnProfileCodec.requestsLegacyAlgorithms(config))
        assertEquals(true, OpenVpnProfileCodec.requestsLegacyAlgorithms(legacy))
    }

    @Test
    fun `compression compatibility is enabled only when the config requests it`() {
        assertEquals(true, OpenVpnProfileCodec.requestsCompression("client\ncomp-lzo\n"))
        assertEquals(true, OpenVpnProfileCodec.requestsCompression("client\ncompress stub-v2\n"))
        assertEquals(false, OpenVpnProfileCodec.requestsCompression("client\ncomp-lzo no\n"))
        assertEquals(false, OpenVpnProfileCodec.requestsCompression("client\n"))
    }

    @Test
    fun `runtime profile preserves an explicit legacy cipher`() {
        val config = "client\ncipher AES-256-CBC\ndata-ciphers AES-128-GCM:AES-256-GCM\nremote vpn.example 1194\n"

        assertEquals(
            "client\ncipher AES-256-CBC\nremote vpn.example 1194\n",
            OpenVpnProfileCodec.runtimeConfig(config),
        )
    }

    @Test
    fun `runtime profile keeps the configured verbosity`() {
        val config = "client\nverb 6\nremote vpn.example 1194\n"

        assertEquals(config, OpenVpnProfileCodec.runtimeConfig(config))
        assertEquals(6, OpenVpnProfileCodec.verbosity(OpenVpnProfileCodec.runtimeConfig(config)))
    }

    @Test
    fun `runtime profile can force verbosity after compatibility adaptation`() {
        val config = "client\nverb 3\ndata-ciphers AES-128-GCM:AES-256-GCM\nremote vpn.example 1194\n"

        val runtime = OpenVpnProfileCodec.runtimeConfig(config, forcedVerbosity = 6)

        assertEquals("client\nverb 6\ncipher AES-128-GCM\nremote vpn.example 1194\n", runtime)
        assertEquals(6, OpenVpnProfileCodec.verbosity(runtime))
        assertEquals("verb 3", config.lines()[1])
    }

    @Test
    fun `editing verbosity is retained by the runtime profile`() {
        val config = "client\nverb 3\nremote vpn.example 1194\n"
        val fields = OpenVpnProfileCodec.editableFields(config)
        val verbIndex = fields.indexOfFirst { it.name == "verb" }
        val edited = OpenVpnProfileCodec.updateEditableField(
            config,
            verbIndex,
            fields[verbIndex].copy(value = "6"),
        )

        assertEquals(6, OpenVpnProfileCodec.verbosity(edited))
        assertEquals(6, OpenVpnProfileCodec.verbosity(OpenVpnProfileCodec.runtimeConfig(edited)))
    }

    @Test
    fun `runtime profile removes Windows-only block outside dns option`() {
        val config = "client\nignore-unknown-option block-outside-dns\nverb 6\n"

        assertEquals("client\nverb 6\n", OpenVpnProfileCodec.runtimeConfig(config))
    }

    @Test
    fun `runtime profile removes Windows-only option wrapped in setenv opt`() {
        val config = "client\nsetenv opt block-outside-dns\nverb 6\n"

        assertEquals("client\nverb 6\n", OpenVpnProfileCodec.runtimeConfig(config))
    }

    @Test
    fun `runtime compatibility adaptation leaves certificate block contents alone`() {
        val config = "client\n<ca>\ndata-ciphers AES-256-GCM\n</ca>\ndata-ciphers AES-128-GCM\n"

        assertEquals(
            "client\n<ca>\ndata-ciphers AES-256-GCM\n</ca>\ncipher AES-128-GCM\n",
            OpenVpnProfileCodec.runtimeConfig(config),
        )
    }

    @Test
    fun `new directive rows stay editable and empty rows are omitted when saving`() {
        val withPlaceholder = OpenVpnProfileCodec.addEditableField(
            "",
            OpenVpnProfileCodec.EditableKind.DIRECTIVE,
        )

        assertEquals(1, OpenVpnProfileCodec.editableFields(withPlaceholder).size)
        assertEquals("", OpenVpnProfileCodec.removeEmptyDirectivePlaceholders(withPlaceholder))
    }

    @Test
    fun `new block is named before adding content and empty blocks are omitted`() {
        val placeholder = OpenVpnProfileCodec.addEditableField("", OpenVpnProfileCodec.EditableKind.INLINE_BLOCK)
        val field = OpenVpnProfileCodec.editableFields(placeholder).single()
        assertEquals(OpenVpnProfileCodec.EditableKind.INLINE_BLOCK, field.kind)
        assertEquals("", field.name)
        assertEquals("", OpenVpnProfileCodec.removeEmptyDirectivePlaceholders(placeholder))
        val named = OpenVpnProfileCodec.updateEditableField(placeholder, 0, field.copy(name = "ca"))
        assertEquals("<ca>\n\n</ca>\n", named)
        assertEquals(true, OpenVpnProfileCodec.isValuelessDirective("client"))
    }
}
