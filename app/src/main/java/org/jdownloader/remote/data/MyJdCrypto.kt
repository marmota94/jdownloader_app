package org.jdownloader.remote.data

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MyJdCrypto {
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun secret(email: String, password: String, domain: String): ByteArray =
        sha256((email.lowercase(Locale.ROOT) + password + domain).toByteArray(Charsets.UTF_8))
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("[0-9a-fA-F]+")))
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun token(secret: ByteArray, session: String) = sha256(secret + unhex(session))
    fun sign(key: ByteArray, path: String): String = hex(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(path.toByteArray(Charsets.UTF_8))
    })
    private fun cipher(mode: Int, token: ByteArray): Cipher {
        require(token.size == 32)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(token.copyOfRange(16, 32), "AES"), IvParameterSpec(token.copyOfRange(0, 16)))
        }
    }
    fun encrypt(token: ByteArray, text: String): String = Base64.getEncoder().encodeToString(cipher(Cipher.ENCRYPT_MODE, token).doFinal(text.toByteArray(Charsets.UTF_8)))
    fun decrypt(token: ByteArray, text: String): String = String(cipher(Cipher.DECRYPT_MODE, token).doFinal(Base64.getMimeDecoder().decode(text)), Charsets.UTF_8)
}
