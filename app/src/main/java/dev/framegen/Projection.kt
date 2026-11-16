package dev.framegen

import android.content.Context

/**
 * Izin PROJECT_MEDIA lewat root (cara yang sama dipakai Suzuna) agar dialog rekam layar sistem dilewati.
 * Dipulihkan ke default saat FrameGen berhenti. Panggil dari thread latar (menjalankan su).
 */
object Projection {
    private fun su(cmd: String): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
    } catch (e: Exception) { false }

    fun grant(ctx: Context): Boolean = su("appops set ${ctx.packageName} PROJECT_MEDIA allow")
    fun restore(ctx: Context) { su("appops set ${ctx.packageName} PROJECT_MEDIA default") }
}
