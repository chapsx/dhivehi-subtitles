package mv.dhivehi.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Detects the text encoding of a subtitle file. */
object TextCodec {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // UTF-16 without BOM: many zero bytes in alternate positions.
        if (bytes.size >= 4) {
            val sample = minOf(bytes.size, 2000)
            var evenZeros = 0
            var oddZeros = 0
            for (i in 0 until sample) if (bytes[i].toInt() == 0) { if (i % 2 == 0) evenZeros++ else oddZeros++ }
            if (oddZeros > sample / 4 && evenZeros < sample / 20) return String(bytes, Charsets.UTF_16LE)
            if (evenZeros > sample / 4 && oddZeros < sample / 20) return String(bytes, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    fun encode(text: String, withBom: Boolean): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        if (!withBom) return body
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
    }
}
