package dev.smartrequest.template

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The built-in function catalog. Every function is pure: it only uses its arguments and standard
 * JDK primitives. Function names are case-insensitive, arguments and results are strings, and all
 * text uses UTF-8.
 */
object BuiltInFunctions {
    val all: List<TemplateFunction> = buildList {
        add(function("base64", 1, 1) { args ->
            Base64.getEncoder().encodeToString(args[0].toByteArray(StandardCharsets.UTF_8))
        })
        add(function("base64url", 1, 1) { args ->
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(args[0].toByteArray(StandardCharsets.UTF_8))
        })
        add(function("base64Decode", 1, 1) { args ->
            String(Base64.getDecoder().decode(args[0].trim()), StandardCharsets.UTF_8)
        })
        add(function("base64ToHex", 1, 1) { args ->
            hex(Base64.getDecoder().decode(args[0].trim()))
        })
        add(function("hexToBase64", 1, 1) { args ->
            Base64.getEncoder().encodeToString(unhexBytes(args[0]))
        })
        add(function("urlencode", 1, 1) { args ->
            URLEncoder.encode(args[0], StandardCharsets.UTF_8).replace("+", "%20")
        })
        add(function("urlencodeForm", 1, 1) { args ->
            URLEncoder.encode(args[0], StandardCharsets.UTF_8)
        })
        add(function("urldecode", 1, 1) { args ->
            URLDecoder.decode(args[0], StandardCharsets.UTF_8)
        })
        add(function("hex", 1, 1) { args -> hex(args[0].toByteArray(StandardCharsets.UTF_8)) })
        add(function("unhex", 1, 1) { args ->
            String(unhexBytes(args[0]), StandardCharsets.UTF_8)
        })
        add(function("lower", 1, 1) { args -> args[0].lowercase(Locale.ROOT) })
        add(function("upper", 1, 1) { args -> args[0].uppercase(Locale.ROOT) })
        add(function("reverse", 1, 1) { args -> args[0].reversed() })
        add(function("trim", 1, 1) { args -> args[0].trim() })
        add(function("length", 1, 1) { args -> args[0].length.toString() })
        add(function("concat", 0, 32) { args -> args.joinToString("") })
        add(function("replace", 3, 3) { args -> args[0].replace(args[1], args[2]) })
        add(function("default", 2, 2) { args -> args[0].ifEmpty { args[1] } })
        add(function("substring", 2, 3) { args ->
            val start = args[1].toIntOrNull()
                ?: throw TemplateFunctionException("substring start must be an integer")
            val end = if (args.size == 3) {
                args[2].toIntOrNull()
                    ?: throw TemplateFunctionException("substring end must be an integer")
            } else {
                args[0].length
            }
            if (start < 0 || end > args[0].length || start > end) {
                throw TemplateFunctionException(
                    "substring range $start..$end is outside 0..${args[0].length}"
                )
            }
            args[0].substring(start, end)
        })
        add(function("jsonEscape", 1, 1) { args -> jsonEscape(args[0]) })
        add(function("md5", 1, 1) { args -> digest("MD5", args[0]) })
        add(function("sha1", 1, 1) { args -> digest("SHA-1", args[0]) })
        add(function("sha256", 1, 1) { args -> digest("SHA-256", args[0]) })
        add(function("sha512", 1, 1) { args -> digest("SHA-512", args[0]) })
        add(function("hmacSha256", 2, 2) { args ->
            hex(hmac("HmacSHA256", args[0], args[1]))
        })
        add(function("hmacSha256Base64", 2, 2) { args ->
            Base64.getEncoder().encodeToString(hmac("HmacSHA256", args[0], args[1]))
        })
        addAll(RsaFunctions.all)
        addAll(OhDaveFunctions.all)
    }

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

    private fun digest(algorithm: String, value: String): String {
        val bytes = MessageDigest.getInstance(algorithm)
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return hex(bytes)
    }

    private fun hmac(algorithm: String, key: String, value: String): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), algorithm))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private fun unhexBytes(value: String): ByteArray {
        val compact = value.replace(Regex("\\s+"), "")
        if (compact.length % 2 != 0) {
            throw TemplateFunctionException("hex input must have an even number of characters")
        }
        return ByteArray(compact.length / 2) { index ->
            val pair = compact.substring(index * 2, index * 2 + 2)
            pair.toIntOrNull(16)?.toByte()
                ?: throw TemplateFunctionException("invalid hex input at position ${index * 2}")
        }
    }

    private fun jsonEscape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
