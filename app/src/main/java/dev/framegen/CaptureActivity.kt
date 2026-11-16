package dev.framegen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/** Activity transparan tanpa tampilan: dipanggil dari tile atau tombol Mulai, minta izin rekam layar, lalu menutup diri. */
class CaptureActivity : Activity() {
    private val rq = 7

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        FgState.load(this)
        if (FgAccessibilityService.instance == null) {
            Toast.makeText(this, "Aktifkan Accessibility FrameGen dulu.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Thread {
            // PROJECT_MEDIA melewati dialog dan memaksa tangkap layar penuh: hanya aman bila overlay dikecualikan (mode Layar penuh).
            val skip = if (FgState.fullScreen) Projection.grant(this) else { Projection.restore(this); false }
            runOnUiThread { launch(skip) }
        }.start()
    }

    private fun launch(skipDialog: Boolean) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Dengan PROJECT_MEDIA aktif dialog dilewati; kalau tidak, mode layar penuh memakai dialog khusus layar penuh.
        val i = if (!skipDialog && FgState.fullScreen)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        startActivityForResult(i, rq)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == rq && res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, FrameGenService::class.java).putExtra("code", res).putExtra("data", data)
            )
        } else {
            Thread { Projection.restore(this) }.start()
        }
        finish()
    }
}
