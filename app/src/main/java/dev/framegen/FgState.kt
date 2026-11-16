package dev.framegen

import android.content.Context

/** Status bersama panel overlay dan thread render; pengaturan disimpan ke SharedPreferences. */
object FgState {
    @Volatile var enabled = true
    @Volatile var force = false
    @Volatile var scale = 1.0f
    @Volatile var mult = 2
    @Volatile var sync = false
    /** Ketajaman 0..1 (penajam gaya CAS di pass salin). */
    @Volatile var sharp = 0f
    /** Preferensi overlay opaque; efektif hanya bila tidak ada zona tembus. */
    @Volatile var opaquePref = true
    @Volatile var opaque = false
    @Volatile var hud = false
    @Volatile var graph = false
    @Volatile var gpuOk = false
    val gpuMs = FloatArray(4)
    /** Cincin frame-time game (ms) untuk grafik dan 1% low; ditulis hanya oleh thread render. */
    val ft = FloatArray(240)
    @Volatile var ftHead = 0
    fun pushFt(ms: Float) { ft[ftHead % 240] = ms; ftHead++ }

    /** [rata-rata ms, rata-rata 1% terburuk ms]; nol bila data belum cukup. */
    fun ftStats(): FloatArray {
        val head = ftHead
        val n = minOf(head, 240)
        if (n < 10) return floatArrayOf(0f, 0f)
        val a = FloatArray(n) { ft[(head - 1 - it) % 240] }
        a.sort()
        var sum = 0f
        for (v in a) sum += v
        val k = maxOf(2, n / 100)
        var worst = 0f
        for (i in 0 until k) worst += a[n - 1 - i]
        return floatArrayOf(sum / n, worst / k)
    }
    /** true = tangkap layar penuh + kecualikan overlay sendiri; false = mode "Satu aplikasi". */
    @Volatile var fullScreen = false
    @Volatile var sfFps = 0f
    @Volatile var sfAt = 0L
    @Volatile var contentW = 1080
    @Volatile var contentH = 2400
    @Volatile var baseFps = 0f
    @Volatile var outFps = 0f
    @Volatile var hz = 0f
    /** Zona HUD: x0,y0,x1,y1 per zona (koordinat 0..1, y dari bawah), terpisah untuk landscape/portrait. */
    @Volatile var zonesL = FloatArray(0)
    @Volatile var zonesP = FloatArray(0)
    /** Zona tembus: area yang dikosongkan supaya game/overlay lain di bawahnya terlihat. */
    @Volatile var holesL = FloatArray(0)
    @Volatile var holesP = FloatArray(0)

    private fun dec(s: String?): FloatArray =
        if (s.isNullOrBlank()) FloatArray(0) else s.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()

    fun load(c: Context) {
        val p = c.getSharedPreferences("fg", Context.MODE_PRIVATE)
        scale = p.getFloat("scale", 1f)
        force = p.getBoolean("force", false)
        mult = p.getInt("mult", 2)
        sync = p.getBoolean("sync", false)
        sharp = p.getFloat("sharp", 0f)
        opaquePref = p.getBoolean("opq", true)
        hud = p.getBoolean("hud", false)
        fullScreen = p.getBoolean("full", false)
        holesL = dec(p.getString("hl", ""))
        holesP = dec(p.getString("hp", ""))
        zonesL = dec(p.getString("zl", ""))
        zonesP = dec(p.getString("zp", ""))
    }

    fun save(c: Context) {
        c.getSharedPreferences("fg", Context.MODE_PRIVATE).edit()
            .putFloat("scale", scale).putBoolean("force", force).putInt("mult", mult).putBoolean("sync", sync).putBoolean("full", fullScreen)
            .putFloat("sharp", sharp).putBoolean("opq", opaquePref).putBoolean("hud", hud)
            .putString("hl", holesL.joinToString(",")).putString("hp", holesP.joinToString(","))
            .putString("zl", zonesL.joinToString(",")).putString("zp", zonesP.joinToString(","))
            .apply()
    }
}
