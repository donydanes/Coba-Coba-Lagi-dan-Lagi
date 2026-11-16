package dev.framegen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Seret jari untuk menandai kotak (maks 8 per jenis). Tiap kotak: x0,y0,x1,y1,jenis (0=salin frame asli,
 * 1=tembus), koordinat 0..1 dari kiri-atas.
 */
class ZoneEditor(ctx: Context, initial: List<FloatArray>) : View(ctx) {
    val rects = ArrayList<FloatArray>(initial)
    var mode = 0f
    private var cur: FloatArray? = null
    private var sx = 0f
    private var sy = 0f
    private val fill = Paint()
    private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val dim = Paint().apply { color = 0x33000000 }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        for (r in rects + listOfNotNull(cur)) {
            val hole = r[4] > 0.5f
            fill.color = if (hole) 0x552979FF else 0x5500E676
            line.color = if (hole) Color.rgb(41, 121, 255) else Color.GREEN
            val x0 = r[0] * width
            val y0 = r[1] * height
            val x1 = r[2] * width
            val y1 = r[3] * height
            c.drawRect(x0, y0, x1, y1, fill)
            c.drawRect(x0, y0, x1, y1, line)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (e.x / width).coerceIn(0f, 1f)
        val y = (e.y / height).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = x; sy = y; cur = floatArrayOf(x, y, x, y, mode) }
            MotionEvent.ACTION_MOVE -> cur = floatArrayOf(minOf(sx, x), minOf(sy, y), maxOf(sx, x), maxOf(sy, y), mode)
            MotionEvent.ACTION_UP -> {
                cur?.let {
                    val same = rects.count { r -> r[4] == it[4] }
                    if (it[2] - it[0] > 0.02f && it[3] - it[1] > 0.02f && same < 8) rects.add(it)
                }
                cur = null
            }
        }
        invalidate()
        return true
    }
}
