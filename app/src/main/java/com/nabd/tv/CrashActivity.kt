package com.nabd.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Plain-View screen (no Compose) shown in its own process after an uncaught exception, so a crash at start-up
 * can still be read on screen, photographed or shared.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra("trace").orEmpty()
        val share = Button(this).apply {
            text = S(R.string.a_5015cf74)
            setOnClickListener {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, trace), S(R.string.a_0196884e)
                ))
            }
        }
        val body = TextView(this).apply {
            text = S(R.string.x_crash_body, trace)
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0D1F.toInt())
            addView(share)
            addView(ScrollView(this@CrashActivity).apply { addView(body) })
        })
    }
}
