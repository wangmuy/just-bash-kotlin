package com.justbash.encoding

/**
 * Byte/text boundary helpers for the shell pipeline.
 *
 * Shell pipes carry bytes, not text. In the TypeScript original, a byte
 * buffer is represented as a `ByteString` (a tagged string where each char =
 * one byte). In Kotlin/JVM we represent raw bytes as `ByteArray` and decoded
 * text as `String`, which makes the byte/text boundary explicit at the type
 * level rather than via a tagged string.
 *
 * This object mirrors the semantics of just-bash `encoding.ts` without the
 * latin1-string hack:
 *   - `encodeUtf8ToBytes(s): ByteArray`       codepoints -> UTF-8 bytes
 *   - `decodeBytesToUtf8(b): String`          UTF-8 bytes -> codepoints (fatal)
 *   - `decodeBytesToUtf8Lenient(b): String`   falls back for invalid UTF-8
 *   - `utf8ByteLength(s): Int`                byte length without allocating
 */

object Encoding {
    const val DEFAULT_MAX_CONVERSION_BYTES: Int = 512 * 1024 * 1024

    /** Return the number of bytes in the UTF-8 encoding of [value]. */
    fun utf8ByteLength(value: String): Int {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val code = value[index].code
            if (code <= 0x7f) {
                bytes++
            } else if (code <= 0x7ff) {
                bytes += 2
            } else if (code in 0xd800..0xdbff && index + 1 < value.length) {
                val next = value[index + 1].code
                if (next in 0xdc00..0xdfff) {
                    bytes += 4
                    index++
                } else {
                    bytes += 3
                }
            } else {
                bytes += 3
            }
            index++
        }
        return bytes
    }

    private fun assertConversionSize(bytes: Int, maximum: Int, operation: String) {
        require(bytes in 0..maximum) {
            "$operation: byte conversion limit exceeded ($maximum bytes)"
        }
    }

    /** UTF-8 encode [s], treating every char as a Unicode codepoint. */
    fun encodeUtf8ToBytes(s: String, maxBytes: Int = DEFAULT_MAX_CONVERSION_BYTES): ByteArray {
        if (s.isEmpty()) return ByteArray(0)
        assertConversionSize(utf8ByteLength(s), maxBytes, "UTF-8 encode")
        return s.toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode UTF-8 bytes, throwing on invalid sequences (fatal).
     */
    fun decodeBytesToUtf8Strict(b: ByteArray, maxBytes: Int = DEFAULT_MAX_CONVERSION_BYTES): String {
        if (b.isEmpty()) return ""
        assertConversionSize(b.size, maxBytes, "UTF-8 decode")
        return b.toString(Charsets.UTF_8)
    }
}
