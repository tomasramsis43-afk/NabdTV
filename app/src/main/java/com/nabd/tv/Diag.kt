package com.nabd.tv

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Best-effort failures that are deliberately survived (a refresh that fails, a cache that cannot be read) are noted here instead of
 * being swallowed silently. The last notes are added to the crash log, so a field report shows what failed just before.
 */
object Diag {
    private const val MAX = 40
    private val notes = ConcurrentLinkedDeque<String>()

    fun note(where: String, e: Throwable) {
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
            " $where: ${e.javaClass.simpleName}: ${Scrub.clean(e.message.orEmpty()).take(160)}"
        notes.addLast(line)
        while (notes.size > MAX) notes.pollFirst()
    }

    fun dump(): String = notes.joinToString("\n")
}
