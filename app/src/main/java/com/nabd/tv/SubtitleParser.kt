package com.nabd.tv

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A single subtitle line with an absolute time window. */
data class SubCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Subtitle decoding and parsing that works without Android classes, so it can be unit-tested.
 * Supports SubRip (.srt), WebVTT (.vtt), SubStation Alpha (.ass/.ssa) and MicroDVD (.sub).
 */
object SubtitleParser {

    /** Selectable encodings shown in the player. "auto" detects BOM / UTF-8 / Arabic legacy encodings. */
    val ENCODINGS = listOf("auto", "UTF-8", "UTF-16LE", "UTF-16BE", "windows-1256", "ISO-8859-6", "ISO-8859-1")

    data class Decoded(val text: String, val charset: String)

    /** Decodes [bytes] using [preference]. Returns the charset name that actually worked. */
    fun decode(bytes: ByteArray, preference: String = "auto"): Decoded {
        if (bytes.isEmpty()) return Decoded("", "UTF-8")
        // Explicit choice first (but still strip a BOM).
        if (preference != "auto") {
            val cs = runCatching { Charset.forName(preference) }.getOrNull()
            if (cs != null) strictDecode(bytes, cs)?.let { return Decoded(it, cs.name()) }
        }
        // Byte-order marks are authoritative.
        bomCharset(bytes)?.let { cs ->
            strictDecode(bytes, cs)?.let { return Decoded(it, cs.name()) }
        }
        // Plain UTF-8 is by far the most common case.
        strictDecode(bytes, Charsets.UTF_8)?.let { return Decoded(it, "UTF-8") }
        // UTF-16 without a BOM (every second byte is usually zero for Latin/Arabic text).
        if (looksLikeUtf16(bytes)) {
            val le = strictDecode(bytes, Charsets.UTF_16LE)
            val be = strictDecode(bytes, Charsets.UTF_16BE)
            val pick = listOfNotNull(le, be).maxByOrNull { printableRatio(it) }
            if (pick != null) return Decoded(pick, if (pick === le) "UTF-16LE" else "UTF-16BE")
        }
        // Arabic legacy subtitles are almost always Windows-1256; otherwise fall back to Latin-1.
        val charset = if (looksArabicLegacy(bytes)) runCatching { Charset.forName("windows-1256") }.getOrNull() else null
        val arabic = charset?.let { strictDecode(bytes, it) }
        if (arabic != null) return Decoded(arabic, charset.name())
        val latin = strictDecode(bytes, Charsets.ISO_8859_1) ?: String(bytes, Charsets.ISO_8859_1)
        return Decoded(latin, "ISO-8859-1")
    }

