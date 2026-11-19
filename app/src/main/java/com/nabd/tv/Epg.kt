package com.nabd.tv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** Streaming XMLTV reader. Two cheap passes: channel index first, then only the wanted programmes. */
object EpgParser {
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US) }

    private fun time(s: String?): Long = try {
        var t = s!!.trim()
        if (t.length == 14) t += " +0000"
        fmt.get()!!.parse(t)!!.time
    } catch (e: Exception) { 0L }

    class ChannelIndex(val ids: Map<String, String>, val names: Map<String, String>)

    /** Reads only the <channel> entries (they precede the programmes), then stops. */
    fun readChannels(input: InputStream): ChannelIndex {
        val ids = HashMap<String, String>()     // lower-case id -> real id
        val names = HashMap<String, String>()   // normalized display-name -> real id
        val p = Xml.newPullParser()
        p.setInput(input, null)
        var cur: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> { cur = p.getAttributeValue(null, "id"); cur?.let { ids[it.lowercase()] = it } }
                    "display-name" -> if (cur != null) {
                        val n = Classifier.normalize(p.nextText())
                        if (n.isNotEmpty()) names.putIfAbsent(n, cur)
                    }
                    "programme" -> return ChannelIndex(ids, names)
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "channel") cur = null
            ev = p.next()
        }
        return ChannelIndex(ids, names)
    }

    fun parse(input: InputStream, wanted: Set<String>, from: Long, to: Long): Map<String, List<Prog>> {
        val map = HashMap<String, MutableList<Prog>>()
        val p = Xml.newPullParser()
        p.setInput(input, null)
        var ch: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "programme" -> {
                        ch = p.getAttributeValue(null, "channel")
                        start = time(p.getAttributeValue(null, "start"))
                        stop = time(p.getAttributeValue(null, "stop"))
                        title = null; desc = ""
                    }
                    "title" -> if (ch != null && title == null) title = p.nextText()
                    "desc" -> if (ch != null && desc.isEmpty()) desc = p.nextText().take(400)
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                val c = ch
                val t = title
                if (c != null && t != null && c in wanted && stop > from && start < to)
                    map.getOrPut(c) { ArrayList() }.add(Prog(start, stop, t.trim(), desc.trim()))
                ch = null
            }
            ev = p.next()
        }
        return map.mapValues { e -> e.value.sortedBy { it.start } }
    }
}

/** Automatic channel → guide matching (tvg-id first, then fuzzy name), with manual overrides on top. */
object EpgMatcher {
    /** @return channel url → real XMLTV channel id */
    fun match(channels: List<Channel>, index: EpgParser.ChannelIndex, overrides: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>(channels.size)
        for (c in channels) {
            val ov = overrides[c.url]
            val id = when {
                ov != null -> ov
                c.tvgId != null && index.ids.containsKey(c.tvgId.lowercase()) -> index.ids[c.tvgId.lowercase()]
                else -> index.names[Classifier.normalize(c.name)]
            }
            if (id != null) out[c.url] = id
        }
        return out
    }
}
