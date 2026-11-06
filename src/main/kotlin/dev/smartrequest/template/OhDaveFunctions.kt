package dev.smartrequest.template

import java.math.BigInteger

/**
 * The legacy "OhDave RSA" password encoding used by the f2eweb / user_ag login flow.
 *
 * This is **not** standard RSA/PKCS#1/OAEP. The original JavaScript turns the plaintext into 16-bit
 * digits (two characters per digit, low byte first), splits it into blocks of `2 * biHighIndex`
 * characters, pads the last block with zero characters, applies raw `modPow`, and writes every block
 * as a zero-padded hex string separated by spaces. The password is reversed before this operation.
 *
 * Passing the key's `exponent` and `modulus` hex strings (not the Base64 `encoded` field) is
 * important: `encoded` is only a standard X.509 public key and is not what the legacy client uses.
 */
object OhDaveFunctions {
    val all: List<TemplateFunction> = listOf(
        object : TemplateFunction {
            override val name: String = "ohDaveRsa"
            override val minArgs: Int = 3
            override val maxArgs: Int = 3

            override fun invoke(args: List<String>): String =
                encrypt(args[0], args[1], args[2])
        }
    )

    fun encrypt(value: String, exponentHex: String, modulusHex: String): String {
        if (value.isEmpty()) {
            throw TemplateFunctionException("ohDaveRsa plaintext is empty")
        }
        val exponent = parseHex(exponentHex, "exponent")
        val modulus = parseHex(modulusHex, "modulus")
        if (modulus.signum() <= 0) {
            throw TemplateFunctionException("ohDaveRsa modulus must be positive")
        }
        val highIndex = biHighIndex(modulus)
        if (highIndex <= 0) {
            throw TemplateFunctionException("ohDaveRsa modulus is too small")
        }
        val chunkSize = 2 * highIndex

        val chars = IntArray(value.length) { index -> value[index].code }
        var paddedLength = chars.size
        if (paddedLength % chunkSize != 0) {
            paddedLength += chunkSize - (paddedLength % chunkSize)
        }
        val padded = IntArray(paddedLength)
        chars.copyInto(padded)

        val result = StringBuilder()
        var offset = 0
        while (offset < paddedLength) {
            var block = BigInteger.ZERO
            var pair = 0
            while (pair < chunkSize) {
                val low = padded[offset + pair] and 0xFF
                val high = padded[offset + pair + 1] and 0xFF
                val digit = low + (high shl 8)
                block = block.add(
                    BigInteger.valueOf(digit.toLong()).shiftLeft(16 * (pair / 2))
                )
                pair += 2
            }
            val encrypted = block.modPow(exponent, modulus)
            if (result.isNotEmpty()) result.append(' ')
            result.append(biToHex(encrypted))
            if (result.length > MAX_OUTPUT_LENGTH) {
                throw TemplateFunctionException("ohDaveRsa output is too large")
            }
            offset += chunkSize
        }
        return result.toString()
    }

    /** Mirrors OhDave's `biHighIndex`: the highest non-zero 16-bit digit index. */
    fun biHighIndex(value: BigInteger): Int =
        if (value.signum() == 0) 0 else (value.abs().bitLength() + 15) / 16 - 1

    /** Mirrors OhDave's `biToHex`: lowercase hex padded to a multiple of four digits. */
    fun biToHex(value: BigInteger): String {
        if (value.signum() == 0) return "0000"
        val hex = value.abs().toString(16)
        val padding = (4 - (hex.length % 4)) % 4
        return "0".repeat(padding) + hex
    }

    private fun parseHex(value: String, field: String): BigInteger {
        if (value.isBlank()) {
            throw TemplateFunctionException("ohDaveRsa $field is empty")
        }
        return try {
            BigInteger(value.trim(), 16)
        } catch (e: NumberFormatException) {
            throw TemplateFunctionException("ohDaveRsa $field is not valid hex", e)
        }
    }

    private const val MAX_OUTPUT_LENGTH = 1_000_000
}
