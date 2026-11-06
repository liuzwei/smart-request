package dev.smartrequest.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class BuiltInFunctionsTest {

    private val registry = FunctionRegistry.builtIns()

    @Test
    fun `base64 and base64url use UTF-8`() {
        assertEquals("5L2g5aW9", invoke("base64", "你好"))
        assertEquals("5L2g5aW9", invoke("base64url", "你好"))
        assertEquals("aGVsbG8=", invoke("base64", "hello"))
        assertEquals("aGVsbG8", invoke("base64url", "hello"))
        assertEquals("hello", invoke("base64Decode", "aGVsbG8="))
    }

    @Test
    fun `url and hex helpers round trip`() {
        assertEquals("a%20b%2Bc", invoke("urlencode", "a b+c"))
        assertEquals("a b+c", invoke("urldecode", "a%20b%2Bc"))
        assertEquals("a+b", invoke("urlencodeForm", "a b"))
        assertEquals("68656c6c6f", invoke("hex", "hello"))
        assertEquals("hello", invoke("unhex", "68 65 6c 6c 6f"))
        assertEquals("aGVsbG8=", invoke("hexToBase64", "68656c6c6f"))
        assertEquals("68656c6c6f", invoke("base64ToHex", "aGVsbG8="))
    }

    @Test
    fun `hash functions use known vectors`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", invoke("md5", "abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", invoke("sha1", "abc"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            invoke("sha256", "abc")
        )
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            invoke("hmacSha256", "key", "The quick brown fox jumps over the lazy dog")
        )
    }

    @Test
    fun `string helpers`() {
        assertEquals("Bearer x", invoke("concat", "Bearer ", "x"))
        assertEquals("cba", invoke("reverse", "abc"))
        assertEquals("abc", invoke("substring", "abcdef", "0", "3"))
        assertEquals("fallback", invoke("default", "", "fallback"))
        assertEquals("a\\\"b", invoke("jsonEscape", "a\"b"))
    }

    @Test
    fun `ohDaveRsa matches the legacy login sample`() {
        val modulus = "cf23d69a983d8c6a6bf09cbc03828f1c89e13f61183eb34268c5746dc75f46438c2ebf3988e7445b6218e88c8256089ae4231cbe626572cb6270d1b7d5c68791c41dfbdd69df8e0ddae2d96e3b26649ed07a7e4d3983228a08c1c65b376b6fcab97cc89f96ac316e550565582f3dfc48113bac8e99d4caa6aaf3e3c3f69541a5"
        val expected = "7606d2a97a80acc4ebdd525997b83792422ea1e120fe57bc277bca92461421f6a5e474359b2e1c2ffa11f1e982c5d9ed1ed4c2eb2bed760ef7fe8ba242e3be1f88081587c5389427f364cb35a33b7241b14330c32e8894b570638d71bf0bf2f75668d65247bc183a5736bd4118c96941d0a7f8bdbd6417b3c84e2c33ff421974"
        assertEquals(expected, invoke("ohDaveRsa", "654321", "10001", modulus))
    }

    @Test
    fun `rsa encrypts and signs with PKCS8 and X509 keys`() {
        val keyPair = keyPair()
        val publicPem = pem("PUBLIC KEY", keyPair.public.encoded)
        val privatePem = pem("PRIVATE KEY", keyPair.private.encoded)

        val ciphertext = invoke("rsaEncrypt", "hello rsa", publicPem)
        assertEquals("hello rsa", invoke("rsaDecrypt", ciphertext, privatePem))
        val hexCiphertext = invoke("base64ToHex", ciphertext)
        assertEquals(
            "hello rsa",
            invoke("rsaDecrypt", invoke("hexToBase64", hexCiphertext), privatePem)
        )

        val pkcs1Ciphertext = invoke("rsaEncryptPkcs1", "hello rsa", publicPem)
        assertEquals("hello rsa", invoke("rsaDecryptPkcs1", pkcs1Ciphertext, privatePem))

        val signature = invoke("rsaSign", "hello rsa", privatePem)
        assertEquals("true", invoke("rsaVerify", "hello rsa", signature, publicPem))
        assertEquals("false", invoke("rsaVerify", "changed", signature, publicPem))
    }

    @Test
    fun `rsa accepts the older PKCS1 PEM forms`() {
        val keyPair = keyPair()
        val publicPem = pem("RSA PUBLIC KEY", pkcs1Public(keyPair.public as RSAPublicKey))
        val privatePem = pem(
            "RSA PRIVATE KEY",
            pkcs1Private(keyPair.private as RSAPrivateCrtKey)
        )

        val ciphertext = invoke("rsaEncrypt", "pkcs1 key", publicPem)
        assertEquals("pkcs1 key", invoke("rsaDecrypt", ciphertext, privatePem))
        val signature = invoke("rsaSign", "pkcs1 key", privatePem)
        assertEquals("true", invoke("rsaVerify", "pkcs1 key", signature, publicPem))
    }

    private fun invoke(name: String, vararg args: String): String {
        val function = registry.find(name) ?: error("missing function $name")
        return function.invoke(args.toList())
    }

    private fun keyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    private fun pem(type: String, bytes: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(bytes)
        return "-----BEGIN $type-----\n$body\n-----END $type-----"
    }

    private fun pkcs1Public(key: RSAPublicKey): ByteArray = sequence(
        integer(key.modulus),
        integer(key.publicExponent)
    )

    private fun pkcs1Private(key: RSAPrivateCrtKey): ByteArray = sequence(
        integer(BigInteger.ZERO),
        integer(key.modulus),
        integer(key.publicExponent),
        integer(key.privateExponent),
        integer(key.primeP),
        integer(key.primeQ),
        integer(key.primeExponentP),
        integer(key.primeExponentQ),
        integer(key.crtCoefficient)
    )

    private fun integer(value: BigInteger): ByteArray = der(0x02, value.toByteArray())

    private fun sequence(vararg parts: ByteArray): ByteArray =
        der(0x30, parts.fold(ByteArray(0)) { result, part -> result + part })

    private fun der(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derLength(length: Int): ByteArray {
        if (length < 0x80) return byteArrayOf(length.toByte())
        var value = length
        val bytes = ArrayList<Byte>()
        while (value > 0) {
            bytes.add(0, (value and 0xFF).toByte())
            value = value ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }
}
