package dev.framegen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Grafik frame-time game (ms) untuk ~240 frame terakhir; garis bantu di 16,7 ms dan 33,3 ms. */
class GraphView(ctx: Context) : View(ctx) {
    private val line = Paint().apply {
        color = 0xFF4C8DFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
    }
    private val ref = Paint().apply { color = 0x55FFFFFF; strokeWidth = 1f }
    private val txt = Paint().apply { color = 0xFFAAAAAA.toInt(); textSize = 20f; isAntiAlias = true }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val head = FgState.ftHead
        val n = minOf(head, 240)
        if (n < 2) return
        var mx = 40f
        for (i in 0 until n) mx = maxOf(mx, FgState.ft[(head - n + i) % 240])
        mx = minOf(mx, 120f)
        path.reset()
        for (i in 0 until n) {
            val v = minOf(FgState.ft[(head - n + i) % 240], mx)
            val x = i * (w / (n - 1))
            val y = h - v / mx * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (r in floatArrayOf(16.7f, 33.3f)) {
            if (r < mx) {
                val y = h - r / mx * h
                c.drawLine(0f, y, w, y, ref)
                c.drawText("%.1f".format(r), 4f, y - 3f, txt)
            }
        }
        c.drawPath(path, line)
    }
}
