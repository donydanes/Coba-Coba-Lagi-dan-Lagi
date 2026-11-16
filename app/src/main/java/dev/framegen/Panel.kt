package dev.framegen

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Bubble "FG" yang bisa digeser; ketuk untuk membuka panel kontrol. */
class Panel(private val ctx: Context, private val wm: WindowManager) {
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun bg(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = bg(0xEE15161B.toInt(), 14)
    }
    private val stats = TextView(ctx)
    private val scaleLabel = TextView(ctx)
    private val sharpLabel = TextView(ctx)
    private val graph = GraphView(ctx)
    private val graphBox = LinearLayout(ctx)
    private val graphStats = TextView(ctx)
    private var hud: MiniHud? = null
    private val scaleChips = ArrayList<TextView>()
    private val multChips = ArrayList<TextView>()
    private var hw = ""
    private val fade = Runnable {
        if (body.visibility != View.VISIBLE) root.animate().alpha(0.15f).setDuration(400).start()
    }
    private var ht: HandlerThread? = null
    private var bg: Handler? = null
    private var shell: RootStats? = null
    private var attached = false
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun chip(t: String, onClick: (TextView) -> Unit): TextView = TextView(ctx).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = bg(0xFF3A3A44.toInt(), 8)
        setOnClickListener { onClick(this) }
    }

    private fun label(s: Float) = when (s) { 0.5f -> "0.5x"; 0.75f -> "0.75x"; else -> "1x" }
    private fun modeText() = if (FgState.force) "Mode: Paksa" else "Mode: Auto"

    private fun refreshChips() {
        for (c in scaleChips) {
            val sel = (c.tag as Float) == FgState.scale
            c.background = bg(if (sel) 0xFF4C8DFF.toInt() else 0xFF3A3A44.toInt(), 8)
        }
        for (c in multChips) {
            val t = c.tag as Int
            val sel = if (t == 0) FgState.sync else (!FgState.sync && t == FgState.mult)
            c.background = bg(if (sel) 0xFF4C8DFF.toInt() else 0xFF3A3A44.toInt(), 8)
        }
    }

    private fun wake() {
        main.removeCallbacks(fade)
        root.animate().cancel()
        root.alpha = 1f
        main.postDelayed(fade, 3000)
    }

    private fun toggle() {
        val open = body.visibility != View.VISIBLE
        body.visibility = if (open) View.VISIBLE else View.GONE
        if (open) startHw() else stopHw()
        wake()
        if (FgState.fullScreen) main.postDelayed({ CaptureExclude.excludeWindow(root) }, 300)
    }

    private fun build() {
        val bubble = TextView(ctx).apply {
            text = "FG"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC4C8DFF.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { wake(); sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(root, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggle(); true }
                else -> false
            }
        }
        root.addView(bubble)

        stats.setTextColor(Color.WHITE)
        stats.textSize = 13f
        body.addView(stats)

        val sw = Switch(ctx).apply {
            text = "Frame Gen"
            setTextColor(Color.WHITE)
            isChecked = FgState.enabled
            setOnCheckedChangeListener { _, c -> FgState.enabled = c }
        }
        body.addView(sw)

        scaleLabel.setTextColor(0xFFAAAAAA.toInt())
        scaleLabel.textSize = 12f
        scaleLabel.setPadding(0, dp(8), 0, 0)
        body.addView(scaleLabel)
        val sb = SeekBar(ctx).apply {
            max = 10 // 50% .. 100%, langkah 5%
            progress = ((FgState.scale * 100f - 50f) / 5f).roundToInt().coerceIn(0, 10)
            progressTintList = ColorStateList.valueOf(0xFF4C8DFF.toInt())
            thumbTintList = ColorStateList.valueOf(0xFF4C8DFF.toInt())
        }
        scaleLabel.text = "Resolusi proses: ${50 + sb.progress * 5}%"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                scaleLabel.text = "Resolusi proses: ${50 + p * 5}%"
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {
                FgState.scale = (50 + bar.progress * 5) / 100f // diterapkan saat jari dilepas
                FgState.save(ctx)
            }
        })
        body.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sharpLabel.setTextColor(0xFFAAAAAA.toInt())
        sharpLabel.textSize = 12f
        sharpLabel.setPadding(0, dp(8), 0, 0)
        body.addView(sharpLabel)
        val sb2 = SeekBar(ctx).apply {
            max = 10 // 0% .. 100%, langkah 10%
            progress = (FgState.sharp * 10f).roundToInt().coerceIn(0, 10)
            progressTintList = ColorStateList.valueOf(0xFF4C8DFF.toInt())
            thumbTintList = ColorStateList.valueOf(0xFF4C8DFF.toInt())
        }
        sharpLabel.text = "Ketajaman: ${sb2.progress * 10}%"
        sb2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                sharpLabel.text = "Ketajaman: ${p * 10}%"
                if (fromUser) FgState.sharp = p / 10f // langsung berlaku
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {
                FgState.sharp = bar.progress / 10f
                FgState.save(ctx)
            }
        })
        body.addView(sb2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body.addView(TextView(ctx).apply {
            text = "Pengali fps"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
        val mrow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        for (m in 2..4) {
            val c = chip("${m}x") { FgState.mult = m; FgState.sync = false; FgState.save(ctx); refreshChips() }
            c.tag = m
            multChips.add(c)
            mrow.addView(c, cell())
        }
        val sc = chip("Sync") { FgState.sync = true; FgState.save(ctx); refreshChips() }
        sc.tag = 0
        multChips.add(sc)
        mrow.addView(sc, cell())
        body.addView(mrow)

        val r2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        r2.addView(chip(modeText()) { FgState.force = !FgState.force; FgState.save(ctx); it.text = modeText() }, cell())
        r2.addView(chip("Zona HUD") { openZones() }, cell())
        body.addView(r2)

        val r3 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun hudText() = if (FgState.hud) "HUD: Hidup" else "HUD: Mati"
        fun grText() = if (graphBox.visibility == View.VISIBLE) "Grafik: Hidup" else "Grafik: Mati"
        r3.addView(chip(hudText()) {
            FgState.hud = !FgState.hud
            FgState.save(ctx)
            it.text = hudText()
            if (FgState.hud) { if (hud == null) hud = MiniHud(ctx, wm).also { h -> h.show() } }
            else { hud?.hide(); hud = null }
        }, cell())
        r3.addView(chip(grText()) {
            graphBox.visibility = if (graphBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            FgState.graph = graphBox.visibility == View.VISIBLE
            it.text = grText()
        }, cell())
        body.addView(r3)
        graphBox.orientation = LinearLayout.VERTICAL
        graphBox.visibility = View.GONE
        graphBox.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        graphStats.setTextColor(Color.WHITE)
        graphStats.textSize = 11f
        graphBox.addView(graphStats)
        body.addView(graphBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
        val stopLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        body.addView(chip("Stop") {
            ctx.startService(Intent(ctx, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP))
        }, stopLp)

        val scroll = MaxScroll(ctx)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT))
        refreshChips()
    }

    private val tick = object : Runnable {
        override fun run() {
            val b = FgState.baseFps
            val head = if (b <= 0f) "Menunggu frame…"
            else "Game %.0f fps → %.0f fps\nLayar %.0f Hz".format(b, FgState.outFps, FgState.hz)
            stats.text = if (hw.isEmpty()) head else head + "\n" + hw
            if (graphBox.visibility == View.VISIBLE) {
                graph.invalidate()
                val s = FgState.ftStats()
                var g = if (s[0] > 0f) "Rata-rata %.1f ms (%.0f fps) · 1%% low %.0f fps".format(s[0], 1000f / s[0], 1000f / s[1])
                else "Mengumpulkan data…"
                g += if (FgState.gpuOk) "\nGPU ms: salin %.1f · gerak %.1f · interp %.1f · tampil %.1f"
                    .format(FgState.gpuMs[0], FgState.gpuMs[1], FgState.gpuMs[2], FgState.gpuMs[3])
                else "\nTimer GPU: tidak tersedia"
                graphStats.text = g
            }
            main.postDelayed(this, 500)
        }
    }

    private fun cell() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(2), 0, dp(2), 0)
    }

    private fun startHw() {
        if (ht != null) return
        val s = RootStats()
        shell = s
        val t = HandlerThread("fg-hw").also { it.start() }
        ht = t
        val h = Handler(t.looper)
        bg = h
        h.post(object : Runnable {
            override fun run() {
                val r = s.read()
                main.post { hw = r ?: "" }
                h.postDelayed(this, 1000)
            }
        })
    }

    private fun stopHw() {
        bg?.removeCallbacksAndMessages(null)
        ht?.quitSafely()
        ht = null; bg = null
        shell?.close(); shell = null
    }

    /** Layar penuh untuk menggambar kotak di atas HUD. Hijau = salin frame asli, biru = tembus. */
    private fun openZones() {
        val land = FgState.contentW > FgState.contentH
        val init = ArrayList<FloatArray>()
        fun load(src: FloatArray, kind: Float) {
            for (i in 0 until src.size / 4) {
                init.add(floatArrayOf(src[i * 4], 1f - src[i * 4 + 3], src[i * 4 + 2], 1f - src[i * 4 + 1], kind))
            }
        }
        load(if (land) FgState.zonesL else FgState.zonesP, 0f)
        load(if (land) FgState.holesL else FgState.holesP, 1f)
        val ed = ZoneEditor(ctx, init)
        val frame = FrameLayout(ctx)
        frame.addView(ed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun close() { runCatching { wm.removeView(frame) } }
        fun pack(kind: Float): FloatArray {
            val l = ed.rects.filter { it[4] == kind }
            val out = FloatArray(l.size * 4)
            l.forEachIndexed { i, r ->
                out[i * 4] = r[0]; out[i * 4 + 1] = 1f - r[3]; out[i * 4 + 2] = r[2]; out[i * 4 + 3] = 1f - r[1]
            }
            return out
        }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val m = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(4), 0, dp(4), 0) }
        fun kindText() = if (ed.mode > 0.5f) "Kotak: Tembus" else "Kotak: Salin"
        bar.addView(chip(kindText()) { ed.mode = if (ed.mode > 0.5f) 0f else 1f; it.text = kindText() }, m)
        bar.addView(chip("Simpan") {
            if (land) { FgState.zonesL = pack(0f); FgState.holesL = pack(1f) }
            else { FgState.zonesP = pack(0f); FgState.holesP = pack(1f) }
            FgState.save(ctx)
            if (FgState.opaque && (FgState.holesL.isNotEmpty() || FgState.holesP.isNotEmpty())) {
                android.widget.Toast.makeText(
                    ctx, "Zona tembus butuh overlay transparan: matikan 'Overlay opaque' di layar utama, lalu Stop dan Mulai lagi.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            close()
        }, m)
        bar.addView(chip("Undo") {
            if (ed.rects.isNotEmpty()) { ed.rects.removeAt(ed.rects.size - 1); ed.invalidate() }
        }, m)
        bar.addView(chip("Hapus semua") { ed.rects.clear(); ed.invalidate() }, m)
        bar.addView(chip("Batal") { close() }, m)
        frame.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(16) })
        val flp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        flp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        body.visibility = View.GONE
        stopHw()
        wm.addView(frame, flp)
    }

    fun show() {
        build()
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(120)
        wm.addView(root, lp)
        attached = true
        wake()
        if (FgState.hud) hud = MiniHud(ctx, wm).also { it.show() }
        if (FgState.fullScreen) main.postDelayed({ CaptureExclude.excludeWindow(root) }, 500)
        tick.run()
    }

    fun hide() {
        main.removeCallbacksAndMessages(null)
        stopHw()
        hud?.hide(); hud = null
        if (attached) runCatching { wm.removeView(root) }
        attached = false
    }
}

/** ScrollView yang tingginya dibatasi 75% layar supaya panel panjang tetap bisa digulir. */
private class MaxScroll(ctx: Context) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        val max = (resources.displayMetrics.heightPixels * 0.75f).toInt()
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
    }
}
