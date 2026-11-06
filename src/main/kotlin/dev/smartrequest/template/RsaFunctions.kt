package dev.smartrequest.template

import java.nio.charset.StandardCharsets
import java.security.Key
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * RSA helpers exposed as template functions:
 *
 * ```
 * {{rsaEncrypt(value, publicKeyPem)}}          OAEP SHA-256, Base64 output
 * {{rsaEncryptPkcs1(value, publicKeyPem)}}     PKCS#1 v1.5, Base64 output
 * {{rsaDecrypt(base64Cipher, privateKeyPem)}}  OAEP SHA-256
 * {{rsaDecryptPkcs1(base64Cipher, privateKeyPem)}}
 * {{rsaSign(value, privateKeyPem)}}            SHA256withRSA, Base64 signature
 * {{rsaVerify(value, base64Signature, publicKeyPem)}}  "true" or "false"
 * ```
 *
 * Keys are accepted as PEM strings (PKCS#8 private / X.509 public, plus the older PKCS#1
 * `RSA PRIVATE KEY` / `RSA PUBLIC KEY` forms) or as raw Base64 DER. Encrypted private keys are
 * rejected: decrypt them first. Keys normally live in environment variables so they can be shared
 * by requests without putting a multi-line PEM into every body.
 */
object RsaFunctions {
    val all: List<TemplateFunction> = listOf(
        function("rsaEncrypt", 2, 2) { args -> encrypt(args[0], args[1], oaep = true) },
        function("rsaEncryptPkcs1", 2, 2) { args -> encrypt(args[0], args[1], oaep = false) },
        function("rsaDecrypt", 2, 2) { args -> decrypt(args[0], args[1], oaep = true) },
        function("rsaDecryptPkcs1", 2, 2) { args -> decrypt(args[0], args[1], oaep = false) },
        function("rsaSign", 2, 2) { args -> sign(args[0], args[1]) },
        function("rsaVerify", 3, 3) { args -> verify(args[0], args[1], args[2]) }
    )

    private fun function(
        name: String,
        minArgs: Int,
        maxArgs: Int,
        body: (List<String>) -> String
    ): TemplateFunction = object : TemplateFunction {
        override val name: String = name
        override val minArgs: Int = minArgs
        override val maxArgs: Int = maxArgs
        override fun invoke(args: List<String>): String = body(args)
    }

    private fun encrypt(value: String, pem: String, oaep: Boolean): String {
        val key = publicKey(pem)
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, oaep)
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun decrypt(value: String, pem: String, oaep: Boolean): String {
        val key = privateKey(pem)
        val bytes = try {
            Base64.getDecoder().decode(value.trim())
        } catch (e: IllegalArgumentException) {
            throw TemplateFunctionException("ciphertext is not valid Base64", e)
        }
        val cipher = cipher(Cipher.DECRYPT_MODE, key, oaep)
        val decrypted = cipher.doFinal(bytes)
        return String(decrypted, StandardCharsets.UTF_8)
    }

    private fun sign(value: String, pem: String): String {
        val key = privateKey(pem)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(key)
        signature.update(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private fun verify(value: String, signatureBase64: String, pem: String): String {
        val key = publicKey(pem)
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureBase64.trim())
        } catch (e: IllegalArgumentException) {
            throw TemplateFunctionException("signature is not valid Base64", e)
        }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(value.toByteArray(StandardCharsets.UTF_8))
        return verifier.verify(signatureBytes).toString()
    }

    private fun cipher(mode: Int, key: Key, oaep: Boolean): Cipher {
        return try {
            if (oaep) {
                val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                cipher.init(mode, key, OAEP_PARAMETERS)
                cipher
            } else {
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(mode, key)
                cipher
            }
        } catch (e: Exception) {
            throw TemplateFunctionException(
                "RSA ${if (mode == Cipher.ENCRYPT_MODE) "encryption" else "decryption"} failed: " +
                    (e.message ?: e.javaClass.simpleName),
                e
            )
        }
    }

    private fun publicKey(pem: String): PublicKey {
        val parsed = readPem(pem)
        val factory = keyFactory()
        val encoded = when (parsed.type) {
            "PUBLIC KEY", "" -> parsed.bytes
            "RSA PUBLIC KEY" -> subjectPublicKeyInfo(parsed.bytes)
            else -> throw TemplateFunctionException(
                "Unsupported RSA public key type '${parsed.type}'; use PUBLIC KEY or RSA PUBLIC KEY"
            )
        }
        val candidates = if (parsed.type == "") {
            listOf(encoded, subjectPublicKeyInfo(encoded))
        } else {
            listOf(encoded)
        }
        var last: Exception? = null
        for (candidate in candidates) {
            try {
                val key = factory.generatePublic(X509EncodedKeySpec(candidate))
                return checkKey(key) as PublicKey
            } catch (e: Exception) {
                last = e
            }
        }
        throw TemplateFunctionException(
            "Invalid RSA public key: ${last?.message ?: "unable to parse the key"}",
            last
        )
    }

