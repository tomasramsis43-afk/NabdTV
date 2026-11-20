package com.nabd.tv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/** Copies external subtitle files into app storage and decodes them on demand. */
object SubtitleFiles {

    private const val DIR = "player_subtitles"

    fun copy(ctx: Context, uri: Uri, mediaKey: String): ExternalSubtitle? = runCatching {
        val resolver = ctx.contentResolver
        val name = displayName(ctx, uri) ?: "subtitle.srt"
        val ext = name.substringAfterLast('.', "srt").lowercase(Locale.US)
            .takeIf { it in setOf("srt", "vtt", "ass", "ssa", "sub", "txt") } ?: "srt"
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val safe = name.substringBeforeLast('.').take(40).replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val out = File(dir, "${mediaKey.hashCode()}_${System.currentTimeMillis()}_$safe.$ext")
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: return null
        if (out.length() == 0L) { out.delete(); return null }
        ExternalSubtitle(out.absolutePath, name, "auto")
    }.getOrNull()

    /** Saves downloaded subtitle bytes the same way [copy] stores a picked file. */
    fun save(ctx: Context, bytes: ByteArray, name: String, mediaKey: String): ExternalSubtitle? = runCatching {
        if (bytes.isEmpty()) return null
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val safe = name.substringBeforeLast('.').take(40).replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val out = File(dir, "${mediaKey.hashCode()}_${System.currentTimeMillis()}_$safe.srt")
        out.writeBytes(bytes)
        ExternalSubtitle(out.absolutePath, name, "auto")
    }.getOrNull()

    fun load(sub: ExternalSubtitle): SubtitleParseResult = runCatching {
        val bytes = File(sub.path).readBytes()
        val decoded = SubtitleParser.decode(bytes, sub.encoding)
        val cues = SubtitleParser.parse(decoded.text, sub.name)
        SubtitleParseResult(cues, decoded.charset, null)
    }.getOrElse { SubtitleParseResult(emptyList(), sub.encoding, it.message ?: "تعذّر قراءة ملف الترجمة") }

    /** Re-reads the same file with another encoding (used after the user fixes mojibake). */
    fun reDecode(sub: ExternalSubtitle, encoding: String): SubtitleParseResult =
        load(sub.copy(encoding = encoding))

    private fun displayName(ctx: Context, uri: Uri): String? {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.columnCount > 0) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}

data class SubtitleParseResult(val cues: List<SubCue>, val charset: String, val error: String?)

/** Subtitle colours offered by the player (ARGB). */
val SUBTITLE_COLORS = listOf(
    "أبيض" to 0xFFFFFFFF.toInt(),
    "أصفر" to 0xFFFFE066.toInt(),
    "سماوي" to 0xFF7FE3FF.toInt(),
    "أخضر" to 0xFF9CFF9C.toInt(),
    "وردي" to 0xFFFFB3D9.toInt()
)
