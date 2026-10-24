package com.v2ray.ang.handler

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class OpenVpnCredentials(
    val username: String = "",
    val password: String = "",
    val privateKeyPassphrase: String = "",
    val externalPkiAlias: String = "",
    val disableClientCertificate: Boolean = false,
    val clientCertificatePem: String = "",
    val privateKeyPem: String = "",
)

/** Stores per-profile OpenVPN secrets encrypted with an Android Keystore AES-GCM key. */
object OpenVpnCredentialStore {
    private const val KEY_ALIAS = "pingng.openvpn.credentials.v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val FORMAT_VERSION = 3
    private const val PREVIOUS_FORMAT_VERSION = 2
    private const val LEGACY_FORMAT_VERSION = 1
    // DataOutput.writeUTF has a 65,535-byte limit. Leave room for its length prefix
    // while supporting full certificate chains and combined PEM identities.
    private const val MAX_SECRET_BYTES = 60 * 1024

    fun load(context: Context, profileGuid: String): OpenVpnCredentials? {
        if (profileGuid.isBlank()) return null
        val file = profileFile(context, profileGuid)
        return withProfileLock(context, profileGuid) {
            if (!file.isFile) return@withProfileLock null
            runCatching {
                val encrypted = AtomicFile(file).openRead().use { it.readBytes() }
                decrypt(profileGuid, encrypted)
            }.getOrElse {
                AtomicFile(file).delete()
                null
            }
        }
    }

    fun save(context: Context, profileGuid: String, credentials: OpenVpnCredentials) {
        require(profileGuid.isNotBlank()) { "A profile id is required" }
        require(
            listOf(
                credentials.username,
                credentials.password,
                credentials.privateKeyPassphrase,
                credentials.externalPkiAlias,
                credentials.clientCertificatePem,
                credentials.privateKeyPem,
            )
                .all { it.toByteArray(StandardCharsets.UTF_8).size <= MAX_SECRET_BYTES },
        ) { "Credential value is too long" }

        val file = profileFile(context, profileGuid)
        withProfileLock(context, profileGuid) {
            val atomicFile = AtomicFile(file)
            val stream = atomicFile.startWrite()
            try {
                stream.write(encrypt(profileGuid, credentials))
                stream.fd.sync()
                atomicFile.finishWrite(stream)
            } catch (error: Throwable) {
                atomicFile.failWrite(stream)
                throw error
            }
        }
    }

    fun delete(context: Context, profileGuid: String) {
        if (profileGuid.isBlank()) return
        val file = profileFile(context, profileGuid)
        withProfileLock(context, profileGuid) { AtomicFile(file).delete() }
    }

    private fun encrypt(profileGuid: String, credentials: OpenVpnCredentials): ByteArray {
        val plaintext = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeUTF(credentials.username)
                output.writeUTF(credentials.password)
                output.writeUTF(credentials.privateKeyPassphrase)
                output.writeUTF(credentials.externalPkiAlias)
                output.writeBoolean(credentials.disableClientCertificate)
                output.writeUTF(credentials.clientCertificatePem)
                output.writeUTF(credentials.privateKeyPem)
            }
            bytes.toByteArray()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(profileGuid.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }
    }

    private fun decrypt(profileGuid: String, encrypted: ByteArray): OpenVpnCredentials {
        val input = DataInputStream(ByteArrayInputStream(encrypted))
        val ivSize = input.readInt()
        require(ivSize in 12..16) { "Invalid encrypted credential data" }
        val iv = ByteArray(ivSize).also(input::readFully)
        val ciphertextSize = input.readInt()
        require(ciphertextSize in 16..(10 * MAX_SECRET_BYTES + 1024)) {
            "Invalid encrypted credential data"
        }
        val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
        require(input.available() == 0) { "Invalid encrypted credential data" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(profileGuid.toByteArray(StandardCharsets.UTF_8))
        val plaintext = DataInputStream(ByteArrayInputStream(cipher.doFinal(ciphertext)))
        val version = plaintext.readInt()
        require(version in LEGACY_FORMAT_VERSION..FORMAT_VERSION) {
            "Unsupported credential data version"
        }
        val credentials = OpenVpnCredentials(
            username = plaintext.readUTF(),
            password = plaintext.readUTF(),
            privateKeyPassphrase = plaintext.readUTF(),
            externalPkiAlias = if (version >= PREVIOUS_FORMAT_VERSION) plaintext.readUTF() else "",
            disableClientCertificate = if (version >= PREVIOUS_FORMAT_VERSION) plaintext.readBoolean() else false,
            clientCertificatePem = if (version >= FORMAT_VERSION) plaintext.readUTF() else "",
            privateKeyPem = if (version >= FORMAT_VERSION) plaintext.readUTF() else "",
        )
        require(plaintext.available() == 0) { "Invalid encrypted credential data" }
        return credentials
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun profileFile(context: Context, profileGuid: String): File =
        File(directory(context), "${profileHash(profileGuid)}.bin")

    private fun directory(context: Context): File =
        File(context.noBackupFilesDir, "openvpn-credentials").apply { mkdirs() }

    private fun profileHash(profileGuid: String): String = MessageDigest.getInstance("SHA-256")
        .digest(profileGuid.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    /** File locking keeps service and UI process reads/writes serialized. */
    private inline fun <T> withProfileLock(context: Context, profileGuid: String, action: () -> T): T {
        val lockFile = File(directory(context), "${profileHash(profileGuid)}.lock")
        return FileOutputStream(lockFile, true).use { lockStream ->
            lockStream.channel.lock().use { action() }
        }
    }
}
