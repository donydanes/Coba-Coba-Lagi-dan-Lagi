package dev.framegen

import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import java.lang.reflect.Method

/**
 * Mengecualikan layer milik sendiri (overlay, panel) dari tangkapan layar MediaProjection lewat
 * SurfaceControl.Transaction.setSkipScreenshot (API tersembunyi). Urutan: coba langsung (berhasil bila hidden API
 * sudah dibuka, mis. lewat tombol root di layar utama), lalu coba pembebasan lewat refleksi.
 */
object CaptureExclude {
    private var exempted = false
    @Volatile var lastError = ""

    private fun err(step: String, e: Throwable) {
        lastError = step + ": " + e.javaClass.simpleName + (e.message?.let { " " + it.take(70) } ?: "")
    }

    private fun exempt(): Boolean {
        if (exempted) return true
        return try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val gdm = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOfNulls<Class<*>>(0).javaClass)
            val vmr = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = gdm.invoke(vmr, "getRuntime", arrayOfNulls<Class<*>>(0)) as Method
            val setEx = gdm.invoke(vmr, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as Method
            setEx.invoke(getRuntime.invoke(null), arrayOf("L"))
            exempted = true
            true
        } catch (e: Throwable) { err("refleksi VMRuntime", e); false }
    }

    private fun skipRaw(sc: SurfaceControl): Boolean = try {
        val tx = SurfaceControl.Transaction()
        val m = SurfaceControl.Transaction::class.java.getDeclaredMethod(
            "setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType!!
        )
        m.isAccessible = true
        m.invoke(tx, sc, true)
        tx.apply()
        tx.close()
        true
    } catch (e: Throwable) { err("setSkipScreenshot", e); false }

    fun skip(sc: SurfaceControl?): Boolean {
        if (sc == null || !sc.isValid) { lastError = "SurfaceControl tidak valid"; return false }
        if (skipRaw(sc)) return true
        if (!exempt()) return false
        return skipRaw(sc)
    }

    fun excludeSurfaceView(sv: SurfaceView): Boolean = skip(sv.surfaceControl)

    /** Jendela biasa (panel): SurfaceControl diambil dari ViewRootImpl lewat refleksi. */
    fun excludeWindow(v: View): Boolean {
        val vri = v.parent ?: return false
        fun get(): SurfaceControl? = try {
            val m = vri.javaClass.getDeclaredMethod("getSurfaceControl")
            m.isAccessible = true
            m.invoke(vri) as? SurfaceControl
        } catch (e: Throwable) { err("ViewRootImpl.getSurfaceControl", e); null }
        var sc = get()
        if (sc == null && exempt()) sc = get()
        return skip(sc)
    }
}
