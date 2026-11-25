package com.nabd.tv

/** Removes credentials from text that may leave the device (crash logs, shared traces, Sentry reports). */
object Scrub {
    private val query = Regex("""\b(password|passwd|pass|pwd|username|user|api_key|apikey|token|client_secret|client_id|secret|access_token|refresh_token|key)=([^&\s"'<>]+)""", RegexOption.IGNORE_CASE)
    // Xtream stream URLs carry the login in the path: /live/USER/PASS/123.ts
    private val xtreamPath = Regex("""/(live|movie|series|timeshift)/[^/\s"'<>]+/[^/\s"'<>]+/""")
    private val userInfo = Regex("""(https?://)[^/\s:@]+:[^/\s@]+@""", RegexOption.IGNORE_CASE)

    fun clean(text: String): String = text
        .replace(query) { "${it.groupValues[1]}=***" }
        .replace(xtreamPath) { "/${it.groupValues[1]}/***/***/" }
        .replace(userInfo) { "${it.groupValues[1]}***:***@" }
}
