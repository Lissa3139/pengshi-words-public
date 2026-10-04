package com.pengshi.words.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SyncCrypto {
    private val magic = "PWSYNC".encodeToByteArray()
    private const val version = 1
    private const val saltLength = 16
    private const val nonceLength = 12
    private const val checksumLength = 32
    private const val keyBits = 256
    private const val pbkdf2Iterations = 120_000
    private val random = SecureRandom()

    fun encrypt(password: String, plaintext: ByteArray): ByteArray {
        require(password.isNotEmpty()) { "Sync password must not be blank" }
        val compressed = gzip(plaintext)
        val salt = ByteArray(saltLength).also(random::nextBytes)
        val nonce = ByteArray(nonceLength).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, deriveKey(password, salt), nonce)
        val ciphertext = cipher.doFinal(compressed)
        val checksum = sha256(compressed)

        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.write(magic)
                data.writeByte(version)
                data.write(salt)
                data.write(nonce)
                data.write(checksum)
                data.writeInt(ciphertext.size)
                data.write(ciphertext)
            }
            output.toByteArray()
        }
    }

    fun decrypt(password: String, encoded: ByteArray): ByteArray {
        require(password.isNotEmpty()) { "Sync password must not be blank" }
        return try {
            val parts = DataInputStream(ByteArrayInputStream(encoded)).use { data ->
                val actualMagic = ByteArray(magic.size).also(data::readFully)
                require(actualMagic.contentEquals(magic)) { "Invalid sync envelope" }
                require(data.readUnsignedByte() == version) { "Unsupported sync envelope version" }
                val salt = ByteArray(saltLength).also(data::readFully)
                val nonce = ByteArray(nonceLength).also(data::readFully)
                val expectedChecksum = ByteArray(checksumLength).also(data::readFully)
                val ciphertextLength = data.readInt()
                require(ciphertextLength in 1..encoded.size) { "Invalid sync ciphertext length" }
                val ciphertext = ByteArray(ciphertextLength).also(data::readFully)
                Triple(salt, nonce, expectedChecksum to ciphertext)
            }
            val salt = parts.first
            val nonce = parts.second
            val expectedChecksum = parts.third.first
            val ciphertext = parts.third.second
            val compressed = cipher(Cipher.DECRYPT_MODE, deriveKey(password, salt), nonce).doFinal(ciphertext)
            require(MessageDigest.isEqual(expectedChecksum, sha256(compressed))) { "Sync checksum mismatch" }
            gunzip(compressed)
        } catch (failure: AEADBadTagException) {
            throw IllegalArgumentException("Unable to decrypt sync payload", failure)
        } catch (failure: java.io.EOFException) {
            throw IllegalArgumentException("Truncated sync envelope", failure)
        } catch (failure: javax.crypto.BadPaddingException) {
            throw IllegalArgumentException("Unable to decrypt sync payload", failure)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, pbkdf2Iterations, keyBits)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
        }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
