package com.nabd.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/** Tiny LAN web server: the TV shows a QR, the phone opens the page and sends the login details. */
class PairingServer(private val onReceived: (Map<String, String>) -> Unit) {
    @Volatile private var badRequests = 0
    @Volatile private var server: ServerSocket? = null
    private val token = SecureRandom().let { r -> (1..8).map { "abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)] }.joinToString("") }

    /** Address the phone should open, or null when the device has no LAN address. */
    var url: String? = null; private set

    fun start(): String? {
        stop()
        val ip = lanAddress() ?: return null
        val ss = (8765..8775).firstNotNullOfOrNull { port -> runCatching { ServerSocket(port) }.getOrNull() } ?: return null
        server = ss
        url = "http://$ip:${ss.localPort}/$token"
        Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                runCatching { handle(s) }
                runCatching { s.close() }
            }
        }.apply { isDaemon = true }.start()
        return url
    }

    fun stop() { runCatching { server?.close() }; server = null; url = null }

    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            if (b.size() > 4096) return null
            b.write(c)
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 5000
        val input = s.getInputStream().buffered()
        val first = readLine(input) ?: return
        val parts = first.split(' ')
        if (parts.size < 2) return
        var len = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val path = parts[1].substringBefore('?')
        val out = s.getOutputStream()
        fun send(code: String, body: String) {
            val bytes = body.toByteArray()
            out.write("HTTP/1.1 $code\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes); out.flush()
        }
        if (path != "/$token") {
            send("404 Not Found", "404")
            // The token is the only secret: stop listening after too many wrong guesses.
            if (++badRequests >= 30) stop()
            return
        }
        if (parts[0] == "POST") {
            if (len <= 0 || len > 8192) { send("400 Bad Request", "400"); return }
            val buf = ByteArray(len)
            var n = 0
            while (n < len) { val r = input.read(buf, n, len - n); if (r < 0) break; n += r }
            val fields = String(buf, 0, n).split('&').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8").trim() else null
            }.toMap()
            send("200 OK", page(S(R.string.x_pair_sent_title), "<p>${S(R.string.x_pair_sent_body)}</p>"))
            onReceived(fields)
        } else send("200 OK", form())
    }

    private fun page(title: String, body: String) = """<!doctype html><html dir="${if (Lang.rtl) "rtl" else "ltr"}" lang="${Lang.code}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Nabd TV</title>
<style>body{font-family:system-ui,sans-serif;background:#07090d;color:#fff;margin:0;padding:24px}
h1{color:#00e5a0}input,select{width:100%;box-sizing:border-box;padding:14px;margin:6px 0 14px;border-radius:10px;border:1px solid #333;background:#121821;color:#fff;font-size:16px}
button{width:100%;padding:15px;border:0;border-radius:10px;background:#00e5a0;color:#000;font-size:17px;font-weight:700}</style></head>
<body><h1>$title</h1>$body</body></html>"""

    private fun form() = page("Nabd TV", """<form method="post">
<label>${S(R.string.x_pair_type)}</label><select name="t" onchange="x(this.value)"><option value="xtream">Xtream Codes</option><option value="m3u">${S(R.string.x_pair_m3u)}</option></select>
<div id="xt"><label>${S(R.string.x_pair_server)}</label><input name="host" placeholder="http://server:port" autocapitalize="none">
<label>${S(R.string.x_pair_user)}</label><input name="user" autocapitalize="none"><label>${S(R.string.x_pair_pass)}</label><input name="pass" type="password" autocapitalize="none"></div>
<div id="m3" style="display:none"><label>${S(R.string.x_pair_m3u)}</label><input name="url" placeholder="http://.../playlist.m3u" autocapitalize="none"></div>
<button type="submit">${S(R.string.x_pair_send)}</button></form>
<script>function x(v){document.getElementById('xt').style.display=v=='xtream'?'block':'none';document.getElementById('m3').style.display=v=='m3u'?'block':'none'}</script>""")
}

/** Renders [text] as a QR code. */
@Composable
fun QrCode(text: String, size: Dp = 220.dp) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    }
    Canvas(Modifier.size(size).background(Color.White)) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width)
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}
