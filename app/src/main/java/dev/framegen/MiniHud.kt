package dev.framegen

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Teks statistik kecil di pojok kiri atas, tidak menerima sentuhan; tetap terlihat walau overlay lain tertutup. */
class MiniHud(private val ctx: Context, private val wm: WindowManager) {
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val tv = TextView(ctx)
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )
    private var attached = false
    private var hw = ""
    private var ht: HandlerThread? = null
    private var bgH: Handler? = null
    private var shell: RootStats? = null

    private val tick = object : Runnable {
        override fun run() {
            val b = FgState.baseFps
            val s = FgState.ftStats()
            val low = if (s[1] > 0f) 1000f / s[1] else 0f
            var t = if (b <= 0f) "FG: menunggu frame" else "%.0f→%.0f fps · 1%% low %.0f".format(b, FgState.outFps, low)
            if (hw.isNotEmpty()) t += "\n" + hw
            tv.text = t
            main.postDelayed(this, 500)
        }
    }

    fun show() {
        tv.setTextColor(Color.WHITE)
        tv.textSize = 11f
        tv.setPadding(dp(6), dp(3), dp(6), dp(3))
        tv.setShadowLayer(3f, 0f, 0f, Color.BLACK)
        tv.background = GradientDrawable().apply { setColor(0x66000000); cornerRadius = dp(6).toFloat() }
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = dp(6)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(tv, lp)
        attached = true
        if (FgState.fullScreen) main.postDelayed({ CaptureExclude.excludeWindow(tv) }, 500)
        startHw()
        tick.run()
    }

    private fun startHw() {
        val s = RootStats()
        shell = s
        val t = HandlerThread("fg-hud").also { it.start() }
        ht = t
        val h = Handler(t.looper)
        bgH = h
        h.post(object : Runnable {
            override fun run() {
                val r = s.read()
                main.post { hw = r ?: "" }
                h.postDelayed(this, 1000)
            }
        })
    }

    fun hide() {
        main.removeCallbacksAndMessages(null)
        bgH?.removeCallbacksAndMessages(null)
        ht?.quitSafely()
        ht = null; bgH = null
        shell?.close(); shell = null
        if (attached) runCatching { wm.removeView(tv) }
        attached = false
    }
}
