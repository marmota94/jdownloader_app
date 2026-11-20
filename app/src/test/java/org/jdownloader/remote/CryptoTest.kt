package org.jdownloader.remote

import org.junit.Assert.*
import org.junit.Test
import org.jdownloader.remote.data.*
import org.jdownloader.remote.cnl.CnlDecryptor
import javax.crypto.Cipher
import javax.crypto.spec.*
import java.util.Base64
import java.util.concurrent.Executors

class CryptoTest {
    @Test fun sha256KnownVector() { assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", MyJdCrypto.hex(MyJdCrypto.sha256("abc".toByteArray()))) }
    @Test fun hmacRfc4231Vector() { assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", MyJdCrypto.sign(ByteArray(20) { 0x0b }, "Hi There")) }
    @Test fun emailNormalizationAndDomainSeparation() {
        assertArrayEquals(MyJdCrypto.secret("A@EXAMPLE.COM", "Password", "server"), MyJdCrypto.secret("a@example.com", "Password", "server"))
        assertFalse(MyJdCrypto.secret("a", "b", "server").contentEquals(MyJdCrypto.secret("a", "b", "device")))
    }
    @Test fun aesKnownCiphertext() {
        val token = ByteArray(32) { it.toByte() }
        assertEquals("encrypted RPC ä", MyJdCrypto.decrypt(token, MyJdCrypto.encrypt(token, "encrypted RPC ä")))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(token.copyOfRange(16,32), "AES"), IvParameterSpec(token.copyOfRange(0,16))) }
        assertEquals(Base64.getEncoder().encodeToString(cipher.doFinal("hello".toByteArray())), MyJdCrypto.encrypt(token,"hello"))
    }
    @Test fun tokenHashesDecodedSessionBytes() {
        val key = ByteArray(32) { 7 }
        assertArrayEquals(MyJdCrypto.sha256(key + byteArrayOf(0, -1)), MyJdCrypto.token(key, "00ff"))
    }
    @Test fun cnlZeroPaddingAndUnicode() {
        val hex = "00112233445566778899aabbccddeeff"
        val key = MyJdCrypto.unhex(hex)
        val plain = "https://example.org/ä\nhttps://example.org/b".toByteArray()
        val padded = plain.copyOf((plain.size + 15) / 16 * 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key,"AES"), IvParameterSpec(key)) }
        assertEquals(String(plain), CnlDecryptor.decode(hex, Base64.getEncoder().encodeToString(cipher.doFinal(padded))))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectInvalidCnlKey() { CnlDecryptor.decode("bad", "AAAA") }
    @Test fun requestIdsAreUniqueAcrossThreads() {
        val ids = RequestIds(); val executor = Executors.newFixedThreadPool(4)
        try { val tasks = (1..1000).map { executor.submit<Long> { ids.next() } }; val values = tasks.map { it.get() }; assertEquals(1000, values.toSet().size) }
        finally { executor.shutdownNow() }
    }
}