    /** Parses a subtitle file, guessing the format from its content and optional file name. */
    fun parse(raw: String, fileName: String = ""): List<SubCue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()
        val lowerName = fileName.lowercase()
        val cues = when {
            lowerName.endsWith(".ass") || lowerName.endsWith(".ssa") ||
                (text.contains("[Events]", true) && text.contains("Dialogue:", true)) -> parseAss(text)
            lowerName.endsWith(".vtt") || text.trimStart().startsWith("WEBVTT", true) -> parseSrtLike(text)
            lowerName.endsWith(".sub") && Regex("^\\{\\d+}\\{\\d+}", RegexOption.MULTILINE).containsMatchIn(text) -> parseMicroDvd(text)
            else -> parseSrtLike(text)
        }
        return cues
            .filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedBy { it.startMs }
            .fold(ArrayList<SubCue>()) { acc, c ->
                // Drop exact duplicates produced by some encoders.
                val prev = acc.lastOrNull()
                if (prev != null && prev.startMs == c.startMs && prev.endMs == c.endMs && prev.text == c.text) acc else acc.also { it += c }
            }
    }

    /** Returns the cue active at [positionMs] (after applying [delayMs] to subtitle timing). */
    fun active(cues: List<SubCue>, positionMs: Long, delayMs: Int): SubCue? {
        if (cues.isEmpty()) return null
        val t = positionMs - delayMs
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            when {
                t < c.startMs -> hi = mid - 1
                t > c.endMs -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }

    fun hasArabic(text: String): Boolean =
        text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }

    /* ------------------------------------------------------------------ */

    private fun bomCharset(b: ByteArray): Charset? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        else -> null
    }

    private fun strictDecode(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (e: CharacterCodingException) {
        null
    }

    private fun looksLikeUtf16(b: ByteArray): Boolean {
        if (b.size < 8) return false
        var zeroEven = 0
        var zeroOdd = 0
        val n = minOf(b.size, 4096)
        for (i in 0 until n) {
            if (b[i] == 0.toByte()) { if (i % 2 == 0) zeroEven++ else zeroOdd++ }
        }
        return zeroEven > n / 8 || zeroOdd > n / 8
    }

    private fun looksArabicLegacy(b: ByteArray): Boolean {
        var high = 0
        var arabic = 0
        for (x in b) {
            val v = x.toInt() and 0xFF
            if (v >= 0x80) {
                high++
                if (v in 0xC1..0xDA || v in 0xE1..0xFA) arabic++
            }
        }
        return high > 12 && arabic * 2 > high
    }

    private fun printableRatio(s: String): Float {
        if (s.isEmpty()) return 0f
        val ok = s.count { it == '\n' || it == '\r' || it == '\t' || (it >= ' ' && it != '\uFFFD') }
        return ok.toFloat() / s.length
    }

    private val timeLine = Regex(
        """(?m)^\s*((?:\d{1,3}:)?\d{1,2}:\d{1,2}(?:[.,]\d{1,3})?)\s*-->\s*((?:\d{1,3}:)?\d{1,2}:\d{1,2}(?:[.,]\d{1,3})?)"""
    )

    private fun parseSrtLike(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        // Walk time line by time line. The text after a timestamp runs until the next blank line or timestamp.
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val m = timeLine.find(lines[i])
            if (m == null) { i++; continue }
            val startMs = parseClock(m.groupValues[1])
            if (startMs == null) { i++; continue }
            val endMs = parseClock(m.groupValues[2])
            if (endMs == null) { i++; continue }
            val start = startMs
            val end = endMs
            i++
            val buf = StringBuilder()
            while (i < lines.size) {
                val ln = lines[i]
                if (ln.isBlank() || timeLine.containsMatchIn(ln)) break
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(ln)
                i++
            }
            val cueText = cleanMarkup(buf.toString())
            if (cueText.isNotBlank()) out += SubCue(start, end, cueText)
        }
        return out
    }

    private fun parseAss(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        var inEvents = false
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) { inEvents = trimmed.equals("[Events]", true); continue }
            if (!inEvents) continue
            if (trimmed.startsWith("Format:", true)) {
                val fields = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                fields.indexOf("start").takeIf { it >= 0 }?.let { startIdx = it }
                fields.indexOf("end").takeIf { it >= 0 }?.let { endIdx = it }
                fields.indexOf("text").takeIf { it >= 0 }?.let { textIdx = it }
                continue
            }
            if (!trimmed.startsWith("Dialogue:", true)) continue
            val body = trimmed.substringAfter(':')
            val parts = body.split(',', limit = maxOf(textIdx + 1, 10))
            if (parts.size <= maxOf(startIdx, endIdx, textIdx)) continue
            val start = parseAssClock(parts[startIdx].trim()) ?: continue
            val end = parseAssClock(parts[endIdx].trim()) ?: continue
            val raw = parts.getOrElse(textIdx) { "" }
            if (raw.contains("\\p1") && !raw.contains("\\p0")) continue   // drawing commands, no readable text
            val t = raw
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                .removeSuffix("\n")
            val cueText = cleanMarkup(t)
            if (cueText.isNotBlank()) out += SubCue(start, end, cueText)
        }
        return out
    }

    private fun parseMicroDvd(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        val re = Regex("""^\{(\d+)}\{(\d+)}(.*)$""")
        val fps = 23.976
        for (line in text.split('\n')) {
            val m = re.find(line.trim()) ?: continue
            val start = ((m.groupValues[1].toLongOrNull() ?: continue) * 1000.0 / fps).toLong()
            val end = ((m.groupValues[2].toLongOrNull() ?: continue) * 1000.0 / fps).toLong()
            val t = m.groupValues[3].replace('|', '\n')
            val cueText = cleanMarkup(t)
            if (cueText.isNotBlank()) out += SubCue(start, end, cueText)
        }
        return out
    }

    private fun parseClock(s: String): Long? {
        val t = s.trim().replace(',', '.')
        val parts = t.split(':')
        val (h, m, sec) = when (parts.size) {
            3 -> Triple(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: return null, parts[2].toDoubleOrNull() ?: return null)
            2 -> Triple(0L, parts[0].toLongOrNull() ?: return null, parts[1].toDoubleOrNull() ?: return null)
            else -> return null
        }
        return ((h * 3600 + m * 60) * 1000.0 + sec * 1000.0).toLong()
    }

    private fun parseAssClock(s: String): Long? {
        val t = s.trim().replace(',', '.')
        val parts = t.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sec = parts[2].toDoubleOrNull() ?: return null
        return ((h * 3600 + m * 60) * 1000.0 + sec * 1000.0).toLong()
    }

    private fun cleanMarkup(s: String): String = s
        .replace(Regex("""<[^>]*>"""), "")                 // HTML / WebVTT tags
        .replace(Regex("""\{[^}]*}"""), "")                // ASS override blocks / SRT position tags
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("""\s+\n"""), "\n")
        .trim()
}
