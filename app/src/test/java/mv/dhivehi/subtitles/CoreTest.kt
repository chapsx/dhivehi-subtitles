package mv.dhivehi.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test fun parsesSrt() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nހެލޯ\r\nދެވަނަ ލައިން\r\n\r\n2\r\n00:01:00,000 --> 00:01:03,000\r\nTwo\r\n"
        val cues = SubtitleFormats.parse(srt, "a.srt")
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].start)
        assertEquals(2500L, cues[0].end)
        assertEquals("ހެލޯ\nދެވަނަ ލައިން", cues[0].text)
        assertEquals(60000L, cues[1].start)
    }

    @Test fun parsesSrtWithoutBlankLines() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nA\n2\n00:00:03,000 --> 00:00:04,000\nB\n"
        val cues = SubtitleFormats.parse(srt)
        assertEquals(listOf("A", "B"), cues.map { it.text })
    }

    @Test fun parsesVtt() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000 align:center\n<v Ali>Hi</v>\n\nnote\n01:00:00.100 --> 01:00:01.000\nX\n"
        val cues = SubtitleFormats.parse(vtt, "a.vtt")
        assertEquals(2, cues.size)
        assertEquals("Hi", cues[0].text)
        assertEquals(3600100L, cues[1].start)
    }

    @Test fun parsesAss() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\an8}Hello, world\\NLine2\n"
        val cues = SubtitleFormats.parse(ass, "a.ass")
        assertEquals(1, cues.size)
        assertEquals(1500L, cues[0].start)
        assertEquals("Hello, world\nLine2", cues[0].text)
    }

    @Test fun roundTripSrt() {
        val cues = listOf(Cue(0, 1234, "ހ"), Cue(3_723_004, 3_724_000, "b\nc"))
        val back = SubtitleFormats.parse(SubtitleFormats.toSrt(cues))
        assertEquals(cues, back)
        assertTrue(SubtitleFormats.toSrt(cues).contains("01:02:03,004 --> 01:02:04,000"))
    }

    @Test fun userTime() {
        assertEquals(12500L, SubtitleFormats.parseUserTime("12.5"))
        assertEquals(83450L, SubtitleFormats.parseUserTime("00:01:23,450"))
        assertEquals(83450L, SubtitleFormats.parseUserTime("1:23.45"))
    }

    @Test fun thaanaPhonetic() {
        assertEquals("ދިވެހި", Thaana.fromLatin("divehi"))
        assertEquals("<i>މަ</i>", Thaana.fromLatin("<i>ma</i>"))
        assertEquals("މަހ", Thaana.fromLatin("hma", reverseLines = true))
    }

    @Test fun decodesEncodings() {
        val s = "ދިވެހި"
        assertEquals(s, TextCodec.decode(s.toByteArray(Charsets.UTF_8)))
        assertEquals(s, TextCodec.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)))
        assertEquals(s, TextCodec.decode(TextCodec.encode(s, true)))
        assertEquals("café", TextCodec.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())))
    }
}
