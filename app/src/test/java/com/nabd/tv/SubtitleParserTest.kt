package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** Pure-JVM coverage for the external subtitle parser used by the player. */
class SubtitleParserTest {

    @Test
    fun parsesSrtWithMarkupAndEntities() {
        val raw = """
            1
            00:00:01,000 --> 00:00:03,500
            مرحبا

            2
            00:00:04,000 --> 00:00:06,000
            <b>Hello</b> &amp; world
        """.trimIndent()

        val cues = SubtitleParser.parse(raw, "movie.srt")

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("مرحبا", cues[0].text)
        assertEquals(4_000L, cues[1].startMs)
        assertEquals("Hello & world", cues[1].text)
    }

    @Test
    fun parsesWebVtt() {
        val raw = """
            WEBVTT

            00:00:01.000 --> 00:00:02.000 align:start
            <c.yellow>First line</c>

            00:00:02.500 --> 00:00:04.000
            Second line
        """.trimIndent()

        val cues = SubtitleParser.parse(raw, "movie.vtt")

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals("First line", cues[0].text)
        assertEquals(2_500L, cues[1].startMs)
        assertEquals("Second line", cues[1].text)
    }

    @Test
    fun parsesAssDialogueWithFormatFields() {
        val raw = """
            [Script Info]
            Title: Test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,مرحبا\Nworld
            Dialogue: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,{\i1}Last{\i0}
        """.trimIndent()

        val cues = SubtitleParser.parse(raw, "movie.ass")

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_000L, cues[0].endMs)
        assertEquals("مرحبا\nworld", cues[0].text)
        assertEquals("Last", cues[1].text)
    }

    @Test
    fun parsesMicroDvdFramesAt23_976Fps() {
        val raw = "{24}{48}Hello|World"
        val cues = SubtitleParser.parse(raw, "movie.sub")
        assertEquals(1, cues.size)
        assertTrue(cues[0].startMs in 990L..1010L)
        assertTrue(cues[0].endMs in 1_990L..2_010L)
        assertEquals("Hello\nWorld", cues[0].text)
    }

    @Test
    fun detectsBomsAndExplicitCharset() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Café".toByteArray(Charsets.UTF_8)
        val utf8 = SubtitleParser.decode(utf8Bom)
        assertEquals("UTF-8", utf8.charset)
        assertEquals("Café", utf8.text)

        val utf16 = "مرحبا".toByteArray(Charsets.UTF_16LE)
        val explicit = SubtitleParser.decode(utf16, "UTF-16LE")
        assertEquals("UTF-16LE", explicit.charset)
        assertEquals("مرحبا", explicit.text)
    }

    @Test
    fun detectsLegacyArabicWindows1256() {
        val text = "مرحبا بالعربية، هذا اختبار ترجمة عربية قديمة. ".repeat(6)
        val bytes = text.toByteArray(Charset.forName("windows-1256"))
        val decoded = SubtitleParser.decode(bytes)
        assertEquals("windows-1256", decoded.charset)
        assertTrue(decoded.text.contains("مرحبا بالعربية"))
        assertTrue(SubtitleParser.hasArabic(decoded.text))
    }

    @Test
    fun appliesSubtitleDelayWhenLookingUpActiveCue() {
        val cues = listOf(
            SubCue(0L, 1_000L, "A"),
            SubCue(2_000L, 3_000L, "B")
        )
        assertEquals("A", SubtitleParser.active(cues, 1_000L, 0)?.text)
        assertNull(SubtitleParser.active(cues, 1_500L, 0))
        assertNull(SubtitleParser.active(cues, 2_500L, 1_000))
        assertEquals("B", SubtitleParser.active(cues, 1_500L, -1_000)?.text)
    }
}
