package com.reader.engine.format.txt

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** BOM → UTF-8 validation → GBK fallback (architecture §6). */
object EncodingDetector {
    fun detect(bytes: ByteArray): Charset {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return StandardCharsets.UTF_8
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return StandardCharsets.UTF_16LE
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return StandardCharsets.UTF_16BE
            }
        }
        if (isValidUtf8(bytes)) return StandardCharsets.UTF_8
        return runCatching { Charset.forName("GBK") }.getOrDefault(StandardCharsets.UTF_8)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)
    }
}