    private fun privateKey(pem: String): PrivateKey {
        val parsed = readPem(pem)
        if (parsed.type == "ENCRYPTED PRIVATE KEY") {
            throw TemplateFunctionException(
                "Encrypted private keys are not supported; decrypt the key first"
            )
        }
        val factory = keyFactory()
        val encoded = when (parsed.type) {
            "PRIVATE KEY", "" -> parsed.bytes
            "RSA PRIVATE KEY" -> privateKeyInfo(parsed.bytes)
            else -> throw TemplateFunctionException(
                "Unsupported RSA private key type '${parsed.type}'; use PRIVATE KEY or RSA PRIVATE KEY"
            )
        }
        val candidates = if (parsed.type == "") {
            listOf(encoded, privateKeyInfo(encoded))
        } else {
            listOf(encoded)
        }
        var last: Exception? = null
        for (candidate in candidates) {
            try {
                val key = factory.generatePrivate(PKCS8EncodedKeySpec(candidate))
                return checkKey(key) as PrivateKey
            } catch (e: Exception) {
                last = e
            }
        }
        throw TemplateFunctionException(
            "Invalid RSA private key: ${last?.message ?: "unable to parse the key"}",
            last
        )
    }

    private fun keyFactory(): KeyFactory = try {
        KeyFactory.getInstance("RSA")
    } catch (e: Exception) {
        throw TemplateFunctionException("RSA is not available: ${e.message}", e)
    }

    private fun checkKey(key: Key): Key {
        val bits = when (key) {
            is RSAPublicKey -> key.modulus.bitLength()
            is RSAPrivateKey -> key.modulus.bitLength()
            else -> throw TemplateFunctionException("The key is not an RSA key")
        }
        if (bits !in MIN_KEY_BITS..MAX_KEY_BITS) {
            throw TemplateFunctionException(
                "RSA key size $bits bits is outside the supported $MIN_KEY_BITS..$MAX_KEY_BITS range"
            )
        }
        return key
    }

    private data class PemKey(val type: String, val bytes: ByteArray)

    private fun readPem(pem: String): PemKey {
        val trimmed = pem.trim()
        if (trimmed.isEmpty()) {
            throw TemplateFunctionException("RSA key is empty")
        }
        val begin = BEGIN.find(trimmed)
            ?: return PemKey("", decodeBase64(trimmed.replace(Regex("\\s+"), "")))
        val type = begin.groupValues[1].trim().uppercase()
        val endMarker = "-----END $type-----"
        val end = trimmed.indexOf(endMarker, begin.range.last + 1)
        if (end < 0) {
            throw TemplateFunctionException("RSA key has no matching '$endMarker'")
        }
        val body = trimmed.substring(begin.range.last + 1, end)
        if (body.contains("Proc-Type:", ignoreCase = true) || type.startsWith("ENCRYPTED")) {
            throw TemplateFunctionException(
                "Encrypted private keys are not supported; decrypt the key first"
            )
        }
        return PemKey(type, decodeBase64(body.replace(Regex("\\s+"), "")))
    }

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw TemplateFunctionException("RSA key is not valid Base64", e)
    }

    /**
     * Wraps a PKCS#1 RSAPublicKey into SubjectPublicKeyInfo so the JDK KeyFactory can read it.
     */
    private fun subjectPublicKeyInfo(pkcs1: ByteArray): ByteArray = sequence(
        RSA_ALGORITHM_IDENTIFIER,
        der(0x03, byteArrayOf(0x00) + pkcs1)
    )

    /**
     * Wraps a PKCS#1 RSAPrivateKey into PKCS#8 PrivateKeyInfo.
     */
    private fun privateKeyInfo(pkcs1: ByteArray): ByteArray = sequence(
        der(0x02, byteArrayOf(0x00)),
        RSA_ALGORITHM_IDENTIFIER,
        der(0x04, pkcs1)
    )

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

    private val BEGIN = Regex("""-----BEGIN ([A-Z0-9 ]+)-----""")
    private val RSA_ALGORITHM_IDENTIFIER = sequence(
        der(
            0x06,
            byteArrayOf(
                0x2A,
                0x86.toByte(),
                0x48,
                0x86.toByte(),
                0xF7.toByte(),
                0x0D,
                0x01,
                0x01,
                0x01
            )
        ),
        der(0x05, ByteArray(0))
    )
    private val OAEP_PARAMETERS = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT
    )
    private const val MIN_KEY_BITS = 1024
    private const val MAX_KEY_BITS = 8192
}
