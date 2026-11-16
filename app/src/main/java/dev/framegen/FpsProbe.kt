package dev.framegen

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Shell root yang tetap terbuka; exec() mengembalikan baris keluaran, atau null jika root gagal. */
class RootShell {
    private var proc: Process? = null
    private var out: OutputStreamWriter? = null
    private var inp: BufferedReader? = null

    fun exec(cmd: String): List<String>? {
        try {
            if (proc == null) {
                val p = Runtime.getRuntime().exec("su")
                proc = p
                out = OutputStreamWriter(p.outputStream)
                inp = BufferedReader(InputStreamReader(p.inputStream))
            }
            out!!.write(cmd + "\necho __END__\n")
            out!!.flush()
            val l = ArrayList<String>()
            while (true) {
                val s = inp!!.readLine() ?: run { close(); return null }
                if (s == "__END__") break
                l.add(s)
            }
            return l
        } catch (e: Exception) {
            close()
            return null
        }
    }

    fun close() {
        runCatching { proc?.destroy() }
        proc = null; out = null; inp = null
    }
}

/** Mengukur fps game dari SurfaceFlinger (dumpsys --latency pada layer game di depan), tiap ~0,7 detik. */
class FpsProbe : Thread("fg-fps") {
    @Volatile private var alive = true

    fun quit() { alive = false; interrupt() }

    override fun run() {
        val sh = RootShell()
        var layer = ""
        var layerAt = 0L
        try {
            while (alive) {
                val now = SystemClock.elapsedRealtime()
                if (layer.isEmpty() || now - layerAt > 5000) { layer = findLayer(sh); layerAt = now }
                if (layer.isNotEmpty()) {
                    val f = measure(sh, layer)
                    if (f > 0f) { FgState.sfFps = f; FgState.sfAt = SystemClock.elapsedRealtime() } else layer = ""
                }
                sleep(700)
            }
        } catch (e: InterruptedException) {
        } finally { sh.close() }
    }

    private fun findLayer(sh: RootShell): String {
        val cmd = "pkg=`dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' | grep -oE ' [A-Za-z0-9_.]+/' | head -1 | tr -d ' /'`; " +
            "[ -n \"\$pkg\" ] && dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"\$pkg\""
        val l = sh.exec(cmd) ?: return ""
        val ok = l.filter { !it.contains("Background", true) && !it.contains("Task=") && !it.contains("Letterbox", true) }
        return ok.firstOrNull { it.startsWith("SurfaceView") } ?: ok.firstOrNull() ?: ""
    }

    private fun measure(sh: RootShell, layer: String): Float {
        val l = sh.exec("dumpsys SurfaceFlinger --latency \"" + layer + "\" 2>/dev/null") ?: return 0f
        val t = ArrayList<Long>()
        for (s in l.drop(1)) {
            val p = s.trim().split(Regex("\\s+"))
            if (p.size >= 3) {
                val v = p[1].toLongOrNull()
                if (v != null && v > 0L && v < Long.MAX_VALUE / 2) t.add(v)
            }
        }
        if (t.size < 3) return 0f
        val last = t.max()
        val r = t.filter { last - it <= 1_000_000_000L }
        if (r.size < 2) return 0f
        val span = (r.max() - r.min()) / 1e9
        return if (span > 0) ((r.size - 1) / span).toFloat() else 0f
    }
}
