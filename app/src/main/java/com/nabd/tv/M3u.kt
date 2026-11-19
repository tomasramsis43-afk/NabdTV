package com.nabd.tv

object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")
    private val headerEpg = Regex("""url-tvg="([^"]+)"""")

    fun epgFromHeader(text: String): String? =
        headerEpg.find(text.take(2000))?.groupValues?.get(1)?.split(',')?.firstOrNull()?.trim()

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>(2048)
        var name = ""
        var logo: String? = null
        var group = "Other"
        var tvgId: String? = null
        var days = 0
        var source: String? = null
        var chno = 0
        var rating: String? = null
        var pending = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val a = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = a["tvg-logo"]?.takeIf { it.isNotBlank() }
                    tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() }
                    group = a["group-title"]?.takeIf { it.isNotBlank() } ?: "Other"
                    days = a["catchup-days"]?.toIntOrNull() ?: 0
                    source = a["catchup-source"]?.takeIf { it.isNotBlank() }
                    chno = a["tvg-chno"]?.toIntOrNull() ?: 0
                    rating = a["tn-rating"]?.takeIf { it.isNotBlank() }
                    name = line.substring(line.lastIndexOf('"') + 1).substringAfter(',', "").trim()
                        .ifEmpty { a["tvg-name"] ?: "Channel" }
                    pending = true
                }
                line.isNotEmpty() && !line.startsWith("#") && pending -> {
                    out += Channel(out.size, name, line, logo, group, tvgId, days, source, chno, rating)
                    pending = false
                }
            }
        }
        return out
    }
}
